package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * TapRadioTool — Gemini-callable tool for searching, playing, and managing
 * internet radio stations and podcasts via TapRadio.
 *
 * All playback routes through the native TapRadio player (ExoPlayer via
 * radio.html auto-play parameters) — NOT the generic browser media player.
 *
 * Actions:
 *   play    — play a station/podcast by name or direct URL
 *   search  — query Radio Browser API (30k+ stations) + iTunes (podcasts)
 *   podcast — search iTunes for podcasts and play the latest episode
 *   list    — return saved station names
 *   stop    — stop current playback
 *   add     — add a station to saved list
 */
class TapRadioTool(private val context: Context) : AiTapTool {
    override val name = "tapradio"

    private data class PlaybackMetadata(
        val name: String? = null,
        val genre: String? = null,
        val subtitle: String? = null,
        val artist: String? = null,
        val kind: String? = null
    ) {
        fun cleanName(): String? = name?.trim()?.takeIf { it.isNotBlank() }
        fun cleanGenre(): String? = genre?.trim()?.takeIf { it.isNotBlank() }
        fun cleanSubtitle(): String? = subtitle?.trim()?.takeIf { it.isNotBlank() }
        fun cleanArtist(): String? = artist?.trim()?.takeIf { it.isNotBlank() }
        fun cleanKind(): String? = kind?.trim()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "TapRadioTool"
        private const val RADIO_PREFS_KEY = "tapradio_stations"
        private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
        private const val ITUNES_TOP_PODCASTS_URL = "https://itunes.apple.com/us/rss/toppodcasts"
        private val RADIO_BROWSER_SERVERS = listOf(
            "https://de1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info",
            "https://at1.api.radio-browser.info"
        )

        // iTunes podcast genre IDs — used to scope top-charts lookups.
        // Full list: https://affiliate.itunes.apple.com/resources/documentation/genre-mapping/
        private val ITUNES_PODCAST_GENRES = mapOf(
            "news" to 1489,
            "politics" to 1471,
            "business" to 1321,
            "technology" to 1318,
            "tech" to 1318,
            "comedy" to 1303,
            "education" to 1304,
            "science" to 1533,
            "health" to 1307,
            "fitness" to 1512,
            "sports" to 1545,
            "true crime" to 1488,
            "crime" to 1488,
            "society" to 1324,
            "culture" to 1324,
            "history" to 1487,
            "arts" to 1301,
            "music" to 1310,
            "fiction" to 1483,
            "leisure" to 1502,
            "religion" to 1314,
            "spirituality" to 1314,
            "kids" to 1305,
            "family" to 1305,
            "tv" to 1309,
            "film" to 1309
        )
    }

    private val prefs by lazy {
        context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
    }

    private fun podcastResultBoundaryInstruction(): String =
        "MEDIA-TYPE ISOLATION: This is a podcast result. If the current user turn asked only for podcasts, " +
            "answer only from this podcast result and do not include prior songs, YouTube suggestions, Spotify/music " +
            "results, radio stations, or local-media results. Topic words such as \"music\", artist names, albums, " +
            "genres, or song titles can describe what the podcast is about; they do not make this a music playback " +
            "request. SEARCH/FALLBACK (RULE 19 applies — see top of system prompt): If these podcast results are weak, " +
            "irrelevant, or empty, say briefly what TapRadio searched, then call your built-in googleSearch tool " +
            "INTERNALLY (voice-grounded only) to retrieve current podcast availability or context. SPEAK the grounded " +
            "summary aloud. DO NOT call open_taplink to a Google search URL — opening the browser is a separate user-" +
            "visible action that requires its own consent (see RULE ZERO-E). After the grounded summary, close with " +
            "ONE offer: 'I can also have TapClaw dig deeper for more options.' That offer is mandatory under RULE 19, " +
            "even if you are confident about the result. Wait for explicit user confirmation before calling " +
            "tapclaw_agent or open_taplink. Do not substitute another media type without confirmation."

    private fun podcastNoResultFallbackInstruction(topic: String): String =
        "TapRadio searched Apple Podcasts/iTunes for podcasts about '$topic' and found nothing useful. " +
            "Keep the answer podcast-only. Per RULE 19 (media search augmentation): call your built-in googleSearch " +
            "tool INTERNALLY (voice-grounded) to find current information about '$topic'-related podcasts and SPEAK " +
            "a one-or-two-sentence summary of the best matches. DO NOT call open_taplink to a Google search URL — " +
            "the browser is a separate user-visible action that needs its own consent under RULE ZERO-E. After " +
            "speaking the grounded summary, close with ONE TapClaw escalation offer: 'I can also have TapClaw dig " +
            "deeper for more options.' Wait for explicit user confirmation before calling tapclaw_agent or " +
            "open_taplink. Do not substitute songs, YouTube videos, radio stations, or local media without confirmation."

    // ── Top podcasts cache ──────────────────────────────────────────
    // The last iTunes top-podcasts fetch (voice or hud call) is cached
    // here so subsequent HUD-display calls can reuse the same rows —
    // including user-narrowed subsets — without re-fetching. The cache
    // persists for the lifetime of the ToolDispatcher, which is the
    // same lifetime as MainActivity.
    private var cachedTopPodcasts: List<TopPodcastEntry> = emptyList()
    private var cachedTopPodcastsLabel: String = ""
    private var cachedTopPodcastsGenre: String? = null

    // ── Episode-search cache (for keyword topic queries) ───────────
    // Separate from cachedTopPodcasts because keyword queries return
    // EPISODES while genre queries return SHOWS. The cache lets the
    // narrow-then-display flow reuse the same fetched results.
    private var cachedEpisodeSearch: List<TopEpisodeEntry> = emptyList()
    private var cachedEpisodeSearchLabel: String = ""
    private var cachedEpisodeSearchQuery: String? = null

    // ── Station-search cache (radio stations discovered via search) ─
    // Lets Gemini's "voice-list first, then show on glasses" flow
    // reuse the same fetched stations (and optionally narrow by
    // selection) without re-hitting Radio Browser.
    private var cachedSearchStations: List<JSONObject> = emptyList()
    private var cachedSearchPodcasts: List<JSONObject> = emptyList()
    private var cachedSearchQuery: String? = null

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"]?.trim()?.lowercase() ?: "list"
        val query = args["query"]?.trim() ?: ""
        val display = args["display"]?.trim()?.lowercase() ?: "voice"
        val metadata = PlaybackMetadata(
            name = args["name"],
            genre = args["genre"],
            subtitle = args["subtitle"],
            artist = args["artist"],
            kind = args["kind"]
        )

        Log.d(TAG, "action=$action query=$query display=$display")

        return when (action) {
            "play" -> playStation(query, metadata)
            "search" -> searchStations(query, display, args["selection"], includePodcasts = true)
            "search_stations", "search_station", "search_radio", "search_radios" ->
                searchStations(query, display, args["selection"], includePodcasts = false)
            "podcast" -> playPodcast(query)
            "preview" -> previewMedia(query)
            "preview_station" -> previewStation(query)
            "preview_podcast" -> previewPodcast(query)
            "info_station", "info", "about_station", "tell_about_station",
            "station_info", "describe_station" -> infoStation(query)
            "top_podcasts", "trending_podcasts", "recent_podcasts" -> {
                // Gemini sometimes forgets to pass genre even when the user
                // named a topic ('top news podcasts' → no genre arg). Fall
                // back in this order: explicit genre arg → query arg →
                // subtitle arg → artist arg. extractGenreHint() then scans
                // whichever string we got for a known genre keyword.
                val rawGenreHint = listOfNotNull(
                    args["genre"]?.trim()?.takeIf { it.isNotBlank() },
                    query.takeIf { it.isNotBlank() },
                    args["subtitle"]?.trim()?.takeIf { it.isNotBlank() },
                    args["artist"]?.trim()?.takeIf { it.isNotBlank() }
                ).firstOrNull().orEmpty()
                val resolvedGenre = extractGenreHint(rawGenreHint).ifBlank { rawGenreHint }
                Log.d(
                    TAG,
                    "top_podcasts args → genre='${args["genre"]}' query='$query' " +
                        "subtitle='${args["subtitle"]}' artist='${args["artist"]}' " +
                        "→ resolvedGenre='$resolvedGenre' display='$display' " +
                        "selection='${args["selection"]}'"
                )
                topPodcasts(resolvedGenre, display, args["selection"])
            }
            "list" -> listStations()
            "stop" -> stopPlayback()
            "add" -> addStation(query, args["name"] ?: "", args["genre"] ?: "")
            else -> Result.success("Unknown TapRadio action: $action. Use play, search, search_stations, podcast, preview, preview_station, preview_podcast, info_station, top_podcasts, list, stop, or add.")
        }
    }

    // ── Preview (describe without playing) ──────────────────────────
    //
    // Preview actions look up a station or podcast and return a brief,
    // descriptive summary WITHOUT the open_taplink URL. This lets Gemini
    // tell the user what it found and wait for follow-up questions or
    // explicit confirmation before actually starting playback.

    /**
     * Generic preview — tries both station and podcast lookups and
     * returns whichever gives a result. If both match, prefers stations
     * (radio callsigns are typically more specific than podcast titles).
     */
    private suspend fun previewMedia(query: String): Result<String> {
        if (query.isBlank()) return Result.success("Please specify a station or podcast name to preview.")
        val stationDesc = describeStation(query)
        if (stationDesc != null) {
            return Result.success(
                "$stationDesc\n\nThis will start playing on TapRadio. Want me to play it, or do you want to ask anything about it first? Say 'play it' or 'go ahead' to start."
            )
        }
        val podcastDesc = describePodcast(query)
        if (podcastDesc != null) {
            return Result.success(
                "$podcastDesc\n\nThis will start playing on TapRadio. Want me to play it, or do you want to ask anything about it first? Say 'play it' or 'go ahead' to start."
            )
        }
        return Result.success("No station or podcast found matching '$query'. Try a different name, or say 'search $query' to discover options.")
    }

    private suspend fun previewStation(query: String): Result<String> {
        if (query.isBlank()) return Result.success("Please specify a station name to preview.")
        val desc = describeStation(query)
            ?: return Result.success("No radio station found matching '$query'. Try 'search $query' to discover options.")
        return Result.success(
            "$desc\n\nThis will start playing on TapRadio. Want me to play it, or do you want to ask anything about it first? Say 'play it' or 'go ahead' to start."
        )
    }

    private suspend fun previewPodcast(query: String): Result<String> {
        if (query.isBlank()) return Result.success("Please specify a podcast name to preview.")
        val desc = describePodcast(query)
            ?: return Result.success("No podcast found matching '$query'. Try a different name.")
        return Result.success(
            "$desc\n\nThis will start playing the latest episode on TapRadio. Want me to play it, or do you want to ask anything about it first? Say 'play it' or 'go ahead' to start."
        )
    }

    // ── Info (describe + ENRICH) ────────────────────────────────────
    //
    // `info_station` is the "tell me more about this station" path.
    //
    // Saved TapRadio favorites typically only record name + genre + stream URL —
    // there is no long-form description. Radio Browser adds a tag, country,
    // codec, and bitrate, but nothing about programming, hosts, history, or
    // signature shows. If Gemini just reads those fields back, the user
    // feels like nothing was learned.
    //
    // So this action deliberately returns two blocks:
    //   1. LOCAL METADATA — whatever TapRadio knows (favorite flag, genre,
    //      country, codec/bitrate) so the reply is grounded in the exact
    //      station the user is asking about.
    //   2. An explicit ENRICHMENT DIRECTIVE telling Gemini to augment the
    //      reply with Google Search grounding — programming schedule,
    //      notable DJs/hosts/shows, owning organization, city/frequency,
    //      signature sound, website, anything recent in the news.
    //
    // The directive is a tool-returned instruction (not a separate tool call)
    // because it's coming back through the Live audio channel as the tool
    // result; Gemini will read the metadata + follow the directive in a
    // single turn.
    private suspend fun infoStation(query: String): Result<String> {
        if (query.isBlank()) {
            return Result.success(
                "Please tell me which station you want to know more about."
            )
        }

        val savedMeta = findSavedStationMeta(query)
        val localBlock: String = if (savedMeta != null) {
            buildString {
                append("Saved station")
                if (savedMeta.fav) append(" (★ favorite)")
                append(": ${savedMeta.name}")
                if (savedMeta.genre.isNotBlank()) append(" — ${savedMeta.genre}")
                if (savedMeta.desc.isNotBlank()) append("\nDescription: ${savedMeta.desc}")
                if (savedMeta.url.isNotBlank()) {
                    append("\nStream: ${savedMeta.url}")
                }
            }
        } else {
            // Not in favorites — fall back to Radio Browser for a seed.
            describeStation(query)
                ?: "No local metadata for '$query'. Answer from Google Search grounding only."
        }

        val enrichmentDirective = buildString {
            append("\n\n---\n")
            append("ENRICHMENT DIRECTIVE — CRITICAL:\n")
            append(
                "The block above is ONLY the TapRadio local metadata for this station. It is " +
                    "intentionally minimal (favorites store just name + genre + stream URL). " +
                    "DO NOT read those raw fields back to the user — they already know the " +
                    "station is saved. Instead, use Google Search grounding NOW to speak a " +
                    "rich, enlightening answer that covers (where relevant): what the station " +
                    "is known for, its city/country and frequency if broadcast, the owning " +
                    "organization or network, signature programs and well-known hosts/DJs, " +
                    "musical style or editorial voice, founding year or notable history, " +
                    "website, and anything recent in the news. "
            )
            append(
                "Keep it to 3-6 sentences of natural speech — no bullet lists, no source " +
                    "citations, no URLs read aloud. If Google Search grounding returns " +
                    "nothing, say so honestly and offer to search the web. "
            )
            if (savedMeta != null) {
                append(
                    "You MAY briefly acknowledge at the end that it's one of their saved " +
                        "favorites and offer to play it. "
                )
            } else {
                append(
                    "After the enriched description, offer to add this station to their " +
                        "TapRadio favorites or start playing it. "
                )
            }
            append(
                "NEVER stop at the local metadata alone — that is not an acceptable answer " +
                    "to 'tell me more about this station'."
            )
        }

        return Result.success(localBlock + enrichmentDirective)
    }

    /** Compact saved-station record used by infoStation(). */
    private data class SavedStationMeta(
        val name: String,
        val genre: String,
        val url: String,
        val desc: String,
        val fav: Boolean
    )

    private fun findSavedStationMeta(query: String): SavedStationMeta? {
        val qLower = query.lowercase().trim()
        if (qLower.isBlank()) return null
        val saved = getSavedStations()
        val match = saved.firstOrNull { station ->
            val n = station.optString("name", "").lowercase().trim()
            val g = station.optString("genre", "").lowercase().trim()
            n.isNotBlank() && (n.contains(qLower) || qLower.contains(n) || g == qLower)
        } ?: return null
        return SavedStationMeta(
            name = match.optString("name", "Unknown").trim(),
            genre = match.optString("genre", "").trim(),
            url = match.optString("url", "").trim(),
            desc = match.optString("desc", "").trim(),
            fav = match.optBoolean("fav", false)
        )
    }

    /**
     * Look up a station and return a one-line description. Returns null
     * if nothing matches. Does NOT include an open_taplink URL.
     */
    private suspend fun describeStation(query: String): String? {
        // 1) Saved stations first (fuzzy match)
        val saved = getSavedStations()
        val qLower = query.lowercase()
        val savedMatch = saved.firstOrNull { station ->
            val n = station.optString("name", "").lowercase()
            val g = station.optString("genre", "").lowercase()
            n.contains(qLower) || qLower.contains(n) || g.contains(qLower)
        }
        if (savedMatch != null) {
            val name = savedMatch.optString("name", "Unknown").trim()
            val genre = savedMatch.optString("genre", "").trim()
            return buildString {
                append("Found saved station: $name")
                if (genre.isNotBlank()) append(" — $genre")
            }
        }

        // 2) Radio Browser lookup (use exact-ish match if possible)
        val results = searchRadioBrowser(query, limit = 5)
        if (results.isEmpty()) return null
        val qLowerRadio = query.lowercase()
        val best = results.firstOrNull {
            it.optString("name", "").lowercase().contains(qLowerRadio) ||
                qLowerRadio.contains(it.optString("name", "").lowercase())
        } ?: results[0]
        val name = best.optString("name", "Unknown").trim()
        val tags = best.optString("tags", "").split(",").firstOrNull()?.trim().orEmpty()
        val country = best.optString("country", "").trim()
        val codec = best.optString("codec", "").trim()
        val bitrate = best.optInt("bitrate", 0)
        return buildString {
            append("Found radio station: $name")
            if (tags.isNotBlank()) append(" ($tags)")
            if (country.isNotBlank()) append(" — $country")
            if (codec.isNotBlank() || bitrate > 0) {
                append(", ")
                if (codec.isNotBlank()) append(codec)
                if (bitrate > 0) {
                    if (codec.isNotBlank()) append(" ")
                    append("${bitrate}kbps")
                }
            }
        }
    }

    /**
     * Look up a podcast and return a short description including the
     * latest episode title if available. Returns null if no match.
     * Does NOT include an open_taplink URL.
     */
    private suspend fun describePodcast(query: String): String? = withContext(Dispatchers.IO) {
        val podcast = searchItunes(query) ?: return@withContext null
        val podcastName = podcast.optString("collectionName",
            podcast.optString("trackName", "Podcast")).trim()
        val artist = podcast.optString("artistName", "").trim()
        val primaryGenre = podcast.optString("primaryGenreName", "").trim()
        val feedUrl = podcast.optString("feedUrl", "").trim()

        // Try to get the latest episode title (best-effort)
        val latestEpisodeTitle = if (feedUrl.isNotBlank()) {
            parseRssFeedForLatestEpisode(feedUrl)?.second?.takeIf { it.isNotBlank() }
        } else null

        buildString {
            append("Found podcast: $podcastName")
            if (artist.isNotBlank()) append(" by $artist")
            if (primaryGenre.isNotBlank()) append(" — $primaryGenre")
            if (!latestEpisodeTitle.isNullOrBlank()) {
                append("\nLatest episode: $latestEpisodeTitle")
            }
        }
    }

    // ── Top Podcasts (iTunes charts) ─────────────────────────────────
    //
    // Fetches the current iTunes top podcasts chart (optionally scoped
    // to a genre) and returns either a voice-friendly numbered list
    // (default) or an open_taplink URL to a HUD-friendly HTML view.

    /**
     * @param genreQuery optional genre/topic keyword (e.g. "news", "jazz", "new wave")
     * @param display    "voice" (default) returns a numbered text list for
     *                   Gemini to read aloud; "glasses" (alias: "hud") returns
     *                   an open_taplink URL to a visual list page that shows
     *                   on the user's glasses / in the browser.
     * @param selection  optional comma-separated 1-based indices from the
     *                   most recent voice list (e.g. "1,3,5"). Only applies
     *                   when display='glasses' — lets Gemini narrow the list
     *                   before displaying without re-fetching.
     *
     * If the topic matches one of Apple's podcast chart genres (news,
     * comedy, business, etc.) this returns the iTunes TOP CHART of SHOWS
     * scoped to that genre. Otherwise — e.g. 'amiga 500', 'history of
     * rome', 'vintage synthesizers', or any arbitrary keyword — it
     * delegates to topEpisodesForKeyword which searches iTunes for
     * individual EPISODES that talk about the topic, ranked by relevance
     * across multiple iTunes country stores.
     */
    private suspend fun topPodcasts(
        genreQuery: String,
        display: String,
        selection: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        // Normalize display aliases — accept 'hud', 'glasses', 'browser',
        // 'display', 'show' all as the visual path.
        val normalizedDisplay = when (display.lowercase().trim()) {
            "hud", "glasses", "browser", "display", "show", "visual" -> "glasses"
            else -> "voice"
        }

        val genreKey = genreQuery.trim().lowercase().takeIf { it.isNotBlank() }
        val genreId = genreKey?.let { key ->
            // Exact match first.
            val exact = ITUNES_PODCAST_GENRES[key]
            if (exact != null) return@let exact

            // Substring fallback — only the abbreviation/prefix direction:
            //   k.contains(key)   ✓  ("fit" → "fitness", "true" → "true crime")
            //   key.contains(k)   ✗  banned: it caused niche topics to silently
            //                         route into generic charts. e.g.
            //   "experimental music".contains("music")          → wrong "Music" chart
            //   "san francisco tape music collective".contains("music")
            //                                                   → wrong "Music" chart
            //
            // Niche topics now fall through to topEpisodesForKeyword (the
            // keyword episode search), which is what the user wanted all along.
            val abbreviationMatch = ITUNES_PODCAST_GENRES.entries
                .firstOrNull { (k, _) -> k.contains(key) }?.value
            if (abbreviationMatch == null) {
                Log.d(
                    TAG,
                    "ITUNES_PODCAST_GENRES no exact/abbreviation match for key='$key' — " +
                        "routing to keyword episode search via topEpisodesForKeyword"
                )
            }
            abbreviationMatch
        }
        val resolvedGenreLabel = genreId?.let { id ->
            ITUNES_PODCAST_GENRES.entries.firstOrNull { it.value == id }?.key
        }

        // KEYWORD PATH — arbitrary topic (no Apple chart genre match).
        // Return EPISODES that talk about the topic, not just shows whose
        // title matches. Handled in its own function for clean typing.
        if (genreId == null && genreKey != null) {
            return@withContext topEpisodesForKeyword(
                keyword = genreKey,
                display = normalizedDisplay,
                selection = selection
            )
        }

        // Try to reuse the cached fetch so narrowing via selection or the
        // same topic applies to the same list Gemini already read aloud.
        val canReuseCache = normalizedDisplay == "glasses" &&
            cachedTopPodcasts.isNotEmpty() &&
            (genreKey == null || genreKey == cachedTopPodcastsGenre)

        val (podcasts, labelPrefix) = if (canReuseCache) {
            Pair(cachedTopPodcasts, cachedTopPodcastsLabel)
        } else {
            val limit = 10

            // Genre-chart path (matched an Apple chart genre like "news")
            // OR no-topic path (generic top chart). Keyword paths are
            // handled above in topEpisodesForKeyword.
            val (fetched, label) = if (genreId != null) {
                val chartUrl = buildString {
                    append(ITUNES_TOP_PODCASTS_URL)
                    append("/limit=$limit")
                    append("/genre=$genreId")
                    append("/json")
                }
                val entries = fetchTopPodcastsChart(chartUrl)
                val prettyGenre = resolvedGenreLabel
                    ?.replaceFirstChar { it.uppercase() }
                    ?: "Podcasts"
                Pair(entries, "Top ${entries.size} $prettyGenre podcasts on Apple")
            } else {
                // No topic at all — generic top chart
                val chartUrl = "$ITUNES_TOP_PODCASTS_URL/limit=$limit/json"
                val entries = fetchTopPodcastsChart(chartUrl)
                Pair(entries, "Top ${entries.size} podcasts on Apple")
            }

            if (fetched.isEmpty()) {
                return@withContext Result.success(
                    if (genreKey != null) {
                        "I couldn't find any podcasts for '$genreKey' right now. " +
                            podcastNoResultFallbackInstruction(genreKey)
                    } else {
                        "I couldn't fetch the top podcasts chart right now. " +
                            podcastNoResultFallbackInstruction("the current top podcasts chart")
                    }
                )
            }

            // Cache for subsequent glasses/selection calls.
            cachedTopPodcasts = fetched
            cachedTopPodcastsLabel = label
            cachedTopPodcastsGenre = genreKey

            Pair(fetched, label)
        }

        when (normalizedDisplay) {
            "glasses" -> {
                // Apply user-narrowed selection if provided.
                val indices = parseSelectionIndices(selection, podcasts.size)
                val displayList = if (indices != null && indices.isNotEmpty()) {
                    indices.mapNotNull { idx -> podcasts.getOrNull(idx) }
                } else {
                    podcasts
                }

                if (displayList.isEmpty()) {
                    return@withContext Result.success(
                        "No podcasts selected to show on the glasses. Pass selection='[comma-separated indices]' or omit to show the full cached list."
                    )
                }

                val glassesLabel = if (indices != null && displayList.size != podcasts.size) {
                    "${displayList.size} selected podcasts"
                } else {
                    labelPrefix
                }

                val glassesUrl = buildPodcastHudUrl(displayList, glassesLabel)
                Result.success(
                    "open_taplink:$glassesUrl\n$glassesLabel now showing on your glasses. Tap any podcast to see its latest episodes."
                )
            }
            else -> {
                // Voice (default) — numbered list for Gemini to read aloud.
                // Each entry includes a one-sentence description (extracted
                // from the iTunes feed) that Gemini should speak verbatim.
                val sb = StringBuilder()
                sb.append("$labelPrefix:\n")
                for ((i, p) in podcasts.withIndex()) {
                    sb.append("${i + 1}. ${p.title}")
                    if (p.artist.isNotBlank()) sb.append(" — ${p.artist}")
                    if (p.description.isNotBlank()) {
                        sb.append("\n   Blurb: ${p.description}")
                    }
                    sb.append("\n")
                }
                sb.append(
                    "\nRESPECT THE USER'S QUANTITY: If the user asked for 'a couple', 'a few', " +
                    "'one or two', '2 or 3', etc., ONLY read that many from the list — " +
                    "do NOT read all of them. 'A couple' = 2, 'a few' = 3, 'some' = 3-4. " +
                    "If the user didn't specify a quantity, read up to 5 (not all 10). " +
                    "Mention that there are more available if they want to hear the rest. " +
                    "Read this list back to the user naturally. For each podcast, " +
                    "say the title and ONE short sentence about what the show is about, " +
                    "using the 'Blurb:' text as a guide (rephrase to one natural sentence; " +
                    "do NOT just read the raw blurb verbatim). Keep each entry to no more " +
                    "than one sentence so the full list reads in roughly 30 seconds. " +
                    "Do NOT invent details about shows you don't recognize — only use what's " +
                    "in the Blurb field. If a podcast has no Blurb, just say the title and " +
                    "artist with no description. " +
                    "If the user asks to narrow the list " +
                        "(e.g. 'just the first three', 'only numbers 2 and 5', 'skip the boring ones'), figure out " +
                        "which original indices they mean from the numbered list above and remember them. " +
                        "If they then ask to show the list on the glasses, the browser, or the display, call " +
                        "this tool again with action='top_podcasts', display='glasses', the SAME genre/topic, " +
                        "and selection='[comma-separated indices]' — for example selection='1,3,5'. " +
                        "If they want to show the full list without narrowing, call with display='glasses' and no selection. " +
                        "NEVER call open_taplink with a made-up URL for this list — the only way to show it " +
                        "is to call this tool again with display='glasses'. " +
                        "For playing a specific show, use preview_podcast (then podcast on confirmation). " +
                        podcastResultBoundaryInstruction()
                )
                Result.success(sb.toString())
            }
        }
    }

    /**
     * Topic-keyword path: search iTunes for individual EPISODES that talk
     * about the topic (not just shows whose title matches). Returns a
     * voice list for Gemini to read aloud, OR a HUD URL for the glasses
     * display, mirroring the topPodcasts contract.
     *
     * Caches the fetch in cachedEpisodeSearch so the narrow-then-display
     * flow can reuse it without re-fetching.
     */
    private suspend fun topEpisodesForKeyword(
        keyword: String,
        display: String,
        selection: String?
    ): Result<String> {
        val limit = 10

        // Reuse cached fetch if the user is just narrowing the same query.
        val canReuseCache = display == "glasses" &&
            cachedEpisodeSearch.isNotEmpty() &&
            keyword == cachedEpisodeSearchQuery

        val (episodes, labelPrefix) = if (canReuseCache) {
            Pair(cachedEpisodeSearch, cachedEpisodeSearchLabel)
        } else {
            val fetched = searchEpisodesBroad(keyword, limit = limit)
            if (fetched.isEmpty()) {
                return Result.success(
                    "I couldn't find any podcast episodes about '$keyword' right now. " +
                        podcastNoResultFallbackInstruction(keyword)
                )
            }
            val pretty = keyword.replaceFirstChar { it.uppercase() }
            val label = "${fetched.size} episodes about '$pretty'"
            cachedEpisodeSearch = fetched
            cachedEpisodeSearchLabel = label
            cachedEpisodeSearchQuery = keyword
            Pair(fetched, label)
        }

        return when (display) {
            "glasses" -> {
                val indices = parseSelectionIndices(selection, episodes.size)
                val displayList = if (indices != null && indices.isNotEmpty()) {
                    indices.mapNotNull { idx -> episodes.getOrNull(idx) }
                } else {
                    episodes
                }
                if (displayList.isEmpty()) {
                    return Result.success(
                        "No episodes selected to show on the glasses. Pass " +
                            "selection='[comma-separated indices]' or omit to show " +
                            "the full cached list."
                    )
                }
                val glassesLabel = if (indices != null && displayList.size != episodes.size) {
                    "${displayList.size} selected episodes about '${keyword.replaceFirstChar { it.uppercase() }}'"
                } else {
                    labelPrefix
                }
                val glassesUrl = buildEpisodeSearchHudUrl(displayList, glassesLabel)
                Result.success(
                    "open_taplink:$glassesUrl\n$glassesLabel now showing on your glasses. " +
                        "Tap an episode to play it, the show name to see other episodes, " +
                        "or the creator to see other podcasts they've made."
                )
            }
            else -> {
                // Voice list — numbered episodes for Gemini to read aloud.
                // Each entry includes show name, artist, and a one-sentence blurb.
                val sb = StringBuilder()
                sb.append("$labelPrefix:\n")
                for ((i, ep) in episodes.withIndex()) {
                    sb.append("${i + 1}. \"${ep.episodeTitle}\"")
                    if (ep.showTitle.isNotBlank()) sb.append(" from ${ep.showTitle}")
                    if (ep.artist.isNotBlank()) sb.append(" by ${ep.artist}")
                    if (ep.description.isNotBlank()) {
                        sb.append("\n   Blurb: ${ep.description}")
                    }
                    sb.append("\n")
                }
                sb.append(
                    "\nRESPECT THE USER'S QUANTITY: If the user asked for 'a couple', 'a few', " +
                        "'one or two', '2 or 3', etc., ONLY read that many from the list — " +
                        "do NOT read all of them. 'A couple' = 2, 'a few' = 3, 'some' = 3-4. " +
                        "If the user didn't specify a quantity, read up to 5 (not all 10). " +
                        "Mention that there are more available if they want to hear the rest. " +
                        "Read this list back to the user naturally. For each episode, " +
                        "say the episode title, the show name (if different), and ONE short " +
                        "sentence about what the episode covers — use the 'Blurb:' text as " +
                        "a guide and rephrase to one natural sentence. Keep each entry to one " +
                        "sentence so the full list reads in roughly 30 seconds. " +
                        "Do NOT invent details — only use the Blurb field. If a Blurb is " +
                        "missing, just say the episode title and show name. " +
                        "After reading the list, offer to display it on the glasses or " +
                        "browser. If the user confirms, call tapradio AGAIN with " +
                        "action='top_podcasts', display='glasses', and the SAME query='$keyword'. " +
                        "If the user asks to narrow the list (e.g. 'just the first three'), " +
                        "remember the original indices and pass them as selection='1,3,5'. " +
                        "NEVER fabricate an open_taplink URL — the only way to show this list " +
                        "is to call this tool again with display='glasses'. " +
                        podcastResultBoundaryInstruction()
                )
                Result.success(sb.toString())
            }
        }
    }

    /**
     * Convert an iTunes search-result JSONObject into a TopPodcastEntry.
     * Returns null if the essential fields are missing.
     *
     * @param countryCode ISO country code of the iTunes store the result came
     *   from (e.g. "us", "gb"). Defaults to "us".
     */
    private fun JSONObject.toTopPodcastEntry(countryCode: String = "us"): TopPodcastEntry? {
        val title = optString("collectionName").trim().ifBlank {
            optString("trackName").trim()
        }
        if (title.isBlank()) return null
        val artist = optString("artistName").trim()
        // Prefer the largest artwork iTunes gives us.
        val artwork = optString("artworkUrl600").trim().ifBlank {
            optString("artworkUrl100").trim()
        }
        val viewUrl = optString("collectionViewUrl").trim()
        val collectionId = optLong("collectionId", 0L).takeIf { it > 0 }?.toString().orEmpty()
        val genre = optString("primaryGenreName").trim()
        // iTunes returns a few possible description fields; pick the longest
        // one available, then trim to one sentence.
        val rawDescription = listOf(
            optString("description"),
            optString("collectionCensoredName"),
            optString("longDescription")
        ).map { it.trim() }.maxByOrNull { it.length }.orEmpty()
        val description = firstSentenceOf(rawDescription)
        return TopPodcastEntry(
            title = title,
            artist = artist,
            artworkUrl = artwork,
            itunesUrl = viewUrl,
            collectionId = collectionId,
            description = description,
            genre = genre,
            country = countryCode
        )
    }

    /**
     * Extract the first sentence of a description string, capped at ~200
     * characters. Strips HTML tags, collapses whitespace, and skips common
     * abbreviations (Dr., Mr., St., etc.). Returns empty string if blank.
     */
    private fun firstSentenceOf(raw: String): String {
        if (raw.isBlank()) return ""
        // Strip HTML tags (some podcasts return HTML in their description)
        val noHtml = raw.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        // Collapse whitespace and newlines
        val collapsed = noHtml.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isBlank()) return ""
        // If the description is already short, return it as-is.
        if (collapsed.length <= 180) return collapsed

        // Walk through the string looking for sentence-ending punctuation
        // followed by whitespace + uppercase. Skip if the character before
        // the period is part of a known abbreviation.
        val abbreviations = setOf(
            "dr", "mr", "mrs", "ms", "st", "jr", "sr", "vs", "etc",
            "inc", "ltd", "co", "corp", "ave", "blvd", "rd", "no",
            "feat", "ft", "rev", "rep", "sen", "gov", "pres",
            "u.s", "u.k", "e.g", "i.e", "a.m", "p.m"
        )
        var i = 0
        while (i < collapsed.length - 1) {
            val ch = collapsed[i]
            if (ch == '.' || ch == '!' || ch == '?') {
                // Look ahead: must be whitespace then uppercase or end-of-string
                val nextNonWs = nextNonWhitespaceIndex(collapsed, i + 1)
                val isEnd = nextNonWs == -1 || nextNonWs >= collapsed.length
                val nextCharIsUpper = !isEnd && collapsed[nextNonWs].isUpperCase()
                val hasWhitespaceBetween = nextNonWs > i + 1
                if (isEnd || (nextCharIsUpper && hasWhitespaceBetween)) {
                    // Check if this period is part of an abbreviation.
                    // Look back at the word ending here.
                    val wordStart = collapsed.lastIndexOf(' ', i - 1) + 1
                    val word = collapsed.substring(wordStart, i).lowercase()
                    if (word !in abbreviations) {
                        val firstSentence = collapsed.substring(0, i + 1).trim()
                        return if (firstSentence.length > 200) {
                            firstSentence.substring(0, 197).trim() + "..."
                        } else {
                            firstSentence
                        }
                    }
                }
            }
            i++
        }
        // No clean sentence break found — truncate at 200 chars.
        return if (collapsed.length > 200) {
            collapsed.substring(0, 197).trim() + "..."
        } else {
            collapsed
        }
    }

    /** Find the index of the next non-whitespace char at or after startIndex. */
    private fun nextNonWhitespaceIndex(s: String, startIndex: Int): Int {
        for (idx in startIndex until s.length) {
            if (!s[idx].isWhitespace()) return idx
        }
        return -1
    }

    /**
     * Scan an arbitrary text string for a known iTunes podcast genre
     * keyword and return the first match (as the map key) or empty
     * string if nothing matches.
     *
     * This is a defensive fallback for cases where Gemini doesn't pass
     * `genre` despite the user clearly naming a topic ('list top news
     * podcasts' → Gemini sometimes sends query='top news podcasts' or
     * even nothing specific). We scan the raw text for any supported
     * genre keyword and use that.
     *
     * Longer keys are tested first so 'true crime' beats 'crime' when
     * both appear in the input.
     */
    private fun extractGenreHint(text: String): String {
        if (text.isBlank()) return ""
        val lower = text.lowercase()
        // Exact match shortcut.
        ITUNES_PODCAST_GENRES[lower]?.let { return lower }
        // Substring scan — order by key length desc to prefer more
        // specific matches ('true crime' > 'crime').
        val sorted = ITUNES_PODCAST_GENRES.keys.sortedByDescending { it.length }
        for (key in sorted) {
            if (lower.contains(key)) return key
        }
        return ""
    }

    /**
     * Parse a comma/space-separated 1-based index string into a list of
     * 0-based indices, filtering out anything out of range.
     */
    private fun parseSelectionIndices(selection: String?, maxSize: Int): List<Int>? {
        if (selection.isNullOrBlank()) return null
        return selection
            .split(',', ' ', ';')
            .mapNotNull { it.trim().toIntOrNull() }
            .map { it - 1 }
            .filter { it in 0 until maxSize }
            .distinct()
    }

    private data class TopPodcastEntry(
        val title: String,
        val artist: String,
        val artworkUrl: String,
        val itunesUrl: String,
        val collectionId: String = "",
        val description: String = "",  // one-sentence blurb (already trimmed)
        val genre: String = "",        // primary iTunes genre
        val country: String = "us"     // ISO country code from iTunes store
    )

    /**
     * A single podcast EPISODE returned by iTunes' podcastEpisode entity
     * search. Used when the user searches for a topic (e.g. "amiga 500")
     * and wants individual episodes that talk about it, not just shows
     * whose title matches.
     */
    private data class TopEpisodeEntry(
        val episodeTitle: String,
        val showTitle: String,         // collectionName (parent show)
        val artist: String,            // artistName (creator)
        val artworkUrl: String,
        val episodeAudioUrl: String,   // direct audio URL (mp3/m4a)
        val description: String,       // one-sentence blurb
        val collectionId: String,      // parent show ID for drill-in
        val trackId: String,           // episode ID for dedupe
        val releaseDate: String,       // raw ISO date from iTunes
        val durationMs: Long,          // playback duration in ms
        val feedUrl: String,           // parent show RSS feed URL
        val genre: String = "",
        val country: String = "us"
    )

    /**
     * Parse the iTunes top-podcasts RSS JSON feed into a lightweight list.
     * The feed shape is:
     *   { "feed": { "entry": [ { "im:name": { "label": ... },
     *                             "im:artist": { "label": ... },
     *                             "im:image": [ { "label": ... } ],
     *                             "link": { "attributes": { "href": ... } } }, ... ] } }
     */
    private fun fetchTopPodcastsChart(url: String): List<TopPodcastEntry> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "TapInsight/1.1.2")
        return try {
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().readText()
            val root = JSONObject(body)
            val feed = root.optJSONObject("feed") ?: return emptyList()
            val entries = feed.optJSONArray("entry") ?: return emptyList()
            val result = mutableListOf<TopPodcastEntry>()
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val title = entry.optJSONObject("im:name")?.optString("label").orEmpty().trim()
                val artist = entry.optJSONObject("im:artist")?.optString("label").orEmpty().trim()
                // Prefer the largest image (typically last in the array).
                val images = entry.optJSONArray("im:image")
                val artwork = if (images != null && images.length() > 0) {
                    images.optJSONObject(images.length() - 1)?.optString("label").orEmpty()
                } else ""
                // iTunes returns `link` as either a single object OR an array
                // of link objects. Handle both shapes.
                val itunesHref = run {
                    val linkObj = entry.optJSONObject("link")
                    if (linkObj != null) {
                        linkObj.optJSONObject("attributes")?.optString("href").orEmpty()
                    } else {
                        val linkArr = entry.optJSONArray("link")
                        if (linkArr != null && linkArr.length() > 0) {
                            linkArr.optJSONObject(0)
                                ?.optJSONObject("attributes")
                                ?.optString("href").orEmpty()
                        } else ""
                    }
                }
                // `id` entry contains the iTunes collection ID as im:id attribute.
                // Shape: { "id": { "label": "https://podcasts.apple.com/...",
                //                   "attributes": { "im:id": "1200361736" } } }
                val collectionId = entry.optJSONObject("id")
                    ?.optJSONObject("attributes")
                    ?.optString("im:id").orEmpty()
                // The chart RSS JSON exposes a `summary` block with a label
                // containing a short description. Use it as the blurb.
                val summary = entry.optJSONObject("summary")?.optString("label").orEmpty()
                val description = firstSentenceOf(summary)
                // Genre is in `category.attributes.label`
                val genre = entry.optJSONObject("category")
                    ?.optJSONObject("attributes")
                    ?.optString("label").orEmpty()
                if (title.isNotBlank()) {
                    result.add(
                        TopPodcastEntry(
                            title = title,
                            artist = artist,
                            artworkUrl = artwork,
                            itunesUrl = itunesHref,
                            collectionId = collectionId,
                            description = description,
                            genre = genre
                        )
                    )
                }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Top podcasts fetch failed: ${e.message}")
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Build a file:///android_asset/podcasts.html URL with the top podcast
     * list encoded as a JSON payload in the ?data= query param. The HTML
     * page decodes it and renders a visual grid.
     */
    /**
     * Build the open_taplink URL that routes discovered stations + podcasts
     * into podcasts.html's kind='stations' renderer. The payload lists the
     * stations first (so the voice-list numbering matches), then podcasts.
     */
    private fun buildStationSearchHudUrl(
        stations: List<JSONObject>,
        podcasts: List<JSONObject>,
        title: String
    ): String {
        val arr = JSONArray()
        // Stations come first so indices match the voice list.
        for (s in stations) {
            val streamUrl = s.optString("url_resolved", s.optString("url", "")).trim()
            val primaryTag = s.optString("tags", "")
                .split(",").firstOrNull()?.trim().orEmpty()
            arr.put(JSONObject()
                .put("kind", "station")
                .put("title", s.optString("name", "Unknown").trim())
                .put("artist", s.optString("country", "").trim())
                .put("genre", primaryTag)
                .put("streamUrl", streamUrl)
                .put("art", s.optString("favicon", "").trim())
                .put("codec", s.optString("codec", "").trim())
                .put("bitrate", s.optInt("bitrate", 0)))
        }
        for (p in podcasts) {
            arr.put(JSONObject()
                .put("kind", "show")
                .put("title", p.optString("collectionName",
                    p.optString("trackName", "Unknown")).trim())
                .put("artist", p.optString("artistName", "").trim())
                .put("art", p.optString("artworkUrl600",
                    p.optString("artworkUrl100", "")).trim())
                .put("itunes", p.optString("collectionViewUrl", "").trim())
                .put("id", p.optLong("collectionId", 0L))
                .put("desc", "")
                .put("genre", p.optString("primaryGenreName", "").trim()))
        }
        val payload = JSONObject()
            .put("title", title)
            .put("kind", "stations")   // podcasts.html dispatches on this
            .put("items", arr)
            .toString()
        val encoded = URLEncoder.encode(payload, "UTF-8")
        return "file:///android_asset/podcasts.html?data=$encoded"
    }

    private fun buildPodcastHudUrl(podcasts: List<TopPodcastEntry>, title: String): String {
        val arr = JSONArray()
        for (p in podcasts) {
            arr.put(JSONObject()
                .put("kind", "show")
                .put("title", p.title)
                .put("artist", p.artist)
                .put("art", p.artworkUrl)
                .put("itunes", p.itunesUrl)
                .put("id", p.collectionId)
                .put("desc", p.description)
                .put("genre", p.genre))
        }
        val payload = JSONObject()
            .put("title", title)
            .put("kind", "shows")  // tells podcasts.html to use show-list view
            .put("items", arr)
            .toString()
        val encoded = URLEncoder.encode(payload, "UTF-8")
        return "file:///android_asset/podcasts.html?data=$encoded"
    }

    // ── Play ────────────────────────────────────────────────────────

    /**
     * Build the open_taplink URL that routes through TapRadio's native
     * ExoPlayer via radio.html auto-play parameters.
     */
    private fun buildNativePlayUrl(
        streamUrl: String,
        name: String,
        genre: String,
        subtitle: String = "",
        artist: String = "",
        kind: String = ""
    ): String {
        val params = mutableListOf(
            "playUrl=${URLEncoder.encode(streamUrl, "UTF-8")}",
            "playName=${URLEncoder.encode(name, "UTF-8")}",
            "playGenre=${URLEncoder.encode(genre, "UTF-8")}"
        )
        if (subtitle.isNotBlank()) {
            params += "playSubtitle=${URLEncoder.encode(subtitle, "UTF-8")}"
        }
        if (artist.isNotBlank()) {
            params += "playArtist=${URLEncoder.encode(artist, "UTF-8")}"
        }
        if (kind.isNotBlank()) {
            params += "playKind=${URLEncoder.encode(kind, "UTF-8")}"
        }
        // Always signal autoplay and include a timestamp nonce so every play
        // request is a fresh WebView load. Without the nonce, loading the same
        // podcast URL twice in a session causes WebView to short-circuit the
        // reload and ExoPlayer sits idle showing last session's metadata.
        params += "autoplay=1"
        params += "_t=${System.currentTimeMillis()}"
        return "open_taplink:file:///android_asset/radio.html?${params.joinToString("&")}"
    }

    private fun inferPlaybackKind(streamUrl: String, requestedKind: String?): String {
        val explicit = requestedKind?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (explicit != null) {
            return when {
                explicit in setOf("m3u", "m3u8", "playlist") -> "playlist"
                explicit in setOf("audio", "audio_file", "audiofile", "media", "media_file", "file") -> "audio"
                explicit in setOf("podcast", "spotify", "radio") -> explicit
                else -> explicit
            }
        }
        val cleanPath = streamUrl.substringBefore('?').substringBefore('#').lowercase()
        return when {
            cleanPath.endsWith(".m3u") || cleanPath.endsWith(".m3u8") -> "playlist"
            else -> "radio"
        }
    }

    private suspend fun playStation(query: String, metadata: PlaybackMetadata): Result<String> {
        if (query.isBlank()) return Result.success("Please specify a station name or URL to play.")

        val savedByUrl = getSavedStations().firstOrNull { station ->
            station.optString("url", "").equals(query, ignoreCase = true)
        }

        // Direct URL — route through native player
        if (query.startsWith("http://") || query.startsWith("https://")) {
            clearNowPlaying()
            val playbackKind = inferPlaybackKind(query, metadata.cleanKind())
            val stationName = metadata.cleanName()
                ?: savedByUrl?.optString("name", "")?.trim()?.takeIf { it.isNotBlank() }
                ?: when (playbackKind) {
                    "playlist" -> "M3U Playlist"
                    "audio" -> query.substringBefore('?').substringBefore('#').substringAfterLast('/').ifBlank { "Audio File" }
                    else -> "Stream"
                }
            val genre = metadata.cleanGenre()
                ?: savedByUrl?.optString("genre", "")?.trim()?.takeIf { it.isNotBlank() }
                ?: when (playbackKind) {
                    "playlist" -> "Playlist"
                    "audio" -> "Audio"
                    else -> "Mix"
                }
            val playLink = buildNativePlayUrl(
                query,
                stationName,
                genre,
                subtitle = metadata.cleanSubtitle().orEmpty(),
                artist = metadata.cleanArtist().orEmpty(),
                kind = playbackKind
            )
            val detail = buildString {
                append("Playing $stationName on TapRadio")
                if (genre.isNotBlank()) append(" ($genre)")
            }
            return Result.success("$playLink\n$detail")
        }

        // Fuzzy match against saved stations
        val stations = getSavedStations()
        val queryLower = query.lowercase()
        val match = stations.firstOrNull { station ->
            val name = station.optString("name", "").lowercase()
            val genre = station.optString("genre", "").lowercase()
            name.contains(queryLower) || queryLower.contains(name) || genre.contains(queryLower)
        }

        if (match != null) {
            val url = match.optString("url", "")
            val stationName = match.optString("name", "Unknown")
            val genre = match.optString("genre", "")
            if (url.isNotBlank()) {
                clearNowPlaying()
                val playLink = buildNativePlayUrl(
                    url,
                    stationName,
                    genre,
                    subtitle = metadata.cleanSubtitle().orEmpty(),
                    artist = metadata.cleanArtist().orEmpty(),
                    kind = metadata.cleanKind().orEmpty()
                )
                return Result.success("$playLink\nPlaying $stationName on TapRadio ($genre)")
            }
        }

        // Not found in saved — try Radio Browser search then play first result
        val searchResults = searchRadioBrowser(query, limit = 5)
        if (searchResults.isNotEmpty()) {
            // Try exact match first, then fuzzy
            val queryLowerRadio = query.lowercase()
            val exactMatch = searchResults.firstOrNull {
                it.optString("name", "").lowercase().contains(queryLowerRadio) ||
                    queryLowerRadio.contains(it.optString("name", "").lowercase())
            }
            val first = exactMatch ?: searchResults[0]
            val url = first.optString("url_resolved", first.optString("url", ""))
            val stationName = first.optString("name", "Unknown")
            val genre = first.optString("tags", "").split(",").firstOrNull()?.trim() ?: "Mix"
            if (url.isNotBlank()) {
                clearNowPlaying()
                val playLink = buildNativePlayUrl(
                    url,
                    stationName,
                    genre,
                    subtitle = metadata.cleanSubtitle().orEmpty(),
                    artist = metadata.cleanArtist().orEmpty(),
                    kind = metadata.cleanKind().orEmpty()
                )
                return Result.success("$playLink\nPlaying $stationName on TapRadio ($genre)")
            }
        }

        // Do NOT fall back to podcast search here — podcasts should only be
        // played via the explicit 'podcast' action. Falling through to iTunes
        // caused radio station selections to incorrectly play podcasts instead.
        return Result.success("No radio station found matching '$query'. Try 'search $query' to discover stations, or say 'podcast $query' to find podcasts.")
    }

    // ── Podcast ─────────────────────────────────────────────────────

    /**
     * Search iTunes for a podcast by name, parse its RSS feed to get
     * the latest episode audio URL, and play via native TapRadio player.
     */
    private suspend fun playPodcast(query: String): Result<String> {
        if (query.isBlank()) return Result.success("Please specify a podcast name to search for.")

        val result = searchAndPlayPodcast(query)
            ?: return Result.success("No podcast found matching '$query'. Try a different name.")
        return Result.success(result)
    }

    private suspend fun searchAndPlayPodcast(query: String): String? = withContext(Dispatchers.IO) {
        // 1) Search iTunes for the podcast
        val podcast = searchItunes(query) ?: return@withContext null
        val feedUrl = podcast.optString("feedUrl", "")
        val podcastName = podcast.optString("collectionName",
            podcast.optString("trackName", "Podcast"))
        val artist = podcast.optString("artistName", "")

        if (feedUrl.isBlank()) {
            return@withContext "Found '$podcastName' but no RSS feed available."
        }

        // 2) Parse RSS feed to get the latest episode audio URL
        val episode = parseRssFeedForLatestEpisode(feedUrl)
        if (episode == null) {
            return@withContext "Found '$podcastName' but could not load the latest episode. Feed: $feedUrl"
        }

        val episodeUrl = episode.first
        val episodeTitle = episode.second

        // 3) Play via native TapRadio player
        val displayName = if (episodeTitle.isNotBlank()) "$podcastName: $episodeTitle" else podcastName
        val playLink = buildNativePlayUrl(
            episodeUrl,
            podcastName,
            "Podcast",
            subtitle = episodeTitle,
            artist = artist,
            kind = "podcast"
        )
        "$playLink\nPlaying podcast: $displayName" + if (artist.isNotBlank()) " by $artist" else ""
    }

    /**
     * Search iTunes Search API for podcasts matching the query.
     * Returns the best match or null.
     */
    private fun searchItunes(query: String): JSONObject? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$ITUNES_SEARCH_URL?term=$encoded&media=podcast&entity=podcast&limit=5"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "TapInsight/1.1.2")
        return try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val results = json.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    results.getJSONObject(0)
                } else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "iTunes search failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Parse an RSS/Atom podcast feed and extract the audio URL and title
     * of the most recent episode. Uses simple XML parsing to avoid
     * pulling in a full XML library dependency.
     *
     * Returns Pair(audioUrl, episodeTitle) or null if no enclosure found.
     */
    private fun parseRssFeedForLatestEpisode(feedUrl: String): Pair<String, String>? {
        val conn = URL(feedUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "TapInsight/1.1.2")
        conn.instanceFollowRedirects = true
        return try {
            if (conn.responseCode != 200) return null
            val xml = conn.inputStream.bufferedReader().readText()

            // Find the first <item> block (most recent episode)
            val itemStart = xml.indexOf("<item>").takeIf { it >= 0 }
                ?: xml.indexOf("<item ").takeIf { it >= 0 }
                ?: return null
            val itemEnd = xml.indexOf("</item>", itemStart).takeIf { it >= 0 }
                ?: xml.length
            val item = xml.substring(itemStart, itemEnd)

            // Extract enclosure URL (the actual audio file)
            val audioUrl = extractEnclosureUrl(item) ?: return null

            // Extract episode title
            val title = extractXmlTag(item, "title") ?: ""

            Pair(audioUrl, title)
        } catch (e: Exception) {
            Log.w(TAG, "RSS feed parse failed for $feedUrl: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Extract the url attribute from an <enclosure> tag. */
    private fun extractEnclosureUrl(itemXml: String): String? {
        // Match <enclosure ... url="..." .../>
        val encIdx = itemXml.indexOf("<enclosure").takeIf { it >= 0 } ?: return null
        val encEnd = itemXml.indexOf(">", encIdx).takeIf { it >= 0 } ?: return null
        val encTag = itemXml.substring(encIdx, encEnd + 1)

        // Extract url attribute value
        val urlAttr = Regex("""url\s*=\s*["']([^"']+)["']""").find(encTag)
        return urlAttr?.groupValues?.getOrNull(1)?.trim()
    }

    /** Extract text content from the first occurrence of an XML tag. */
    private fun extractXmlTag(xml: String, tag: String): String? {
        // Handle CDATA: <title><![CDATA[Episode Title]]></title>
        val openTag = "<$tag>"
        val closeTag = "</$tag>"
        val start = xml.indexOf(openTag).takeIf { it >= 0 } ?: return null
        val end = xml.indexOf(closeTag, start).takeIf { it >= 0 } ?: return null
        var content = xml.substring(start + openTag.length, end).trim()
        // Strip CDATA wrapper if present
        if (content.startsWith("<![CDATA[")) {
            content = content.removePrefix("<![CDATA[").removeSuffix("]]>").trim()
        }
        return content.ifBlank { null }
    }

    // ── Search ──────────────────────────────────────────────────────

    private suspend fun searchStations(
        query: String,
        display: String = "voice",
        selection: String? = null,
        includePodcasts: Boolean = true
    ): Result<String> {
        if (query.isBlank()) return Result.success("Please provide a search term (e.g. genre, station name, or country).")

        // Normalize display aliases — match topPodcasts() so Gemini can use
        // the same vocabulary for both: 'hud', 'glasses', 'browser',
        // 'display', 'show', 'visual' all map to the visual (open_taplink) path.
        val normalizedDisplay = when (display.lowercase().trim()) {
            "hud", "glasses", "browser", "display", "show", "visual" -> "glasses"
            else -> "voice"
        }

        // Reuse previously-fetched search results when Gemini is only
        // asking to display the SAME query on the glasses (common flow:
        // user hears the voice list, then says "show that on my glasses").
        // This keeps the pair consistent between the voice numbering and
        // the visual cards so "#3 on the list" matches.
        val canReuseCache = normalizedDisplay == "glasses" &&
            cachedSearchQuery != null &&
            cachedSearchQuery.equals(query, ignoreCase = true) &&
            (cachedSearchStations.isNotEmpty() || cachedSearchPodcasts.isNotEmpty())

        val radioResults: List<JSONObject>
        val podcastResults: List<JSONObject>
        if (canReuseCache) {
            radioResults = cachedSearchStations
            podcastResults = if (includePodcasts) cachedSearchPodcasts else emptyList()
        } else {
            radioResults = searchRadioBrowser(query, limit = 5)
            podcastResults = if (includePodcasts) searchItunesMultiple(query, limit = 3) else emptyList()
            // Cache for the voice→glasses narrow-then-display path.
            cachedSearchStations = radioResults
            cachedSearchPodcasts = podcastResults
            cachedSearchQuery = query
        }

        if (radioResults.isEmpty() && podcastResults.isEmpty()) {
            val typeLabel = if (includePodcasts) "stations or podcasts" else "radio stations"
            return Result.success("No $typeLabel found for '$query'. Try a different search term.")
        }

        if (normalizedDisplay == "glasses") {
            // Apply user-narrowed selection (e.g. "3,5,7" → only those
            // 1-based indices from the combined voice list). Indices span
            // stations first, then podcasts, in the same order the voice
            // list was read, so "1,2" after a mixed result grabs the top
            // two stations.
            val combined = radioResults.map { "station" to it } +
                podcastResults.map { "podcast" to it }
            val indices = parseSelectionIndices(selection, combined.size)
            val picked = if (indices != null && indices.isNotEmpty()) {
                indices.mapNotNull { idx -> combined.getOrNull(idx) }
            } else {
                combined
            }
            val pickedStations = picked.filter { it.first == "station" }.map { it.second }
            val pickedPodcasts = picked.filter { it.first == "podcast" }.map { it.second }

            if (pickedStations.isEmpty() && pickedPodcasts.isEmpty()) {
                return Result.success(
                    "No stations selected to show on the glasses. Pass selection='[comma-separated indices]' or omit to show the full list."
                )
            }

            val label = if (indices != null && picked.size != combined.size) {
                "${picked.size} selected for '$query'"
            } else if (!includePodcasts) {
                "Radio stations for '$query'"
            } else {
                "Stations and podcasts for '$query'"
            }
            val glassesUrl = buildStationSearchHudUrl(pickedStations, pickedPodcasts, label)
            return Result.success(
                "open_taplink:$glassesUrl\n$label now showing on your glasses. Tap any card to play it."
            )
        }

        // VOICE path — numbered list Gemini reads aloud.
        val sb = StringBuilder()
        if (radioResults.isNotEmpty()) {
            sb.append("[RADIO STATIONS]:\n")
            for ((i, station) in radioResults.withIndex()) {
                val stationName = station.optString("name", "Unknown").take(40)
                val tags = station.optString("tags", "").take(30)
                val country = station.optString("country", "")
                val streamUrl = station.optString("url_resolved",
                    station.optString("url", ""))
                sb.append("${i + 1}. $stationName")
                if (tags.isNotBlank()) sb.append(" ($tags)")
                if (country.isNotBlank()) sb.append(" — $country")
                if (streamUrl.isNotBlank()) sb.append(" [URL: $streamUrl]")
                sb.append("\n")
            }
        }
        if (podcastResults.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("[PODCASTS]:\n")
            val offset = radioResults.size  // continue the numbering
            for ((i, podcast) in podcastResults.withIndex()) {
                val podName = podcast.optString("collectionName",
                    podcast.optString("trackName", "Unknown")).take(40)
                val artist = podcast.optString("artistName", "").take(25)
                sb.append("${offset + i + 1}. $podName")
                if (artist.isNotBlank()) sb.append(" by $artist")
                sb.append("\n")
            }
        }

        sb.append("\nTo play a RADIO STATION: call tapradio with action='play', query set to the station's stream URL from the [URL: ...] field above, and include name='[station name]' plus genre='[genre]' when available.")
        if (includePodcasts) {
            sb.append("\nTo play a PODCAST: call tapradio with action='podcast' and query='[podcast name]'.")
        }
        sb.append("\nIMPORTANT: Always use the stream URL (not the station name) when playing a radio station.\n")
        sb.append("\nAfter reading the list, offer to display it on the glasses — if the user agrees, call this tool again ")
        sb.append("with action='${if (includePodcasts) "search" else "search_stations"}', the SAME query='$query', and display='glasses'. To narrow the list first, include ")
        sb.append("selection='[1-based indices from the numbered list above, comma-separated]'. ")
        sb.append("NEVER fabricate an open_taplink URL — the only way to show this list visually is to call tapradio again with display='glasses'.")
        return Result.success(sb.toString())
    }

    /** Search iTunes for multiple podcast results (single region, single query). */
    private suspend fun searchItunesMultiple(query: String, limit: Int = 3): List<JSONObject> =
        searchItunesRegion(query, "us", limit)

    /** Search a single iTunes region with a single query. Returns raw JSON results. */
    private fun searchItunesRegion(query: String, country: String, limit: Int): List<JSONObject> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$ITUNES_SEARCH_URL?term=$encoded&media=podcast&entity=podcast" +
            "&country=$country&limit=$limit"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "TapInsight/1.1.2")
        return try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val results = json.optJSONArray("results")
                if (results != null) {
                    (0 until results.length()).map { results.getJSONObject(it) }
                } else emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "iTunes search failed for term='$query' country='$country': ${e.message}")
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    // ── Broad podcast search (multi-query, multi-region, ranked) ────
    //
    // For niche/keyword queries like "amiga 500", "history of rome",
    // "vintage synthesizers", a single iTunes search returns very few
    // results because iTunes does exact phrase matching. To improve recall:
    //
    //   1. Generate query variations (broader/synonym forms)
    //   2. Search each variation across multiple iTunes country stores
    //   3. Dedupe by collectionId
    //   4. Rank by relevance to the ORIGINAL query
    //   5. Return the top N
    //
    // No external auth needed — iTunes Search API is free and unauthenticated.
    // Using country variations dramatically expands coverage for European
    // and international shows that don't appear in the US store.

    private val ITUNES_REGIONS = listOf("us", "gb", "ca", "au", "de", "fr")

    /**
     * Generate query variations to broaden the search.
     * For "amiga 500" we want to try ["amiga 500", "amiga", "commodore amiga"].
     * For "history of rome" we want to try ["history of rome", "ancient rome", "rome history"].
     *
     * Strategy:
     *   1. Always include the original query verbatim
     *   2. If the query has multiple words, add the longest single-word version
     *      (skipping stopwords like "the", "of", "and")
     *   3. Strip trailing numbers (e.g. "amiga 500" → "amiga")
     *   4. Apply known synonym/expansion map for common topics
     */
    private fun expandQueryVariations(query: String): List<String> {
        val variations = linkedSetOf<String>()  // preserves order, dedupes
        val original = query.trim()
        if (original.isBlank()) return emptyList()
        variations.add(original)

        val lower = original.lowercase()

        // Strip trailing numbers ("amiga 500" → "amiga", "windows 95" → "windows")
        val noTrailingNum = lower.replace(Regex("\\s+\\d+\\s*$"), "").trim()
        if (noTrailingNum.isNotBlank() && noTrailingNum != lower) {
            variations.add(noTrailingNum)
        }

        // Strip stopwords and try the longest remaining word as a fallback
        val stopwords = setOf(
            "the", "of", "a", "an", "and", "or", "in", "on", "at", "to",
            "for", "with", "by", "from", "about", "podcast", "podcasts",
            "show", "shows", "the best", "top"
        )
        val words = lower
            .replace(Regex("[^\\p{L}\\p{N}\\s']"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in stopwords }
        val significantWords = words.filter { it.length > 2 }
        if (significantWords.size > 1) {
            // Add the longest single significant word
            val longest = significantWords.maxByOrNull { it.length }
            if (longest != null) variations.add(longest)
        }

        // Apply synonym/expansion map for known niche topics
        for ((key, expansions) in QUERY_EXPANSION_MAP) {
            if (lower.contains(key)) {
                for (expansion in expansions) {
                    variations.add(expansion)
                }
            }
        }

        return variations.toList().take(5)  // hard cap to keep request count reasonable
    }

    /**
     * Map of query keyword → broader/synonym terms to also search.
     * Keep this tight; over-expansion creates noise and slow searches.
     */
    private val QUERY_EXPANSION_MAP = mapOf(
        "amiga" to listOf("amiga", "commodore", "retro computing"),
        "atari" to listOf("atari", "retro computing"),
        "commodore" to listOf("commodore", "retro computing", "amiga"),
        "c64" to listOf("commodore 64", "commodore", "retro computing"),
        "zx spectrum" to listOf("zx spectrum", "retro computing", "sinclair"),
        "msx" to listOf("msx", "retro computing"),
        "vintage computer" to listOf("vintage computers", "retro computing"),
        "retro game" to listOf("retro gaming", "video game history"),
        "synth" to listOf("synthesizer", "modular synth"),
        "vinyl" to listOf("vinyl records", "record collecting"),
        "ham radio" to listOf("ham radio", "amateur radio"),
        "shortwave" to listOf("shortwave radio", "ham radio"),
        "history of rome" to listOf("history of rome", "ancient rome", "roman empire"),
        "byzantine" to listOf("byzantine", "byzantine history"),
        "medieval" to listOf("medieval history", "middle ages")
    )

    /**
     * Score how well a podcast title/description matches the original query.
     * Higher score = better match. Used to rank merged multi-region results.
     */
    private fun scoreRelevance(podcast: TopPodcastEntry, originalQuery: String): Int {
        val q = originalQuery.lowercase().trim()
        if (q.isBlank()) return 0
        val title = podcast.title.lowercase()
        val artist = podcast.artist.lowercase()
        val desc = podcast.description.lowercase()
        val queryWords = q.split(Regex("\\s+")).filter { it.length > 2 }

        var score = 0
        // Exact phrase in title is the strongest signal
        if (title.contains(q)) score += 100
        // Title contains all individual query words
        if (queryWords.isNotEmpty() && queryWords.all { title.contains(it) }) score += 60
        // Title contains any query word
        if (queryWords.any { title.contains(it) }) score += 30
        // Artist/network mentions the query
        if (artist.contains(q)) score += 20
        // Description mentions the query phrase or words
        if (desc.contains(q)) score += 25
        if (queryWords.isNotEmpty() && queryWords.all { desc.contains(it) }) score += 15
        // Slight preference for podcasts with descriptions (they're usually more legit)
        if (podcast.description.isNotBlank()) score += 5
        // Prefer the user's own region (US default) when scores are tied
        if (podcast.country == "us") score += 2
        return score
    }

    /**
     * Broad multi-query, multi-region iTunes podcast search.
     * Generates query variations, fans out across iTunes country stores,
     * dedupes by collectionId, ranks by relevance, and returns the top N.
     *
     * @param query the user's original query (e.g. "amiga 500")
     * @param limit max podcasts to return after ranking (default 10)
     */
    private suspend fun searchPodcastsBroad(
        query: String,
        limit: Int = 10
    ): List<TopPodcastEntry> = withContext(Dispatchers.IO) {
        val variations = expandQueryVariations(query)
        if (variations.isEmpty()) return@withContext emptyList()

        Log.d(TAG, "Broad search for '$query' → variations=$variations, regions=$ITUNES_REGIONS")

        // Run all (variation × region) combinations in parallel.
        val all = coroutineScope {
            val deferred = mutableListOf<Deferred<List<TopPodcastEntry>>>()
            for (variation in variations) {
                for (region in ITUNES_REGIONS) {
                    deferred.add(async(Dispatchers.IO) {
                        val rawResults = searchItunesRegion(variation, region, limit = 25)
                        rawResults.mapNotNull { it.toTopPodcastEntry(region) }
                    })
                }
            }
            deferred.flatMap { it.await() }
        }
        Log.d(TAG, "Broad search collected ${all.size} raw results before dedupe")

        // Dedupe by collectionId, keeping the first occurrence (which has US/GB priority via list order)
        val seen = mutableSetOf<String>()
        val deduped = all.filter { entry ->
            val key = entry.collectionId.ifBlank { "${entry.title}|${entry.artist}" }
            seen.add(key)
        }
        Log.d(TAG, "Broad search has ${deduped.size} unique results after dedupe")

        // Score and sort by relevance to the original query
        val ranked = deduped
            .map { entry -> entry to scoreRelevance(entry, query) }
            .filter { (_, score) -> score > 0 }  // drop completely irrelevant matches
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (entry, _) -> entry }

        Log.d(TAG, "Broad search final ranked count: ${ranked.size}")
        ranked
    }

    // ── Episode search (multi-query, multi-region, ranked) ──────────
    //
    // For topic queries the user usually wants individual EPISODES that
    // talk about the topic, not just shows whose title matches. iTunes
    // exposes this via entity=podcastEpisode. Uses the same multi-query,
    // multi-region, dedupe-then-rank approach as searchPodcastsBroad.

    /** Single-region episode search returning raw iTunes JSON results. */
    private fun searchEpisodesRegion(query: String, country: String, limit: Int): List<JSONObject> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$ITUNES_SEARCH_URL?term=$encoded&media=podcast&entity=podcastEpisode" +
            "&country=$country&limit=$limit"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "TapInsight/1.1.2")
        return try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val results = json.optJSONArray("results")
                if (results != null) {
                    (0 until results.length()).map { results.getJSONObject(it) }
                } else emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "iTunes episode search failed for term='$query' country='$country': ${e.message}")
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /** Convert an iTunes episode result JSON into a TopEpisodeEntry. */
    private fun JSONObject.toTopEpisodeEntry(countryCode: String = "us"): TopEpisodeEntry? {
        // iTunes returns episode results with kind="podcast-episode".
        // The trackName field is the episode title; collectionName is the show.
        val episodeTitle = optString("trackName").trim()
        if (episodeTitle.isBlank()) return null
        val showTitle = optString("collectionName").trim()
        val artist = optString("artistName").trim()
        val artwork = optString("artworkUrl600").trim().ifBlank {
            optString("artworkUrl160").trim().ifBlank {
                optString("artworkUrl100").trim()
            }
        }
        // Direct audio URL — iTunes returns this in episodeUrl OR previewUrl
        val audioUrl = optString("episodeUrl").trim().ifBlank {
            optString("previewUrl").trim()
        }
        if (audioUrl.isBlank()) return null  // no point listing un-playable episodes
        val description = firstSentenceOf(
            optString("description").trim().ifBlank {
                optString("shortDescription").trim()
            }
        )
        val collectionId = optLong("collectionId", 0L).takeIf { it > 0 }?.toString().orEmpty()
        val trackId = optLong("trackId", 0L).takeIf { it > 0 }?.toString().orEmpty()
        val releaseDate = optString("releaseDate").trim()
        val durationMs = optLong("trackTimeMillis", 0L)
        val feedUrl = optString("feedUrl").trim()
        val genre = optString("primaryGenreName").trim()
        return TopEpisodeEntry(
            episodeTitle = episodeTitle,
            showTitle = showTitle,
            artist = artist,
            artworkUrl = artwork,
            episodeAudioUrl = audioUrl,
            description = description,
            collectionId = collectionId,
            trackId = trackId,
            releaseDate = releaseDate,
            durationMs = durationMs,
            feedUrl = feedUrl,
            genre = genre,
            country = countryCode
        )
    }

    /** Score how well an episode matches the original query. */
    private fun scoreEpisodeRelevance(ep: TopEpisodeEntry, originalQuery: String): Int {
        val q = originalQuery.lowercase().trim()
        if (q.isBlank()) return 0
        val title = ep.episodeTitle.lowercase()
        val show = ep.showTitle.lowercase()
        val artist = ep.artist.lowercase()
        val desc = ep.description.lowercase()
        val queryWords = q.split(Regex("\\s+")).filter { it.length > 2 }

        var score = 0
        // Episode title is the strongest signal — episodes specifically about the topic
        if (title.contains(q)) score += 120
        if (queryWords.isNotEmpty() && queryWords.all { title.contains(it) }) score += 70
        if (queryWords.any { title.contains(it) }) score += 40
        // Show title is a secondary signal (whole show is about the topic)
        if (show.contains(q)) score += 50
        if (queryWords.isNotEmpty() && queryWords.all { show.contains(it) }) score += 25
        // Description matches
        if (desc.contains(q)) score += 30
        if (queryWords.isNotEmpty() && queryWords.all { desc.contains(it) }) score += 20
        if (queryWords.any { desc.contains(it) }) score += 10
        // Artist mentions
        if (artist.contains(q)) score += 15
        // Slight preference for episodes that have descriptions and US region
        if (ep.description.isNotBlank()) score += 5
        if (ep.country == "us") score += 2
        return score
    }

    /**
     * Broad multi-query, multi-region episode search.
     * Returns ranked episodes most relevant to the original query.
     */
    private suspend fun searchEpisodesBroad(
        query: String,
        limit: Int = 10
    ): List<TopEpisodeEntry> = withContext(Dispatchers.IO) {
        val variations = expandQueryVariations(query)
        if (variations.isEmpty()) return@withContext emptyList()

        Log.d(TAG, "Broad episode search for '$query' → variations=$variations, regions=$ITUNES_REGIONS")

        val all = coroutineScope {
            val deferred = mutableListOf<Deferred<List<TopEpisodeEntry>>>()
            for (variation in variations) {
                for (region in ITUNES_REGIONS) {
                    deferred.add(async(Dispatchers.IO) {
                        val rawResults = searchEpisodesRegion(variation, region, limit = 25)
                        rawResults.mapNotNull { it.toTopEpisodeEntry(region) }
                    })
                }
            }
            deferred.flatMap { it.await() }
        }
        Log.d(TAG, "Broad episode search collected ${all.size} raw results before dedupe")

        // Dedupe by trackId (or fallback to title|show key)
        val seen = mutableSetOf<String>()
        val deduped = all.filter { ep ->
            val key = ep.trackId.ifBlank { "${ep.episodeTitle}|${ep.showTitle}" }
            seen.add(key)
        }
        Log.d(TAG, "Broad episode search has ${deduped.size} unique results after dedupe")

        // Score and sort by relevance
        val ranked = deduped
            .map { ep -> ep to scoreEpisodeRelevance(ep, query) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (ep, _) -> ep }

        Log.d(TAG, "Broad episode search final ranked count: ${ranked.size}")
        ranked
    }

    /** Build the HUD URL payload for an episode-search result list. */
    private fun buildEpisodeSearchHudUrl(episodes: List<TopEpisodeEntry>, title: String): String {
        val arr = JSONArray()
        for (ep in episodes) {
            arr.put(JSONObject()
                .put("kind", "episode")
                .put("episodeTitle", ep.episodeTitle)
                .put("showTitle", ep.showTitle)
                .put("artist", ep.artist)
                .put("art", ep.artworkUrl)
                .put("audioUrl", ep.episodeAudioUrl)
                .put("desc", ep.description)
                .put("collectionId", ep.collectionId)
                .put("trackId", ep.trackId)
                .put("releaseDate", ep.releaseDate)
                .put("durationMs", ep.durationMs)
                .put("feedUrl", ep.feedUrl)
                .put("genre", ep.genre))
        }
        val payload = JSONObject()
            .put("title", title)
            .put("kind", "episode_search")  // tells podcasts.html to use episode-list view
            .put("items", arr)
            .toString()
        val encoded = URLEncoder.encode(payload, "UTF-8")
        return "file:///android_asset/podcasts.html?data=$encoded"
    }

    // ── List / Stop / Add ───────────────────────────────────────────

    private fun listStations(): Result<String> {
        val stations = getSavedStations()
        if (stations.isEmpty()) {
            return Result.success("No saved radio stations. Add stations in the TapRadio companion app, or say 'search [genre]' to discover new ones.")
        }

        val sb = StringBuilder("Saved stations (${stations.size}):\n")
        for (i in 0 until stations.size) {
            val s = stations[i]
            val stationName = s.optString("name", "Unknown")
            val genre = s.optString("genre", "")
            val fav = s.optBoolean("fav", false)
            sb.append(if (fav) "★ " else "• ")
            sb.append(stationName)
            if (genre.isNotBlank()) sb.append(" ($genre)")
            sb.append("\n")
        }
        sb.append("\nSay 'play [station name]' to start listening.")
        sb.append(
            "\n\nGEMINI: If the user asks for more info / background / 'tell me about' / " +
                "'what is' any station in this list, call tapradio AGAIN with " +
                "action='info_station' and query=[the station name]. Then follow that " +
                "tool's ENRICHMENT DIRECTIVE — augment the local metadata with Google " +
                "Search grounding before speaking. Never read the raw favorites list " +
                "back as 'more info'."
        )
        return Result.success(sb.toString())
    }

    private fun stopPlayback(): Result<String> {
        clearNowPlaying()
        return Result.success("TapRadio stopped.")
    }

    private fun addStation(url: String, stationName: String, genre: String): Result<String> {
        if (url.isBlank()) return Result.success("Please provide a stream URL to add.")
        val stations = getSavedStationsMutable()
        for (i in 0 until stations.length()) {
            val existing = stations.optJSONObject(i) ?: continue
            if (existing.optString("url", "").equals(url, ignoreCase = true)) {
                val existingName = existing.optString("name", stationName.ifBlank { url })
                return Result.success("'$existingName' is already saved in TapRadio.")
            }
        }
        val newStation = JSONObject()
            .put("name", stationName.ifBlank { "New Station" })
            .put("url", url)
            .put("genre", genre.ifBlank { "Other" })
            .put("desc", "")
            .put("fav", false)
        stations.put(newStation)
        prefs.edit().putString(RADIO_PREFS_KEY, stations.toString()).apply()
        return Result.success("Added '${stationName.ifBlank { url }}' to TapRadio.")
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun setNowPlaying(stationName: String, genre: String) {
        prefs.edit()
            .putBoolean("tapradio_now_playing_active", true)
            .putString("tapradio_now_playing_name", stationName)
            .putString("tapradio_now_playing_genre", genre)
            .apply()
    }

    /** Clear radio HUD state — used when playback is delegated to browser. */
    private fun clearNowPlaying() {
        prefs.edit()
            .putBoolean("tapradio_now_playing_active", false)
            .remove("tapradio_now_playing_name")
            .remove("tapradio_now_playing_genre")
            .remove("tapradio_now_playing_kind")
            .remove("tapradio_now_playing_url")
            .remove("tapradio_now_playing_position_ms")
            .remove("tapradio_now_playing_duration_ms")
            .remove("tapradio_now_playing_error")
            .apply()
    }

    private fun getSavedStations(): List<JSONObject> {
        val raw = prefs.getString(RADIO_PREFS_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse saved stations: ${e.message}")
            emptyList()
        }
    }

    private fun getSavedStationsMutable(): JSONArray {
        val raw = prefs.getString(RADIO_PREFS_KEY, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    /**
     * Search Radio Browser using multiple strategies:
     *  1. Advanced search (name + tag combined) — broadest match
     *  2. By-name fallback — exact name substring
     *  3. By-tag fallback — matches genre/tag keywords
     * Deduplicates by station name and returns the union.
     */
    private suspend fun searchRadioBrowser(query: String, limit: Int = 5): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val seen = mutableSetOf<String>()
            val results = mutableListOf<JSONObject>()

            for (server in RADIO_BROWSER_SERVERS) {
                try {
                    // 1) Advanced search — matches name OR tag, ordered by votes
                    val advUrl = "$server/json/stations/search?name=$encoded&tag=$encoded" +
                        "&limit=$limit&order=votes&reverse=true"
                    val advResults = fetchStations(advUrl)
                    for (s in advResults) {
                        val key = s.optString("name", "").lowercase()
                        if (key.isNotBlank() && seen.add(key)) results.add(s)
                    }

                    // 2) By-name — in case advanced search missed substring matches
                    if (results.size < limit) {
                        val nameUrl = "$server/json/stations/byname/$encoded" +
                            "?limit=$limit&order=votes&reverse=true"
                        for (s in fetchStations(nameUrl)) {
                            val key = s.optString("name", "").lowercase()
                            if (key.isNotBlank() && seen.add(key)) results.add(s)
                        }
                    }

                    // 3) By-tag — catches genre searches like "jazz", "news", "comedy"
                    if (results.size < limit) {
                        val tagUrl = "$server/json/stations/bytag/$encoded" +
                            "?limit=$limit&order=votes&reverse=true"
                        for (s in fetchStations(tagUrl)) {
                            val key = s.optString("name", "").lowercase()
                            if (key.isNotBlank() && seen.add(key)) results.add(s)
                        }
                    }

                    if (results.isNotEmpty()) {
                        return@withContext results.take(limit)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Radio Browser search failed on $server: ${e.message}")
                }
            }
            emptyList()
        }

    /** Fetch station list from a single Radio Browser URL. */
    private fun fetchStations(urlStr: String): List<JSONObject> {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "TapInsight/1.1.2")
        return try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(body)
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } else emptyList()
        } finally {
            conn.disconnect()
        }
    }
}
