package com.TapLink.app.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Reads the device's shared media library (MediaStore) and returns
 * photos + videos that live in `/storage/emulated/0/DCIM/Camera/`
 * (and other DCIM subfolders). Used by the photos-gallery and the
 * companion app to surface the RayNeo native Camera app's captures
 * alongside TapInsight's own `Media/Photos/` saves.
 *
 * Permission gating:
 *   • Android 13+ (API 33+) requires READ_MEDIA_IMAGES /
 *     READ_MEDIA_VIDEO. The app declares both in the manifest;
 *     runtime grants are still required.
 *   • Pre-13 falls back to READ_EXTERNAL_STORAGE.
 *
 * The enumerator returns an empty list with no error if permission
 * is denied — callers should call [hasPermission] first and prompt
 * the user to grant access if they want DCIM content.
 */
class DcimEnumerator(private val context: Context) {

    companion object {
        private const val TAG = "DcimEnumerator"

        /** Path-fragment matched against MediaStore.DATA to scope queries. */
        private const val DCIM_PATH_PREFIX = "/DCIM/"

        /**
         * DCIM subfolder TapInsight writes into via MediaStore (see
         * CameraTool.DCIM_SUBFOLDER). Kept as a string constant here so
         * [listOwn] can filter MediaStore queries down to "files this
         * app contributed" without depending on the app module.
         */
        const val DCIM_OWN_SUBFOLDER = "TapInsight"

        fun hasPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val imagesOk = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
                val videosOk = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
                imagesOk && videosOk
            } else {
                @Suppress("DEPRECATION")
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        }

        fun requiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            } else {
                @Suppress("DEPRECATION")
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    /** One entry in the merged gallery. `source` distinguishes DCIM vs Media. */
    data class DcimEntry(
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        /** Epoch millis. MediaStore returns seconds; we convert. */
        val dateTakenMs: Long,
        /** content:// Uri the WebView can fetch via the companion proxy. */
        val contentUri: Uri,
        val width: Int,
        val height: Int,
        val isVideo: Boolean,
        val durationMs: Long?,
        /** "DCIM/Camera/IMG_20260518_091122.jpg" — relative to /storage/emulated/0/. */
        val relativeDisplayPath: String?
    )

    /**
     * Returns all images + videos in any DCIM subfolder, newest first.
     * Empty list if permission is missing (caller should pre-check).
     */
    fun listAll(limit: Int = 500): List<DcimEntry> {
        if (!hasPermission(context)) {
            Log.d(TAG, "listAll: permission not granted, returning empty list")
            return emptyList()
        }
        val out = mutableListOf<DcimEntry>()
        queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, limit, out, null, null)
        queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, limit, out, null, null)
        // Newest first across both collections.
        out.sortByDescending { it.dateTakenMs }
        return if (out.size > limit) out.take(limit) else out
    }

    /**
     * Returns DCIM entries this app wrote — specifically the
     * `DCIM/[DCIM_OWN_SUBFOLDER]/` directory that CameraTool saves
     * into via MediaStore. Per Android scoped-storage rules, an app
     * can always read its own MediaStore contributions back without
     * READ_MEDIA_IMAGES, so this works even when [hasPermission] is
     * false.
     *
     * This is the path that lets the photos gallery show the user's
     * TapInsight captures without any runtime permission grant.
     * RayNeo Camera photos still require the full grant — call
     * [listAll] for that, gated on [hasPermission].
     */
    fun listOwn(limit: Int = 500): List<DcimEntry> {
        val out = mutableListOf<DcimEntry>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("DCIM/${DCIM_OWN_SUBFOLDER}%")
            queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, limit, out, selection, args)
            queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, limit, out, selection, args)
        } else {
            @Suppress("DEPRECATION")
            val selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            val args = arrayOf("%/DCIM/${DCIM_OWN_SUBFOLDER}/%")
            queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, limit, out, selection, args)
            queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, limit, out, selection, args)
        }
        out.sortByDescending { it.dateTakenMs }
        return if (out.size > limit) out.take(limit) else out
    }

    private fun queryCollection(
        collection: Uri,
        isVideo: Boolean,
        limit: Int,
        out: MutableList<DcimEntry>,
        extraSelection: String?,
        extraArgs: Array<String>?
    ) {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            projection += MediaStore.MediaColumns.DATA
        }
        if (isVideo) projection += MediaStore.Video.Media.DURATION

        // Filter to DCIM. On Q+ we use RELATIVE_PATH; pre-Q we LIKE-match DATA.
        // When the caller passed an extraSelection (used by listOwn to narrow
        // to our own subfolder), we AND it onto the base DCIM filter so
        // permission-free queries still pass MediaStore's "only your own
        // entries" check.
        val (baseSelection, baseArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("DCIM/%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%${DCIM_PATH_PREFIX}%")
        }
        val selection: String
        val args: Array<String>
        if (extraSelection != null && extraArgs != null) {
            selection = "($baseSelection) AND ($extraSelection)"
            args = baseArgs + extraArgs
        } else {
            selection = baseSelection
            args = baseArgs
        }

        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $limit"

        try {
            context.contentResolver.query(
                collection, projection.toTypedArray(), selection, args, sortOrder
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateAddedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val widthCol = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightCol = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val relPathCol =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    else -1
                @Suppress("DEPRECATION")
                val dataCol =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                        c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    else -1
                val durationCol =
                    if (isVideo) c.getColumnIndex(MediaStore.Video.Media.DURATION) else -1

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val relativePath = when {
                        relPathCol >= 0 -> {
                            val rp = c.getString(relPathCol) ?: ""
                            val nm = c.getString(nameCol) ?: ""
                            if (rp.isNotEmpty()) rp + nm else null
                        }
                        dataCol >= 0 -> c.getString(dataCol)?.let { absolute ->
                            val marker = "/storage/emulated/0/"
                            if (absolute.startsWith(marker)) absolute.substring(marker.length)
                            else absolute
                        }
                        else -> null
                    }
                    out += DcimEntry(
                        displayName = c.getString(nameCol) ?: "(unnamed)",
                        mimeType = c.getString(mimeCol) ?: "application/octet-stream",
                        sizeBytes = c.getLong(sizeCol),
                        dateTakenMs = c.getLong(dateAddedCol) * 1000L,
                        contentUri = contentUri,
                        width = if (widthCol >= 0) c.getInt(widthCol) else 0,
                        height = if (heightCol >= 0) c.getInt(heightCol) else 0,
                        isVideo = isVideo,
                        durationMs = if (durationCol >= 0) c.getLong(durationCol) else null,
                        relativeDisplayPath = relativePath
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryCollection failed for $collection: ${e.message}")
        }
    }

    /**
     * Read raw bytes for a single content URI. Used by the companion
     * server to proxy DCIM bytes over its existing HTTP API instead of
     * exposing content:// URIs to the companion browser (which can't
     * resolve them).
     */
    fun readBytes(contentUri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(contentUri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "readBytes failed for $contentUri: ${e.message}")
            null
        }
    }
}
