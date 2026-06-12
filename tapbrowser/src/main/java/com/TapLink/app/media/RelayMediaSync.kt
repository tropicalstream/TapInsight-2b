package com.TapLink.app.media

import android.content.Context
import com.TapLinkX3.app.BuildConfig
import com.TapLinkX3.app.DebugLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * RelayMediaSync — pull-sync engine that keeps the on-glasses Media Library
 * topped up with whatever Hermes/OpenClaw stage on the Mac relay.
 *
 * Why PULL instead of push: the glasses roam between networks (and sit
 * behind Cloudflare when remote), so the Mac can never reliably reach them
 * by IP. The glasses, however, can always reach
 * the operator's Cloudflare-tunnelled domain — so they ask the relay what is staged
 * (GET /media-index.json) and download anything new themselves
 * (GET /media/<filename>). The Mac never needs to know the glasses' IP.
 *
 * Index schema (see tools/image_relay.py — _serve_media_index):
 *   { count, files: [ { filename, url, absolute_url, mime, size_bytes,
 *                       modified, root } ] }
 * The file key is "filename" (NOT "name"); "modified" is a local-time ISO
 * timestamp with seconds precision; the list arrives newest-first.
 *
 * Downloaded files are routed into the standard library buckets
 * (Text, Photos, Music, Videos — playlists land in Playlists) using
 * MediaLibraryService.defaultFolderForFilename with the relay's MIME type
 * as the kind hint. NOTE: never write MIME globs like "text" + "/" + "*"
 * inside a block comment — Kotlin block comments NEST and an unmatched
 * comment-opener inside one breaks the whole file.
 *
 * Guard rails per run: at most MAX_PER_RUN files, each at most MAX_BYTES,
 * and only files modified within the last MAX_AGE_DAYS days. The relay's
 * camera frame (and our own README) are never synced.
 *
 * Sync ledger (tombstones): a hidden .relay_sync_ledger.json in the media
 * root records every file version ever synced (name + size + modified
 * timestamp). A file the user deleted on the glasses STAYS deleted — it is
 * only fetched again when the staged copy on the Mac changes (new size or
 * mtime), because that makes the index entry differ from the ledger entry.
 *
 * New-file dots: a second hidden file (.relay_sync_new.json) next to the
 * ledger records library-relative paths pulled by sync that the user has
 * not opened yet; library_local.html renders these with a glowing dot and
 * clears them through markRelayFileSeen.
 *
 * Trigger surface (all debounced through syncAsync, plus direct
 * syncBlocking calls from the 5-minute MainActivity loop and the library
 * page's Sync button via MediaLibraryBridge.syncFromRelay):
 *   - 5-minute auto-sync loop (first run ~20s after launch)
 *   - hermes chat-turn completion
 *   - notification arrival (push or 5-minute pull drain)
 *   - manual Sync button in the Media Library toolbar
 */
object RelayMediaSync {

    private const val TAG = "RelayMediaSync"
    private const val PREFS_NAME = "visionclaw_prefs"
    private const val PREF_RELAY_MEDIA_BASE = "relay_media_base"
    /** Build-time operator relay base (TAPINSIGHT_RELAY_BASE gradle
     *  property); blank when unconfigured — sync then only runs if the
     *  user set relay_media_base in prefs. */
    private val DEFAULT_RELAY_BASE: String =
        BuildConfig.DEFAULT_RELAY_BASE.trim().trimEnd('/')

    /** Hard cap on downloads per run — a fresh install never bulk-slurps. */
    private const val MAX_PER_RUN = 25

    /** Per-file size ceiling (bytes). Big album rips travel over USB, not relay. */
    private const val MAX_BYTES = 20_000_000L

    /** Only files staged within this many days are eligible. */
    private const val MAX_AGE_DAYS = 14L

    /** Never sync these — the live camera frame and our own bootstrap README. */
    private val EXCLUDED_FILES = setOf("camera_frame.jpg", "README.txt")

    /** Hidden ledger in the media root: every file version ever synced. */
    private const val LEDGER_FILE = ".relay_sync_ledger.json"

    /** Hidden new-file set next to the ledger: synced-but-not-yet-opened paths. */
    private const val NEW_FILE = ".relay_sync_new.json"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    var syncing: Boolean = false
        private set

    @Volatile
    var lastSummary: String = ""
        private set

    @Volatile
    var lastSyncAtMs: Long = 0L
        private set

    @Volatile
    private var lastStartMs: Long = 0L

    // ── Ledger + new-file set persistence ──────────────────────────────

    private fun loadLedger(root: File): JSONObject {
        return try {
            val f = File(root, LEDGER_FILE)
            if (f.isFile) JSONObject(f.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun loadNewSet(root: File): JSONObject {
        return try {
            val f = File(root, NEW_FILE)
            if (f.isFile) JSONObject(f.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveNewSet(root: File, obj: JSONObject) {
        try {
            File(root, NEW_FILE).writeText(obj.toString())
        } catch (e: Exception) {
            DebugLog.w(TAG, "new-set save failed: ${e.message}")
        }
    }

    /** JSON object keyed by library-relative path — files synced but not yet opened. */
    fun newFilesJson(context: Context): String {
        return try {
            val root = MediaLibraryService(context).let { it.ensureBootstrap(); it.mediaRoot }
            loadNewSet(root).toString()
        } catch (e: Exception) {
            "{}"
        }
    }

    /** Clear a file's new-from-sync dot (the user opened it). */
    fun markFileSeen(context: Context, relativePath: String) {
        val rel = relativePath.trim()
        if (rel.isBlank()) return
        try {
            val root = MediaLibraryService(context).let { it.ensureBootstrap(); it.mediaRoot }
            val set = loadNewSet(root)
            if (set.has(rel)) {
                set.remove(rel)
                saveNewSet(root, set)
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "markFileSeen failed: ${e.message}")
        }
    }

    /** Atomic-ish write: temp file then rename, with copy fallback. */
    private fun saveLedger(root: File, ledger: JSONObject) {
        try {
            val tmp = File(root, "$LEDGER_FILE.tmp")
            tmp.writeText(ledger.toString())
            if (!tmp.renameTo(File(root, LEDGER_FILE))) {
                tmp.copyTo(File(root, LEDGER_FILE), overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "ledger save failed: ${e.message}")
        }
    }

    // ── Trigger surface ────────────────────────────────────────────────

    /**
     * Shared debounced entry point for every event-driven trigger (hermes
     * chat-turn completion, notification arrival, …). Skips when a sync is
     * already running or one started in the last 10 seconds — a chat turn
     * that also rings the bell must not fire two back-to-back syncs.
     */
    fun syncAsync(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        if (syncing || now - lastStartMs < 10_000) return
        lastStartMs = now
        DebugLog.d(TAG, "event-driven sync triggered ($reason)")
        val app = context.applicationContext
        Thread {
            runCatching { syncBlocking(app) }
        }.start()
    }

    /**
     * Relay base URL — overridable through prefs for testing, defaulting to
     * the public Cloudflare hostname. Trailing slashes stripped so callers
     * can append "/media-index.json" etc. directly.
     */
    fun relayBase(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val base = prefs.getString(PREF_RELAY_MEDIA_BASE, null)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_RELAY_BASE
        return base.trimEnd('/')
    }

    // ── The sync run ───────────────────────────────────────────────────

    /**
     * One full sync pass. Safe to call from any background thread; never
     * call on the main thread (network + file IO). Returns the run summary
     * (also stored in [lastSummary] as a JSON string for the library page):
     *   success — { ok: true, fetched, skipped, errors }
     *   failure — { ok: false, error }
     */
    fun syncBlocking(context: Context): JSONObject {
        synchronized(this) {
            if (syncing) {
                return JSONObject().put("ok", false).put("error", "sync already running")
            }
            syncing = true
        }
        lastStartMs = System.currentTimeMillis()

        val service = MediaLibraryService(context)
        service.ensureBootstrap()
        val root = service.mediaRoot
        val ledger = loadLedger(root)
        val newSet = loadNewSet(root)
        var ledgerDirty = false
        var newSetDirty = false

        var fetched = 0
        var skipped = 0
        var errors = 0
        var summary: JSONObject

        try {
            val base = relayBase(context)
            val indexReq = Request.Builder().url("$base/media-index.json").build()
            val indexBody: String = http.newCall(indexReq).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("index fetch HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("empty index response")
            }
            val index = JSONObject(indexBody)
            val filesArr = index.optJSONArray("files") ?: JSONArray()
            DebugLog.d(TAG, "sync start: base=$base indexed=${filesArr.length()}")

            // The relay already serves newest-first, but sort defensively —
            // the 25-per-run cap must always spend itself on the NEWEST files.
            // ISO timestamps compare correctly as plain strings.
            val items = ArrayList<JSONObject>(filesArr.length())
            for (i in 0 until filesArr.length()) {
                filesArr.optJSONObject(i)?.let { items.add(it) }
            }
            items.sortByDescending { it.optString("modified") }

            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val cutoffMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(MAX_AGE_DAYS)

            for (item in items) {
                if (fetched >= MAX_PER_RUN) break
                try {
                    // NOTE: the index file key is "filename", not "name".
                    val name = item.optString("filename").trim()
                    if (name.isBlank() || name.startsWith(".")) continue
                    if (name in EXCLUDED_FILES) continue

                    val size = item.optLong("size_bytes", -1L)
                    if (size > MAX_BYTES) {
                        skipped++
                        continue
                    }

                    val modified = item.optString("modified")
                    val modifiedMs = try {
                        isoFormat.parse(modified)?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                    if (modifiedMs > 0 && modifiedMs < cutoffMs) {
                        // Older than the sync window — leave it for USB/companion.
                        skipped++
                        continue
                    }

                    // Tombstone check: a version we have synced before is never
                    // fetched again — even if the user deleted the local copy.
                    // Only a CHANGED staged copy (new size or mtime) re-downloads.
                    val prior = ledger.optJSONObject(name)
                    if (prior != null &&
                        prior.optLong("size", -1L) == size &&
                        prior.optString("modified") == modified
                    ) {
                        skipped++
                        continue
                    }

                    val mime = item.optString("mime")
                    val bucket = service.defaultFolderForFilename(name, mime)
                    val rel = "$bucket/$name"
                    val dest = service.resolveSafe(rel)
                    if (dest == null) {
                        DebugLog.w(TAG, "rejected unsafe filename from index: $name")
                        errors++
                        continue
                    }
                    dest.parentFile?.mkdirs()

                    // Download to a hidden temp file (the library hides
                    // dotfiles) and rename into place so a dropped connection
                    // never leaves a half-file visible.
                    val fileReq = Request.Builder().url("$base/media/$name").build()
                    http.newCall(fileReq).execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        val body = resp.body ?: throw Exception("empty body")
                        val tmp = File(dest.parentFile, ".${dest.name}.part")
                        body.byteStream().use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (!tmp.renameTo(dest)) {
                            tmp.copyTo(dest, overwrite = true)
                            tmp.delete()
                        }
                    }
                    if (modifiedMs > 0) dest.setLastModified(modifiedMs)

                    ledger.put(
                        name,
                        JSONObject()
                            .put("size", size)
                            .put("modified", modified)
                            .put("syncedAtMs", System.currentTimeMillis())
                    )
                    ledgerDirty = true
                    newSet.put(rel, System.currentTimeMillis())
                    newSetDirty = true
                    fetched++
                    DebugLog.d(TAG, "downloaded $rel ($size bytes)")
                } catch (e: Exception) {
                    errors++
                    DebugLog.w(TAG, "file sync failed: ${e.message}")
                }
            }

            summary = JSONObject()
                .put("ok", true)
                .put("fetched", fetched)
                .put("skipped", skipped)
                .put("errors", errors)
            DebugLog.d(TAG, "sync done: fetched=$fetched skipped=$skipped errors=$errors")
        } catch (e: Exception) {
            summary = JSONObject()
                .put("ok", false)
                .put("error", e.message ?: e.toString())
            DebugLog.w(TAG, "sync failed: ${e.message}")
        } finally {
            if (ledgerDirty) saveLedger(root, ledger)
            if (newSetDirty) saveNewSet(root, newSet)
            syncing = false
        }

        lastSummary = summary.toString()
        lastSyncAtMs = System.currentTimeMillis()
        return summary
    }
}
