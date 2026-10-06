package com.almog.spotifytablet.lyrics

import com.almog.spotifytablet.lyrics.model.TrackRhythmContext
import com.almog.spotifytablet.lyrics.model.WordSync
import com.almog.spotifytablet.lyrics.model.calculateRhythmSpringSpec
import com.almog.spotifytablet.lyrics.model.calculateWordProgressEasing
import com.almog.spotifytablet.lyrics.model.clampWordOverlaps
import com.almog.spotifytablet.lyrics.parser.EnhancedLrcParser
import com.almog.spotifytablet.lyrics.parser.LyricTimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsEngineTest {

    @Test
    fun testClampWordOverlaps_DurationFloorGuard() {
        val words = listOf(
            WordSync(text = "Hello", startTimeMs = 1000L, endTimeMs = 1000L),
            WordSync(text = "World", startTimeMs = 1005L, endTimeMs = 1005L)
        )
        val clamped = words.clampWordOverlaps(2000L)
        assertTrue(clamped[0].endTimeMs >= clamped[0].startTimeMs + 10L)
        assertTrue(clamped[1].endTimeMs >= clamped[1].startTimeMs + 10L)
    }

    @Test
    fun testLyricTimeUtils_SMPTEAndFrames() {
        val frameMs = LyricTimeUtils.parseTime("60f")
        assertEquals(2000L, frameMs)

        val tickMs = LyricTimeUtils.parseTime("1500t")
        assertEquals(1500L, tickMs)

        val smpteMs = LyricTimeUtils.parseTime("00:01:02:15")
        assertEquals(62500L, smpteMs)
    }

    @Test
    fun testRhythmSpringSpec_DynamicLerp() {
        val rhythm120 = TrackRhythmContext(bpm = 120f)
        val specFull = rhythm120.calculateRhythmSpringSpec<Float>(baseStiffness = 300f, baseDamping = 0.8f, lerpFactor = 1.0f)
        assertNotNull(specFull)

        val specHalf = rhythm120.calculateRhythmSpringSpec<Float>(baseStiffness = 300f, baseDamping = 0.8f, lerpFactor = 0.5f)
        assertNotNull(specHalf)
    }

    @Test
    fun testCalculateWordProgressEasing_Bounds() {
        val rhythm = TrackRhythmContext(bpm = 128f)
        assertEquals(0f, calculateWordProgressEasing(-0.5f, 200L, rhythm), 0.001f)
        assertEquals(1f, calculateWordProgressEasing(1.5f, 200L, rhythm), 0.001f)
        val mid = calculateWordProgressEasing(0.5f, 300L, rhythm)
        assertTrue(mid in 0f..1f)
    }

    @Test
    fun testExtractGraphemeClusters_HebrewWithNikkud() {
        // Hebrew word "שָׁלוֹם" with Shin dot and Qamats diacritics
        val text = "שָׁלוֹם"
        val word = WordSync(text = text, startTimeMs = 1000L, endTimeMs = 1500L)
        // Ensure graphemes are not separated from base letters
        assertTrue(word.graphemes.isNotEmpty())
        assertEquals(4, word.graphemes.size)
        assertTrue(word.graphemes[0].startsWith("ש"))
    }

    @Test
    fun testParseLrcmuxStructuredJson() {
        val json = """
        {
          "lines": [
            {
              "start": 14165,
              "end": 21000,
              "text": "Everybody dies, surprise, surprise",
              "words": [
                { "text": "Everybody ", "start": 14165, "end": 15000 },
                { "text": "dies, ", "start": 15050, "end": 16000 },
                { "text": "surprise, ", "start": 16050, "end": 17500 },
                { "text": "surprise", "start": 17550, "end": 19000 }
              ]
            }
          ]
        }
        """.trimIndent()

        val track = EnhancedLrcParser.parse(json)
        assertNotNull(track)
        assertTrue(track!!.isWordSynced)
        assertEquals(1, track.lines.size)
        val line = track.lines[0]
        assertEquals(14165L, line.startTimeMs)
        assertEquals(4, line.words.size)
        assertEquals("Everybody", line.words[0].text)
        assertTrue(line.words[0].trailingSpace)
        assertEquals(14165L, line.words[0].startTimeMs)
    }

    @Test
    fun testParseLrcmuxJson_MetaLevelLineRejectedWordSync() {
        val json = """
        {
          "meta": {
            "level": "line"
          },
          "lines": [
            {
              "start": 1000,
              "end": 3000,
              "text": "Line level fallback lyrics"
            }
          ]
        }
        """.trimIndent()

        val track = EnhancedLrcParser.parse(json)
        assertNotNull(track)
        org.junit.Assert.assertFalse(track!!.isWordSynced)
        assertEquals(1, track.lines.size)
    }

    @Test
    fun testSongOutro_ActiveLineFadesAway() {
        val lines = listOf(
            com.almog.spotifytablet.lyrics.model.LyricLine(
                startTimeMs = 1000L,
                endTimeMs = 5000L,
                words = emptyList()
            ),
            com.almog.spotifytablet.lyrics.model.LyricLine(
                startTimeMs = 6000L,
                endTimeMs = 10000L,
                words = emptyList()
            )
        )
        val track = com.almog.spotifytablet.lyrics.model.LyricTrack(
            isWordSynced = false,
            lines = lines
        )
        val viewModel = com.almog.spotifytablet.lyrics.viewmodel.LyricsViewModel(
            repository = com.almog.spotifytablet.lyrics.repository.LyricsRepository()
        )
        viewModel.setLoadedTrackForTesting(track)

        // While singing the last line (e.g. at 8000ms), activeLineIndex must be 1
        viewModel.updatePosition(8000L)
        assertEquals(1, viewModel.uiState.value.activeLineIndex)

        // During grace reading buffer (e.g. at 11000ms, which is endTimeMs + 1000ms), activeLineIndex remains 1
        viewModel.updatePosition(11000L)
        assertEquals(1, viewModel.uiState.value.activeLineIndex)

        // After reading buffer passes (outroThresholdMs = 10000 + 2500 = 12500ms, test at 13000ms)
        // activeLineIndex must drop to -1 so the last line disappears / fades away!
        viewModel.updatePosition(13000L)
        assertEquals(-1, viewModel.uiState.value.activeLineIndex)
    }

    @Test
    fun testTTMLParser_EntityReferencesAndSpans() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                <body>
                    <div>
                        <p begin="00:01.000" end="00:05.000">
                            <span begin="00:01.000" end="00:02.000">Rock &amp;</span>
                            <span begin="00:02.100" end="00:03.500">Roll</span>
                        </p>
                    </div>
                </body>
            </tt>
        """.trimIndent()

        val parsed = com.almog.spotifytablet.lyrics.parser.TTMLParser.parse(ttml)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.lines.size)
        val line = parsed.lines[0]
        assertEquals(2, line.words.size)
        assertEquals("Rock &", line.words[0].text)
        assertEquals("Roll", line.words[1].text)
    }

    @Test
    fun testFindActiveLineIndex_TemporalLocalityParity() {
        val lines = listOf(
            com.almog.spotifytablet.lyrics.model.LyricLine(startTimeMs = 1000L, endTimeMs = 3000L, rawText = "Line 1"),
            com.almog.spotifytablet.lyrics.model.LyricLine(startTimeMs = 4000L, endTimeMs = 7000L, rawText = "Line 2"),
            com.almog.spotifytablet.lyrics.model.LyricLine(startTimeMs = 8000L, endTimeMs = 11000L, rawText = "Line 3")
        )

        // Intro (before first line)
        assertEquals(-1, com.almog.spotifytablet.lyrics.viewmodel.findActiveLineIndex(lines, 500L))
        assertEquals(-1, com.almog.spotifytablet.lyrics.viewmodel.findActiveLineIndex(lines, 500L, hintIndex = 0))

        // Line 1 steady state
        assertEquals(0, com.almog.spotifytablet.lyrics.viewmodel.findActiveLineIndex(lines, 2000L))
        assertEquals(0, com.almog.spotifytablet.lyrics.viewmodel.findActiveLineIndex(lines, 2000L, hintIndex = 0))

        // Transition from Line 1 to Line 2 (hint = 0, next = 1)
        assertEquals(1, com.almog.spotifytablet.lyrics.viewmodel.findActiveLineIndex(lines, 4500L, hintIndex = 0))
        assertEquals(1, com.almog.spotifytablet.lyrics.viewmodel.findActiveLineIndex(lines, 4500L, hintIndex = 1))

        // Discontinuous Seek: from Line 2 (hint = 1) backward to Line 1
        assertEquals(0, com.almog.spotifytablet.lyrics.viewmodel.findActiveLineIndex(lines, 1500L, hintIndex = 1))

        // Discontinuous Seek: forward to Line 3
        assertEquals(2, com.almog.spotifytablet.lyrics.viewmodel.findActiveLineIndex(lines, 9000L, hintIndex = 0))

        // Outro threshold parity
        assertEquals(2, com.almog.spotifytablet.lyrics.viewmodel.findActiveLineIndex(lines, 12000L, hintIndex = 2))
        assertEquals(-1, com.almog.spotifytablet.lyrics.viewmodel.findActiveLineIndex(lines, 15000L, hintIndex = 2))
    }
}

