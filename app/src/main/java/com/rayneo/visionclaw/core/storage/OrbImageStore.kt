package com.rayneo.visionclaw.core.storage

import android.content.Context
import java.io.File

/**
 * Persists the user's custom chat-panel orb image. Two files live in
 * the app's private filesDir:
 *
 *   • custom_orb.png         — the cropped square image displayed in the
 *                              chat panel. This is what the glasses-side
 *                              ImageView loads when the user has a custom
 *                              orb. The image is always square; the chat
 *                              panel applies a circular ViewOutlineProvider
 *                              clip at render time, so corner pixels are
 *                              never visible.
 *
 *   • custom_orb_original.png — the user's full original upload, kept so
 *                              they can re-open the cropper in the
 *                              companion app and reposition / re-zoom
 *                              without re-uploading. Optional — if absent,
 *                              the cropper opens fresh next time.
 *
 * Storage location is internal app storage (not external / world-readable),
 * which is the right scope for a per-device personalization preference.
 *
 * Files are written atomically by writing to a `.tmp` sibling and renaming
 * — important because the chat panel reads these during normal lifecycle
 * and we don't want to ever load a half-written image.
 */
class OrbImageStore(context: Context) {

    private val dir: File = context.filesDir
    private val croppedFile: File = File(dir, "custom_orb.png")
    private val originalFile: File = File(dir, "custom_orb_original.png")

    /** True when the user has uploaded a custom cropped orb image. */
    fun hasCustom(): Boolean = croppedFile.exists() && croppedFile.length() > 0

    /** True when we still have the original upload (for re-cropping). */
    fun hasOriginal(): Boolean = originalFile.exists() && originalFile.length() > 0

    /** Path to the cropped square. Caller must check hasCustom() first. */
    fun customFile(): File = croppedFile

    /** Path to the original upload. Caller must check hasOriginal() first. */
    fun originalFile(): File = originalFile

    /**
     * Save the cropped square image atomically. The bytes are expected to
     * be a square PNG at any reasonable size (we render at 78dp ≈ 234px
     * on a 3x screen, so 256×256 or 512×512 are both fine). The chat panel
     * applies a circular outline clip on display.
     */
    fun saveCropped(bytes: ByteArray) {
        atomicWrite(croppedFile, bytes)
    }

    /** Save the user's original upload (any aspect ratio). */
    fun saveOriginal(bytes: ByteArray) {
        atomicWrite(originalFile, bytes)
    }

    /** Delete both files. Reverts the chat panel to the default earth orb. */
    fun deleteAll() {
        if (croppedFile.exists()) croppedFile.delete()
        if (originalFile.exists()) originalFile.delete()
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // renameTo can fail across mounts — fall back to copy.
            target.writeBytes(tmp.readBytes())
            tmp.delete()
        }
    }
}
