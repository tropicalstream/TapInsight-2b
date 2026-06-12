package com.rayneo.visionclaw.ui.panels.chat

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.rayneo.visionclaw.R
import com.rayneo.visionclaw.core.model.ChatMessage
import java.util.regex.Pattern

class ChatAdapter(
    private val onUrlTapped: (String) -> Unit,
    private val onAssistantRequested: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val MAX_HISTORY_CARDS = 20
        // Must mirror ChatPanelFragment.CARD_HEIGHT_DP — the fragment
        // uses this height when computing the focus-snap offset.
        private const val CARD_HEIGHT_DP = 96f

        const val VIEW_TYPE_CHAT_CARD = 1
        const val VIEW_TYPE_SENTINEL_CARD = 2

        /** Theme cyan used for URL highlights and bold link headers. */
        @JvmStatic
        val URL_COLOR: Int = Color.parseColor("#00FFFF")

        /**
         * URL pattern used to scrape URLs from prose. Requires an explicit
         * `http://`, `https://`, or `www.` prefix to match. We deliberately
         * avoid Android's [android.util.Patterns.WEB_URL], which is so
         * permissive that it interprets any `word.Word` sequence as a
         * domain — that's how prose like "...converting to MP3 now.Here's
         * the Kraftwerk..." used to surface a phantom `https://now.here`
         * link in chat cards.
         *
         * Trailing punctuation (`.`, `,`, `;`, `:`, `!`, `?`, closing
         * brackets) is stripped by [normalizeUrl] so URLs that end a
         * sentence don't carry the period along.
         */
        private val STRICT_URL_PATTERN: Pattern = Pattern.compile(
            "(?:https?://|www\\.)[^\\s<>\"']+",
            Pattern.CASE_INSENSITIVE
        )

        /**
         * Structured "open this URL" directive emitted by tools/agents
         * (notably the OpenClaw companion when it finishes processing a
         * media job). When this appears anywhere in a message we treat
         * it as the canonical launch URL and skip free-text scraping
         * entirely — that's how we avoid surfacing both the real link
         * AND a phantom one extracted from sloppy prose in the same
         * message body. The URL portion still requires an explicit
         * protocol.
         */
        private val OPEN_TAPLINK_PATTERN: Pattern = Pattern.compile(
            "(?i)\\bopen_taplink\\s+(https?://[^\\s<>\"']+)"
        )
    }

    sealed class CardItem {
        data class MessageCard(val message: ChatMessage) : CardItem()
        data object NewChatCard : CardItem()
    }

    private data class UrlMatch(
        val raw: String,
        val normalized: String,
        val start: Int,
        val end: Int
    )

    private val chatHistory = mutableListOf<ChatMessage>()

    /**
     * The adapter-level focused position.  Updated by the fragment whenever
     * [focusedCardIndex] changes.  Used during [onBindViewHolder] to apply
     * an initial focused / unfocused alpha so that newly-bound cards
     * (including the New Chat sentinel) are *always* correctly dimmed,
     * even when the fragment's post-layout [applyFocusVisuals] hasn't
     * run yet.
     */
    var focusedPosition: Int = RecyclerView.NO_POSITION

    /**
     * Left-edge inset (in dp) that the LATEST chat row's bubble claims
     * so its text flows around the bottom-left orb without colliding
     * with it. Default 68dp (60dp orb + 8dp gutter) — the avatar is a
     * persistent screen element, so the inset is always live. Older
     * history rows always render at margin 0 and stretch over the
     * orb's vertical zone (the orb is drawn on top via elevation, so
     * it stays readable above older content). Set to 0 to disable.
     */
    private var lastRowLeftInsetDp: Int = 68

    fun setLastRowLeftInsetDp(dp: Int) {
        if (dp == lastRowLeftInsetDp) return
        lastRowLeftInsetDp = dp
        // Both the latest message AND the New Chat sentinel use the
        // inset, so refresh both positions when the value changes.
        val lastMessage = chatHistory.size - 1
        val sentinel = chatHistory.size
        if (lastMessage >= 0) notifyItemChanged(lastMessage)
        notifyItemChanged(sentinel)
    }

    fun submitMessages(messages: List<ChatMessage>) {
        chatHistory.clear()
        chatHistory += messages.takeLast(MAX_HISTORY_CARDS)
        notifyDataSetChanged()
    }

    fun getFirstContentPosition(): Int = 0

    /** The sentinel is always the final position. */
    fun getLastContentPosition(): Int = chatHistory.size

    fun getLatestMessagePosition(): Int {
        return if (chatHistory.isEmpty()) getLastContentPosition() else chatHistory.size - 1
    }

    fun isContentPosition(position: Int): Boolean {
        return position in getFirstContentPosition()..getLastContentPosition()
    }

    private fun cardItemForPosition(position: Int): CardItem {
        return if (isNewChatCard(position)) {
            CardItem.NewChatCard
        } else {
            val messageIndex = position.coerceIn(0, chatHistory.lastIndex)
            CardItem.MessageCard(chatHistory[messageIndex])
        }
    }

    fun isNewChatCard(position: Int): Boolean {
        return position == getLastContentPosition()
    }

    fun getCardUrl(position: Int): String? {
        if (!isContentPosition(position) || isNewChatCard(position)) return null
        if (position !in chatHistory.indices) return null
        return findUrls(chatHistory[position].text).firstOrNull()?.normalized
    }

    fun getCardText(position: Int): String? {
        if (!isContentPosition(position) || isNewChatCard(position)) return null
        if (position !in chatHistory.indices) return null
        return chatHistory[position].text
    }

    /**
     * Return the line-start URL entries embedded in the focused card so the
     * voice resolver can match phrases like "open the first link" or
     * "open the KPFA one" against them. Empty when the card has no
     * line-start URLs (mid-prose URLs are intentionally excluded — those
     * keep the existing inline-tap-via-card-body behaviour).
     */
    fun getCardLinkEntries(position: Int): List<CardUrlExtractor.Entry> {
        if (!isContentPosition(position) || isNewChatCard(position)) return emptyList()
        if (position !in chatHistory.indices) return emptyList()
        return CardUrlExtractor.extract(chatHistory[position].text)
    }

    override fun getItemCount(): Int = chatHistory.size + 1

    override fun getItemViewType(position: Int): Int {
        return if (isNewChatCard(position)) VIEW_TYPE_SENTINEL_CARD else VIEW_TYPE_CHAT_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ChatViewHolder -> {
                holder.bind(cardItemForPosition(position), onUrlTapped)
                // Apply initial focused / unfocused alpha so that every card
                // — including the New Chat sentinel — is correctly dimmed the
                // instant it appears, before the fragment's applyFocusVisuals
                // can run its post-layout pass.  These values mirror the
                // CARD_FOCUS_* constants in ChatPanelFragment.
                val focused = position == focusedPosition
                val bubble = holder.itemView.findViewById<View>(R.id.messageBubble)
                if (bubble != null) {
                    bubble.alpha = if (focused) 1.0f else 0.78f
                    bubble.scaleX = if (focused) 1.04f else 0.96f
                    bubble.scaleY = if (focused) 1.04f else 0.96f
                    // The LATEST chat row AND the New Chat sentinel both
                    // sit at the bottom of the recycler where the orb
                    // overlays the screen, so BOTH get a small left
                    // inset to dodge the avatar. Older history rows
                    // render at margin 0 and read full-width — the orb
                    // is drawn on top via elevation so older content
                    // tucks behind it visually.
                    val isLatestMessage = position == chatHistory.size - 1 && position >= 0
                    val isNewChatSentinel = position == chatHistory.size
                    val dodgesOrb = (isLatestMessage || isNewChatSentinel) && lastRowLeftInsetDp > 0
                    val targetStartPx = if (dodgesOrb) {
                        (lastRowLeftInsetDp * bubble.resources.displayMetrics.density).toInt()
                    } else {
                        0
                    }
                    val lp = bubble.layoutParams
                    if (lp is android.view.ViewGroup.MarginLayoutParams && lp.marginStart != targetStartPx) {
                        lp.marginStart = targetStartPx
                        bubble.layoutParams = lp
                    }
                }
            }
        }
    }

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rowRoot: LinearLayout = itemView.findViewById(R.id.messageRow)
        private val bubble: LinearLayout = itemView.findViewById(R.id.messageBubble)
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val messageScrollView: ScrollView = itemView.findViewById(R.id.messageScrollView)
        private val launchCard: LinearLayout = itemView.findViewById(R.id.launchCard)
        private val launchCardUrl: TextView = itemView.findViewById(R.id.launchCardUrl)

        fun bind(item: CardItem, onUrlTapped: (String) -> Unit) {
            when (item) {
                is CardItem.NewChatCard -> bindNewChatCard()
                is CardItem.MessageCard -> bindMessageCard(item.message, onUrlTapped)
            }
        }

        private fun bindNewChatCard() {
            // Full-width bubble, vertically centred text.  Same height
            // as regular chat cards so the column reads as a uniform
            // stack.
            rowRoot.gravity = Gravity.FILL
            stretchBubble()

            setUniformCardHeight()
            bubble.minimumHeight = 0
            bubble.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            bubble.setBackgroundResource(R.drawable.bg_chat_bubble_assistant)

            messageText.movementMethod = null
            messageText.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            messageText.text = "New Chat"
            messageText.setTextColor(Color.parseColor("#FFFFFFFF"))
            messageText.setShadowLayer(3f, 0f, 1f, Color.BLACK)

            launchCard.visibility = View.GONE
            launchCard.setOnClickListener(null)
            itemView.setOnClickListener { onAssistantRequested() }
        }

        private fun bindMessageCard(message: ChatMessage, onUrlTapped: (String) -> Unit) {
            val isUser = message.fromUser
            rowRoot.gravity = Gravity.FILL
            stretchBubble()

            setUniformCardHeight()
            bubble.minimumHeight = 0
            bubble.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            bubble.setBackgroundResource(
                if (isUser) R.drawable.bg_chat_bubble_user else R.drawable.bg_chat_bubble_assistant
            )

            val urls = findUrls(message.text)
            messageText.movementMethod = LinkMovementMethod.getInstance()
            messageText.highlightColor = Color.TRANSPARENT
            messageText.gravity = Gravity.START
            messageText.setTextColor(Color.WHITE)
            messageText.setShadowLayer(3f, 0f, 1f, Color.BLACK)
            messageText.text = buildLinkedText(message.text, urls, onUrlTapped)
            messageScrollView.post {
                messageScrollView.fullScroll(View.FOCUS_DOWN)
            }

            val launchUrl = urls.firstOrNull()
            if (!isUser && launchUrl != null) {
                launchCard.visibility = View.VISIBLE
                launchCardUrl.text = launchUrl.raw
                // No direct click listeners — taps route through
                // handleFocusedCardTap() which handles expand → open → close flow.
                launchCard.setOnClickListener(null)
                itemView.setOnClickListener(null)
            } else {
                launchCard.visibility = View.GONE
                launchCard.setOnClickListener(null)
                itemView.setOnClickListener(null)
            }
        }

        /**
         * Ensure the bubble fills the card's width instead of wrapping
         * around its content.  The old behaviour centred a narrow pill;
         * the redesign wants each card to stretch from the recycler's
         * left gutter to its right edge.
         */
        private fun stretchBubble() {
            val params = bubble.layoutParams as? LinearLayout.LayoutParams ?: return
            var changed = false
            if (params.width != LinearLayout.LayoutParams.MATCH_PARENT) {
                params.width = LinearLayout.LayoutParams.MATCH_PARENT
                changed = true
            }
            if (params.gravity != Gravity.FILL) {
                params.gravity = Gravity.FILL
                changed = true
            }
            if (changed) bubble.layoutParams = params
        }

        private fun dpToPx(dp: Float): Int {
            return (dp * itemView.resources.displayMetrics.density).toInt()
        }

        private fun setUniformCardHeight() {
            val targetHeightPx = dpToPx(CARD_HEIGHT_DP)
            val params = rowRoot.layoutParams
            if (params != null && params.height != targetHeightPx) {
                params.height = targetHeightPx
                rowRoot.layoutParams = params
            }
            rowRoot.minimumHeight = targetHeightPx
        }

        private fun buildLinkedText(
            text: String,
            urls: List<UrlMatch>,
            onUrlTapped: (String) -> Unit
        ): CharSequence {
            // Delegated to the shared helper so the chat row and the
            // reader-mode expansion render identically. The chat row
            // doesn't track per-URL focus (only reader mode does), so we
            // pass focusedEntryIndex = -1 and let the helper render the
            // bold cyan headers without any chevron prefix.
            return ChatCardSpannableBuilder.build(text, focusedEntryIndex = -1) { url ->
                onUrlTapped(url)
            }
        }
    }

    private fun findUrls(text: String): List<UrlMatch> {
        // Priority 1: structured `open_taplink <url>` directives. When a
        // tool/agent emits one of these the URL it carries is the canonical
        // launch target and we deliberately ignore everything else in the
        // message body — that's how a card whose prose says "...converting
        // to MP3 now.Here's the Kraftwerk..." stops surfacing a phantom
        // "now.here" link alongside the real appassets URL.
        val directiveMatches = findDirectiveUrls(text)
        if (directiveMatches.isNotEmpty()) return directiveMatches

        // Priority 2: free-text URL extraction. Strict pattern that requires
        // an explicit protocol or `www.` prefix to appear in the source —
        // we never synthesize URLs from bare `word.Word` patterns.
        val matches = ArrayList<UrlMatch>()
        val matcher = STRICT_URL_PATTERN.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val raw = text.substring(start, end)
            matches += UrlMatch(
                raw = raw,
                normalized = normalizeUrl(raw),
                start = start,
                end = end
            )
        }
        return matches
    }

    /**
     * Scan [text] for `open_taplink <url>` directives. Each occurrence
     * yields a [UrlMatch] whose `start`/`end` cover only the URL portion
     * (so the directive keyword itself doesn't get colorised cyan in the
     * chat bubble).
     */
    private fun findDirectiveUrls(text: String): List<UrlMatch> {
        val matches = ArrayList<UrlMatch>()
        val matcher = OPEN_TAPLINK_PATTERN.matcher(text)
        while (matcher.find()) {
            val urlStart = matcher.start(1)
            val urlEnd = matcher.end(1)
            val raw = matcher.group(1) ?: continue
            matches += UrlMatch(
                raw = raw,
                normalized = normalizeUrl(raw),
                start = urlStart,
                end = urlEnd
            )
        }
        return matches
    }

    private fun normalizeUrl(raw: String): String {
        val sanitized = raw.trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?')
        return if (sanitized.startsWith("http://") || sanitized.startsWith("https://")) {
            sanitized
        } else {
            "https://$sanitized"
        }
    }
}
