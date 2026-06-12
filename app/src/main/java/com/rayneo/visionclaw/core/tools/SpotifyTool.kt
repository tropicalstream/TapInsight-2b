package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.rayneo.visionclaw.core.network.ActiveNetworkHttp
import com.rayneo.visionclaw.core.storage.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Spotify tool — wires the client_id / client_secret entered in the
 * TapInsight companion app through to a Gemini-callable tool on the glasses,
 * and routes playback through the built-in TapRadio media player (the
 * radio.html page in the TapBrowser module, which has the media toolbar,
 * persistent playback state across navigation, and FF/rewind buttons).
 *
 * Capabilities with just (client_id, client_secret):
 *  - Obtain an app-level access token via the Client Credentials OAuth grant.
 *  - Call the Spotify Web API /search endpoint for tracks matching a query
 *    and build a multi-track queue of preview clips the user can play.
 *  - Hand the queue off to radio.html via the open_taplink URL scheme with
 *    playKind=spotify, so the TapRadio media toolbar drives playback and
 *    the FF/Rewind buttons step through queue entries.
 *
 * Full-track streaming (as opposed to 30s preview clips) requires a user-
 * authorized OAuth token with the "streaming" scope, which is NOT what the
 * Client Credentials grant produces. Tracks that Spotify doesn't ship a
 * preview_url for cannot be streamed from within TapInsight. Those are
 * filtered out of the queue up front; if the whole query returns no
 * preview-able tracks we tell the user clearly rather than failing silently.
 *
 * Actions that REQUIRE a user-authorized OAuth token (not yet wired via the
 * companion app) — save/library operations — return an actionable message.
 */
class SpotifyTool(private val context: Context) : AiTapTool {
    override val name = "spotify_player"

    private val prefs: AppPreferences by lazy { AppPreferences(context) }

    // Cached Client Credentials token. Spotify issues them with a ~1h TTL;
    // we refresh a little early to avoid edge-case 401s on slow networks.
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedTokenExpiryElapsedMs: Long = 0L

    override suspend fun execute(args: Map<String, String>): Result<String> = withContext(Dispatchers.IO) {
        val action = args["action"]?.trim().orEmpty().lowercase()
        val query = args["query"]?.trim().orEmpty()

        Log.d(TAG, "execute action=\"$action\" query=\"$query\"")

        val clientId = prefs.spotifyClientId.trim()
        val clientSecret = prefs.spotifyClientSecret.trim()
        if (clientId.isEmpty()) {
            return@withContext Result.success(
                "Spotify isn't configured yet. Open the TapInsight companion app, " +
                    "scroll to the Spotify step, and paste your Client ID and " +
                    "Client Secret from developer.spotify.com. After saving, " +
                    "click Connect Spotify to log in so we can play full tracks."
            )
        }

        // Try to make sure we have a fresh user-OAuth access token for
        // Premium full-track playback. Falls back silently to Client
        // Credentials + preview clips if the user hasn't connected.
        val userToken = getOrRefreshUserAccessToken()

        when (action) {
            "", "play" -> playFromQuery(
                userToken = userToken,
                clientId = clientId,
                clientSecret = clientSecret,
                query = query
            )
            "search" -> searchOnly(
                userToken = userToken,
                clientId = clientId,
                clientSecret = clientSecret,
                query = query
            )
            "pause" -> controlPlayback(userToken, "pause")
            "resume" -> controlPlayback(userToken, "play")
            "next" -> controlPlayback(userToken, "next")
            "previous" -> controlPlayback(userToken, "previous")
            "save" -> saveCurrentToLibrary(userToken)
            "current", "now_playing", "nowplaying", "whats_playing", "current_track" ->
                getCurrentTrack(userToken)
            else -> Result.success(
                "Spotify action \"$action\" isn't supported. Supported actions: " +
                    "play, pause, resume, next, previous, save, search, current."
            )
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private fun playFromQuery(
        userToken: String?,
        clientId: String,
        clientSecret: String,
        query: String
    ): Result<String> {
        if (query.isBlank()) {
            return Result.success(
                "What should I play on Spotify? Tell me a song, artist, or " +
                    "album name."
            )
        }

        // Prefer the user-OAuth path when we have a valid access token — this
        // unlocks full-track playback through the Web Playback SDK embedded in
        // spotify.html. Free-tier users still get routed here; the SDK itself
        // blocks playback for non-Premium accounts, at which point we fall
        // back to preview clips below.
        val tokenForSearch = userToken ?: fetchAppToken(clientId, clientSecret)
            ?: return Result.success(
                "Couldn't authenticate with Spotify. Double-check the Client " +
                    "ID and Client Secret in the companion app, then re-connect."
            )

        val hits = searchTracks(tokenForSearch, query, SEARCH_LIMIT)
        if (hits.isEmpty()) {
            return Result.success("No Spotify tracks matched \"$query\".")
        }

        val userProduct = prefs.spotifyUserProduct.trim().lowercase()
        val premiumUser = userToken != null && userProduct == "premium"

        if (premiumUser) {
            // Route through spotify.html (Web Playback SDK) for full-track
            // streaming. The queue param carries Spotify URIs that the SDK
            // plays via Connect-device transfer; FF/Rewind on the media
            // toolbar walk through the queue. Auto-play on launch — the
            // device dropdown defaults to the last device the user picked
            // (typically their phone), so the request "play X on spotify"
            // starts playing X immediately without a manual press.
            val playLink = buildSpotifySdkTapLink(
                queue = hits,
                startIndex = 0,
                accessToken = userToken!!,
                expiryMs = prefs.spotifyAccessTokenExpiryMs,
                startPaused = false
            )
            val lead = hits.first()
            val artistLine = lead.artist?.let { " by $it" } ?: ""
            val queueTail = if (hits.size > 1) {
                " (queued ${hits.size - 1} more track${if (hits.size - 1 == 1) "" else "s"})"
            } else ""
            return Result.success(
                "$playLink\nPlaying \"${lead.title}\"$artistLine on Spotify (full track)$queueTail."
            )
        }

        // Preview-clip fallback: Client Credentials OR a non-Premium user
        // session. Spotify may return hits with preview_url=null, so filter.
        val playable = hits.filter { !it.previewUrl.isNullOrBlank() }
        if (playable.isEmpty()) {
            val fallbackLead = hits.first()
            val fallbackArtist = fallbackLead.artist?.let { " by $it" } ?: ""
            val upsell = if (userToken == null) {
                " Connect your Spotify Premium account in the companion app to " +
                    "unlock full-track playback."
            } else if (userProduct != "premium") {
                " Spotify Premium is required for full-track streaming from TapInsight."
            } else ""
            return Result.success(
                "I found \"${fallbackLead.title}\"$fallbackArtist on Spotify, " +
                    "but Spotify didn't provide a preview clip for that track.$upsell"
            )
        }

        val playLink = buildOpenTapLink(playable, startIndex = 0)
        val lead = playable.first()
        val artistLine = lead.artist?.let { " by $it" } ?: ""
        val queueTail = if (playable.size > 1) {
            " (queued ${playable.size - 1} more track${if (playable.size - 1 == 1) "" else "s"})"
        } else ""
        val previewNote = if (userToken == null) {
            " These are 30-second previews — connect your Spotify Premium " +
                "account in the companion app to stream full tracks."
        } else ""
        return Result.success(
            "$playLink\nPlaying \"${lead.title}\"$artistLine on TapRadio$queueTail.$previewNote"
        )
    }

    private fun searchOnly(
        userToken: String?,
        clientId: String,
        clientSecret: String,
        query: String
    ): Result<String> {
        if (query.isBlank()) {
            return Result.success("What should I search for on Spotify?")
        }
        val token = userToken ?: fetchAppToken(clientId, clientSecret)
            ?: return Result.success(
                "Couldn't authenticate with Spotify. Double-check the Client " +
                    "ID and Client Secret in the companion app."
            )
        val hits = searchTracks(token, query, 1)
        val top = hits.firstOrNull()
            ?: return Result.success("No Spotify tracks matched \"$query\".")
        val artistLine = top.artist?.let { " by $it" } ?: ""
        return Result.success("Top Spotify match: \"${top.title}\"$artistLine.")
    }

    /** POST a playback-control command to the user's active Spotify device. */
    private fun controlPlayback(userToken: String?, command: String): Result<String> {
        if (userToken.isNullOrBlank()) {
            return Result.success(
                "Connect your Spotify account in the companion app first — open " +
                    "the Spotify step and click Connect Spotify."
            )
        }
        val (method, path) = when (command) {
            "play" -> "PUT" to "/v1/me/player/play"
            "pause" -> "PUT" to "/v1/me/player/pause"
            "next" -> "POST" to "/v1/me/player/next"
            "previous" -> "POST" to "/v1/me/player/previous"
            else -> return Result.success("Unknown Spotify command \"$command\".")
        }
        return try {
            val emptyBody = "".toRequestBody("application/json; charset=utf-8".toMediaType())
            val requestBuilder = Request.Builder()
                .url("https://api.spotify.com$path")
                .header("Authorization", "Bearer $userToken")
                .header("Content-Type", "application/json")
            if (method == "PUT") requestBuilder.put(emptyBody) else requestBuilder.post(emptyBody)
            val response = ActiveNetworkHttp.execute(
                requestBuilder.build(),
                connectTimeoutMs = 8_000,
                readTimeoutMs = 8_000
            )
            when (response.code) {
                in 200..299 -> Result.success(
                    when (command) {
                        "play" -> "Resuming Spotify."
                        "pause" -> "Paused Spotify."
                        "next" -> "Skipped to next track."
                        "previous" -> "Went back to the previous track."
                        else -> "Sent $command to Spotify."
                    }
                )
                404 -> Result.success(
                    "No active Spotify device found. Start playback first (say " +
                        "\"play [song] on Spotify\") and try again."
                )
                403 -> Result.success(
                    "Spotify refused that command. Full playback control requires " +
                        "a Premium account."
                )
                else -> Result.success(
                    "Spotify $command failed (HTTP ${response.code}). Try again."
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify control $command error", e)
            Result.success("Couldn't reach Spotify to $command.")
        }
    }

    /** Save the user's currently-playing track to Liked Songs. */
    private fun saveCurrentToLibrary(userToken: String?): Result<String> {
        if (userToken.isNullOrBlank()) {
            return Result.success(
                "Connect your Spotify account in the companion app first, then " +
                    "try saving again."
            )
        }
        return try {
            val nowPlaying = ActiveNetworkHttp.get(
                url = "https://api.spotify.com/v1/me/player/currently-playing",
                headers = mapOf("Authorization" to "Bearer $userToken"),
                connectTimeoutMs = 8_000,
                readTimeoutMs = 8_000
            )
            if (nowPlaying.code == 204 || nowPlaying.body.isBlank()) {
                return Result.success("Nothing is playing on Spotify right now.")
            }
            if (nowPlaying.code !in 200..299) {
                return Result.success("Couldn't fetch the current Spotify track (HTTP ${nowPlaying.code}).")
            }
            val item = JSONObject(nowPlaying.body).optJSONObject("item")
                ?: return Result.success("Nothing is playing on Spotify right now.")
            val id = item.optString("id").takeIf { it.isNotBlank() }
                ?: return Result.success("No track ID available for the current Spotify item.")
            val title = item.optString("name", "this track")
            val emptyBody = "".toRequestBody("application/json; charset=utf-8".toMediaType())
            val putReq = Request.Builder()
                .url("https://api.spotify.com/v1/me/tracks?ids=$id")
                .header("Authorization", "Bearer $userToken")
                .header("Content-Type", "application/json")
                .put(emptyBody)
                .build()
            val put = ActiveNetworkHttp.execute(
                putReq,
                connectTimeoutMs = 8_000,
                readTimeoutMs = 8_000
            )
            if (put.code in 200..299) {
                Result.success("Saved \"$title\" to your Spotify Liked Songs.")
            } else {
                Result.success("Couldn't save that track (HTTP ${put.code}).")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify save-to-library error", e)
            Result.success("Couldn't save to Spotify Liked Songs.")
        }
    }

    /**
     * Report what Spotify is currently playing on the user's active Connect
     * device (phone, desktop, speaker, or our Web Playback SDK session).
     *
     * Notes on account tier:
     *   • /v1/me/player/currently-playing works for Free AND Premium
     *     accounts — it only reports state, it does not initiate playback.
     *     Historically this function returned confusing "Premium required"
     *     errors, because no dedicated action existed and Gemini would
     *     fabricate a refusal from unrelated tool description text.  This
     *     action exists so "what is playing" never misroutes into a
     *     playback-control code-path that does require Premium.
     *   • On 204 (nothing active) we surface a clean, human-readable message
     *     rather than a raw HTTP code.  403 typically means the access token
     *     is missing the user-read-playback-state scope — actionable hint
     *     provided.
     */
    private fun getCurrentTrack(userToken: String?): Result<String> {
        if (userToken.isNullOrBlank()) {
            return Result.success(
                "Connect your Spotify account in the companion app first so I " +
                    "can see what's playing — open the Spotify step and click " +
                    "Connect Spotify."
            )
        }
        return try {
            val resp = ActiveNetworkHttp.get(
                url = "https://api.spotify.com/v1/me/player/currently-playing?additional_types=track,episode",
                headers = mapOf("Authorization" to "Bearer $userToken"),
                connectTimeoutMs = 8_000,
                readTimeoutMs = 8_000
            )
            if (resp.code == 204 || resp.body.isBlank()) {
                Result.success(
                    "Nothing is playing on Spotify right now. Start a track on " +
                        "your phone or say \"play <song> on Spotify\" first."
                )
            } else if (resp.code == 401) {
                Result.success(
                    "Spotify access token is stale. Open the companion app and " +
                        "click Refresh status in the Spotify section."
                )
            } else if (resp.code == 403) {
                Result.success(
                    "Spotify refused the now-playing request. The connected " +
                        "account is missing the user-read-playback-state scope — " +
                        "re-run Connect Spotify in the companion app to " +
                        "re-authorize with the current scope set."
                )
            } else if (resp.code !in 200..299) {
                Result.success(
                    "Couldn't read the current Spotify track (HTTP ${resp.code})."
                )
            } else {
                val root = JSONObject(resp.body)
                val isPlaying = root.optBoolean("is_playing", false)
                val item = root.optJSONObject("item")
                if (item == null) {
                    Result.success("Spotify isn't currently focused on a track.")
                } else {
                    val type = item.optString("type", "track")
                    val title = item.optString("name", "").trim()
                    val artist = if (type == "episode") {
                        item.optJSONObject("show")?.optString("name")?.trim().orEmpty()
                    } else {
                        val artists = item.optJSONArray("artists") ?: JSONArray()
                        val names = mutableListOf<String>()
                        for (i in 0 until artists.length()) {
                            val n = artists.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                            if (n.isNotEmpty()) names.add(n)
                        }
                        names.joinToString(", ")
                    }
                    val album = item.optJSONObject("album")?.optString("name")?.trim().orEmpty()
                    val device = root.optJSONObject("device")
                    val deviceName = device?.optString("name")?.trim().orEmpty()
                    val deviceType = device?.optString("type")?.trim().orEmpty()
                    val verb = if (isPlaying) "Playing" else "Paused on"
                    val byClause = if (artist.isNotBlank()) " by $artist" else ""
                    val fromClause = if (album.isNotBlank() && type == "track") " (from $album)" else ""
                    val onClause = when {
                        deviceName.isNotBlank() && deviceType.isNotBlank() ->
                            " on $deviceName ($deviceType)"
                        deviceName.isNotBlank() -> " on $deviceName"
                        else -> ""
                    }
                    val titleSafe = if (title.isBlank()) "(untitled)" else title
                    Result.success("$verb \"$titleSafe\"$byClause$fromClause$onClause.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify currently-playing error", e)
            Result.success("Couldn't reach Spotify to check what's playing.")
        }
    }

    // ── OAuth: user-authorized (Authorization Code + PKCE) access ─────────

    /**
     * Returns a non-expired user-OAuth access token, refreshing via the stored
     * refresh_token when necessary. Returns null if the user hasn't connected
     * their Spotify account yet or if refresh fails.
     */
    private fun getOrRefreshUserAccessToken(): String? {
        val existing = prefs.spotifyAccessToken.trim()
        val expiryMs = prefs.spotifyAccessTokenExpiryMs
        val now = System.currentTimeMillis()
        if (existing.isNotBlank() && now < expiryMs) return existing

        val refresh = prefs.spotifyRefreshToken.trim()
        if (refresh.isBlank()) return null
        val clientId = prefs.spotifyClientId.trim()
        if (clientId.isBlank()) return null

        return try {
            val form = buildString {
                append("grant_type=refresh_token")
                append("&refresh_token=").append(URLEncoder.encode(refresh, "UTF-8"))
                append("&client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
            }
            val headers = mutableMapOf(
                "Content-Type" to "application/x-www-form-urlencoded"
            )
            val clientSecret = prefs.spotifyClientSecret.trim()
            if (clientSecret.isNotBlank()) {
                headers["Authorization"] = "Basic " + Base64.encodeToString(
                    "$clientId:$clientSecret".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
            }
            val response = ActiveNetworkHttp.postForm(
                url = "https://accounts.spotify.com/api/token",
                encodedFormBody = form,
                headers = headers,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000
            )
            if (response.code !in 200..299) {
                Log.w(TAG, "Spotify user refresh failed: HTTP ${response.code} body=${response.body.take(200)}")
                return null
            }
            val json = JSONObject(response.body)
            val newAccess = json.optString("access_token").trim()
            val expiresIn = json.optLong("expires_in", 3_600L)
            val newRefresh = json.optString("refresh_token").trim()
            if (newAccess.isEmpty()) return null
            prefs.spotifyAccessToken = newAccess
            prefs.spotifyAccessTokenExpiryMs = System.currentTimeMillis() + (expiresIn - 60L) * 1000L
            if (newRefresh.isNotEmpty()) prefs.spotifyRefreshToken = newRefresh
            newAccess
        } catch (e: Exception) {
            Log.w(TAG, "Spotify user refresh exception", e)
            null
        }
    }

    // ── OAuth: Client Credentials grant ───────────────────────────────────

    /**
     * Returns a valid app-level Spotify access token, refreshing when needed.
     * Null on failure (network, bad creds, quota). Token scope: search/metadata
     * only — NOT user playback.
     */
    private fun fetchAppToken(clientId: String, clientSecret: String): String? {
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            val cached = cachedToken
            if (!cached.isNullOrEmpty() && now < cachedTokenExpiryElapsedMs) {
                return cached
            }
        }

        val authHeader = "Basic " + Base64.encodeToString(
            "$clientId:$clientSecret".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        return try {
            val response = ActiveNetworkHttp.postForm(
                url = "https://accounts.spotify.com/api/token",
                encodedFormBody = "grant_type=client_credentials",
                headers = mapOf(
                    "Authorization" to authHeader,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000
            )
            if (response.code !in 200..299) {
                Log.w(TAG, "Spotify token request failed: HTTP ${response.code} body=${response.body.take(200)}")
                return null
            }
            val json = JSONObject(response.body)
            val token = json.optString("access_token").takeIf { it.isNotEmpty() }
                ?: return null
            val expiresIn = json.optInt("expires_in", 3_600)
            synchronized(this) {
                cachedToken = token
                // Refresh 60 s before the server-declared expiry.
                cachedTokenExpiryElapsedMs =
                    SystemClock.elapsedRealtime() + (expiresIn - 60).coerceAtLeast(30) * 1_000L
            }
            token
        } catch (e: Exception) {
            Log.w(TAG, "Spotify token request error", e)
            null
        }
    }

    // ── Search ────────────────────────────────────────────────────────────

    private data class SpotifyTrackHit(
        val trackUri: String,      // e.g. spotify:track:4iV5W9uYEdYUVa79Axb7Rh
        val openUrl: String,       // e.g. https://open.spotify.com/track/...
        val previewUrl: String?,   // 30s MP3 preview — null if Spotify didn't provide one
        val title: String,
        val artist: String?,
        val album: String?,
        val albumArtUrl: String?,
        val durationMs: Int
    )

    private fun searchTracks(token: String, query: String, limit: Int): List<SpotifyTrackHit> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val capped = limit.coerceIn(1, 50)
            val response = ActiveNetworkHttp.get(
                url = "https://api.spotify.com/v1/search?q=$encoded&type=track&limit=$capped",
                headers = mapOf("Authorization" to "Bearer $token"),
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000
            )
            if (response.code == 401) {
                // Token likely expired under concurrency — invalidate cache and
                // let the next call re-auth. Return empty for this attempt.
                synchronized(this) { cachedToken = null }
                Log.w(TAG, "Spotify search got 401 — clearing cached token")
                return emptyList()
            }
            if (response.code !in 200..299) {
                Log.w(TAG, "Spotify search failed: HTTP ${response.code} body=${response.body.take(200)}")
                return emptyList()
            }
            val tracks = JSONObject(response.body)
                .optJSONObject("tracks")
                ?.optJSONArray("items")
                ?: return emptyList()
            (0 until tracks.length()).mapNotNull { i ->
                val item = tracks.optJSONObject(i) ?: return@mapNotNull null
                val trackUri = item.optString("uri").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val openUrl = item.optJSONObject("external_urls")?.optString("spotify").orEmpty()
                val title = item.optString("name", "unknown track")
                val previewUrl = item.optString("preview_url")
                    .takeIf { it.isNotEmpty() && it != "null" }
                val durationMs = item.optInt("duration_ms", 0)
                val artistArr = item.optJSONArray("artists")
                val artist = artistArr?.let { arr ->
                    if (arr.length() == 0) null
                    else (0 until arr.length())
                        .mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotEmpty() } }
                        .joinToString(", ")
                        .takeIf { it.isNotEmpty() }
                }
                val albumObj = item.optJSONObject("album")
                val album = albumObj?.optString("name")?.takeIf { it.isNotEmpty() }
                val albumArtUrl = albumObj?.optJSONArray("images")?.let { images ->
                    // Prefer a middle-sized image (~300px) if available.
                    if (images.length() == 0) null
                    else {
                        val preferred = (0 until images.length())
                            .mapNotNull { images.optJSONObject(it) }
                            .minByOrNull { img ->
                                val w = img.optInt("width", 0)
                                if (w == 0) Int.MAX_VALUE else kotlin.math.abs(w - 300)
                            }
                        preferred?.optString("url")?.takeIf { it.isNotEmpty() }
                    }
                }
                SpotifyTrackHit(
                    trackUri = trackUri,
                    openUrl = openUrl.ifBlank { deriveOpenUrlFromUri(trackUri) },
                    previewUrl = previewUrl,
                    title = title,
                    artist = artist,
                    album = album,
                    albumArtUrl = albumArtUrl,
                    durationMs = durationMs
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify search error", e)
            emptyList()
        }
    }

    private fun deriveOpenUrlFromUri(uri: String): String {
        // spotify:track:ID  →  https://open.spotify.com/track/ID
        val parts = uri.split(":")
        return if (parts.size == 3 && parts[0] == "spotify") {
            "https://open.spotify.com/${parts[1]}/${parts[2]}"
        } else {
            ""
        }
    }

    // ── open_taplink builder: route into TapRadio (radio.html) ────────────

    /**
     * Build the open_taplink URL that routes through TapRadio's native
     * ExoPlayer via radio.html auto-play parameters, with a Spotify-specific
     * queue payload so FF / Rewind buttons step through tracks and track
     * metadata renders in the media toolbar.
     *
     * URL parameters (all URL-encoded):
     *   playUrl        — preview_url of the first track (ExoPlayer streams this)
     *   playName       — "Title — Artist" for the first track
     *   playSubtitle   — artist (kept separate so the podcast-bookmark modal
     *                    pre-fills nicely if the user taps ★ Save)
     *   playArtist     — artist
     *   playGenre      — "Spotify"
     *   playKind       — "spotify" (radio.html branches on this)
     *   spotifyQueue   — JSON array of track objects with all metadata
     *   spotifyIndex   — 0-based start index within the queue
     */
    private fun buildOpenTapLink(
        queue: List<SpotifyTrackHit>,
        startIndex: Int
    ): String {
        val startIdx = startIndex.coerceIn(0, queue.size - 1)
        val lead = queue[startIdx]
        val firstName = lead.artist?.let { "${lead.title} \u2014 $it" } ?: lead.title
        val params = mutableListOf(
            "playUrl=${URLEncoder.encode(lead.previewUrl.orEmpty(), "UTF-8")}",
            "playName=${URLEncoder.encode(firstName, "UTF-8")}",
            "playGenre=${URLEncoder.encode("Spotify", "UTF-8")}",
            "playKind=${URLEncoder.encode("spotify", "UTF-8")}"
        )
        lead.artist?.let {
            params += "playSubtitle=${URLEncoder.encode(it, "UTF-8")}"
            params += "playArtist=${URLEncoder.encode(it, "UTF-8")}"
        }

        val queueJson = JSONArray().apply {
            queue.forEach { hit ->
                put(JSONObject().apply {
                    put("title", hit.title)
                    put("artist", hit.artist.orEmpty())
                    put("album", hit.album.orEmpty())
                    put("previewUrl", hit.previewUrl.orEmpty())
                    put("openUrl", hit.openUrl)
                    put("albumArtUrl", hit.albumArtUrl.orEmpty())
                    put("durationMs", hit.durationMs)
                    put("trackUri", hit.trackUri)
                })
            }
        }.toString()
        params += "spotifyQueue=${URLEncoder.encode(queueJson, "UTF-8")}"
        params += "spotifyIndex=${URLEncoder.encode(startIdx.toString(), "UTF-8")}"
        return "open_taplink:file:///android_asset/radio.html?${params.joinToString("&")}"
    }

    /**
     * Build the open_taplink URL that routes through spotify.html (Web
     * Playback SDK) for Premium full-track streaming. The page loads
     * Spotify's JS SDK, creates a browser-based Connect device, and walks
     * through the queue of Spotify URIs when FF / Rewind is pressed.
     *
     * URL parameters (all URL-encoded):
     *   access_token   — user-authorized OAuth token (short-lived)
     *   expires_at_ms  — epoch-ms at which the token goes stale (for the
     *                    page to know when to call /api/spotify/refresh)
     *   queue          — JSON array of {uri,title,artist,album,art,durationMs}
     *   index          — 0-based start index within the queue
     *   playName       — "Title — Artist" for the media toolbar
     *   playArtist     — artist
     *   playKind       — "spotify_sdk" (distinguishes from "spotify" preview mode)
     *   autoplay        — "0" means load the queue paused; user must pick
     *                    output/play explicitly.
     */
    private fun buildSpotifySdkTapLink(
        queue: List<SpotifyTrackHit>,
        startIndex: Int,
        accessToken: String,
        expiryMs: Long,
        startPaused: Boolean = false
    ): String {
        val startIdx = startIndex.coerceIn(0, queue.size - 1)
        val lead = queue[startIdx]
        val firstName = lead.artist?.let { "${lead.title} \u2014 $it" } ?: lead.title
        val queueJson = JSONArray().apply {
            queue.forEach { hit ->
                put(JSONObject().apply {
                    put("uri", hit.trackUri)
                    put("title", hit.title)
                    put("artist", hit.artist.orEmpty())
                    put("album", hit.album.orEmpty())
                    put("albumArtUrl", hit.albumArtUrl.orEmpty())
                    put("durationMs", hit.durationMs)
                    put("openUrl", hit.openUrl)
                    // previewUrl is carried through even on the Premium SDK
                    // path so spotify.html has a local fallback: if the Web
                    // Playback SDK can't initialise on this WebView (Widevine
                    // / EME limitations) we play the 30-second preview_url
                    // via an HTML5 <audio> element on the glasses, instead
                    // of punting playback to an external Spotify Connect
                    // device.  Premium is NOT required to use preview_url.
                    put("previewUrl", hit.previewUrl.orEmpty())
                })
            }
        }.toString()

        val params = mutableListOf(
            "access_token=${URLEncoder.encode(accessToken, "UTF-8")}",
            "expires_at_ms=${URLEncoder.encode(expiryMs.toString(), "UTF-8")}",
            "queue=${URLEncoder.encode(queueJson, "UTF-8")}",
            "index=${URLEncoder.encode(startIdx.toString(), "UTF-8")}",
            "playName=${URLEncoder.encode(firstName, "UTF-8")}",
            "playGenre=${URLEncoder.encode("Spotify", "UTF-8")}",
            "playKind=${URLEncoder.encode("spotify_sdk", "UTF-8")}"
        )
        if (startPaused) {
            params += "autoplay=0"
            params += "start_paused=1"
        }
        lead.artist?.let {
            params += "playSubtitle=${URLEncoder.encode(it, "UTF-8")}"
            params += "playArtist=${URLEncoder.encode(it, "UTF-8")}"
        }
        return "open_taplink:file:///android_asset/spotify.html?${params.joinToString("&")}"
    }

    private companion object {
        const val TAG = "SpotifyTool"
        const val SEARCH_LIMIT = 10
    }
}
