package com.TapLink.app.media

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale

/**
 * MediaLibraryService — scans, parses, and mutates the on-device media
 * library used by the in-browser playlist player.
 *
 * Lives in the tapbrowser module (not app/) because it needs to be reachable
 * from both the on-glasses WebView bridge (MediaLibraryBridge) AND the
 * companion HTTP server in app/. Since app/ already depends on :tapbrowser
 * as a library module, placing the service here keeps the dependency arrow
 * one-way.
 *
 * Root path: context.getExternalFilesDir(null)/Media/ — which resolves to
 *   /storage/emulated/0/Android/data/com.rayneo.visionclaw/files/Media/
 *
 * This is app-private external storage, visible over USB/MTP, requires no
 * runtime permissions, and is wiped only on full uninstall.
 *
 * On first run ─
 *   Media/
 *   ├── Music/        (audio folder — drop .mp3/.m4a/.ogg/.opus/.wav)
 *   ├── Videos/       (video folder — drop .mp4/.webm/.mkv/.mov)
 *   ├── Playlists/    (M3U/M3U8 station and media playlists)
 *   ├── Text/         (readable text files for Gemini TTS)
 *   └── README.txt    (human-readable setup instructions)
 *
 * Any folder can contain a .m3u playlist file; paths inside the .m3u are
 * resolved relative to the .m3u's own directory (standard M3U behavior).
 */
class MediaLibraryService(private val context: Context) {

    companion object {
        private const val TAG = "MediaLibraryService"
        private const val MEDIA_DIR_NAME = "Media"
        const val DEFAULT_MUSIC_DIR = "Music"
        const val DEFAULT_VIDEOS_DIR = "Videos"
        const val DEFAULT_PLAYLISTS_DIR = "Playlists"
        const val DEFAULT_TEXT_DIR = "Text"
        const val DEFAULT_PHOTOS_DIR = "Photos"

        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "weba")
        val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "avi")
        val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        /**
         * Readable text formats the media player can display (and read aloud
         * via Gemini 3.1 TTS). Kept narrow on purpose — these open in the
         * text-viewer branch of media_player.html.
         */
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "log", "csv", "json", "xml",
            "html", "htm", "rtf", "ini", "cfg", "conf", "yaml", "yml", "toml",
            // Subtitle files are plain text: classifying them as TEXT keeps
            // sidecar .srt/.vtt rows openable in the library instead of
            // rendering as dead OTHER rows.
            "srt", "vtt"
        )

        private const val README_CONTENT =
            "TapInsight Media Library\n" +
                "========================\n\n" +
            "Drop audio, video, playlist, and text files into this folder.\n\n" +
                "Default folders:\n" +
                "  Music/     audio files\n" +
                "  Videos/    video files\n" +
                "  Playlists/ M3U/M3U8 playlists, including IP-radio station lists\n" +
                "  Text/      text files for reading aloud\n\n" +
                "Supported formats:\n" +
                "  Audio: mp3, m4a, aac, ogg, opus, wav, flac\n" +
                "  Video: mp4, webm, mkv, mov, m4v, 3gp, avi\n" +
                "  Text: txt, md, markdown, log, csv, json, xml, html, rtf, ini, cfg, conf, yaml, toml\n" +
                "  Playlists: m3u, m3u8\n\n" +
                "Playlists:\n" +
                "  Create a .m3u file in Playlists/ or any folder. Paths inside the .m3u are\n" +
                "  resolved relative to that folder. Example:\n\n" +
                "    #EXTM3U\n" +
                "    #EXTINF:180,Morning Coffee\n" +
                "    coffee.mp3\n" +
                "    #EXTINF:240,Subway Ride\n" +
                "    ../Videos/commute.mp4\n\n" +
                "Or use the companion web UI to auto-generate and edit playlists.\n"
    }

    /** The root Media folder. Created lazily. */
    val mediaRoot: File by lazy {
        val external = context.getExternalFilesDir(null)
            ?: context.filesDir // fall back to internal on storage emulation quirks
        File(external, MEDIA_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    /** Ensure the default subfolders + README exist. Call on app start. */
    fun ensureBootstrap() {
        try {
            if (!mediaRoot.exists()) mediaRoot.mkdirs()
            File(mediaRoot, DEFAULT_MUSIC_DIR).mkdirs()
            File(mediaRoot, DEFAULT_VIDEOS_DIR).mkdirs()
            File(mediaRoot, DEFAULT_PLAYLISTS_DIR).mkdirs()
            File(mediaRoot, DEFAULT_TEXT_DIR).mkdirs()
            File(mediaRoot, DEFAULT_PHOTOS_DIR).mkdirs()
            val readme = File(mediaRoot, "README.txt")
            if (!readme.exists() || !readme.readText(Charsets.UTF_8).contains("Playlists/")) {
                readme.writeText(README_CONTENT)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bootstrap media root: ${e.message}")
        }
    }

    /**
     * Resolve a relative path (received from the companion UI or browser)
     * to an absolute File, rejecting any attempt to escape the Media root.
     *
     * Returns null if the resolved path escapes mediaRoot.
     */
    fun resolveSafe(relativePath: String): File? {
        val cleaned = relativePath.trim().trimStart('/', '\\')
        val resolved = File(mediaRoot, cleaned).canonicalFile
        val rootCanonical = mediaRoot.canonicalFile
        // canonicalPath avoids issues with ../ and symlinks
        return if (resolved.path == rootCanonical.path ||
                   resolved.path.startsWith(rootCanonical.path + File.separator)) {
            resolved
        } else {
            Log.w(TAG, "Rejected unsafe path: $relativePath → ${resolved.path}")
            null
        }
    }

    /** Convert an absolute file back to a Media-root-relative path. */
    fun relativize(file: File): String {
        val rootPath = mediaRoot.canonicalFile.path
        val filePath = file.canonicalFile.path
        return if (filePath == rootPath) {
            ""
        } else if (filePath.startsWith(rootPath + File.separator)) {
            filePath.substring(rootPath.length + 1).replace(File.separatorChar, '/')
        } else {
            // Outside root — shouldn't happen if callers use resolveSafe, but
            // fall back to the absolute path.
            filePath
        }
    }

    /**
     * Classify a file. The is-directory check runs FIRST: a directory whose
     * name carries a trailing media-style extension ("Album.mp3") must
     * classify as FOLDER, not by its name suffix — the old suffix-first
     * order misclassified such folders in both frontends. Extensionless
     * regular files fall through to magic-byte content sniffing instead of
     * landing as dead OTHER rows.
     */
    fun classify(file: File): MediaKind {
        if (file.isDirectory) return MediaKind.FOLDER
        val ext = file.extension.lowercase(Locale.ROOT)
        return when {
            ext in AUDIO_EXTENSIONS -> MediaKind.AUDIO
            ext in VIDEO_EXTENSIONS -> MediaKind.VIDEO
            ext in PLAYLIST_EXTENSIONS -> MediaKind.PLAYLIST
            ext in TEXT_EXTENSIONS -> MediaKind.TEXT
            ext in IMAGE_EXTENSIONS -> MediaKind.IMAGE
            ext.isEmpty() && file.isFile -> sniffKind(file)
            else -> MediaKind.OTHER
        }
    }

    /**
     * Magic-byte content sniffing for extensionless files. Album rips and
     * other files dropped into the library without an extension used to
     * classify as OTHER — the one kind with no click handler, no delete,
     * and no open action in either frontend. Reading the first bytes and
     * recognizing the common containers makes those rows playable.
     *
     * Signatures (offset 0 unless noted):
     *   ID3v2:       "ID3"                              → AUDIO (MP3)
     *   FLAC:        "fLaC"                             → AUDIO
     *   OGG:         "OggS" (Vorbis and Opus)           → AUDIO
     *   RIFF/WAVE:   "RIFF" + "WAVE" at offset 8        → AUDIO
     *   RIFF/AVI:    "RIFF" + "AVI " at offset 8        → VIDEO
     *   AIFF:        "FORM" + "AIFF"/"AIFC" at offset 8 → AUDIO
     *   ISO BMFF:    "ftyp" at offset 4 — the brand at offset 8 tells the
     *                M4A audio family apart from generic MP4 video
     *   EBML:        1A 45 DF A3 (Matroska / WebM)      → VIDEO
     *   MPEG sync:   FF Ex / FF Fx (bare MP3, AAC ADTS) → AUDIO
     */
    private fun sniffKind(file: File): MediaKind {
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
            Log.w(TAG, "sniffKind: failed to read ${file.name}: ${e.message}")
            return MediaKind.OTHER
        }
        if (n < 4) return MediaKind.OTHER

        fun b(i: Int): Int = head[i].toInt() and 0xFF
        fun ascii(offset: Int, text: String): Boolean {
            if (n < offset + text.length) return false
            for (i in text.indices) {
                if (head[offset + i] != text[i].code.toByte()) return false
            }
            return true
        }

        return when {
            // ID3v2 tag → MP3.
            ascii(0, "ID3") -> MediaKind.AUDIO
            // FLAC native stream.
            ascii(0, "fLaC") -> MediaKind.AUDIO
            // OGG container (Vorbis or Opus).
            ascii(0, "OggS") -> MediaKind.AUDIO
            // RIFF — WAVE is audio, AVI is video.
            ascii(0, "RIFF") && ascii(8, "WAVE") -> MediaKind.AUDIO
            ascii(0, "RIFF") && ascii(8, "AVI ") -> MediaKind.VIDEO
            // AIFF / AIFF-C.
            ascii(0, "FORM") && (ascii(8, "AIFF") || ascii(8, "AIFC")) -> MediaKind.AUDIO
            // ISO BMFF (MP4 family): the container brand distinguishes
            // M4A-style audio from generic MP4 video.
            ascii(4, "ftyp") -> {
                val brand = if (n >= 12) {
                    String(head, 8, 4, Charsets.US_ASCII).trim().uppercase(Locale.ROOT)
                } else ""
                if (brand.startsWith("M4A") || brand.startsWith("M4B") || brand.startsWith("M4P")) {
                    MediaKind.AUDIO
                } else {
                    MediaKind.VIDEO
                }
            }
            // EBML header → Matroska / WebM.
            b(0) == 0x1A && b(1) == 0x45 && b(2) == 0xDF && b(3) == 0xA3 -> MediaKind.VIDEO
            // Bare MPEG audio frame sync (MP3 / AAC ADTS).
            b(0) == 0xFF && (b(1) and 0xE0) == 0xE0 -> MediaKind.AUDIO
            else -> MediaKind.OTHER
        }
    }

    /**
     * Pick the standard top-level folder for a new file when the caller has
     * not intentionally chosen a folder. This keeps Gemini/TapClaw/companion
     * saves predictable: audio in Music, video in Videos, station playlists in
     * Playlists, and readable documents in Text.
     */
    fun defaultFolderForFilename(filename: String, kindHint: String? = null): String {
        val cleanKind = kindHint?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val ext = filename.substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        return when {
            cleanKind.contains("playlist") ||
                cleanKind.contains("m3u") ||
                ext in PLAYLIST_EXTENSIONS -> DEFAULT_PLAYLISTS_DIR
            cleanKind.contains("video") || ext in VIDEO_EXTENSIONS -> DEFAULT_VIDEOS_DIR
            cleanKind.contains("text") ||
                cleanKind.contains("document") ||
                ext in TEXT_EXTENSIONS -> DEFAULT_TEXT_DIR
            cleanKind.contains("audio") ||
                cleanKind.contains("music") ||
                ext in AUDIO_EXTENSIONS -> DEFAULT_MUSIC_DIR
            cleanKind.contains("photo") ||
                cleanKind.contains("image") ||
                cleanKind.contains("picture") ||
                ext in IMAGE_EXTENSIONS -> DEFAULT_PHOTOS_DIR
            else -> DEFAULT_TEXT_DIR
        }
    }

    /**
     * List one folder's immediate children (not recursive). Used to drive
     * the companion's folder browser.
     */
    fun listFolder(relativePath: String): FolderListing? {
        val folder = resolveSafe(relativePath) ?: return null
        if (!folder.exists() || !folder.isDirectory) return null

        val entries = folder.listFiles()
            ?.asSequence()
            ?.filter { !it.name.startsWith(".") } // hide dotfiles
            ?.map { file ->
                val kind = classify(file)
                val size = if (file.isFile) file.length() else 0L
                MediaEntry(
                    name = file.name,
                    relativePath = relativize(file),
                    kind = kind,
                    sizeBytes = size,
                    lastModifiedMs = file.lastModified()
                )
            }
            ?.toList()
            ?.sortedWith(compareBy({ it.kind.sortOrder }, { it.name.lowercase(Locale.ROOT) }))
            ?: emptyList()

        return FolderListing(
            relativePath = relativize(folder),
            absolutePath = folder.path,
            entries = entries
        )
    }

    /**
     * Recursively find every .m3u/.m3u8 file under the Media root. Used by
     * the companion library page's "all playlists" view.
     */
    fun findAllPlaylists(): List<MediaEntry> {
        val out = mutableListOf<MediaEntry>()
        walkSafely(mediaRoot) { file ->
            if (file.isFile && file.extension.lowercase(Locale.ROOT) in PLAYLIST_EXTENSIONS) {
                out.add(
                    MediaEntry(
                        name = file.name,
                        relativePath = relativize(file),
                        kind = MediaKind.PLAYLIST,
                        sizeBytes = file.length(),
                        lastModifiedMs = file.lastModified()
                    )
                )
            }
        }
        return out.sortedBy { it.relativePath.lowercase(Locale.ROOT) }
    }

    /** Count playable media files (audio + video) in a folder, non-recursive. */
    fun countPlayableInFolder(folder: File): Int {
        val files = folder.listFiles() ?: return 0
        return files.count {
            it.isFile && it.extension.lowercase(Locale.ROOT).let { ext ->
                ext in AUDIO_EXTENSIONS || ext in VIDEO_EXTENSIONS
            }
        }
    }

    /**
     * Parse a .m3u file into a list of entries. Handles:
     *   - #EXTM3U header (ignored)
     *   - #EXTINF:<duration>,<title> preceding a path line
     *   - blank lines and # comments
     *   - Windows CRLF line endings
     *   - relative paths (resolved against the .m3u's own directory)
     *   - absolute paths and file:// / http(s):// URLs (passed through)
     */
    fun parsePlaylist(playlistFile: File): ParsedPlaylist {
        if (!playlistFile.exists() || !playlistFile.isFile) {
            return ParsedPlaylist(playlistFile.name, emptyList(), emptyList())
        }
        val entries = mutableListOf<PlaylistEntry>()
        val warnings = mutableListOf<String>()
        val baseDir = playlistFile.parentFile ?: mediaRoot

        var pendingDuration: Int? = null
        var pendingTitle: String? = null

        playlistFile.readLines(Charsets.UTF_8).forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> { /* skip blank */ }
                line.equals("#EXTM3U", ignoreCase = true) -> { /* header */ }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    // Parse "#EXTINF:180,Title Here"
                    val body = line.substring(8).trim()
                    val comma = body.indexOf(',')
                    val durStr = if (comma >= 0) body.substring(0, comma) else body
                    pendingDuration = durStr.trim().toIntOrNull()
                    pendingTitle = if (comma >= 0) body.substring(comma + 1).trim() else null
                }
                line.startsWith("#") -> { /* skip other directives */ }
                else -> {
                    val resolvedEntry = resolvePlaylistEntry(line, baseDir)
                    if (resolvedEntry == null) {
                        warnings.add("Skipped unresolved entry: $line")
                    } else {
                        entries.add(
                            PlaylistEntry(
                                rawPath = line,
                                resolvedRelativePath = resolvedEntry.second,
                                absolutePath = resolvedEntry.first?.path,
                                isAbsoluteUrl = resolvedEntry.first == null,
                                title = pendingTitle?.takeIf { it.isNotBlank() }
                                    ?: deriveTitle(resolvedEntry.first, line),
                                durationSeconds = pendingDuration?.takeIf { it > 0 },
                                kind = resolvedEntry.first?.let { classify(it) }
                                    ?: classifyPlaylistUrl(line)
                            )
                        )
                    }
                    pendingDuration = null
                    pendingTitle = null
                }
            }
        }

        return ParsedPlaylist(
            name = playlistFile.nameWithoutExtension,
            entries = entries,
            warnings = warnings
        )
    }

    /**
     * Resolve an M3U entry line to (File?, mediaRootRelativePath).
     * Returns null File for absolute URLs (http(s)://, file://); the caller
     * should treat those as pass-through URLs rather than file paths.
     * Returns null overall if the path escapes the Media root.
     */
    private fun resolvePlaylistEntry(line: String, baseDir: File): Pair<File?, String>? {
        // Absolute URLs — pass through
        val lower = line.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return null to line
        }
        if (lower.startsWith("file://")) {
            // file:// URL — not reachable by the companion browser unless it
            // maps back into Media root. Try to coerce.
            return null to line
        }

        // Relative or absolute filesystem path
        val candidate = if (line.startsWith("/")) File(line) else File(baseDir, line)
        val canonical = try {
            candidate.canonicalFile
        } catch (e: IOException) {
            return null
        }
        val rootCanonical = mediaRoot.canonicalFile
        if (canonical.path != rootCanonical.path &&
            !canonical.path.startsWith(rootCanonical.path + File.separator)
        ) {
            // Escaped the root
            return null
        }
        return canonical to relativize(canonical)
    }

    private fun deriveTitle(file: File?, fallback: String): String {
        return file?.nameWithoutExtension ?: fallback
    }

    /**
     * Remote entries inside an M3U are usually playable streams, even when the
     * URL has no extension. Use the extension when it is informative, otherwise
     * default to AUDIO so the on-glasses playlist player treats the row as a
     * station/track rather than an unknown document.
     */
    private fun classifyPlaylistUrl(url: String): MediaKind {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            ext in VIDEO_EXTENSIONS -> MediaKind.VIDEO
            ext in AUDIO_EXTENSIONS -> MediaKind.AUDIO
            ext in PLAYLIST_EXTENSIONS -> MediaKind.PLAYLIST
            else -> MediaKind.AUDIO
        }
    }

    /**
     * Write a playlist to disk, converting entries' absolute/relative paths
     * to paths relative to the playlist's own directory. Overwrites.
     */
    fun writePlaylist(playlistFile: File, entries: List<PlaylistWriteEntry>): Boolean {
        return try {
            val baseDir = playlistFile.parentFile ?: mediaRoot
            val sb = StringBuilder()
            sb.append("#EXTM3U\n")
            for (entry in entries) {
                val duration = entry.durationSeconds ?: -1
                val titleClean = entry.title.replace(Regex("[\r\n]"), " ").trim()
                sb.append("#EXTINF:").append(duration).append(',').append(titleClean).append('\n')
                sb.append(toRelativeOrUrl(entry.targetPathOrUrl, baseDir)).append('\n')
            }
            playlistFile.parentFile?.mkdirs()
            playlistFile.writeText(sb.toString(), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            Log.w(TAG, "writePlaylist failed: ${e.message}")
            false
        }
    }

    /** Convert an absolute file path to a relative path from baseDir. */
    private fun toRelativeOrUrl(pathOrUrl: String, baseDir: File): String {
        val lower = pathOrUrl.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("file://")
        ) return pathOrUrl

        // Treat as Media-root-relative path (from companion UI)
        val resolved = resolveSafe(pathOrUrl) ?: return pathOrUrl
        return try {
            baseDir.canonicalFile.toPath().relativize(resolved.toPath()).toString()
                .replace(File.separatorChar, '/')
        } catch (e: Exception) {
            resolved.path
        }
    }

    /**
     * Auto-generate a playlist for a folder. Scans the folder (non-recursive)
     * for playable files and writes a .m3u named after the folder.
     * If a playlist by that name already exists, a numeric suffix is appended.
     */
    fun generatePlaylistForFolder(folderRelativePath: String): File? {
        val folder = resolveSafe(folderRelativePath) ?: return null
        if (!folder.exists() || !folder.isDirectory) return null

        val playable = folder.listFiles { f ->
            f.isFile && f.extension.lowercase(Locale.ROOT).let { ext ->
                ext in AUDIO_EXTENSIONS || ext in VIDEO_EXTENSIONS
            }
        }?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: emptyList()

        if (playable.isEmpty()) return null

        // Pick a unique name: "<folder>.m3u" → "<folder>-1.m3u" if taken.
        val baseName = folder.name.ifBlank { "playlist" }
        var candidate = File(folder, "$baseName.m3u")
        var i = 1
        while (candidate.exists()) {
            candidate = File(folder, "$baseName-$i.m3u")
            i++
        }

        val entries = playable.map {
            PlaylistWriteEntry(
                targetPathOrUrl = relativize(it),
                title = it.nameWithoutExtension,
                durationSeconds = null
            )
        }
        return if (writePlaylist(candidate, entries)) candidate else null
    }

    /** Delete a file safely — must be inside Media root. */
    fun deleteEntry(relativePath: String): Boolean {
        val file = resolveSafe(relativePath) ?: return false
        if (!file.exists()) return false
        // Refuse to delete the Media root itself
        if (file.canonicalPath == mediaRoot.canonicalPath) return false
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    /**
     * Depth-first walk that tolerates permission errors and ignores dotfiles.
     */
    private fun walkSafely(root: File, visitor: (File) -> Unit) {
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (f.name.startsWith(".")) continue
            try {
                if (f.isDirectory) {
                    f.listFiles()?.forEach { stack.addLast(it) }
                } else {
                    visitor(f)
                }
            } catch (e: Exception) {
                Log.w(TAG, "walkSafely error at ${f.path}: ${e.message}")
            }
        }
    }

    // ── Data classes ──────────────────────────────────────────────────

    enum class MediaKind(val sortOrder: Int) {
        FOLDER(0), PLAYLIST(1), AUDIO(2), VIDEO(3), IMAGE(4), TEXT(5), OTHER(6)
    }

    data class MediaEntry(
        val name: String,
        val relativePath: String,
        val kind: MediaKind,
        val sizeBytes: Long,
        val lastModifiedMs: Long
    )

    data class FolderListing(
        val relativePath: String,
        val absolutePath: String,
        val entries: List<MediaEntry>
    )

    data class PlaylistEntry(
        val rawPath: String,
        val resolvedRelativePath: String,
        val absolutePath: String?,
        val isAbsoluteUrl: Boolean,
        val title: String,
        val durationSeconds: Int?,
        val kind: MediaKind
    )

    data class ParsedPlaylist(
        val name: String,
        val entries: List<PlaylistEntry>,
        val warnings: List<String>
    )

    data class PlaylistWriteEntry(
        val targetPathOrUrl: String,
        val title: String,
        val durationSeconds: Int?
    )
}
