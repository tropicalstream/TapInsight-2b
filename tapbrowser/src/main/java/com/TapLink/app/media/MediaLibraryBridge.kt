package com.TapLink.app.media

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import com.TapLinkX3.app.EmbeddedCaptionExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * JavaScript bridge that exposes MediaLibraryService directly to the
 * on-glasses library UI. Installed on the tapbrowser WebView as
 * `window.TapMedia`.
 *
 * Security model:
 *   JavascriptInterface methods run on a background thread, so we can't
 *   touch WebView state. Instead, MainActivity updates `currentUrlRef` from
 *   onPageStarted/onPageFinished, and every bridge method that touches the
 *   filesystem checks `isTrusted()` — which returns true only for the asset
 *   pages we ship (library_local.html, media_player.html). Calls from any
 *   other loaded page (third-party sites, etc.) get an error object.
 *
 *   Media URLs are served via WebViewAssetLoader at
 *   https://appassets.androidplatform.net/media/<relative-path>. The asset
 *   loader's PathHandler enforces the Media-root containment check again,
 *   so even a malicious asset page can't coerce us into reading outside the
 *   Media folder.
 *
 * Data model:
 *   All methods return JSON strings (JS-friendly). The wire schema mirrors
 *   the existing /api/library/... endpoints so library_local.html and the
 *   companion library.html can share render/editor code.
 */
class MediaLibraryBridge(
    private val context: Context,
    private val currentUrlRef: AtomicReference<String>,
    /**
     * On-glasses Gemini 3.1 TTS client. Optional — if null, [speakText]
     * returns an informative error so the JS side can fall back to browser
     * speechSynthesis (which only exists in the companion web viewer).
     * Wired by MainActivity/DualWebViewGroup where the bridge is created.
     */
    private val ttsClient: GlassesTtsClient? = null,
    /**
     * Fish.audio TTS client. Optional — if null OR if the engine selection
     * is "gemini" OR if Fish isn't configured, [speakText] falls through to
     * [ttsClient] (Gemini) without surfacing the absence to the user. Both
     * clients return the same SynthesisResult-shaped values, so routing is
     * just a `when` over the active engine.
     */
    private val fishTtsClient: FishTtsClient? = null
) {

    /**
     * Callback used by the async TTS pipeline to push completion events back
     * into the WebView. Implementations MUST post to the WebView's UI thread
     * (i.e. wrap `webView.evaluateJavascript(...)` in `webView.post { ... }`).
     * Set by the host AFTER `addJavascriptInterface` so the WebView reference
     * is available — bridge construction can't take the WebView directly
     * because the bridge is a lazy property the WebView itself depends on.
     * If left null, [startSpeakText] returns an error and JS falls back to
     * the synchronous [speakText] path.
     */
    var jsEvaluator: ((String) -> Unit)? = null

    /**
     * Callback used by the photos gallery's "Grant access to device
     * photos" button. The RayNeo X3 Pro doesn't expose an Android
     * Settings UI for runtime permission grants, so the only way for
     * the user to authorize READ_MEDIA_IMAGES / READ_MEDIA_VIDEO is
     * through the standard ActivityCompat.requestPermissions dialog
     * launched from the host Activity.
     *
     * The host (MainActivity / DualWebViewGroup) sets this after
     * addJavascriptInterface; the implementation must marshal onto the
     * UI thread and call requestPermissions with
     * [DcimEnumerator.requiredPermissions]. If left null, the bridge
     * method returns an error so the JS shows a clear "not supported"
     * toast instead of silently swallowing the tap.
     */
    var permissionRequester: (() -> Unit)? = null

    /**
     * Host callback for HEVC/H.265 gallery videos. These can decode audio
     * through WebView while painting only a black video surface on the X3 Pro,
     * so the Activity provides an in-app Media3 player fallback.
     */
    var nativeVideoOpener: ((uriText: String, mimeType: String?, title: String?) -> String)? = null

    companion object {
        private const val TAG = "MediaLibraryBridge"
        /** JS side reads this as `window.TapMedia`. */
        const val JS_NAME = "TapMedia"
        /** Virtual host used by WebViewAssetLoader for media streaming. */
        const val ASSETS_HOST = "appassets.androidplatform.net"
        private const val PREFS_NAME = "visionclaw_prefs"
        private const val SESSION_TOKEN_KEY = "companion_session_token"
        private const val LOOPBACK_MEDIA_BASE = "https://127.0.0.1:19110"
        /**
         * Asset-page filenames that get fast-path trust without needing
         * `currentUrlRef` to be primed yet. Kept for diagnostics / explicit
         * intent only — the actual gate is a host-based check in [isTrusted]
         * because (a) the bridge is only ever bound to our own in-app WebView
         * and (b) `currentUrlRef` can race the very first bridge call on a
         * cold MediaPlayer load, which previously surfaced as a confusing
         * "Not permitted from this page" error before any user action.
         */
        private val TRUSTED_ASSETS = setOf(
            "library_local.html",
            "media_player.html",
            "photos_gallery.html",
            "AR_Dashboard_Landscape_Sidebar.html"
        )
    }

    /**
     * Exposed (not private) so MediaFileInterceptor can share the same
     * instance for path-safe resolution of `/media/...` requests without
     * re-bootstrapping the Media folder.
     */
    val service: MediaLibraryService = MediaLibraryService(context).also { it.ensureBootstrap() }

    val mediaRoot: File get() = service.mediaRoot

    private fun companionSessionToken(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(SESSION_TOKEN_KEY, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString(SESSION_TOKEN_KEY, token).commit()
        return token
    }

    private fun toLoopbackDcimVideoUrl(dcimRelativePath: String): String {
        val cleanPath = dcimRelativePath
            .replace(File.separatorChar, '/')
            .trimStart('/')
        val encodedPath = URLEncoder.encode(cleanPath, "UTF-8").replace("+", "%20")
        val encodedToken = URLEncoder.encode(companionSessionToken(), "UTF-8")
        return "$LOOPBACK_MEDIA_BASE/media/dcim-video?path=$encodedPath&token=$encodedToken"
    }

    @JavascriptInterface
    fun resolveDcimVideoPlaybackUrl(urlOrPath: String?): String {
        if (!isTrusted()) return ""
        val raw = urlOrPath?.trim().orEmpty()
        if (raw.isBlank()) return ""
        val marker = "/local-video/"
        val tail = if (raw.contains(marker)) {
            raw.substringAfter(marker)
        } else {
            raw
        }.substringBefore('?').substringBefore('#')
        val decoded = try { URLDecoder.decode(tail, "UTF-8") } catch (_: Exception) { tail }
        val clean = decoded.replace(File.separatorChar, '/').trimStart('/')
        if (clean.isBlank() || clean.contains("..") || clean.contains('\\')) return ""
        return toLoopbackDcimVideoUrl(clean)
    }

    /** Lazy DCIM enumerator — only allocated if the gallery actually
     *  asks for the merged shared-storage view. */
    private val dcim: DcimEnumerator by lazy { DcimEnumerator(context) }

    /**
     * Background worker pool for async TTS synth. A small cached pool gives
     * us headroom to run the current chunk's HTTP call in parallel with the
     * next chunk's prefetch without starving either. Cached pool threads die
     * off after 60s of idle so we don't hold OS resources between sessions.
     */
    private val ttsExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "GlassesTts").apply { isDaemon = true }
    }

    private fun isTrusted(): Boolean {
        // The bridge is attached via `addJavascriptInterface` only to our own
        // in-app tapbrowser WebView — third-party sites never get a reference
        // to `window.TapMedia`. So the only thing `isTrusted` has to rule out
        // is a third-party page navigating the same WebView into an origin
        // that isn't one of ours. A host-based check is sufficient for that
        // and avoids a class of first-load bugs the old strict filename match
        // caused: on a cold MediaPlayer load, the bridge's very first call
        // can fire before onPageStarted has finished priming `currentUrlRef`,
        // surfacing as "Not permitted from this page" before any user action.
        //
        // Accept:
        //   - empty ref (first load, about:blank, early DOMContentLoaded)
        //   - anything under file:///android_asset/         (legacy path)
        //   - anything under our WebViewAssetLoader host    (new path)
        val url = currentUrlRef.get().orEmpty()
        if (url.isEmpty()) return true
        if (url == "about:blank") return true
        if (url.startsWith("file:///android_asset/")) return true
        if (url.startsWith("https://$ASSETS_HOST/")) return true
        if (url.startsWith("http://$ASSETS_HOST/")) return true
        return false
    }

    private fun denied(method: String): String {
        Log.w(TAG, "Bridge call denied from URL=${currentUrlRef.get()}: $method")
        return JSONObject().put("error", "Not permitted from this page").toString()
    }

    // ── Folder browsing ────────────────────────────────────────────────

    @JavascriptInterface
    fun listFolder(path: String?): String {
        if (!isTrusted()) return denied("listFolder")
        val listing = service.listFolder(path ?: "")
            ?: return JSONObject().put("error", "Folder not found").toString()
        val arr = JSONArray()
        for (e in listing.entries) {
            arr.put(
                JSONObject()
                    .put("name", e.name)
                    .put("relativePath", e.relativePath)
                    .put("kind", e.kind.name)
                    .put("sizeBytes", e.sizeBytes)
                    .put("lastModifiedMs", e.lastModifiedMs)
            )
        }
        val bc = JSONArray()
        bc.put(JSONObject().put("name", "Media").put("path", ""))
        if (listing.relativePath.isNotEmpty()) {
            val parts = listing.relativePath.split('/').filter { it.isNotBlank() }
            var acc = ""
            for (p in parts) {
                acc = if (acc.isEmpty()) p else "$acc/$p"
                bc.put(JSONObject().put("name", p).put("path", acc))
            }
        }
        return JSONObject()
            .put("relativePath", listing.relativePath)
            .put("absolutePath", listing.absolutePath)
            .put("breadcrumbs", bc)
            .put("entries", arr)
            .toString()
    }

    // ── Playlist I/O ───────────────────────────────────────────────────

    @JavascriptInterface
    fun parsePlaylist(path: String?): String {
        if (!isTrusted()) return denied("parsePlaylist")
        val file = service.resolveSafe(path ?: "")
            ?: return JSONObject().put("error", "Bad path").toString()
        if (!file.exists() || !file.isFile) {
            return JSONObject().put("error", "Playlist not found").toString()
        }
        val parsed = service.parsePlaylist(file)
        val arr = JSONArray()
        for (e in parsed.entries) {
            val playUrl = when {
                e.isAbsoluteUrl -> e.rawPath
                e.absolutePath != null -> toMediaUrl(e.resolvedRelativePath)
                else -> ""
            }
            arr.put(
                JSONObject()
                    .put("rawPath", e.rawPath)
                    .put("resolvedRelativePath", e.resolvedRelativePath)
                    .put("isAbsoluteUrl", e.isAbsoluteUrl)
                    .put("title", e.title)
                    .put("durationSeconds", e.durationSeconds ?: JSONObject.NULL)
                    .put("kind", e.kind.name)
                    .put("playUrl", playUrl)
            )
        }
        val warnings = JSONArray()
        for (w in parsed.warnings) warnings.put(w)
        return JSONObject()
            .put("name", parsed.name)
            .put("relativePath", service.relativize(file))
            .put("entries", arr)
            .put("warnings", warnings)
            .toString()
    }

    @JavascriptInterface
    fun writePlaylist(path: String?, entriesJson: String?): String {
        if (!isTrusted()) return denied("writePlaylist")
        val file = service.resolveSafe(path ?: "")
            ?: return JSONObject().put("error", "Bad path").toString()
        val arr = try {
            JSONArray(entriesJson ?: "[]")
        } catch (e: Exception) {
            return JSONObject().put("error", "Bad entries JSON").toString()
        }
        val list = ArrayList<MediaLibraryService.PlaylistWriteEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pathOrUrl = o.optString("targetPathOrUrl").trim()
            if (pathOrUrl.isEmpty()) continue
            val title = o.optString("title").ifBlank {
                pathOrUrl.substringAfterLast('/').substringBeforeLast('.')
            }
            val dur = if (o.has("durationSeconds") && !o.isNull("durationSeconds"))
                o.optInt("durationSeconds") else null
            list.add(MediaLibraryService.PlaylistWriteEntry(pathOrUrl, title, dur))
        }
        val ok = service.writePlaylist(file, list)
        return JSONObject()
            .put("status", if (ok) "saved" else "error")
            .put("path", service.relativize(file))
            .put("entryCount", list.size)
            .toString()
    }

    @JavascriptInterface
    fun generatePlaylist(folder: String?): String {
        if (!isTrusted()) return denied("generatePlaylist")
        val created = service.generatePlaylistForFolder(folder ?: "")
            ?: return JSONObject().put("error", "No playable files in folder").toString()
        return JSONObject()
            .put("status", "created")
            .put("path", service.relativize(created))
            .toString()
    }

    @JavascriptInterface
    fun deleteEntry(path: String?): String {
        if (!isTrusted()) return denied("deleteEntry")
        val ok = service.deleteEntry(path ?: "")
        return JSONObject()
            .put("status", if (ok) "deleted" else "error")
            .put("path", path ?: "")
            .toString()
    }

    /**
     * Rotate a JPEG/PNG/WEBP in place. Used by the photos gallery's
     * rotate buttons. `degrees` is interpreted clockwise; the call
     * decodes the bitmap, applies a Matrix.postRotate, and re-encodes
     * back over the original file at high quality.
     *
     * The new bitmap is held entirely in memory once; on a memory-tight
     * device a >24MP RAW would be a problem, but TapInsight saves only
     * the live camera frame which is bounded by the Gemini Live frame
     * size (a few MB at most).
     *
     * Returns JSON: `{"status":"rotated", "path":"...", "degrees":90}`
     * on success; `{"error":"..."}` on failure.
     */
    @JavascriptInterface
    fun rotateImage(path: String?, degrees: Int): String {
        if (!isTrusted()) return denied("rotateImage")
        if (path.isNullOrBlank()) return JSONObject().put("error", "Empty path").toString()
        val normalizedDeg = ((degrees % 360) + 360) % 360
        if (normalizedDeg == 0) {
            return JSONObject().put("status", "noop").put("path", path).toString()
        }
        val file = service.resolveSafe(path)
            ?: return JSONObject().put("error", "Path outside Media root").toString()
        if (!file.exists() || !file.isFile) {
            return JSONObject().put("error", "File not found").toString()
        }
        val ext = file.extension.lowercase(java.util.Locale.ROOT)
        if (ext !in MediaLibraryService.IMAGE_EXTENSIONS) {
            return JSONObject().put("error", "Not an image file").toString()
        }

        val bitmap = try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "rotateImage decode failed: ${e.message}")
            null
        } ?: return JSONObject().put("error", "Could not decode image").toString()

        val matrix = android.graphics.Matrix().apply { postRotate(normalizedDeg.toFloat()) }
        val rotated = try {
            android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
        } catch (e: Exception) {
            bitmap.recycle()
            Log.w(TAG, "rotateImage matrix create failed: ${e.message}")
            return JSONObject().put("error", "Rotation failed").toString()
        }

        val format = when (ext) {
            "png" -> android.graphics.Bitmap.CompressFormat.PNG
            "webp" -> android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS
            else -> android.graphics.Bitmap.CompressFormat.JPEG
        }
        val quality = if (format == android.graphics.Bitmap.CompressFormat.JPEG) 92 else 100

        // Write to a sibling tmp file then atomically rename so a
        // crash mid-write doesn't corrupt the original.
        val tmp = File(file.parentFile, file.name + ".tmp.${System.currentTimeMillis()}")
        try {
            tmp.outputStream().use { rotated.compress(format, quality, it) }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return JSONObject().put("error", "Atomic replace failed").toString()
            }
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "rotateImage write failed: ${e.message}")
            return JSONObject().put("error", "Write failed: ${e.localizedMessage}").toString()
        } finally {
            if (rotated !== bitmap) rotated.recycle()
            bitmap.recycle()
        }

        // Best-effort MediaScanner so the native gallery picks up the
        // change. The file path is unchanged, so this just refreshes
        // the image-pixel cache.
        try {
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath),
                arrayOf("image/${if (ext == "jpg") "jpeg" else ext}"), null
            )
        } catch (_: Exception) {}

        return JSONObject()
            .put("status", "rotated")
            .put("path", path)
            .put("degrees", normalizedDeg)
            .toString()
    }

    /**
     * Merged photo+video listing: TapInsight's own `Media/Photos/`
     * folder plus the device's DCIM MediaStore entries (RayNeo
     * native Camera app captures). Newest first.
     *
     * Returns JSON `{"hasMediaPermission": bool, "entries": [...]}`.
     * Each entry has:
     *   source: "library" | "dcim"
     *   name, lastModifiedMs, sizeBytes, kind ("IMAGE"|"VIDEO"),
     *   thumbnailUrl, fullUrl   ← always virtual https URLs the
     *     WebView can <img src=…> directly.
     *   relativePath ("Photos/IMG_…jpg") OR dcimId (numeric, used in
     *     subsequent rotate/delete bridge calls — DCIM ops are NOT
     *     yet wired; PR says read-only for shared storage).
     *
     * When [DcimEnumerator.hasPermission] is false, hasMediaPermission
     * is false and only the library entries are returned. The JS side
     * can show a "Grant access" CTA in that case.
     */
    @JavascriptInterface
    fun listAllPhotos(): String {
        if (!isTrusted()) return denied("listAllPhotos")

        val arr = JSONArray()

        // ── Library (Media/Photos) entries ──
        val libraryListing = service.listFolder(MediaLibraryService.DEFAULT_PHOTOS_DIR)
        libraryListing?.entries?.forEach { e ->
            if (e.kind != MediaLibraryService.MediaKind.IMAGE &&
                e.kind != MediaLibraryService.MediaKind.VIDEO) return@forEach
            arr.put(
                JSONObject()
                    .put("source", "library")
                    .put("name", e.name)
                    .put("relativePath", e.relativePath)
                    .put("lastModifiedMs", e.lastModifiedMs)
                    .put("sizeBytes", e.sizeBytes)
                    .put("kind", e.kind.name)
                    .put("fullUrl", toMediaUrl(e.relativePath))
                    .put("thumbnailUrl", toMediaUrl(e.relativePath))
                    .put(
                        "nativeVideoUri",
                        if (e.kind == MediaLibraryService.MediaKind.VIDEO) {
                            service.resolveSafe(e.relativePath)?.let { Uri.fromFile(it).toString() }
                                ?: JSONObject.NULL
                        } else {
                            JSONObject.NULL
                        }
                    )
            )
        }

        // ── DCIM (shared storage) entries ──
        // Two tiers:
        //   1. listOwn() — TapInsight's own DCIM contributions
        //      (`DCIM/TapInsight/`). Always queried because MediaStore
        //      lets an app read its own entries without
        //      READ_MEDIA_IMAGES. These are the photos the user saved
        //      via "save this photo" / camera_action.
        //   2. listAll() — every DCIM image + video from every app
        //      (RayNeo Camera, screenshots, etc.). Only queried when
        //      hasMediaPermission is true.
        //
        // We tag the merged result with `source` so the gallery can
        // tell library vs own-dcim vs other-dcim apart for write
        // permissions (rotate/delete are only allowed on entries the
        // app actually owns).
        val hasMediaPermission = DcimEnumerator.hasPermission(context)
        val emittedDcimIds = HashSet<Long>()

        fun emitDcimEntry(d: DcimEnumerator.DcimEntry, source: String) {
            val dcimId = ContentUris.parseId(d.contentUri)
            if (!emittedDcimIds.add(dcimId)) return  // de-dupe own ∩ all
            val kindSeg = if (d.isVideo) "video" else "image"
            val proxyUrl = "https://$ASSETS_HOST/dcim/$kindSeg/$dcimId"
            val dcimVideoUrl = d.relativeDisplayPath
                ?.takeIf { d.isVideo && it.startsWith("DCIM/") }
                ?.removePrefix("DCIM/")
                ?.takeIf { it.isNotBlank() }
                ?.let { toLoopbackDcimVideoUrl(it) }
            val fullUrl = dcimVideoUrl ?: proxyUrl
            val videoCodecMime = if (d.isVideo) detectVideoCodecMime(d.contentUri) else null
            arr.put(
                JSONObject()
                    .put("source", source)
                    .put("name", d.displayName)
                    .put("dcimId", dcimId)
                    .put("dcimUri", d.contentUri.toString())
                    .put("nativeVideoUri", if (d.isVideo) d.contentUri.toString() else JSONObject.NULL)
                    .put("lastModifiedMs", d.dateTakenMs)
                    .put("sizeBytes", d.sizeBytes)
                    .put("kind", if (d.isVideo) "VIDEO" else "IMAGE")
                    .put("mimeType", d.mimeType)
                    .put("relativeDisplayPath", d.relativeDisplayPath ?: JSONObject.NULL)
                    .put("fullUrl", fullUrl)
                    .put("thumbnailUrl", proxyUrl)
                    .put("width", d.width)
                    .put("height", d.height)
                    .put("durationMs", d.durationMs ?: JSONObject.NULL)
                    .put("videoCodecMime", videoCodecMime ?: JSONObject.NULL)
            )
        }

        // (1) own entries — always available, no permission needed.
        try {
            for (d in dcim.listOwn(limit = 1000)) {
                emitDcimEntry(d, "dcim_own")
            }
        } catch (e: Exception) {
            Log.w(TAG, "listOwn DCIM query failed: ${e.message}")
        }

        // (2) other-app entries — only with permission.
        if (hasMediaPermission) {
            for (d in dcim.listAll(limit = 1000)) {
                emitDcimEntry(d, "dcim")
            }
        }

        // (3) direct-filesystem fallback. On some Android builds the
        // MediaScanner refuses to register files written by raw
        // filesystem services (RayNeo's monocular camera being a
        // concrete example). In that case File.walk can still see
        // them, so we enumerate the DCIM tree directly and emit the
        // results with source="local". The interceptor's
        // /local-image/ route reads bytes from the file path.
        // De-duped by absolute path against entries we already
        // emitted via MediaStore (compare to relativeDisplayPath
        // ending — best-effort, not perfect).
        try {
            val dcimRoot = File("/storage/emulated/0/DCIM")
            if (hasMediaPermission && dcimRoot.exists() && dcimRoot.canRead()) {
                val mediaExts = setOf(
                    "jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif",
                    "mp4", "mov", "m4v", "webm", "mkv", "3gp"
                )
                // We want to avoid double-emitting things MediaStore
                // already covered. Track display-name fragments we've
                // already seen so File.walk doesn't re-add them.
                val seenNames = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val nm = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                    seenNames += nm.lowercase(java.util.Locale.ROOT)
                }
                dcimRoot.walkTopDown()
                    .filter { it.isFile && it.length() > 0 }
                    .filter { it.extension.lowercase(java.util.Locale.ROOT) in mediaExts }
                    .take(500)
                    .forEach { f ->
                        if (f.name.lowercase(java.util.Locale.ROOT) in seenNames) return@forEach
                        val ext = f.extension.lowercase(java.util.Locale.ROOT)
                        val isVideo = ext in setOf("mp4", "mov", "m4v", "webm", "mkv", "3gp")
                        val mime = when (ext) {
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "webp" -> "image/webp"
                            "gif" -> "image/gif"
                            "bmp" -> "image/bmp"
                            "heic", "heif" -> "image/heic"
                            "mp4", "m4v" -> "video/mp4"
                            "webm" -> "video/webm"
                            "mov" -> "video/quicktime"
                            "mkv" -> "video/x-matroska"
                            "3gp" -> "video/3gpp"
                            else -> "application/octet-stream"
                        }
                        val encodedAbsolute = URLEncoder.encode(f.absolutePath, "UTF-8")
                            .replace("+", "%20")
                        val dcimRelative = f.absolutePath
                            .removePrefix("/storage/emulated/0/DCIM/")
                            .split(File.separatorChar)
                            .filter { it.isNotBlank() }
                            .joinToString("/")
                        val imageProxyUrl = "https://$ASSETS_HOST/local-image/$encodedAbsolute"
                        val videoProxyUrl = toLoopbackDcimVideoUrl(dcimRelative)
                        val proxyUrl = if (isVideo) videoProxyUrl else imageProxyUrl
                        val videoCodecMime = if (isVideo) detectVideoCodecMime(f) else null
                        // Videos get a separate thumb URL pointing at
                        // the MediaMetadataRetriever-backed route. The
                        // gallery uses thumbnailUrl in the grid (<img>)
                        // and fullUrl when handing off to media_player
                        // for playback. For images both URLs point at
                        // the same content.
                        val thumbUrl = if (isVideo) {
                            "https://$ASSETS_HOST/local-video-thumb/$encodedAbsolute"
                        } else proxyUrl
                        val relPath = f.absolutePath.removePrefix("/storage/emulated/0/")
                        arr.put(
                            JSONObject()
                                .put("source", "local")
                                .put("name", f.name)
                                .put("lastModifiedMs", f.lastModified())
                                .put("sizeBytes", f.length())
                                .put("kind", if (isVideo) "VIDEO" else "IMAGE")
                                .put("mimeType", mime)
                                .put("relativeDisplayPath", relPath)
                                .put("fullUrl", proxyUrl)
                                .put("thumbnailUrl", thumbUrl)
                                .put("nativeVideoUri", if (isVideo) Uri.fromFile(f).toString() else JSONObject.NULL)
                                .put("width", 0)
                                .put("height", 0)
                                .put("durationMs", JSONObject.NULL)
                                .put("videoCodecMime", videoCodecMime ?: JSONObject.NULL)
                        )
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Filesystem DCIM walk failed: ${e.message}")
        }

        // Sort newest first by lastModifiedMs.
        val sorted = JSONArray()
        val asList = (0 until arr.length()).map { arr.getJSONObject(it) }
            .sortedByDescending { it.optLong("lastModifiedMs") }
        for (o in asList) sorted.put(o)

        return JSONObject()
            .put("hasMediaPermission", hasMediaPermission)
            .put("entries", sorted)
            .toString()
    }

    private fun detectVideoCodecMime(uri: Uri): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            detectVideoCodecMime(extractor)
        } catch (e: Exception) {
            Log.d(TAG, "detectVideoCodecMime failed for $uri: ${e.message}")
            null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun detectVideoCodecMime(file: File): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            detectVideoCodecMime(extractor)
        } catch (e: Exception) {
            Log.d(TAG, "detectVideoCodecMime failed for ${file.name}: ${e.message}")
            null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun detectVideoCodecMime(extractor: MediaExtractor): String? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/", ignoreCase = true)) return mime
        }
        return null
    }

    @JavascriptInterface
    fun openNativeVideo(dcimUri: String?, mimeType: String?, title: String?): String {
        if (!isTrusted()) return denied("openNativeVideo")
        val uriText = dcimUri?.trim().orEmpty()
        if (uriText.isBlank()) {
            return JSONObject().put("error", "Missing video URI").toString()
        }
        val opener = nativeVideoOpener
            ?: return JSONObject().put("error", "Native video player unavailable").toString()
        return opener(uriText, mimeType, title)
    }

    /**
     * Trigger MediaScannerConnection over the device's DCIM folder
     * and a couple of common subfolders so on-disk files that aren't
     * yet registered with MediaStore become queryable.
     *
     * Why this is needed: RayNeo's monocular camera service and some
     * other system tools write directly to `/storage/emulated/0/DCIM/`
     * via raw filesystem calls — they don't go through MediaStore.
     * Until something scans them, even with READ_MEDIA_IMAGES granted,
     * a MediaStore query returns nothing for those files. Calling
     * this method asks the MediaScanner service to walk the paths
     * and register what it finds.
     *
     * Returns JSON with the scan result. The scan is asynchronous in
     * the framework, so we wait up to 4 seconds for the last callback
     * before responding. Empty results within the timeout simply mean
     * "nothing newly registered" — not necessarily an error.
     *
     *   {"status":"ok", "scanned":[...paths...], "imageCount": N, "videoCount": M}
     *   {"error":"<reason>"}
     */
    @JavascriptInterface
    fun scanDcim(): String {
        if (!isTrusted()) return denied("scanDcim")
        val hasPerm = DcimEnumerator.hasPermission(context)

        // ── Step 1: walk DCIM via File API to find candidate files ──
        val dcimRoot = File("/storage/emulated/0/DCIM")
        val dcimReadable = dcimRoot.exists() && dcimRoot.canRead()
        val mediaExts = setOf(
            "jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif",
            "mp4", "mov", "m4v", "webm", "mkv", "3gp"
        )
        val foundPaths = mutableListOf<String>()
        if (dcimReadable) {
            try {
                dcimRoot.walkTopDown()
                    .filter { it.isFile && it.length() > 0 }
                    .filter { it.extension.lowercase(java.util.Locale.ROOT) in mediaExts }
                    .take(2000)
                    .forEach { foundPaths += it.absolutePath }
            } catch (e: Exception) {
                Log.w(TAG, "scanDcim: walk threw ${e.message}")
            }
        }

        // ── Step 2: scan whatever we found (real file paths, not folders) ──
        // Falls back to scanning the well-known folder paths if File.walk
        // couldn't see anything — better than nothing.
        val scanTargets: Array<String> = if (foundPaths.isNotEmpty()) {
            foundPaths.toTypedArray()
        } else {
            arrayOf(
                "/storage/emulated/0/DCIM",
                "/storage/emulated/0/DCIM/Camera",
                "/storage/emulated/0/DCIM/${DcimEnumerator.DCIM_OWN_SUBFOLDER}",
                "/storage/emulated/0/Pictures"
            )
        }
        val scannedBack = java.util.Collections.synchronizedList(mutableListOf<String>())
        val latch = java.util.concurrent.CountDownLatch(scanTargets.size)
        try {
            android.media.MediaScannerConnection.scanFile(
                context, scanTargets, null
            ) { path, _ ->
                scannedBack += path
                latch.countDown()
            }
        } catch (e: Exception) {
            Log.w(TAG, "scanDcim: scanFile threw ${e.message}")
        }
        try {
            latch.await(if (scanTargets.size > 100) 8 else 4,
                java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {}

        // ── Step 3: diagnostic MediaStore counts ──
        // imagesTotal/videosTotal are unfiltered — useful for distinguishing
        // "MediaStore is empty" from "MediaStore is fine but our DCIM filter
        // doesn't match". imagesDcim/videosDcim are with the DCIM filter,
        // which is what the gallery actually uses.
        val imagesTotal = countMediaStore(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
        val videosTotal = countMediaStore(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, false)
        val imagesDcim = try { dcim.listAll(limit = 5000).count { !it.isVideo } } catch (_: Exception) { 0 }
        val videosDcim = try { dcim.listAll(limit = 5000).count { it.isVideo } } catch (_: Exception) { 0 }

        val foundJson = JSONArray()
        foundPaths.take(20).forEach { foundJson.put(it) }   // cap response size
        val scannedJson = JSONArray()
        scannedBack.take(20).forEach { scannedJson.put(it) }

        return JSONObject()
            .put("status", "ok")
            .put("hasMediaPermission", hasPerm)
            .put("dcimReadable", dcimReadable)
            .put("filesFoundOnDisk", foundPaths.size)
            .put("filesScanned", scannedBack.size)
            .put("imagesTotal", imagesTotal)
            .put("videosTotal", videosTotal)
            .put("imageCount", imagesDcim)
            .put("videoCount", videosDcim)
            .put("found", foundJson)
            .put("scanned", scannedJson)
            .toString()
    }

    /**
     * Quick row-count against a MediaStore collection. Returns -1 if the
     * query throws (permission denied, provider unavailable, etc.).
     */
    private fun countMediaStore(collection: android.net.Uri, isVideo: Boolean): Int {
        @Suppress("UNUSED_PARAMETER")
        val _ignored = isVideo
        return try {
            context.contentResolver.query(
                collection,
                arrayOf(android.provider.MediaStore.MediaColumns._ID),
                null, null, null
            )?.use { it.count } ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "countMediaStore failed: ${e.message}")
            -1
        }
    }

    /**
     * Launch the system runtime-permission dialog for
     * READ_MEDIA_IMAGES / READ_MEDIA_VIDEO (or READ_EXTERNAL_STORAGE on
     * pre-Tiramisu). Used by photos_gallery.html when
     * [DcimEnumerator.hasPermission] returned false — the RayNeo X3 Pro
     * has no Android Settings UI, so this is the user's only path to
     * granting access to DCIM photos and videos.
     *
     * Returns JSON:
     *   - `{"status":"requested"}` when the host launched the dialog.
     *   - `{"status":"alreadyGranted"}` when permission was already on.
     *   - `{"error":"…"}` when no host callback is wired (older builds).
     *
     * The actual grant result lands in MainActivity.onRequestPermissionsResult;
     * the gallery polls / refreshes when it regains focus, so no extra
     * bridge call back is needed for the "after-grant" reload.
     */
    @JavascriptInterface
    fun requestMediaPermission(): String {
        if (!isTrusted()) return denied("requestMediaPermission")
        if (DcimEnumerator.hasPermission(context)) {
            return JSONObject().put("status", "alreadyGranted").toString()
        }
        val requester = permissionRequester
            ?: return JSONObject()
                .put("error", "Permission UI not wired on this build.")
                .toString()
        // Marshal to the main thread — JS interface calls run on a
        // background WebView worker, but requestPermissions must be
        // launched from the Activity's main thread.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                requester.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "permissionRequester invocation failed: ${e.message}")
            }
        }
        return JSONObject().put("status", "requested").toString()
    }

    /**
     * Persist a URL to use as the AR Dashboard background ("browser
     * wallpaper" in user terms). The URL is whatever the gallery
     * passes — typically a `/local-image/`, `/media/`, or `/dcim/`
     * proxy URL pointing at one of the user's photos. The dashboard
     * reads this on load and applies it via CSS background-image.
     *
     * Storage: SharedPreferences key `browser_wallpaper_url` in
     * the existing visionclaw_prefs file (same place the chat orb,
     * Gemini key, etc. live).
     *
     * Pass `""` (empty string) to clear the wallpaper and return to
     * the default dark background.
     */
    @JavascriptInterface
    fun setBrowserWallpaper(url: String?): String {
        if (!isTrusted()) return denied("setBrowserWallpaper")
        val clean = url?.trim().orEmpty()
        return try {
            val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("browser_wallpaper_url", clean).apply()
            JSONObject()
                .put("status", if (clean.isEmpty()) "cleared" else "set")
                .put("url", clean)
                .toString()
        } catch (e: Exception) {
            Log.w(TAG, "setBrowserWallpaper failed: ${e.message}")
            JSONObject().put("error", e.message ?: "Write failed").toString()
        }
    }

    /**
     * Read-side accessor for the dashboard. Returns the currently
     * configured wallpaper URL, or empty string if none set. The
     * dashboard JS uses this on DOMContentLoaded to apply the
     * background-image.
     */
    @JavascriptInterface
    fun getBrowserWallpaper(): String {
        if (!isTrusted()) return denied("getBrowserWallpaper")
        return try {
            val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
            JSONObject()
                .put("url", prefs.getString("browser_wallpaper_url", "") ?: "")
                .toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Read failed").toString()
        }
    }

    @JavascriptInterface
    fun findAllPlaylists(): String {
        if (!isTrusted()) return denied("findAllPlaylists")
        val list = service.findAllPlaylists()
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("name", e.name)
                    .put("relativePath", e.relativePath)
                    .put("kind", e.kind.name)
                    .put("sizeBytes", e.sizeBytes)
                    .put("lastModifiedMs", e.lastModifiedMs)
            )
        }
        return arr.toString()
    }

    // ── Playback URL ───────────────────────────────────────────────────

    /**
     * Return the virtual https URL the WebView will use to stream a media
     * file. The URL resolves through the WebViewAssetLoader interceptor,
     * which enforces Media-root containment again, so the bridge
     * consumer (library_local.html, media_player.html) doesn't get a
     * sharper privilege than the WebView itself.
     */
    @JavascriptInterface
    fun getMediaUrl(relativePath: String?): String {
        return toMediaUrl(relativePath ?: "")
    }

    // ── Relay pull-sync (RelayMediaSync) ───────────────────────────────

    /**
     * Kick off a relay pull-sync in the background. Returns immediately
     * with `{ok, status}` — status is "started", or "already-running" when
     * a sync is in flight (the page then just polls [getRelaySyncStatus]).
     * Wired to the Sync button in library_local.html.
     */
    @JavascriptInterface
    fun syncFromRelay(): String {
        if (!isTrusted()) return denied("syncFromRelay")
        if (RelayMediaSync.syncing) {
            return JSONObject().put("ok", true).put("status", "already-running").toString()
        }
        Thread {
            runCatching { RelayMediaSync.syncBlocking(context) }
        }.start()
        return JSONObject().put("ok", true).put("status", "started").toString()
    }

    /**
     * JSON object keyed by library-relative path: files pulled by sync but
     * not yet opened. library_local.html renders these as glowing dots.
     */
    @JavascriptInterface
    fun getRelayNewFiles(): String {
        if (!isTrusted()) return denied("getRelayNewFiles")
        return RelayMediaSync.newFilesJson(context)
    }

    /** Clear a file's new-from-sync dot once the user opens it. */
    @JavascriptInterface
    fun markRelayFileSeen(relativePath: String?): String {
        if (!isTrusted()) return denied("markRelayFileSeen")
        RelayMediaSync.markFileSeen(context, relativePath ?: "")
        return JSONObject().put("ok", true).toString()
    }

    /**
     * Poll target for the Sync button's busy state: `{ok, syncing,
     * lastSyncAtMs, lastSummary}` — lastSummary is the JSON summary string
     * of the most recent completed run.
     */
    @JavascriptInterface
    fun getRelaySyncStatus(): String {
        if (!isTrusted()) return denied("getRelaySyncStatus")
        return JSONObject()
            .put("ok", true)
            .put("syncing", RelayMediaSync.syncing)
            .put("lastSyncAtMs", RelayMediaSync.lastSyncAtMs)
            .put("lastSummary", RelayMediaSync.lastSummary)
            .toString()
    }

    // ── Embedded captions ──────────────────────────────────────────────

    /**
     * Demux subtitle tracks muxed INSIDE a media container (MP4 `mov_text`,
     * MKV/WebM-muxed SRT/VTT) and return them as the same
     * `[{start, end, text}]` cue JSON (seconds, fractional) the page's SRT
     * engine already renders. media_player.html tries this automatically
     * when no sidecar `.srt`/`.vtt` exists — priority is `?srt=` param,
     * then sidecar file, then this embedded extraction.
     *
     * [source] is a library-relative path (re-checked for Media-root
     * containment) or a `content:`/`file:` URI string (DCIM entries).
     * Returns "[]" when the file has no usable embedded subtitle track.
     * Blocking is fine here: bridge methods run on the WebView's bridge
     * thread, never the UI thread.
     */
    @JavascriptInterface
    fun getEmbeddedCaptions(source: String?): String {
        if (!isTrusted()) return denied("getEmbeddedCaptions")
        val src = source?.trim().orEmpty()
        if (src.isEmpty()) return "[]"
        val resolved = if (src.startsWith("content:", ignoreCase = true) ||
            src.startsWith("file:", ignoreCase = true)
        ) {
            src
        } else {
            service.resolveSafe(src)?.absolutePath ?: return "[]"
        }
        return EmbeddedCaptionExtractor.extract(context, resolved) ?: "[]"
    }

    // ── Root info ──────────────────────────────────────────────────────

    @JavascriptInterface
    fun getRootInfo(): String {
        if (!isTrusted()) return denied("getRootInfo")
        val root = service.mediaRoot
        val free = try { root.freeSpace } catch (e: Exception) { 0L }
        val total = try { root.totalSpace } catch (e: Exception) { 0L }
        return JSONObject()
            .put("rootAbsolute", root.absolutePath)
            .put("rootShortHint", "Android/data/${context.packageName}/files/Media")
            .put("freeBytes", free)
            .put("totalBytes", total)
            .toString()
    }

    // ── Cloud Text-to-Speech (Gemini 3.1 / Fish.audio) ─────────────────

    /**
     * Internal payload type that both the Gemini and Fish synth paths
     * collapse into. Keeps [speakText]/[startSpeakText] free of engine
     * branching at the wire-format level — they just translate the
     * winning [SynthOutcome] into the JSON the JS side already expects.
     */
    private sealed class SynthOutcome {
        data class Ok(
            val wavBytes: ByteArray,
            val mimeType: String,
            val model: String,
            val sampleRate: Int,
            val engine: String,
            val voiceName: String
        ) : SynthOutcome()

        data class Err(val message: String) : SynthOutcome()
    }

    /**
     * Decide whether to route this synth through Fish or Gemini. Both
     * fall back to the other if their preferred engine isn't usable, so
     * the user never sees a silent reader because of a single mis-saved
     * preference.
     *
     * Routing rules:
     *   1. If `readout_engine == "fish"` AND Fish has a key + a picked
     *      voice, try Fish first; on Fish error, fall through to Gemini.
     *   2. Otherwise (engine == "gemini" or Fish not ready), use Gemini.
     *   3. If both fail, return the most informative error message.
     */
    private fun synthChunk(chunk: String, voiceHint: String?): SynthOutcome {
        val wantFish = isFishEngineActive(context) && isFishReadoutReady(context)
        if (wantFish && fishTtsClient != null) {
            when (val result = fishTtsClient.synthesize(chunk)) {
                is FishTtsClient.SynthesisResult.Success -> {
                    val cfg = resolveGlassesFishConfig(context, nullIfNotActiveEngine = false)
                    return SynthOutcome.Ok(
                        wavBytes = result.wavBytes,
                        mimeType = result.mimeType,
                        model = result.model,
                        sampleRate = result.sampleRate,
                        engine = "fish",
                        voiceName = cfg?.activeVoiceName.orEmpty()
                    )
                }
                is FishTtsClient.SynthesisResult.NotConfigured -> {
                    Log.i(TAG, "Fish engine selected but not fully configured — falling through to Gemini.")
                    // Fall through to Gemini below.
                }
                is FishTtsClient.SynthesisResult.Error -> {
                    Log.w(TAG, "Fish synth failed (${result.message}); falling back to Gemini.")
                    // Fall through to Gemini below.
                }
            }
        }
        // Gemini path — either the user picked Gemini, or Fish is unavailable.
        val gemini = ttsClient ?: return SynthOutcome.Err("TTS not wired on this build.")
        return when (val gemResult = gemini.synthesize(chunk, voiceHint?.trim()?.takeIf { it.isNotBlank() })) {
            is GlassesTtsClient.SynthesisResult.Success -> {
                val voiceName = context
                    .getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
                    .getString("research_tts_voice_name", "")?.trim().orEmpty()
                SynthOutcome.Ok(
                    wavBytes = gemResult.wavBytes,
                    mimeType = gemResult.mimeType,
                    model = gemResult.model,
                    sampleRate = gemResult.sampleRate,
                    engine = "gemini",
                    voiceName = voiceName.ifBlank { "Kore" }
                )
            }
            is GlassesTtsClient.SynthesisResult.ApiKeyMissing -> SynthOutcome.Err(
                "Gemini API key not configured. Set it in the companion app."
            )
            is GlassesTtsClient.SynthesisResult.Error -> SynthOutcome.Err(gemResult.message)
        }
    }

    private fun outcomeToJson(outcome: SynthOutcome): String = when (outcome) {
        is SynthOutcome.Ok -> {
            val id = TtsCacheStore.put(outcome.wavBytes)
            JSONObject()
                .put("audioUrl", "https://$ASSETS_HOST/tts/$id.wav")
                .put("model", outcome.model)
                .put("sampleRate", outcome.sampleRate)
                .put("engine", outcome.engine)
                .put("voiceName", outcome.voiceName)
                .toString()
        }
        is SynthOutcome.Err -> JSONObject().put("error", outcome.message).toString()
    }

    /**
     * Probe what engine + voice will actually be used the next time the
     * reader fires. Surfaced to JS so media_player.html can show a live
     * "Fish · Auntie Mae" / "Gemini · Kore" indicator next to the scrub
     * bar without a round-trip through synthesis.
     *
     * Returned shape:
     *   { engine: "gemini"|"fish", voiceName: "Kore", ready: true,
     *     reason: "" | "<why it isn't ready>" }
     */
    @JavascriptInterface
    fun probeTts(): String {
        if (!isTrusted()) return denied("probeTts")
        val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
        val selected = (prefs.getString("readout_engine", "gemini") ?: "gemini").trim()
        // Effective engine reflects fallback: if user picked Fish but
        // there's no key/voice, the next synthesis will run on Gemini.
        val fishReady = isFishReadoutReady(context)
        val effective = if (selected == "fish" && fishReady) "fish" else "gemini"
        val obj = JSONObject().put("engine", effective).put("selected", selected)
        // Whether the JS side should strip Markdown / decoration marks
        // before sending text to the synth. Gemini handles these
        // implicitly; Fish.audio's realistic voices read them out
        // literally ("asterisk asterisk", "underline emphasis underline",
        // "open bracket Illustration close bracket"). Default true so
        // the experience is good out of the box; user can toggle off in
        // the companion app if they specifically want every character
        // spoken. Returned in the probe payload regardless of engine
        // so the JS reader can decide once and not branch on engine.
        obj.put("cleanText", prefs.getBoolean("fish_clean_text", true))
        when (effective) {
            "fish" -> {
                val cfg = resolveGlassesFishConfig(context, nullIfNotActiveEngine = false)
                obj.put("voiceName", cfg?.activeVoiceName.orEmpty())
                obj.put("model", cfg?.model?.ifBlank { "s2-pro" } ?: "s2-pro")
                obj.put("ready", true)
                obj.put("reason", "")
            }
            else -> {
                val voiceName = (prefs.getString("research_tts_voice_name", "") ?: "").trim()
                val geminiReady = (prefs.getString("gemini_api_key", "") ?: "").trim().isNotEmpty()
                obj.put("voiceName", voiceName.ifBlank { "Kore" })
                obj.put("model", (prefs.getString("research_tts_model", "") ?: "").ifBlank { "gemini-2.5-flash-preview-tts" })
                obj.put("ready", geminiReady)
                val reason = when {
                    selected == "fish" && !fishReady ->
                        "Fish.audio engine selected but no voice/key — falling back to Gemini."
                    !geminiReady ->
                        "No Gemini API key configured."
                    else -> ""
                }
                obj.put("reason", reason)
            }
        }
        return obj.toString()
    }

    /**
     * Synthesize a chunk of text via the active cloud TTS engine
     * (Gemini 3.1 by default, Fish.audio when the user has switched
     * engines in the companion app) and return a virtual audio URL the
     * WebView can load. The on-glasses media_player.html reads text
     * files by calling this one chunk at a time. The returned JSON
     * shape is:
     *
     *   Success:  {"audioUrl":"…","model":"…","engine":"…","voiceName":"…"}
     *   Failure:  {"error":"<reason>"}
     *
     * The JS side prefers this bridge over browser `speechSynthesis` because
     * the RayNeo WebView doesn't expose Web Speech.
     */
    @JavascriptInterface
    fun speakText(text: String?, voiceHint: String?, @Suppress("UNUSED_PARAMETER") rate: Double): String {
        if (!isTrusted()) return denied("speakText")
        val chunk = text?.trim().orEmpty()
        if (chunk.isBlank()) {
            return JSONObject().put("error", "Nothing to read aloud.").toString()
        }
        val outcome = synthChunk(chunk, voiceHint)
        if (outcome is SynthOutcome.Err) Log.w(TAG, "TTS error: ${outcome.message}")
        return outcomeToJson(outcome)
    }

    /**
     * Async variant of [speakText]. Returns immediately so JS can keep the
     * current chunk playing while the next one is being synthesized — without
     * this, each bridge call blocks the JS thread for the full HTTP round-trip
     * and leaves audible gaps between chunks.
     *
     * Contract:
     *   * Returns `{"status":"pending","requestId":"<id>"}` if kicked off.
     *   * Returns `{"error":"…"}` if the page isn't trusted, no key is set,
     *     no jsEvaluator was wired, or the text is empty.
     *   * On completion, posts `window.__ttsComplete('<id>', <JSON>)` back
     *     into the WebView where JSON is either
     *     `{"audioUrl":"…","model":"…","sampleRate":N}` or `{"error":"…"}`.
     *
     * The JS side maintains a map of requestId → Promise resolver so multiple
     * prefetches can be in flight at once (chunk N+1 queued while N plays).
     */
    @JavascriptInterface
    fun startSpeakText(text: String?, voiceHint: String?, @Suppress("UNUSED_PARAMETER") rate: Double, requestId: String?): String {
        if (!isTrusted()) return denied("startSpeakText")
        val id = requestId?.trim().orEmpty()
        if (id.isEmpty()) {
            return JSONObject().put("error", "Missing requestId.").toString()
        }
        // Snapshot the evaluator so the background callback uses the same
        // reference we validated at call time, even if the host later swaps
        // it out during the request.
        val dispatcher = jsEvaluator ?: return JSONObject()
            .put("error", "Async TTS not wired on this build.")
            .toString()
        val chunk = text?.trim().orEmpty()
        if (chunk.isBlank()) {
            return JSONObject().put("error", "Nothing to read aloud.").toString()
        }
        if (ttsClient == null && fishTtsClient == null) {
            return JSONObject().put("error", "TTS not wired on this build.").toString()
        }
        val voice = voiceHint?.trim()?.takeIf { it.isNotBlank() }
        ttsExecutor.execute {
            val payload: String = try {
                val outcome = synthChunk(chunk, voice)
                if (outcome is SynthOutcome.Err) {
                    Log.w(TAG, "TTS error (req=$id): ${outcome.message}")
                }
                outcomeToJson(outcome)
            } catch (t: Throwable) {
                Log.e(TAG, "Unhandled TTS synth exception (req=$id)", t)
                JSONObject().put("error", t.message ?: t.javaClass.simpleName).toString()
            }
            // jsEvaluator must dispatch this to the WebView's main thread.
            val js = "window.__ttsComplete && window.__ttsComplete(${JSONObject.quote(id)}, ${JSONObject.quote(payload)});"
            dispatcher(js)
        }
        return JSONObject()
            .put("status", "pending")
            .put("requestId", id)
            .toString()
    }

    /**
     * Clear the TTS cache so any in-flight `<audio>` load that races with a
     * stop can 404 cleanly instead of playing after the user hit stop.
     */
    @JavascriptInterface
    fun stopSpeaking(): String {
        TtsCacheStore.clear()
        return JSONObject().put("status", "stopped").toString()
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun toMediaUrl(relativePath: String): String {
        val clean = relativePath.trim().trimStart('/', '\\')
        if (clean.isEmpty()) return ""
        // URL-encode each path segment so spaces / unicode survive.
        val encoded = clean.split('/').joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        return "https://$ASSETS_HOST/media/$encoded"
    }
}
