package com.TapLink.app.unipanel

/**
 * Cross-module snapshot of what TapRadio is currently playing.
 *
 * Written by the native radio player in tapbrowser (com.TapLink.app.MainActivity)
 * and read by the visionclaw app's `identify_song` tool, which uses [streamUrl]
 * to fetch the station's live ICY `StreamTitle` (the "Artist - Title" most
 * internet stations broadcast) on demand — no paid API, exact when present.
 *
 * Deliberately tiny and dependency-free: just volatile fields. Updating it must
 * never affect playback, so the radio player only sets these values, never reads
 * back or branches on them.
 */
object NowPlayingBridge {

    @Volatile var stationName: String? = null
    @Volatile var streamUrl: String? = null
    @Volatile var isPlaying: Boolean = false
    @Volatile var streamStartedAtMs: Long = 0L
    /** Live "Artist - Title" from the stream's ICY metadata, updated by the
     *  player's onMetadata callback as the track changes. Null when the station
     *  broadcasts no track info. This is what identify_song reads, so it's
     *  always current — no stale separate-connection fetch. */
    @Volatile var trackTitle: String? = null
    @Volatile var trackArtist: String? = null
    @Volatile var trackName: String? = null
    @Volatile var trackUpdatedAtMs: Long = 0L

    /** Called by the radio player when a station starts. Resets the track —
     *  the new station's first ICY metadata will populate it. */
    fun started(stationName: String?, streamUrl: String?) {
        this.stationName = stationName?.takeIf { it.isNotBlank() }
        this.streamUrl = streamUrl?.takeIf { it.isNotBlank() }
        this.trackTitle = null
        this.trackArtist = null
        this.trackName = null
        this.trackUpdatedAtMs = 0L
        this.streamStartedAtMs = System.currentTimeMillis()
        this.isPlaying = this.streamUrl != null
    }

    /** Called by the radio player's onMetadata when an ICY StreamTitle arrives. */
    fun updateTrack(title: String?) {
        val cleaned = title?.trim()?.takeIf { it.isNotBlank() }
        trackTitle = cleaned
        trackUpdatedAtMs = if (cleaned != null) System.currentTimeMillis() else 0L
        val parts = cleaned
            ?.split(Regex("\\s+[-–—]\\s+"), limit = 2)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (parts.size == 2) {
            trackArtist = parts[0]
            trackName = parts[1]
        } else {
            trackArtist = null
            trackName = cleaned
        }
    }

    /** Called when playback is paused/resumed/buffering without replacing the stream. */
    fun setPlaybackActive(active: Boolean) {
        isPlaying = active && streamUrl != null
    }

    /** Called by the radio player when playback stops/releases. */
    fun stopped() {
        isPlaying = false
        trackTitle = null
        trackArtist = null
        trackName = null
        trackUpdatedAtMs = 0L
        streamStartedAtMs = 0L
    }
}
