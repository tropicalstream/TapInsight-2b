package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Base64
import android.util.Log
import com.TapLink.app.media.MediaLibraryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Save this image" tool — captures what the user is currently seeing and
 * files it into the on-glasses Media Library Photos folder.
 *
 * Args (Gemini-side function schema):
 *   • `source` (optional) — "screen" (default; the TapBrowser WebView
 *     frame) or "camera" (the live camera frame, when streaming).
 *   • `filename` (optional) — desired base name; sanitized, de-extensioned,
 *     capped at 60 chars. Falls back to a screen_yyyyMMdd-HHmmss timestamp
 *     name. Collisions get a -2/-3/… suffix instead of overwriting.
 *
 * The frame providers hand back base64 JPEG (same providers the vision
 * tools use); the decoded bytes are written under Photos/ where the media
 * library and photos gallery pick them up immediately.
 */
class SaveScreenImageTool(
    private val context: Context,
    private val browserFrameProvider: () -> String? = { null },
    private val cameraFrameProvider: () -> String? = { null }
) : AiTapTool {

    companion object {
        private const val TAG = "SaveScreenImageTool"
    }

    override val name = "save_screen_image"

    override suspend fun execute(args: Map<String, String>): Result<String> = withContext(Dispatchers.IO) {
        val source = (args["source"]?.trim()?.lowercase(Locale.US) ?: "").ifBlank { "screen" }
        val base64 = if (source == "camera") cameraFrameProvider() else browserFrameProvider()
        if (base64.isNullOrBlank()) {
            val why = if (source == "camera") {
                "The camera isn't streaming right now, so there's no camera frame to save."
            } else {
                "The browser isn't showing anything to capture right now."
            }
            return@withContext Result.failure(IllegalStateException(why))
        }
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("Captured image was empty."))
            }
            try {
                val service = MediaLibraryService(context)
                service.ensureBootstrap()
                val photosDir = File(service.mediaRoot, MediaLibraryService.DEFAULT_PHOTOS_DIR)
                photosDir.mkdirs()
                val requested = args["filename"]?.trim() ?: ""
                val baseName = Regex("[\\\\/:*?\"<>|\\s]+").replace(requested, "_")
                    .removeSuffix(".jpg")
                    .removeSuffix(".jpeg")
                    .take(60)
                    .ifBlank { "screen_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) }
                var target = File(photosDir, "$baseName.jpg")
                var n = 2
                while (target.exists()) {
                    target = File(photosDir, "$baseName-$n.jpg")
                    n++
                }
                target.writeBytes(bytes)
                Log.i(TAG, "Saved ${bytes.size} bytes → ${target.absolutePath}")
                Result.success(
                    "Saved the " + (if (source == "camera") "camera image" else "screen image") +
                        " to your Media Library Photos folder as ${target.name} (${bytes.size / 1024} KB)."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Save failed", e)
                Result.failure(IllegalStateException("Couldn't write the image: ${e.message}"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame base64 decode failed: ${e.message}")
            Result.failure(IllegalStateException("Couldn't decode the captured image."))
        }
    }
}
