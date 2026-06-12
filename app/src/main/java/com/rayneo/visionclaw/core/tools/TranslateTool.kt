package com.rayneo.visionclaw.core.tools

import android.util.Log

/**
 * TranslateTool — real-time translation for the TapInsight AR assistant.
 *
 * Triggered via voice commands like:
 *   "translate this to Spanish"
 *   "what does that sign say in English"
 *   "translate: where is the train station"
 *   "say hello in Japanese"
 *
 * The tool leverages Gemini's native multilingual capabilities rather than
 * a separate translation API, keeping the dependency footprint small and
 * enabling contextual/conversational translation that understands idioms.
 *
 * When used with the camera feed, Gemini can also translate text seen in
 * the user's field of view (menus, signs, documents).
 */
class TranslateTool : AiTapTool {

    override val name = "translate_text"

    companion object {
        private const val TAG = "TranslateTool"

        // Supported language shortcuts for quick lookup
        val LANGUAGE_ALIASES = mapOf(
            "spanish" to "Spanish (es)", "french" to "French (fr)",
            "german" to "German (de)", "italian" to "Italian (it)",
            "portuguese" to "Portuguese (pt)", "japanese" to "Japanese (ja)",
            "chinese" to "Chinese (zh)", "mandarin" to "Chinese (zh)",
            "korean" to "Korean (ko)", "arabic" to "Arabic (ar)",
            "hindi" to "Hindi (hi)", "russian" to "Russian (ru)",
            "thai" to "Thai (th)", "vietnamese" to "Vietnamese (vi)",
            "turkish" to "Turkish (tr)", "dutch" to "Dutch (nl)",
            "polish" to "Polish (pl)", "swedish" to "Swedish (sv)",
            "greek" to "Greek (el)", "hebrew" to "Hebrew (he)",
            "indonesian" to "Indonesian (id)", "malay" to "Malay (ms)",
            "tagalog" to "Tagalog (tl)", "czech" to "Czech (cs)",
            "danish" to "Danish (da)", "finnish" to "Finnish (fi)",
            "norwegian" to "Norwegian (no)", "hungarian" to "Hungarian (hu)",
            "romanian" to "Romanian (ro)", "ukrainian" to "Ukrainian (uk)",
            "swahili" to "Swahili (sw)", "bengali" to "Bengali (bn)",
            "tamil" to "Tamil (ta)", "urdu" to "Urdu (ur)",
            "persian" to "Persian (fa)", "farsi" to "Persian (fa)",
            "english" to "English (en)"
        )
    }

    /**
     * Execute translation. This tool is designed to work in two modes:
     *
     * 1. **Text translation**: User provides text and target language.
     *    The tool formats a translation prompt for Gemini to process.
     *
     * 2. **Camera/vision translation**: When used during a Live session
     *    with camera enabled, the contextPrompt tells Gemini to translate
     *    whatever text is visible in the camera feed.
     *
     * Args:
     *   - "text": The text to translate (or "camera" for live vision)
     *   - "target_language": Target language (e.g., "Spanish", "ja")
     *   - "source_language": (Optional) Source language hint
     */
    override suspend fun execute(args: Map<String, String>): Result<String> {
        val text = args["text"]?.trim().orEmpty()
        val targetLang = args["target_language"]?.trim().orEmpty()
        val sourceLang = args["source_language"]?.trim().orEmpty()

        if (text.isBlank() && targetLang.isBlank()) {
            return Result.failure(Exception("Please specify what to translate and to which language."))
        }

        // Resolve language aliases
        val resolvedTarget = LANGUAGE_ALIASES[targetLang.lowercase()] ?: targetLang
        val resolvedSource = if (sourceLang.isNotBlank()) {
            LANGUAGE_ALIASES[sourceLang.lowercase()] ?: sourceLang
        } else ""

        Log.d(TAG, "Translate: text=${text.take(80)} target=$resolvedTarget source=$resolvedSource")

        // If text is "camera" or blank, this is a vision-based translation request
        if (text.isBlank() || text.equals("camera", ignoreCase = true) ||
            text.equals("screen", ignoreCase = true) ||
            text.equals("what I see", ignoreCase = true)) {
            return Result.success(
                buildString {
                    append("[TRANSLATE_VISION]")
                    append("\nMode: Camera/Vision translation")
                    append("\nTarget language: $resolvedTarget")
                    if (resolvedSource.isNotBlank()) append("\nSource language: $resolvedSource")
                    append("\nInstruction: Look at the camera feed and translate any visible text to $resolvedTarget.")
                    append(" Preserve formatting and layout context (e.g., menu items, signs, labels).")
                }
            )
        }

        // Text-based translation — format the request
        return Result.success(
            buildString {
                append("[TRANSLATE_TEXT]")
                append("\nOriginal: $text")
                append("\nTarget language: $resolvedTarget")
                if (resolvedSource.isNotBlank()) append("\nDetected/specified source: $resolvedSource")
                append("\n\nTranslation: ")
                // The actual translation is done by Gemini when it receives this as a tool result.
                // We provide the structured request; Gemini's multilingual model handles the rest.
                append("(Gemini: translate the above text to $resolvedTarget naturally. ")
                append("If it's a phrase or idiom, provide both a literal and natural translation. ")
                append("For single words, also include pronunciation guidance.)")
            }
        )
    }
}
