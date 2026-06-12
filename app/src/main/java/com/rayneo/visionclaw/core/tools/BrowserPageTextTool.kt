package com.rayneo.visionclaw.core.tools

import android.content.Context
import com.TapLink.app.media.BrowserFrameHolder
import com.TapLink.app.media.MediaLibraryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Returns the active TapBrowser page's full DOM/body text. Use this
 * for "read/summarize the web page" requests; browser_vision is only
 * for visible-screen questions.
 */
class BrowserPageTextTool(
    private val context: Context,
    private val pageTextProvider: (Int) -> BrowserFrameHolder.PageTextResult? = { null }
) : AiTapTool {

    override val name = "browser_page_text"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val maxChars = args["max_chars"]?.toIntOrNull()
            ?: args["maxChars"]?.toIntOrNull()
            ?: SAVE_MAX_CHARS
        val forceSave = args["force_save"]?.equals("true", ignoreCase = true) == true ||
            args["forceSave"]?.equals("true", ignoreCase = true) == true ||
            args["autoplay"] == "1"
        return withContext(Dispatchers.IO) {
            val page = pageTextProvider(maxChars.coerceIn(1000, HARD_MAX_CHARS))
            if (page == null || page.text.isBlank()) {
                Result.failure(
                    IllegalStateException(
                        "I can't read the full web page right now — make sure TapBrowser is open and the page has loaded."
                    )
                )
            } else {
                Result.success(formatResult(page, forceSave))
            }
        }
    }

    private fun formatResult(page: BrowserFrameHolder.PageTextResult, forceSave: Boolean): String {
        if (!forceSave && page.text.length <= SPOKEN_TEXT_LIMIT && !page.truncated) {
            return page.toToolText()
        }

        val saved = savePageText(page)
        val excerpt = page.text.take(SPOKEN_EXCERPT_CHARS).trim()
        return buildString {
            appendLine("Title: ${page.title}")
            appendLine("URL: ${page.url}")
            appendLine()
            if (saved != null) {
                appendLine("Saved full extracted page text to Media Library: ${saved.relativePath}")
                appendLine("open_taplink:${saved.playerUrl}&autoplay=1")
            } else {
                appendLine("The page is long. I could not save it to the Media Library, so here is an excerpt.")
            }
            if (page.truncated) {
                appendLine("Note: extraction was capped at ${page.text.length} of ${page.originalLength} characters.")
            }
            appendLine()
            appendLine("Excerpt for spoken response:")
            appendLine(excerpt)
            appendLine()
            appendLine("Instruction: For read-aloud requests, open the saved text reader link above. It will read the extracted page verbatim.")
        }.trim()
    }

    private fun savePageText(page: BrowserFrameHolder.PageTextResult): SavedTextFile? {
        return try {
            val library = MediaLibraryService(context).also { it.ensureBootstrap() }
            val filename = uniqueFilename(library, baseFilename(page))
            val relativePath = "${MediaLibraryService.DEFAULT_TEXT_DIR}/$filename"
            val target = library.resolveSafe(relativePath) ?: return null
            target.parentFile?.mkdirs()
            target.writeText(
                buildString {
                    append(page.text)
                    if (page.truncated) {
                        appendLine()
                        appendLine()
                        appendLine("[Extraction capped at ${page.text.length} of ${page.originalLength} characters.]")
                    }
                },
                Charsets.UTF_8
            )
            SavedTextFile(
                relativePath = library.relativize(target),
                playerUrl = "https://appassets.androidplatform.net/assets/media_player.html" +
                    "?type=text&url=https%3A%2F%2Fappassets.androidplatform.net%2Fmedia%2F" +
                    urlEncodePath(library.relativize(target)) +
                    "&title=" + java.net.URLEncoder.encode(target.nameWithoutExtension, "UTF-8")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun baseFilename(page: BrowserFrameHolder.PageTextResult): String {
        val title = page.title.ifBlank {
            page.url.substringAfter("://", page.url).substringBefore('/').ifBlank { "web-page" }
        }
        val cleanTitle = title
            .replace(Regex("[^A-Za-z0-9._ -]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(72)
            .ifBlank { "web-page" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "$cleanTitle-$stamp.txt"
    }

    private fun uniqueFilename(library: MediaLibraryService, preferred: String): String {
        val folder = library.resolveSafe(MediaLibraryService.DEFAULT_TEXT_DIR)
            ?: File(library.mediaRoot, MediaLibraryService.DEFAULT_TEXT_DIR)
        val base = preferred.substringBeforeLast('.', preferred)
        val ext = preferred.substringAfterLast('.', "txt")
        var candidate = preferred
        var index = 2
        while (File(folder, candidate).exists()) {
            candidate = "$base-$index.$ext"
            index++
        }
        return candidate
    }

    private fun urlEncodePath(path: String): String =
        path.split('/').joinToString("%2F") { java.net.URLEncoder.encode(it, "UTF-8") }

    private data class SavedTextFile(
        val relativePath: String,
        val playerUrl: String
    )

    private companion object {
        const val SPOKEN_TEXT_LIMIT = 12000
        const val SPOKEN_EXCERPT_CHARS = 5000
        const val SAVE_MAX_CHARS = 200000
        const val HARD_MAX_CHARS = 250000
    }
}
