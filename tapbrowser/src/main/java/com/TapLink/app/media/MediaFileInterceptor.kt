package com.TapLink.app.media

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.Locale

/**
 * WebView resource interceptor that serves files from the on-glasses Media
 * folder and the asset pages that drive the library UI through a single
 * virtual https host.
 *
 *   https://appassets.androidplatform.net/media/<relative-path>   (media bytes)
 *   https://appassets.androidplatform.net/assets/<filename>        (APK assets)
 *
 * Why both on one host: library_local.html and media_player.html issue
 * cross-origin sub-resource requests for their media. Loading the HTML
 * from file:///android_asset/... and the media from https://appassets
 * means the audio element is pulling cross-origin — which the WebView
 * silently drops for many sub-resource types (and which breaks entirely
 * when allowFileAccess = false). Serving both the page and its media from
 * the same virtual origin makes everything same-origin and lets <audio>
 * / <video> elements load the interceptor-backed URL without any CORS
 * gymnastics.
 *
 * The virtual host mirrors what WebViewAssetLoader uses, but we do the
 * routing by hand so we can:
 *
 *   • Honor Range / partial-content requests for audio/video seeking. The
 *     built-in PathHandler API only receives the path string, so it can't
 *     see the Range header — which means HTML5 <audio>/<video> can't
 *     resume mid-stream. Doing it ourselves gives us 206 + Content-Range.
 *   • Route through MediaLibraryService.resolveSafe(), which enforces
 *     Media-root containment the same way MediaLibraryBridge does, so a
 *     compromised asset page can't escape the library root even via
 *     absolute/encoded URL tricks.
 *
 * Not gated on the "trusted asset" check — the URL space is read-only and
 * contained to Media/ + the packaged assets, and we'd otherwise break
 * third-party HTTPS pages from ever loading these URLs if they tried
 * (they can't actually see them, but keeping the interceptor unconditional
 * is simpler and matches the semantics of a real asset host).
 */
class MediaFileInterceptor(
    private val context: Context,
    private val service: MediaLibraryService
) {

    /**
     * Lazy enumerator for the DCIM proxy route. Allocated on first
     * `/dcim/…` request so a build without DCIM access (or one whose
     * users never grant the permission) doesn't carry the allocation
     * cost. Wraps MediaStore queries / openInputStream — the actual
     * work is one ContentResolver call per request.
     */
    private val dcim: DcimEnumerator by lazy { DcimEnumerator(context) }

    companion object {
        private const val TAG = "MediaFileInterceptor"
        const val HOST = "appassets.androidplatform.net"
        const val MEDIA_PREFIX = "/media/"
        const val ASSETS_PREFIX = "/assets/"
        const val TTS_PREFIX = "/tts/"
        /**
         * Proxy prefix for RayNeo native Camera (DCIM) photos and videos.
         * `<kind>` is "image" or "video"; `<id>` is the MediaStore _ID
         * (i.e. the suffix of the content:// URI). The WebView can't load
         * `<img src="content://…">` directly — even with
         * setAllowContentAccess, the resolver behaves unreliably across
         * RayNeo's OEM WebView. Routing through this proxy means DCIM
         * photos load via the same virtual https origin as library
         * photos, with no special WebView config required.
         */
        const val DCIM_PREFIX = "/dcim/"

        /**
         * Proxy prefix for direct-filesystem reads of DCIM files. Used
         * for photos that File.walk can enumerate but MediaStore refuses
         * to register (a real-world failure mode on some Android builds
         * where system services write files via raw filesystem calls).
         * Path is URL-encoded after the prefix; the handler enforces
         * containment inside `/storage/emulated/0/DCIM/` so this can't
         * be coerced into reading arbitrary files.
         */
        const val LOCAL_PREFIX = "/local-image/"

        /**
         * Dedicated proxy prefix for direct-filesystem DCIM videos.
         * Unlike /local-image/, this accepts a path relative to DCIM
         * instead of an encoded absolute path. Chromium's media loader is
         * much pickier than <img>; avoiding `%2Fstorage%2F...` absolute
         * paths keeps local videos on a normal URL shape while preserving
         * the same DCIM containment and Range-aware streaming.
         */
        const val LOCAL_VIDEO_PREFIX = "/local-video/"

        /**
         * Safety: the only filesystem root /local-image/ will read from.
         * Any decoded path must `startsWith` this prefix and must not
         * contain `..` segments. Kept narrow on purpose so the proxy
         * can't escape into app-private storage or other apps' data.
         */
        const val LOCAL_ALLOWED_ROOT = "/storage/emulated/0/DCIM/"

        /**
         * Proxy prefix for video thumbnails extracted from local
         * DCIM files. MediaMetadataRetriever pulls the first decoded
         * frame, we compress to JPEG, and serve through this URL so
         * the gallery grid can paint a real thumbnail instead of the
         * generic placeholder. Same path-safety rules as /local-image/.
         */
        const val LOCAL_VIDEO_THUMB_PREFIX = "/local-video-thumb/"

        /**
         * Filenames we're willing to serve out of the APK assets/ folder via
         * https://appassets.androidplatform.net/assets/ . Kept narrow on
         * purpose — a stray third-party page shouldn't be able to walk
         * assets/ just because it guessed a name.
         */
        private val ALLOWED_ASSET_PAGES = setOf(
            "library_local.html",
            "media_player.html",
            // radio.html is served on the same virtual https origin so that
            // library_local.html can hand off HTTP-stream playlists to it
            // without a cross-protocol (https → file://) top-level
            // navigation, which the WebView silently blocks. See
            // library_local.html#openPlaylistInTapRadio and
            // OpenClawTool.addMediaLibraryHint for the navigation contract.
            "radio.html",
            // Material-style photos gallery. Reached via three paths:
            //   1. CameraTool returns `open_taplink:.../photos_gallery.html?focus=…`
            //      after `save_photo`, so the user can say "view it".
            //   2. library_local.html navigates here when the user taps an
            //      IMAGE/VIDEO entry in the Photos folder.
            //   3. Gemini's "open the gallery" voice command.
            // Without this entry the WebView 404s the main-frame load and
            // the browser shows a generic "Not found" page.
            "photos_gallery.html"
        )

        /**
         * RayNeo's WebView media stack can fail with net::ERR_FAILED when
         * Chromium consumes a very small tail Range from a custom
         * RandomAccessFile-backed WebResourceResponse stream. MP4 playback
         * probes these tail ranges even when the moov atom is front-loaded.
         * Buffering small ranges into a ByteArrayInputStream makes those
         * probes deterministic while keeping large media ranges streaming.
         */
        private const val BUFFERED_RANGE_MAX_BYTES = 1 * 1024 * 1024

        /**
         * DCIM camera clips are usually short. On the RayNeo WebView, direct
         * local video playback has proved more fragile than library audio:
         * Chromium's FFmpeg demuxer may issue an initial open-ended
         * `bytes=0-` request plus tiny tail probes, then report
         * PIPELINE_ERROR_READ if either response is backed by a live
         * RandomAccessFile stream. For bounded local camera clips, hand it a
         * plain ByteArrayInputStream so all reads are deterministic. Larger
         * files still stream to avoid blowing up memory.
         */
        private const val LOCAL_VIDEO_BUFFER_MAX_BYTES = 64 * 1024 * 1024

        /** URL of an on-glasses asset page served through this interceptor. */
        fun assetUrl(filename: String): String =
            "https://$HOST$ASSETS_PREFIX${filename.trimStart('/')}"

        /** Return true if the url matches our virtual media host+prefix. */
        fun matches(url: String?): Boolean {
            if (url == null) return false
            val lower = url.lowercase(Locale.US)
            return (lower.startsWith("https://$HOST/media/") ||
                    lower.startsWith("http://$HOST/media/"))
        }

        /** Return true if the url is one of our https-served asset pages. */
        fun matchesAssetPage(url: String?): Boolean {
            if (url == null) return false
            val lower = url.lowercase(Locale.US)
            return (lower.startsWith("https://$HOST/assets/") ||
                    lower.startsWith("http://$HOST/assets/"))
        }
    }

    /**
     * Attempt to handle a WebView resource request. Returns null if the
     * request isn't for our virtual media host, so the caller can fall
     * through to normal network loading.
     */
    fun handle(request: WebResourceRequest?): WebResourceResponse? {
        // Pin to a non-null local so Kotlin smart-casts `req` for later
        // header lookups — `request?.url` alone does not flow a non-null
        // guarantee back to the parameter reference.
        val req = request ?: return null
        val uri = req.url ?: return null
        if (!HOST.equals(uri.host, ignoreCase = true)) return null
        val rawPath = uri.path ?: return null

        // Visibility on the on-glasses hang: log once per request. If the
        // spinner is stuck, `adb logcat | grep MediaFileInterceptor` shows
        // whether a /assets/media_player.html main-frame request AND the
        // subsequent /media/... <audio> request both arrived.
        Log.d(TAG, "handle url=${req.url} method=${req.method} range=${req.requestHeaders?.entries?.firstOrNull { it.key.equals("Range", true) }?.value}")

        // ── /assets/<file> → APK assets (HTML pages that drive the UI) ──
        if (rawPath.startsWith(ASSETS_PREFIX)) {
            return handleAssetRequest(rawPath.removePrefix(ASSETS_PREFIX))
        }

        // ── /tts/<id>.wav → synthesized audio from TtsCacheStore ──
        // Written by MediaLibraryBridge.speakText() when the on-glasses media
        // player wants to read a text file aloud via Gemini 3.1. Same virtual
        // origin as /media/ so the WebView doesn't trip over CORS.
        if (rawPath.startsWith(TTS_PREFIX)) {
            return handleTtsRequest(rawPath.removePrefix(TTS_PREFIX))
        }

        // ── /dcim/<image|video>/<id> → RayNeo native Camera proxy ──
        // Streams DCIM MediaStore bytes through the asset host so the
        // gallery's <img src> works. See [DCIM_PREFIX] for rationale.
        if (rawPath.startsWith(DCIM_PREFIX)) {
            return handleDcimRequest(rawPath.removePrefix(DCIM_PREFIX))
        }

        // ── /local-image/<encoded-path> → direct-filesystem fallback ──
        // For files that exist on disk under DCIM/ but MediaStore refuses
        // to register. See [LOCAL_PREFIX] for the safety contract.
        if (rawPath.startsWith(LOCAL_PREFIX)) {
            val rangeHeader = req.requestHeaders
                ?.entries
                ?.firstOrNull { it.key.equals("Range", ignoreCase = true) }
                ?.value
                ?.trim()
            return handleLocalImageRequest(rawPath.removePrefix(LOCAL_PREFIX), rangeHeader)
        }

        // ── /local-video/<dcim-relative-path> → direct filesystem video ──
        if (rawPath.startsWith(LOCAL_VIDEO_PREFIX)) {
            val rangeHeader = req.requestHeaders
                ?.entries
                ?.firstOrNull { it.key.equals("Range", ignoreCase = true) }
                ?.value
                ?.trim()
            return handleLocalVideoRequest(rawPath.removePrefix(LOCAL_VIDEO_PREFIX), rangeHeader)
        }

        // ── /local-video-thumb/<encoded-path> → first-frame thumbnail ──
        // Returns a small JPEG of the video's first frame so the
        // gallery grid can paint a real thumbnail.
        if (rawPath.startsWith(LOCAL_VIDEO_THUMB_PREFIX)) {
            return handleLocalVideoThumbRequest(rawPath.removePrefix(LOCAL_VIDEO_THUMB_PREFIX))
        }

        if (!rawPath.startsWith(MEDIA_PREFIX)) return null

        val encodedRel = rawPath.removePrefix(MEDIA_PREFIX)
        val relPath = try {
            URLDecoder.decode(encodedRel, "UTF-8")
        } catch (e: Exception) {
            Log.w(TAG, "Bad URL encoding in /media path: $encodedRel")
            return errorResponse(400, "Bad Request")
        }

        val file = service.resolveSafe(relPath)
            ?: run {
                Log.w(TAG, "/media path outside root: $relPath")
                return errorResponse(403, "Forbidden")
            }
        if (!file.exists() || !file.isFile) {
            Log.w(TAG, "/media file not found: relPath=$relPath resolved=${file.path}")
            return errorResponse(404, "Not Found")
        }

        // Determine the real Content-Type from the file's magic bytes,
        // not just the extension. AI-music services like Suno / Udio
        // frequently produce files with `.mp3` extensions but actual
        // M4A/AAC payloads inside. When we serve those as audio/mpeg,
        // Chromium's WebView demuxer (the bundled ffmpeg-based one
        // behind the HTML5 <audio> element) opens the MP3 demuxer,
        // can't find frame sync, and bails with
        //   "demuxer error: could not open ffmpegdemuxer / open context failed"
        // which is exactly what users see in the Media Library player.
        // Sniffing fixes that whole class — Chromium picks the right
        // demuxer when the Content-Type matches the actual bytes.
        val extMime = guessMime(file.name)
        val sniffedMime = sniffAudioMime(file)
        val mime = sniffedMime ?: extMime
        if (sniffedMime != null && !sameAudioFamily(sniffedMime, extMime)) {
            Log.w(
                TAG,
                "MIME override for ${file.name}: extension says $extMime, " +
                    "magic bytes say $sniffedMime — using $sniffedMime so " +
                    "Chromium picks the right demuxer."
            )
        }

        // (Previously: a one-shot self-healing path that detected
        // base64-encoded media files on disk and decoded them in place.
        // Removed once the ingest fix in CompanionServer.writeLibraryFile
        // — explicit encoding="base64" + defensive auto-detect by
        // extension — guaranteed binary files never land on disk as
        // base64 text. Old corrupted files were repaired manually via
        // adb push, so the self-healing path was no longer earning its
        // keep.)

        // For MP3 files, find the start of the first real audio frame.
        // AI-music services (Sonauto, Suno, Udio, MusicGen, ...) often
        // ship MP3s with very large or non-standard ID3v2 tags — embedded
        // cover art, custom GEOB blocks, padded TXXX frames, etc. Chromium's
        // bundled ffmpeg demuxer rejects some of these with
        //   DEMUXER_ERROR_COULD_NOT_OPEN / FFmpegDemuxer: open context failed
        // even though the audio frames after the tag are perfectly valid
        // (which is why the same file plays fine through ExoPlayer when
        // streamed via the OpenClaw path). Skipping the ID3v2 tag bytes
        // entirely sidesteps the problem — browsers don't need ID3 to
        // play audio, just clean frame sync at the start of the byte
        // stream we serve.
        val mp3Offset =
            if (mime.equals("audio/mpeg", ignoreCase = true)) findFirstMp3FrameOffset(file)
            else 0L
        if (mp3Offset > 0L) {
            Log.w(
                TAG,
                "MP3 ID3v2 strip for ${file.name}: skipping first " +
                    "$mp3Offset bytes of tag/preamble so Chromium's demuxer " +
                    "sees clean frame sync from byte 0."
            )
        }
        val totalLength = (file.length() - mp3Offset).coerceAtLeast(0L)
        val rangeHeader = req.requestHeaders
            ?.entries
            ?.firstOrNull { it.key.equals("Range", ignoreCase = true) }
            ?.value
            ?.trim()

        return try {
            if (!rangeHeader.isNullOrEmpty()) {
                buildPartialResponse(file, totalLength, rangeHeader, mime, mp3Offset)
                    ?: buildFullResponse(file, totalLength, mime, mp3Offset)
            } else {
                buildFullResponse(file, totalLength, mime, mp3Offset)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to serve $relPath: ${e.message}")
            errorResponse(500, "Internal Error")
        }
    }

    // ── Asset pages (library_local.html, media_player.html, …) ────────

    /**
     * Serve a file out of the APK's assets/ folder.
     *
     * Path safety: we refuse anything containing "..", leading "/", or a
     * backslash, and only accept an allowlisted set of filenames so a
     * compromised remote page can't brute-force its way into reading
     * arbitrary assets via the virtual host. We intentionally keep the
     * set small — just the pages that drive the library/player UI — and
     * add entries as we need them.
     */
    private fun handleAssetRequest(rawRel: String): WebResourceResponse {
        val cleaned = try {
            URLDecoder.decode(rawRel, "UTF-8")
        } catch (e: Exception) {
            return errorResponse(400, "Bad Request")
        }
        // Strip query/fragment (shouldn't arrive here, but be defensive).
        val bare = cleaned.substringBefore('?').substringBefore('#')
        if (bare.isEmpty() ||
            bare.contains("..") ||
            bare.startsWith("/") ||
            bare.contains('\\')
        ) {
            Log.w(TAG, "Rejecting /assets request: $bare")
            return errorResponse(403, "Forbidden")
        }
        if (bare !in ALLOWED_ASSET_PAGES) {
            Log.w(TAG, "Rejecting non-allowlisted /assets request: $bare")
            return errorResponse(404, "Not Found")
        }

        return try {
            // Read the asset fully so we can hand the WebView a real
            // Content-Length header. Some WebView builds sit on a
            // spinner when the main-frame response is a chunked stream
            // with no declared length, and these pages are small.
            val bytes = context.assets.open(bare).use { it.readBytes() }
            val mime = guessMime(bare)
            val headers = linkedMapOf(
                "Content-Length" to bytes.size.toString(),
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*"
            )
            val encoding = if (mime.startsWith("text/")) "UTF-8" else null
            val resp = WebResourceResponse(mime, encoding, ByteArrayInputStream(bytes))
            resp.responseHeaders = headers
            resp.setStatusCodeAndReasonPhrase(200, "OK")
            Log.d(TAG, "served asset $bare (${bytes.size} bytes, $mime)")
            resp
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open asset $bare: ${e.message}")
            errorResponse(404, "Not Found")
        }
    }

    // ── /tts/<id>.wav → from TtsCacheStore ───────────────────────────

    /**
     * Serve a synthesized TTS WAV out of [TtsCacheStore]. The bridge wrote
     * it there right before asking the JS side to load the URL, so the lookup
     * should hit on the first request; a cache miss only happens if the
     * WebView retries after the cache evicted the entry (bounded LRU).
     */
    private fun handleTtsRequest(rawId: String): WebResourceResponse {
        val bare = rawId.substringBefore('?').substringBefore('#')
        // Strip trailing .wav the JS side appended for the mime hint.
        val id = bare.removeSuffix(".wav")
        val bytes = TtsCacheStore.get(id)
            ?: return errorResponse(404, "TTS cache miss")
        val headers = linkedMapOf(
            "Content-Length" to bytes.size.toString(),
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*"
        )
        val resp = WebResourceResponse("audio/wav", null, ByteArrayInputStream(bytes))
        resp.responseHeaders = headers
        resp.setStatusCodeAndReasonPhrase(200, "OK")
        Log.d(TAG, "served TTS $id (${bytes.size}B)")
        return resp
    }

    // ── /dcim/<kind>/<id> → MediaStore proxy ─────────────────────────

    /**
     * Stream bytes for a RayNeo native Camera DCIM entry through the
     * asset host. `rawTail` looks like `image/12345` or `video/678` —
     * the kind segment tells us which MediaStore collection to base
     * the content URI on, and the id is the row's `_ID`.
     *
     * Why proxy instead of `<img src="content://…">`: even with
     * `WebSettings.setAllowContentAccess(true)`, RayNeo's WebView
     * frequently refuses to load content:// URIs (the resolver isn't
     * always exposed to the renderer process, and some OEMs disable
     * the path entirely). A simple HTTPS proxy through this
     * interceptor avoids the issue, makes the URL identical in shape
     * to library media URLs, and lets the gallery JS use a single
     * code path for both sources.
     */
    private fun handleDcimRequest(rawTail: String): WebResourceResponse {
        val tail = rawTail.substringBefore('?').substringBefore('#')
        val parts = tail.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) {
            return errorResponse(400, "Bad DCIM path")
        }
        val kind = parts[0].lowercase(Locale.US)
        val id = parts[1].toLongOrNull()
            ?: return errorResponse(400, "Bad DCIM id")
        if (!DcimEnumerator.hasPermission(context)) {
            return errorResponse(403, "DCIM permission not granted")
        }
        val baseCollection = when (kind) {
            "image" -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> return errorResponse(400, "Unknown DCIM kind: $kind")
        }
        val contentUri = android.content.ContentUris.withAppendedId(baseCollection, id)
        val bytes = dcim.readBytes(contentUri)
            ?: return errorResponse(404, "DCIM entry not found")
        // Best-effort mime: trust the file extension via DCIM listing,
        // but the cheapest path here is to peek the bytes header. For
        // image/jpeg this is the magic FF D8 FF; for png it's 89 50 4E
        // 47. Anything else we fall back to a generic
        // image/* or video/* based on kind so the WebView still treats
        // the response as image data and the <img> tag renders.
        val mime = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() -> "image/png"
            bytes.size >= 4 && bytes[0] == 0x52.toByte() &&
                bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() &&
                bytes[3] == 0x46.toByte() -> "image/webp"
            kind == "video" -> "video/mp4"
            else -> "image/*"
        }
        val headers = linkedMapOf(
            "Content-Length" to bytes.size.toString(),
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*"
        )
        val resp = WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
        resp.responseHeaders = headers
        resp.setStatusCodeAndReasonPhrase(200, "OK")
        Log.d(TAG, "served DCIM $kind/$id (${bytes.size}B, $mime)")
        return resp
    }

    // ── /local-image/<path> → direct File-system read ────────────────

    /**
     * Serve a file straight off disk, used when MediaStore can't see
     * it but `File.walk()` from the app process can. The encoded path
     * after the prefix is URL-decoded and must satisfy:
     *
     *   • starts with [LOCAL_ALLOWED_ROOT] (no escape into other roots),
     *   • contains no `..` segments (no traversal),
     *   • resolves to a real file under that root,
     *   • is a recognised image or video MIME type.
     *
     * Reject everything else with 403/404. We intentionally don't
     * surface filesystem errors verbatim — just enough to debug.
     */
    private fun handleLocalImageRequest(
        rawTail: String,
        rangeHeader: String?
    ): WebResourceResponse {
        val decoded = try {
            URLDecoder.decode(rawTail, "UTF-8")
        } catch (e: Exception) {
            return errorResponse(400, "Bad path encoding")
        }
        val bare = decoded.substringBefore('?').substringBefore('#')
        if (bare.contains("..") || !bare.startsWith(LOCAL_ALLOWED_ROOT)) {
            Log.w(TAG, "Rejecting /local-image request outside DCIM root: $bare")
            return errorResponse(403, "Forbidden")
        }
        val file = File(bare)
        // canonicalPath collapses any sneaky symlink → if it doesn't
        // still live under DCIM, refuse. (Cheap belt-and-suspenders.)
        val canonical = try { file.canonicalPath } catch (_: Exception) { bare }
        if (!canonical.startsWith(LOCAL_ALLOWED_ROOT)) {
            Log.w(TAG, "Rejecting /local-image after canonicalize: $canonical")
            return errorResponse(403, "Forbidden")
        }
        if (!file.exists() || !file.isFile) {
            return errorResponse(404, "Not found")
        }
        val ext = file.extension.lowercase(Locale.US)
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
            else -> return errorResponse(415, "Unsupported media type")
        }
        return try {
            val length = file.length()
            Log.d(
                TAG,
                "/local-image serving ${file.name} mime=$mime length=$length " +
                    "range='${rangeHeader ?: ""}'"
            )
            // Reuse the same Range-aware response builders /media/ uses.
            // Without 206 Partial Content support the WebView's <video>
            // element refuses to start playback, so videos in particular
            // need this — and audio/large image responses benefit from
            // seeking too. mp3Offset=0 because /local-image/ doesn't do
            // the ID3v2-strip trick that /media/ uses for audio
            // (only relevant for AI-music ingested .mp3 files).
            if (!rangeHeader.isNullOrEmpty()) {
                buildPartialResponse(file, length, rangeHeader, mime, 0L)
                    ?: buildFullResponse(file, length, mime, 0L)
            } else {
                buildFullResponse(file, length, mime, 0L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open /local-image $bare: ${e.message}", e)
            errorResponse(500, "Open failed")
        }
    }

    // ── /local-video/<relative-path> → direct File-system video ───────

    /**
     * Serve a DCIM video by path relative to /storage/emulated/0/DCIM/.
     * This mirrors [handleLocalImageRequest] but avoids encoded absolute
     * filesystem paths in the media element's URL, which can confuse the
     * WebView media pipeline even though thumbnail <img> requests work.
     */
    private fun handleLocalVideoRequest(
        rawTail: String,
        rangeHeader: String?
    ): WebResourceResponse {
        val decoded = try {
            URLDecoder.decode(rawTail, "UTF-8")
        } catch (e: Exception) {
            return errorResponse(400, "Bad path encoding")
        }
        val relative = decoded
            .substringBefore('?')
            .substringBefore('#')
            .trimStart('/')
        if (relative.isBlank() || relative.contains("..") || relative.contains('\\')) {
            Log.w(TAG, "Rejecting /local-video bad relative path: $relative")
            return errorResponse(403, "Forbidden")
        }

        val file = File(LOCAL_ALLOWED_ROOT, relative)
        val canonical = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
        if (!canonical.startsWith(LOCAL_ALLOWED_ROOT)) {
            Log.w(TAG, "Rejecting /local-video after canonicalize: $canonical")
            return errorResponse(403, "Forbidden")
        }
        if (!file.exists() || !file.isFile) {
            return errorResponse(404, "Not found")
        }

        val ext = file.extension.lowercase(Locale.US)
        val mime = when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            else -> return errorResponse(415, "Unsupported video type")
        }

        return try {
            val length = file.length()
            Log.d(
                TAG,
                "/local-video serving ${file.name} mime=$mime length=$length " +
                    "range='${rangeHeader ?: ""}'"
            )
            if (!rangeHeader.isNullOrEmpty()) {
                buildPartialResponse(
                    file = file,
                    totalLength = length,
                    rangeHeader = rangeHeader,
                    mime = mime,
                    skipBytes = 0L,
                    bufferedRangeMaxBytes = LOCAL_VIDEO_BUFFER_MAX_BYTES.toLong()
                ) ?: buildLocalVideoFullResponse(file, length, mime)
            } else {
                buildLocalVideoFullResponse(file, length, mime)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open /local-video $relative: ${e.message}", e)
            errorResponse(500, "Open failed")
        }
    }

    /**
     * In-memory LRU of generated video thumbnails. Keyed by absolute
     * file path + mtime, so a file that's overwritten in place produces
     * a fresh thumbnail. Small cap — these are tiny JPEGs (~10–30 KB
     * each), and we'd rather pay decode cost over OOM risk.
     */
    private val videoThumbCache = object : LinkedHashMap<String, ByteArray>(
        24, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ByteArray>?): Boolean {
            return size > 64
        }
    }

    /**
     * Serve a JPEG thumbnail extracted from the first frame of a
     * local video. Same path-safety rules as [handleLocalImageRequest].
     * Uses MediaMetadataRetriever to grab the first decoded frame,
     * scales it to fit a 320 px box (cheap to encode, plenty for a
     * grid tile), and caches by `<path>@<mtime>` so repeat requests
     * hit memory.
     */
    private fun handleLocalVideoThumbRequest(rawTail: String): WebResourceResponse {
        val decoded = try {
            URLDecoder.decode(rawTail, "UTF-8")
        } catch (e: Exception) {
            return errorResponse(400, "Bad path encoding")
        }
        val bare = decoded.substringBefore('?').substringBefore('#')
        if (bare.contains("..") || !bare.startsWith(LOCAL_ALLOWED_ROOT)) {
            return errorResponse(403, "Forbidden")
        }
        val file = File(bare)
        val canonical = try { file.canonicalPath } catch (_: Exception) { bare }
        if (!canonical.startsWith(LOCAL_ALLOWED_ROOT)) {
            return errorResponse(403, "Forbidden")
        }
        if (!file.exists() || !file.isFile) {
            return errorResponse(404, "Not found")
        }
        val cacheKey = "$bare@${file.lastModified()}"
        val cached = synchronized(videoThumbCache) { videoThumbCache[cacheKey] }
        val bytes = cached ?: try {
            extractVideoThumbnail(file)
        } catch (e: Exception) {
            Log.w(TAG, "extractVideoThumbnail failed for $bare: ${e.message}")
            null
        }
        if (bytes == null || bytes.isEmpty()) {
            return errorResponse(500, "Thumbnail extract failed")
        }
        if (cached == null) {
            synchronized(videoThumbCache) { videoThumbCache[cacheKey] = bytes }
        }
        val headers = linkedMapOf(
            "Content-Length" to bytes.size.toString(),
            // Browser cache helps when scrolling the grid — these are
            // immutable per (path, mtime) so a long max-age is safe.
            "Cache-Control" to "public, max-age=86400",
            "Access-Control-Allow-Origin" to "*"
        )
        val resp = WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes))
        resp.responseHeaders = headers
        resp.setStatusCodeAndReasonPhrase(200, "OK")
        return resp
    }

    /**
     * Pull the first frame from a video via MediaMetadataRetriever,
     * scale to fit a 320 px box, encode as JPEG. Returns null on any
     * failure — the caller surfaces a 500 in that case.
     *
     * Why MediaMetadataRetriever instead of ThumbnailUtils: more
     * predictable behavior on partial/broken files (it'll either
     * succeed with a frame or throw cleanly; ThumbnailUtils sometimes
     * deadlocks on malformed MP4s).
     */
    private fun extractVideoThumbnail(file: File): ByteArray? {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val frame = mmr.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val maxDim = 320
            val w = frame.width
            val h = frame.height
            val scale = if (w > maxDim || h > maxDim) {
                maxDim.toFloat() / maxOf(w, h)
            } else 1f
            val scaled = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    frame, (w * scale).toInt(), (h * scale).toInt(), true
                )
            } else frame
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out)
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
            return out.toByteArray()
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    // ── Response builders ─────────────────────────────────────────────

    private fun buildFullResponse(
        file: File,
        length: Long,
        mime: String,
        skipBytes: Long = 0L
    ): WebResourceResponse {
        val headers = linkedMapOf(
            "Content-Length" to length.toString(),
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*"
        )
        val encoding = if (mime.startsWith("text/")) "UTF-8" else null
        val stream: InputStream = if (skipBytes > 0L) {
            BoundedFileStream(file, skipBytes, length)
        } else {
            FileInputStream(file)
        }
        val resp = WebResourceResponse(mime, encoding, stream)
        resp.responseHeaders = headers
        resp.setStatusCodeAndReasonPhrase(200, "OK")
        return resp
    }

    private fun buildPartialResponse(
        file: File,
        totalLength: Long,
        rangeHeader: String,
        mime: String,
        skipBytes: Long = 0L,
        bufferedRangeMaxBytes: Long = BUFFERED_RANGE_MAX_BYTES.toLong()
    ): WebResourceResponse? {
        val (start, end) = parseRange(rangeHeader, totalLength) ?: run {
            // Return 416 so the client knows to retry without Range.
            val headers = linkedMapOf(
                "Content-Range" to "bytes */$totalLength",
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*"
            )
            val resp = WebResourceResponse(
                "text/plain",
                "UTF-8",
                ByteArrayInputStream(ByteArray(0))
            )
            resp.responseHeaders = headers
            resp.setStatusCodeAndReasonPhrase(416, "Range Not Satisfiable")
            return resp
        }

        val contentLength = end - start + 1
        // When we're stripping a tag preamble (skipBytes > 0), the
        // logical Range space the client sees starts at 0 of the
        // POST-strip stream, but on disk we have to seek to
        // skipBytes + start. The Content-Range we report uses the
        // logical (post-strip) offsets so the client's seek math
        // stays correct.
        val absoluteStart = skipBytes + start
        val stream: InputStream =
            if (contentLength <= bufferedRangeMaxBytes) {
                ByteArrayInputStream(readRangeBytes(file, absoluteStart, contentLength))
            } else {
                BoundedFileStream(file, absoluteStart, contentLength)
            }
        val headers = linkedMapOf(
            "Content-Type" to mime,
            "Content-Length" to contentLength.toString(),
            "Accept-Ranges" to "bytes",
            "Content-Range" to "bytes $start-$end/$totalLength",
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*"
        )
        val encoding = if (mime.startsWith("text/")) "UTF-8" else null
        val resp = WebResourceResponse(mime, encoding, stream)
        resp.responseHeaders = headers
        resp.setStatusCodeAndReasonPhrase(206, "Partial Content")
        return resp
    }

    private fun buildLocalVideoFullResponse(
        file: File,
        length: Long,
        mime: String
    ): WebResourceResponse {
        if (length <= LOCAL_VIDEO_BUFFER_MAX_BYTES) {
            val bytes = readRangeBytes(file, 0L, length)
            val headers = linkedMapOf(
                "Content-Length" to bytes.size.toString(),
                "Accept-Ranges" to "bytes",
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*"
            )
            val resp = WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
            resp.responseHeaders = headers
            resp.setStatusCodeAndReasonPhrase(200, "OK")
            return resp
        }
        return buildFullResponse(file, length, mime, 0L)
    }

    private fun readRangeBytes(file: File, startOffset: Long, length: Long): ByteArray {
        val size = length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = ByteArray(size)
        java.io.RandomAccessFile(file, "r").use { raf ->
            if (startOffset > 0L) raf.seek(startOffset)
            var offset = 0
            while (offset < size) {
                val read = raf.read(bytes, offset, size - offset)
                if (read < 0) break
                offset += read
            }
            return if (offset == size) bytes else bytes.copyOf(offset)
        }
    }

    private fun errorResponse(code: Int, reason: String): WebResourceResponse {
        val resp = WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(reason.toByteArray(Charsets.UTF_8))
        )
        resp.responseHeaders = mapOf(
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*"
        )
        resp.setStatusCodeAndReasonPhrase(code, reason)
        return resp
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Parse an HTTP Range header of the form `bytes=<start>-<end>` (either
     * end optional). Returns null on malformed input or unsatisfiable ranges.
     * Only the first byte-range-spec is honored — we don't support
     * multipart/byteranges.
     */
    private fun parseRange(header: String, totalLength: Long): Pair<Long, Long>? {
        if (totalLength <= 0) return null
        val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
        if (spec.isEmpty()) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startStr = spec.substring(0, dash).trim()
        val endStr = spec.substring(dash + 1).trim()

        val start: Long
        val end: Long
        when {
            startStr.isEmpty() -> {
                // Suffix-range: "bytes=-500" means last 500 bytes.
                val suffix = endStr.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                start = (totalLength - suffix).coerceAtLeast(0L)
                end = totalLength - 1
            }
            endStr.isEmpty() -> {
                // Open-ended range: "bytes=500-" means 500 to end.
                start = startStr.toLongOrNull() ?: return null
                end = totalLength - 1
            }
            else -> {
                start = startStr.toLongOrNull() ?: return null
                end = endStr.toLongOrNull() ?: return null
            }
        }
        if (start < 0 || end < start || start >= totalLength) return null
        val clampedEnd = end.coerceAtMost(totalLength - 1)
        return start to clampedEnd
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/aac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "weba" -> "audio/webm"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "m3u" -> "audio/x-mpegurl"
            "m3u8" -> "application/vnd.apple.mpegurl"
            "txt", "log", "rtf" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "yaml", "yml" -> "application/x-yaml"
            "toml", "ini", "cfg", "conf" -> "text/plain"
            "html", "htm" -> "text/html"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }

    /**
     * Read the first few bytes of [file] and identify the actual audio
     * container from the magic header. Returns null when:
     *   - the file isn't audio (we leave video/image/text alone)
     *   - the bytes don't match any known audio container
     *
     * Used to override the extension-based MIME on the /media path's
     * responses when the file's real format differs from its extension.
     * This specifically fixes AI-music files that ship with `.mp3`
     * names but contain MP4/AAC, OGG/Opus, or FLAC payloads.
     *
     * NOTE on KDoc safety: do NOT write "slash-asterisk" sequences
     * inside this comment. Kotlin allows nested block comments, so a
     * literal slash-asterisk here would open a nested comment and the
     * first asterisk-slash would close only the nested one, devouring
     * the rest of the file. This bit me once already.
     *
     * Magic-byte references:
     *   ID3v2:        "ID3" (49 44 33) at offset 0
     *   MP3 sync:     11 bits of 1s at offset 0  (FF Ex or FF Fx)
     *   AAC ADTS:     11 bits of 1s at offset 0  (FF Fx) - same shape
     *                  as MP3, distinguished by the layer bits in byte
     *                  1. We label as audio/aac so Chromium tries the
     *                  ADTS demuxer instead of the MP3 one.
     *   ISO BMFF:     "ftyp" (66 74 79 70) at offset 4 - covers M4A
     *                  and MP4 audio
     *   OGG:          "OggS" at offset 0 - both Vorbis and Opus
     *   FLAC:         "fLaC" at offset 0
     *   RIFF/WAVE:    "RIFF" at 0, "WAVE" at 8
     *   Matroska/WebM:0x1A 0x45 0xDF 0xA3 at offset 0 (EBML header)
     */
    private fun sniffAudioMime(file: File): String? {
        val head = ByteArray(16)
        val n = try {
            FileInputStream(file).use { fis ->
                var read = 0
                while (read < head.size) {
                    val r = fis.read(head, read, head.size - read)
                    if (r <= 0) break
                    read += r
                }
                read
            }
        } catch (e: Exception) {
            Log.w(TAG, "sniffAudioMime: failed to read ${file.name}", e)
            return null
        }
        if (n < 4) return null

        fun b(i: Int): Int = head[i].toInt() and 0xFF

        // ID3v2 → MP3 (or rarely an AAC stream that happens to carry an
        // ID3v2 tag, but Chromium handles both via audio/mpeg).
        if (n >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            return "audio/mpeg"
        }
        // OGG (Vorbis or Opus). Server-side we can't easily distinguish
        // without parsing the codec setup; audio/ogg works for both.
        if (n >= 4 && head[0] == 'O'.code.toByte() && head[1] == 'g'.code.toByte() &&
            head[2] == 'g'.code.toByte() && head[3] == 'S'.code.toByte()
        ) return "audio/ogg"
        // FLAC native stream.
        if (n >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
            head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()
        ) return "audio/flac"
        // RIFF/WAVE.
        if (n >= 12 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'A'.code.toByte() &&
            head[10] == 'V'.code.toByte() && head[11] == 'E'.code.toByte()
        ) return "audio/wav"
        // ISO Base Media File Format — M4A / MP4-audio.
        // Bytes 4-7 = "ftyp"; bytes 8-11 = brand (M4A_, mp42, isom, etc.).
        if (n >= 12 &&
            head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        ) {
            val brand = String(head, 8, 4, Charsets.US_ASCII).trim()
            // Audio-only brands → audio/mp4. Generic mp4/mp42/isom can
            // carry either audio or video; for files in the audio-extension
            // path the audio mime works (Chromium probes further).
            return when {
                brand.equals("M4A ", ignoreCase = true) ||
                    brand.equals("M4B ", ignoreCase = true) ||
                    brand.equals("M4P ", ignoreCase = true) -> "audio/mp4"
                else -> "audio/mp4"
            }
        }
        // EBML header → Matroska / WebM container (rare for audio-only,
        // but Suno occasionally exports WebM/Opus with .mp3 ext).
        if (n >= 4 && b(0) == 0x1A && b(1) == 0x45 && b(2) == 0xDF && b(3) == 0xA3) {
            return "audio/webm"
        }
        // Bare MP3 / AAC ADTS — both start with FF E_/F_. We treat them
        // both as audio/mpeg; Chromium picks the right inner demuxer.
        if (n >= 2 && b(0) == 0xFF && (b(1) and 0xE0) == 0xE0) {
            return "audio/mpeg"
        }
        // Unknown — let the caller fall back to the extension-based mime.
        return null
    }

    /**
     * For an MP3 file, return the byte offset of the first audio frame
     * (i.e., the first byte AFTER any leading ID3v2 tag). Returns 0 if
     * there's no ID3v2 tag, or if we can't reliably parse one.
     *
     * ID3v2 layout (ID3v2.3 / ID3v2.4):
     *   bytes 0-2 :  "ID3"   (ASCII 49 44 33)
     *   bytes 3-4 :  major + minor version
     *   byte  5   :  flags (we only care about bit 4 = footer present)
     *   bytes 6-9 :  synchsafe 32-bit size of the tag MINUS this 10-byte
     *               header. "Synchsafe" = each byte's high bit is zero,
     *               so the value is (b6 << 21) | (b7 << 14) | (b8 << 7) | b9.
     *
     * Some encoders (including a few AI-music services) chain multiple
     * ID3v2 tags or pad heavily. We loop, advancing past each tag we
     * find, until the next bytes don't look like an ID3v2 header.
     *
     * Why this fixes the demuxer: Chromium's bundled ffmpeg MP3 demuxer
     * can fail to skip past oversized tags or ones with unfamiliar
     * frame IDs. Serving from the audio-frame offset bypasses the
     * tag entirely. Browsers don't need ID3 to play audio.
     */
    private fun findFirstMp3FrameOffset(file: File): Long {
        return try {
            FileInputStream(file).use { fis ->
                val length = file.length()
                val header = ByteArray(10)
                var offset = 0L
                var done = false
                // Walk chained ID3v2 tags. In practice there's at most
                // one, but the walk is cheap and handles weird AI-music
                // encoders that double-stack tags.
                while (!done && offset < length) {
                    fis.channel.position(offset)
                    var read = 0
                    while (read < header.size) {
                        val r = fis.read(header, read, header.size - read)
                        if (r <= 0) break
                        read += r
                    }
                    val isId3 = read >= 10 &&
                        header[0] == 'I'.code.toByte() &&
                        header[1] == 'D'.code.toByte() &&
                        header[2] == '3'.code.toByte()
                    if (!isId3) {
                        done = true
                    } else {
                        // Parse synchsafe size at bytes 6..9.
                        fun ss(i: Int) = header[i].toInt() and 0x7F
                        val tagSize = (ss(6) shl 21) or (ss(7) shl 14) or
                            (ss(8) shl 7) or ss(9)
                        if (tagSize <= 0) {
                            done = true
                        } else {
                            val flags = header[5].toInt() and 0xFF
                            // ID3v2.4 footer flag adds 10 trailing bytes.
                            val footerExtra = if ((flags and 0x10) != 0) 10 else 0
                            offset += 10L + tagSize.toLong() + footerExtra
                        }
                    }
                }
                offset.coerceAtMost(length)
            }
        } catch (e: Exception) {
            Log.w(TAG, "findFirstMp3FrameOffset: failed for ${file.name}", e)
            0L
        }
    }

    /**
     * Whether two audio MIME strings represent the same logical family,
     * for the purpose of "should I log a warning when the sniff result
     * differs from the extension". Avoids spamming logs for the harmless
     * "extension says audio/aac, sniff says audio/mp4 (M4A container)"
     * case where both would actually decode fine.
     */
    private fun sameAudioFamily(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        val mp4 = setOf("audio/mp4", "audio/aac", "audio/m4a")
        if (a.lowercase() in mp4 && b.lowercase() in mp4) return true
        return false
    }

    // ── Bounded stream ────────────────────────────────────────────────

    /**
     * RandomAccessFile-backed bounded stream. Switched from
     * FileInputStream + skip() because skip() can return less than
     * requested on large seeks (the contract permits short reads),
     * which left the stream positioned wrong for video seek requests
     * and caused FFmpeg's demuxer to read mismatched bytes. seek()
     * on a RAF is atomic and guaranteed to land at the requested
     * absolute offset.
     */
    private class BoundedFileStream(
        file: File,
        startOffset: Long,
        initialRemaining: Long
    ) : InputStream() {
        private val raf = java.io.RandomAccessFile(file, "r").also {
            if (startOffset > 0) it.seek(startOffset)
        }
        private var remaining: Long = initialRemaining

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = raf.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = raf.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int {
            return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        override fun close() {
            try { raf.close() } catch (_: Exception) {}
        }
    }
}
