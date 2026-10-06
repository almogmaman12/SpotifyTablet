package com.almog.spotifytablet.lyrics

import com.almog.spotifytablet.lyrics.model.LyricLine
import com.almog.spotifytablet.lyrics.model.LyricTrack
import com.almog.spotifytablet.lyrics.verifier.LyricsMatchVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMatchVerifierTest {

    @Test
    fun testCleanTrackTitle_RemovesNoiseTags() {
        assertEquals("Anti-Hero", LyricsMatchVerifier.cleanTrackTitle("Anti-Hero"))
        assertEquals("Cruel Summer", LyricsMatchVerifier.cleanTrackTitle("Cruel Summer - Live from Paris"))
        assertEquals("In The End", LyricsMatchVerifier.cleanTrackTitle("In The End - 2020 Remaster"))
        assertEquals("Stan", LyricsMatchVerifier.cleanTrackTitle("Stan - Radio Edit"))
        assertEquals("Hotel California", LyricsMatchVerifier.cleanTrackTitle("Hotel California - Live On MTV, 1994"))
        assertEquals("Bad Blood", LyricsMatchVerifier.cleanTrackTitle("Bad Blood (feat. Kendrick Lamar)"))
        assertEquals("Sunflower", LyricsMatchVerifier.cleanTrackTitle("Sunflower (From \"Spider-Man: Into the Spider-Verse\")"))
        assertEquals("Love Story", LyricsMatchVerifier.cleanTrackTitle("Love Story (Taylor's Version)"))
        assertEquals("Numb", LyricsMatchVerifier.cleanTrackTitle("Numb (2011 Remaster)"))
        assertEquals("Valerie", LyricsMatchVerifier.cleanTrackTitle("Valerie  - Version Revisited"))
        assertEquals("Valerie", LyricsMatchVerifier.cleanTrackTitle("Valerie (feat. Amy Winehouse) (Version Revisited)"))
        assertEquals("Valerie", LyricsMatchVerifier.cleanTrackTitle("Valerie (version revisited)"))
    }

    @Test
    fun testTitleMatch_ExactAndVariations() {
        assertTrue(LyricsMatchVerifier.isTitleMatch("Anti-Hero", "Anti Hero"))
        assertTrue(LyricsMatchVerifier.isTitleMatch("Cruel Summer", "Cruel Summer - Live"))
        assertTrue(LyricsMatchVerifier.isTitleMatch("In The End - 2020 Remaster", "In the End"))
        assertTrue(LyricsMatchVerifier.isTitleMatch("Bad Blood (feat. Kendrick Lamar)", "Bad Blood"))
        assertTrue(LyricsMatchVerifier.isTitleMatch("Valerie  - Version Revisited", "Valerie (feat. Amy Winehouse) (Version Revisited)"))
        assertTrue(LyricsMatchVerifier.isTitleMatch("Valerie  - Version Revisited", "Valerie (version revisited)"))
    }

    @Test
    fun testTitleMatch_RejectsDifferentSongs() {
        // "Stay" must NEVER match "Stay With Me"
        assertFalse(LyricsMatchVerifier.isTitleMatch("Stay", "Stay With Me"))
        // "Bad Blood" must NEVER match "Shake It Off"
        assertFalse(LyricsMatchVerifier.isTitleMatch("Bad Blood", "Shake It Off"))
        // "Hello" must NEVER match "Hello Goodbye"
        assertFalse(LyricsMatchVerifier.isTitleMatch("Hello", "Hello Goodbye"))
        // "Ghost" must NEVER match "Ghost Town"
        assertFalse(LyricsMatchVerifier.isTitleMatch("Ghost", "Ghost Town"))
        // Completely different track must be rejected (like Musixmatch returning Nokia for Valerie)
        assertFalse(LyricsMatchVerifier.isTitleMatch("Valerie  - Version Revisited", "NOKIA"))
    }

    @Test
    fun testArtistMatch_CollaborationsAndExact() {
        assertTrue(LyricsMatchVerifier.isArtistMatch("Taylor Swift", "Taylor Swift"))
        assertTrue(LyricsMatchVerifier.isArtistMatch("Post Malone, Swae Lee", "Post Malone"))
        assertTrue(LyricsMatchVerifier.isArtistMatch("Post Malone", "Post Malone feat. Swae Lee"))
        assertTrue(LyricsMatchVerifier.isArtistMatch("The Weeknd, Daft Punk", "The Weeknd"))
        assertTrue(LyricsMatchVerifier.isArtistMatch("Ed Sheeran & Justin Bieber", "Ed Sheeran"))
    }

    @Test
    fun testArtistMatch_RejectsMismatchedArtists() {
        assertFalse(LyricsMatchVerifier.isArtistMatch("Adele", "Lionel Richie"))
        assertFalse(LyricsMatchVerifier.isArtistMatch("The Kid LAROI", "Rihanna"))
        assertFalse(LyricsMatchVerifier.isArtistMatch("Taylor Swift", "Kanye West"))
        assertFalse(LyricsMatchVerifier.isArtistMatch("Billie Eilish", "Finneas"))
    }

    @Test
    fun testDurationMatch_Tolerance() {
        // 200,000 ms = 200s
        assertTrue(LyricsMatchVerifier.isDurationMatch(200_000, 203.0, 8.0)) // diff = 3s
        assertTrue(LyricsMatchVerifier.isDurationMatch(200_000, 194.0, 8.0)) // diff = 6s
        assertFalse(LyricsMatchVerifier.isDurationMatch(200_000, 215.0, 8.0)) // diff = 15s -> reject!
        assertFalse(LyricsMatchVerifier.isDurationMatch(200_000, 150.0, 8.0)) // diff = 50s -> reject!
        // Unknown duration should not reject
        assertTrue(LyricsMatchVerifier.isDurationMatch(0, 210.0, 8.0))
        assertTrue(LyricsMatchVerifier.isDurationMatch(200_000, 0.0, 8.0))
    }

    @Test
    fun testValidateLyricTrackTimeline_RejectsExtendedOrCorrupt() {
        val normalLines = listOf(
            LyricLine(startTimeMs = 5000L, endTimeMs = 10000L, rawText = "Line 1"),
            LyricLine(startTimeMs = 12000L, endTimeMs = 20000L, rawText = "Line 2"),
            LyricLine(startTimeMs = 25000L, endTimeMs = 45000L, rawText = "Line 3"),
            LyricLine(startTimeMs = 50000L, endTimeMs = 170000L, rawText = "Line 4")
        )
        val normalTrack = LyricTrack(isWordSynced = false, lines = normalLines)
        // Song duration is 180,000ms (3 minutes)
        val validResult = LyricsMatchVerifier.validateLyricTrackTimeline(normalTrack, 180_000)
        assertTrue(validResult.isValid)

        // Test extended track: last line starts at 220,000ms, but song duration is 180,000ms
        val extendedLines = normalLines + LyricLine(startTimeMs = 220000L, endTimeMs = 240000L, rawText = "Extended Outro")
        val extendedTrack = LyricTrack(isWordSynced = false, lines = extendedLines)
        val extendedResult = LyricsMatchVerifier.validateLyricTrackTimeline(extendedTrack, 180_000)
        assertFalse(extendedResult.isValid)
        assertTrue((extendedResult as LyricsMatchVerifier.LyricValidationResult.Rejected).reason.contains("exceed track duration"))

        // Test corrupt sync: all lines start at 0ms
        val corruptLines = listOf(
            LyricLine(startTimeMs = 0L, endTimeMs = 0L, rawText = "Line 1"),
            LyricLine(startTimeMs = 0L, endTimeMs = 0L, rawText = "Line 2"),
            LyricLine(startTimeMs = 0L, endTimeMs = 0L, rawText = "Line 3")
        )
        val corruptTrack = LyricTrack(isWordSynced = false, lines = corruptLines)
        val corruptResult = LyricsMatchVerifier.validateLyricTrackTimeline(corruptTrack, 180_000)
        assertFalse(corruptResult.isValid)
    }

    @Test
    fun testFindBestLrclibMatch_SelectsBestCandidateAndRejectsWrong() {
        val candidates = listOf(
            // Candidate 1: Karaoke / Cover with different artist
            LyricsMatchVerifier.LrclibCandidate(
                id = 1,
                trackName = "Stay",
                artistName = "Karaoke All Stars",
                durationSec = 140.0,
                syncedLyrics = "[00:10.00] Stay..."
            ),
            // Candidate 2: Different song with "Stay" in title by same artist
            LyricsMatchVerifier.LrclibCandidate(
                id = 2,
                trackName = "Stay With Me",
                artistName = "The Kid LAROI",
                durationSec = 180.0,
                syncedLyrics = "[00:10.00] Stay with me..."
            ),
            // Candidate 3: Exact match with synced lyrics
            LyricsMatchVerifier.LrclibCandidate(
                id = 3,
                trackName = "Stay",
                artistName = "The Kid LAROI, Justin Bieber",
                durationSec = 141.0,
                syncedLyrics = "[00:08.50] I do the same thing I told you that I never would..."
            )
        )

        val best = LyricsMatchVerifier.findBestLrclibMatch(
            candidates = candidates,
            expectedArtist = "The Kid LAROI",
            expectedTitle = "Stay",
            expectedDurationMs = 141_000 // 141 seconds
        )

        assertNotNull(best)
        assertEquals(3L, best!!.id)
        assertEquals("Stay", best.trackName)
    }

    @Test
    fun testFindBestLrclibMatch_RejectsWhenNoCandidatePasses() {
        val candidates = listOf(
            LyricsMatchVerifier.LrclibCandidate(
                id = 10,
                trackName = "Different Song",
                artistName = "Another Artist",
                durationSec = 200.0,
                syncedLyrics = "[00:10.00] Some lyrics"
            )
        )
        val result = LyricsMatchVerifier.findBestLrclibMatch(
            candidates = candidates,
            expectedArtist = "Taylor Swift",
            expectedTitle = "Cruel Summer",
            expectedDurationMs = 178_000
        )
        assertNull(result)
    }

    @Test
    fun testHebrewTitlesAndArtists() {
        assertTrue(LyricsMatchVerifier.isTitleMatch("ממעמקים", "ממעמקים - Live"))
        assertTrue(LyricsMatchVerifier.isArtistMatch("עידן רייכל", "עידן רייכל"))
        assertTrue(LyricsMatchVerifier.isArtistMatch("טונה, רביד פלוטניק", "טונה"))
        assertFalse(LyricsMatchVerifier.isTitleMatch("סהרה", "רוצה שלום"))
        assertFalse(LyricsMatchVerifier.isArtistMatch("עומר אדם", "אייל גולן"))
    }

    @Test
    fun testValidateLyricTrackTimeline_RejectsPrematureTruncation() {
        // Song is 200,000ms (3m 20s), but lyrics end at 15,000ms with only 2 lines
        val truncatedLines = listOf(
            LyricLine(startTimeMs = 2000L, endTimeMs = 8000L, rawText = "Intro snippet"),
            LyricLine(startTimeMs = 9000L, endTimeMs = 15000L, rawText = "Second snippet")
        )
        val truncatedTrack = LyricTrack(isWordSynced = false, lines = truncatedLines)
        val result = LyricsMatchVerifier.validateLyricTrackTimeline(truncatedTrack, 200_000)
        assertFalse(result.isValid)
        assertTrue((result as LyricsMatchVerifier.LyricValidationResult.Rejected).reason.contains("Premature lyrics termination"))
    }

    @Test
    fun testCommonAmbiguousSongTitles_StrictlyRejectsWrongArtists() {
        // "Memories" by Maroon 5 must reject "Memories" by David Guetta
        assertFalse(LyricsMatchVerifier.isArtistMatch("Maroon 5", "David Guetta"))
        // "Photograph" by Ed Sheeran must reject "Photograph" by Nickelback
        assertFalse(LyricsMatchVerifier.isArtistMatch("Ed Sheeran", "Nickelback"))
        // "One" by U2 must reject "One" by Metallica
        assertFalse(LyricsMatchVerifier.isArtistMatch("U2", "Metallica"))
    }

    @Test
    fun testSameArtistDifferentTracks_StrictlyRejects() {
        assertFalse(LyricsMatchVerifier.isTitleMatch("God's Plan", "Hotline Bling"))
        assertFalse(LyricsMatchVerifier.isTitleMatch("Blank Space", "Shake It Off"))
        assertFalse(LyricsMatchVerifier.isTitleMatch("Shape of You", "Perfect"))
    }
}
