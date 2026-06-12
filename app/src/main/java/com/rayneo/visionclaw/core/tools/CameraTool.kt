package com.rayneo.visionclaw.core.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.TapLink.app.media.MediaLibraryService
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gemini-callable camera tool. Implements `save_photo`: captures the
 * most recent camera frame (delivered as base64 JPEG via [frameProvider],
 * populated by the Gemini Live multimodal pipeline) and writes it to
 * shared storage under `DCIM/TapInsight/`.
 *
 * Why DCIM/MediaStore instead of app-private Media/Photos:
 *
 *   • Photos appear automatically in the RayNeo Camera's native gallery
 *     and any other photo-viewer on the device, alongside the user's
 *     own captures.
 *
 *   • Visible over USB/MTP to a desktop file browser without any
 *     hidden Android/data drilling.
 *
 *   • No permission needed to WRITE via MediaStore on Android 10+
 *     (scoped storage).
 *
 *   • No permission needed to READ our own contributions back via
 *     MediaStore (see [com.TapLink.app.media.DcimEnumerator.listOwn]).
 *
 *   • The bridge route already exists — `/dcim/image/<id>` proxies
 *     bytes through the same virtual https host the rest of the
 *     gallery uses, so adding MediaStore-backed photos doesn't break
 *     the existing entryUrl pipeline.
 *
 * Pre-Q (API <= 28) falls back to a direct file write into
 * `/storage/emulated/0/DCIM/TapInsight/` + MediaScannerConnection,
 * which is the legacy path for that platform window.
 *
 * The success response includes an `open_taplink:` directive pointing
 * at the on-glasses photos gallery so the user can say "view it" and
 * the result is rendered immediately. The focus parameter uses the
 * MediaStore content URI (or path on pre-Q) so the gallery's lightbox
 * can locate the just-saved photo in either listing.
 */
class CameraTool(
    private val context: Context,
    private val frameProvider: () -> String? = { null }
) : AiTapTool {

    override val name = "camera_action"

    companion object {
        private const val TAG = "CameraTool"
        private const val GALLERY_URL =
            "https://appassets.androidplatform.net/assets/photos_gallery.html"
        private val FILENAME_TIMESTAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        /** Sub-folder of `DCIM/` we own; readable without permission. */
        const val DCIM_SUBFOLDER = "TapInsight"
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"] ?: "save_photo"
        val title = args["title"]?.trim()?.takeIf { it.isNotBlank() } ?: "TapInsight Photo"

        Log.d(TAG, "action=$action title=$title")

        return when (action) {
            "save_photo" -> savePhoto(title)
            "read_qr" -> Result.success("QR scan not yet implemented.")
            "start_recording" -> Result.success("Recording started: $title")
            "stop_recording" -> Result.success("Recording saved.")
            else -> Result.success("Unknown camera action: $action")
        }
    }

    private fun savePhoto(title: String): Result<String> {
        val base64 = frameProvider()?.trim().orEmpty()
        if (base64.isEmpty()) {
            Log.w(TAG, "save_photo: no camera frame available")
            return Result.failure(
                IllegalStateException(
                    "Camera frame not available. Make sure the AR camera is active before saving a photo."
                )
            )
        }

        val jpegBytes = try {
            Base64.decode(base64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "save_photo: base64 decode failed", e)
            return Result.failure(IOException("Failed to decode camera frame: ${e.localizedMessage}"))
        }

        if (jpegBytes.isEmpty()) {
            return Result.failure(IOException("Camera frame decoded to 0 bytes."))
        }

        val timestamp = FILENAME_TIMESTAMP.format(Date())
        val safeTitle = title.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim()
            .replace(Regex("\\s+"), "_")
            .ifBlank { "photo" }
        val filename = "$timestamp-$safeTitle.jpg"

        // Try MediaStore-backed DCIM save first. Returns a content://
        // URI on success; null if the platform/permissions reject it
        // and we should fall back to the legacy app-private path.
        val dcimSave = saveToDcimViaMediaStore(jpegBytes, filename)
        if (dcimSave != null) {
            Log.i(TAG, "save_photo: wrote ${jpegBytes.size}B to DCIM/$DCIM_SUBFOLDER/$filename (id=${dcimSave.id})")
            // Focus param uses the MediaStore id so the gallery can
            // navigate directly to it (DCIM entries don't have a
            // library relativePath). Bridge entries return both
            // `dcimId` and `fullUrl` — gallery focus matches on
            // either.
            val focusParam = java.net.URLEncoder.encode("dcim:${dcimSave.id}", "UTF-8")
            val galleryDeepLink = "$GALLERY_URL?focus=$focusParam"
            return Result.success(
                "Photo saved as $filename. " +
                    "Say \"view it\" or \"open the gallery\" and I'll show you.\n" +
                    "open_taplink:$galleryDeepLink"
            )
        }

        // Pre-Q fallback OR MediaStore insert failure: legacy app-private
        // save path so something still lands on disk. The bridge's
        // library listing already exposes Media/Photos/, so it'll show
        // up in the gallery from there.
        val library = MediaLibraryService(context).apply { ensureBootstrap() }
        val photosDir = File(library.mediaRoot, MediaLibraryService.DEFAULT_PHOTOS_DIR)
        if (!photosDir.exists()) photosDir.mkdirs()
        val outFile = File(photosDir, filename)
        try {
            outFile.outputStream().use { it.write(jpegBytes) }
        } catch (e: Exception) {
            Log.e(TAG, "save_photo: write failed for ${outFile.path}", e)
            return Result.failure(IOException("Failed to write photo: ${e.localizedMessage}"))
        }
        try {
            MediaScannerConnection.scanFile(
                context, arrayOf(outFile.absolutePath),
                arrayOf("image/jpeg"), null
            )
        } catch (_: Exception) {}

        val libraryRelativePath = "${MediaLibraryService.DEFAULT_PHOTOS_DIR}/$filename"
        Log.i(TAG, "save_photo (fallback): wrote ${outFile.length()}B to $libraryRelativePath")
        val focusParam = java.net.URLEncoder.encode(libraryRelativePath, "UTF-8")
        val galleryDeepLink = "$GALLERY_URL?focus=$focusParam"
        return Result.success(
            "Photo saved to the on-glasses Photos library as $filename. " +
                "Say \"view it\" or \"open the gallery\" and I'll show you.\n" +
                "open_taplink:$galleryDeepLink"
        )
    }

    /** Result of a successful MediaStore-backed DCIM save. */
    private data class DcimSave(val id: Long, val uri: Uri)

    /**
     * Insert the JPEG bytes into MediaStore under `DCIM/TapInsight/`.
     * Returns the new row's [DcimSave] (id + uri) on success, null on
     * failure (e.g. resolver returned null, write threw, or we're on
     * an unsupported API level).
     *
     * No permission needed: writes to DCIM via MediaStore are allowed
     * for any app on Android 10+ under scoped storage rules. On
     * pre-Q this method bails immediately (returns null) because
     * RELATIVE_PATH isn't supported there — the legacy file-system
     * fallback in [savePhoto] handles that window.
     */
    private fun saveToDcimViaMediaStore(jpegBytes: ByteArray, filename: String): DcimSave? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-Q: try a direct file write into DCIM. WRITE_EXTERNAL_STORAGE
            // is declared up to API 28, so this works without runtime
            // permission below scoped storage.
            return try {
                val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val subDir = File(dcim, DCIM_SUBFOLDER)
                if (!subDir.exists()) subDir.mkdirs()
                val outFile = File(subDir, filename)
                outFile.outputStream().use { it.write(jpegBytes) }
                // Trigger MediaStore scan so we can find this entry by URI later.
                val scanned = java.util.concurrent.CompletableFuture<Uri?>()
                MediaScannerConnection.scanFile(
                    context, arrayOf(outFile.absolutePath),
                    arrayOf("image/jpeg")
                ) { _, uri -> scanned.complete(uri) }
                val uri = try {
                    scanned.get(2, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: Exception) { null }
                if (uri != null) DcimSave(ContentUris.parseId(uri), uri) else null
            } catch (e: Exception) {
                Log.w(TAG, "Pre-Q DCIM write failed: ${e.message}")
                null
            }
        }

        // Q+: MediaStore insert with RELATIVE_PATH. IS_PENDING gates the
        // file behind a half-published state so partial writes don't
        // surface in other gallery apps.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$DCIM_SUBFOLDER/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore insert failed: ${e.message}")
            null
        } ?: return null

        try {
            resolver.openOutputStream(uri)?.use { it.write(jpegBytes) }
                ?: throw IOException("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return DcimSave(ContentUris.parseId(uri), uri)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore write failed; rolling back: ${e.message}")
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            return null
        }
    }
}
