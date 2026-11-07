package com.sora.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistNavigationTest {
    private val tracks = listOf("second-in-library", "first-in-library")

    @Test fun wrapsOnlyWithinPlaylist() {
        assertEquals(tracks[0], nextTrack(tracks, tracks[1], 1, RepeatMode.ALL, true))
        assertEquals(tracks[1], nextTrack(tracks, tracks[0], -1, RepeatMode.ALL, false))
        assertEquals(tracks[0], nextTrack(tracks, "outside-playlist", 1, RepeatMode.ALL, false))
        assertNull(nextTrack(emptyList(), "outside-playlist", 1, RepeatMode.ALL, false))
    }

    @Test fun sequentialPlaybackStopsAtBoundary() {
        assertNull(nextTrack(tracks, tracks[1], 1, RepeatMode.OFF, true))
        assertNull(nextTrack(tracks, tracks[0], -1, RepeatMode.OFF, false))
    }

    @Test fun repeatOneAppliesToCompletionAndNotManualSkip() {
        assertEquals(tracks[0], nextTrack(tracks, tracks[0], 1, RepeatMode.ONE, true))
        assertEquals(tracks[1], nextTrack(tracks, tracks[0], 1, RepeatMode.ONE, false))
    }
}
