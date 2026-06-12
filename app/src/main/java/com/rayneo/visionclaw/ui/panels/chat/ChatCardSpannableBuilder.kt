package com.rayneo.visionclaw.ui.panels.chat

import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import java.util.regex.Pattern

/**
 * Shared spannable renderer for chat-card text. Produces the bold-header +
 * summary visual treatment for line-start (and bullet+URL-tail) URL entries
 * while keeping the existing inline cyan colour for mid-prose URLs.
 *
 * Used by:
 *   • ChatAdapter.bindMessageCard — the regular chat row.
 *   • ChatPanelFragment.enterReaderMode — the expanded reader view.
 *
 * Centralising this here means a chat card and its reader-mode expansion
 * always agree on what the user sees. Without the shared helper, reader
 * mode rendered raw markdown text (`**Title:**`, `https://…` inline,
 * etc.) while the row view rendered bold cyan headers — confusing.
 */
object ChatCardSpannableBuilder {

    private const val TAG = "ChatCardSpannable"

    /** Theme cyan used for URL highlights and bold link headers. */
    @JvmField
    val URL_COLOR: Int = android.graphics.Color.parseColor("#00FFFF")
    /** Dim white used for section-context metadata lines under each entry. */
    private val META_COLOR: Int = android.graphics.Color.parseColor("#88FFFFFF")
    /** Indentation (in pixels-equivalent leading-margin units) applied to
     *  summary and meta lines so they visually attach to the bold header. */
    private const val SUMMARY_LEADING_MARGIN = 16
    /** Separator we put between the bullet's description and the section
     *  context inside the extracted summary string — see CardUrlExtractor. */
    private const val SECTION_DELIMITER = "  ·  "

    private val STRICT_URL_PATTERN: Pattern = Pattern.compile(
        "(?:https?://|www\\.)[^\\s<>\"']+",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Build the spannable representation of [text] for display in either the
     * chat row or the reader-mode TextView. [onUrlTapped] receives the
     * canonical URL when a bold header is clicked. [focusedEntryIndex] is
     * the index (in the entries list returned by `CardUrlExtractor.extract`)
     * of the URL header the user has currently focused via trackpad swipe;
     * the focused header gets a chevron prefix (▶) and a brighter weight so
     * it's visually distinct. -1 disables focus highlighting (e.g. for the
     * regular chat row, which doesn't track per-URL focus).
     *
     * Section-header lines (`**Direct MP3 / Podcast Streams:**`) are dropped
     * from the rendered output. Their text is already attached to each
     * bullet's summary as `· Section name` context, so duplicating them as
     * raw markdown was just noise.
     */
    fun build(
        text: String,
        focusedEntryIndex: Int = -1,
        onUrlTapped: (String) -> Unit
    ): CharSequence {
        if (text.isBlank()) return text
        val entries = CardUrlExtractor.extract(text)
        if (entries.isEmpty()) {
            // No line-start URL entries — fall back to plain text with any
            // inline URLs highlighted in cyan and any section markers
            // stripped (still nicer than raw `**` showing up in prose).
            return applyInlineUrlHighlights(stripSectionHeaders(text))
        }

        val sorted = entries.sortedBy { it.entryStart }
        val builder = SpannableStringBuilder()
        var cursor = 0
        for ((idx, entry) in sorted.withIndex()) {
            if (entry.entryStart < cursor) continue
            if (entry.entryStart > cursor) {
                val gap = stripSectionHeaders(text.substring(cursor, entry.entryStart))
                builder.append(applyInlineUrlHighlights(gap))
            }

            // Bold cyan tappable display title in place of the URL line.
            // Focused entries get a chevron prefix as the visual focus cue.
            // We place a blank line BEFORE every entry except the first
            // (the first one's gap-fill from the preamble already provides
            // separation).
            //
            // Each entry is prefixed with `N. ` so:
            //   - The user can disambiguate ordinal voice commands at a
            //     glance ("which one is the third link?" → look for `3.`).
            //   - Gemini, reading the chat card text in PREVIOUS
            //     CONVERSATION context, sees the same numbering and can
            //     resolve "open the third link" deterministically.
            if (idx > 0) builder.append('\n')
            val isFocused = idx == focusedEntryIndex
            val titleStart = builder.length
            if (isFocused) builder.append("▶ ")
            builder.append("${idx + 1}. ")
            builder.append(entry.displayTitle)
            val titleEnd = builder.length
            builder.setSpan(
                ForegroundColorSpan(URL_COLOR),
                titleStart, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                titleStart, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        // URLPipe diagnostic: every chat-card click logs the
                        // exact URL we're handing to viewModel.openUrl. Lets
                        // us correlate the user's tap with the URL the
                        // extractor produced (logged earlier as
                        // `URLPipe/extract`) and the URL the launcher
                        // ultimately receives (`URLPipe/open`).
                        Log.d(
                            TAG,
                            "URLPipe/tap idx=${idx + 1} type=${entry.mediaType.name} " +
                                "raw='${entry.rawUrl}' canonical='${entry.canonicalUrl}'"
                        )
                        onUrlTapped(entry.rawUrl)
                    }
                },
                titleStart, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // Split the summary into body (description) + meta (section
            // context). CardUrlExtractor joins them with SECTION_DELIMITER,
            // so we can split there to render each on its own line.
            if (entry.summary.isNotBlank()) {
                val parts = entry.summary.split(SECTION_DELIMITER, limit = 2)
                val body = parts.getOrNull(0)?.trim().orEmpty()
                val meta = parts.getOrNull(1)?.trim()?.trimEnd(':').orEmpty()

                if (body.isNotBlank()) {
                    builder.append('\n')
                    val bodyStart = builder.length
                    builder.append(body)
                    val bodyEnd = builder.length
                    builder.setSpan(
                        LeadingMarginSpan.Standard(SUMMARY_LEADING_MARGIN),
                        bodyStart, bodyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (meta.isNotBlank()) {
                    builder.append('\n')
                    val metaStart = builder.length
                    builder.append(meta)
                    val metaEnd = builder.length
                    builder.setSpan(
                        ForegroundColorSpan(META_COLOR),
                        metaStart, metaEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    builder.setSpan(
                        RelativeSizeSpan(0.85f),
                        metaStart, metaEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    builder.setSpan(
                        LeadingMarginSpan.Standard(SUMMARY_LEADING_MARGIN),
                        metaStart, metaEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            builder.append('\n')

            cursor = entry.entryEnd
        }
        if (cursor < text.length) {
            val tail = stripSectionHeaders(text.substring(cursor))
            builder.append(applyInlineUrlHighlights(tail))
        }
        return builder
    }

    private val SECTION_HEADER_LINE_PATTERN: Pattern = Pattern.compile(
        "(?m)^\\s*\\*\\*[^*\\n]+\\*\\*\\s*:?\\s*$"
    )

    /**
     * Remove standalone markdown section header lines from a text segment.
     * The bullets beneath a section already have its name appended to their
     * summary, so duplicating it as raw `**Title:**` text is just clutter.
     */
    private fun stripSectionHeaders(segment: String): String {
        if (segment.isEmpty()) return segment
        val cleaned = SECTION_HEADER_LINE_PATTERN.matcher(segment).replaceAll("")
        // Collapse any 3+ consecutive newlines that stripping might produce.
        return cleaned.replace(Regex("\\n{3,}"), "\n\n")
    }

    /**
     * For card text that has no line-start URL entries (or for the
     * pre/post-entry "gap" segments), still highlight any plain-text URLs
     * in cyan so they're visually distinguishable. No ClickableSpan — the
     * tap routes through the card-level handler, same as before.
     */
    private fun applyInlineUrlHighlights(segment: String): CharSequence {
        if (segment.isEmpty()) return segment
        val spannable = SpannableString(segment)
        val matcher = STRICT_URL_PATTERN.matcher(segment)
        while (matcher.find()) {
            spannable.setSpan(
                ForegroundColorSpan(URL_COLOR),
                matcher.start(),
                matcher.end(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }
}
