package com.almog.spotifytablet.lyrics

import com.almog.spotifytablet.lyrics.model.LyricLine
import com.almog.spotifytablet.lyrics.model.LyricTrack
import com.almog.spotifytablet.lyrics.model.WordSync
import com.almog.spotifytablet.lyrics.web.SpicyLyricsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpicyLyricsJsonTest {

    private fun line(
        start: Long, end: Long, text: String,
        words: List<WordSync> = emptyList(),
        bg: Boolean = false, agent: String? = null, synthesized: Boolean = false
    ) = LyricLine(start, end, words, text, isSynthesized = synthesized, isBackground = bg, agentId = agent)

    private fun word(text: String, s: Long, e: Long, space: Boolean = true) = WordSync(text, s, e, space)

    @Test
    fun quote_escapesControlAndJsLineSeparators() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", SpicyLyricsJson.quote("a\"b\\c\nd"))
        assertEquals("\"x\\u2028y\"", SpicyLyricsJson.quote("x\u2028y"))
        assertEquals("\"\\u0001\"", SpicyLyricsJson.quote("\u0001"))
    }

    @Test
    fun wordSyncedTrack_isSyllable_andMarksSyllablesOfOneWord() {
        val track = LyricTrack(
            isWordSynced = true,
            lines = listOf(
                line(1000, 3000, "Hello you", listOf(word("Hel", 1000, 1500, space = false), word("lo", 1500, 2000), word("you", 2000, 3000, space = false)))
            )
        )
        val json = SpicyLyricsJson.toJson(track)
        assertTrue(json.startsWith("{\"type\":\"Syllable\""))
        // "Hel" continues into "lo"; the last syllable is never a continuation.
        assertTrue(json.contains("{\"t\":\"Hel\",\"s\":1000,\"e\":1500,\"part\":true}"))
        assertTrue(json.contains("{\"t\":\"lo\",\"s\":1500,\"e\":2000,\"part\":false}"))
        assertTrue(json.contains("{\"t\":\"you\",\"s\":2000,\"e\":3000,\"part\":false}"))
    }

    @Test
    fun synthesizedOnlyTrack_isLineType_withoutWords() {
        val track = LyricTrack(
            isWordSynced = true,
            lines = listOf(line(0, 2000, "Just a line", listOf(word("Just", 0, 500)), synthesized = true))
        )
        val json = SpicyLyricsJson.toJson(track)
        assertTrue(json.startsWith("{\"type\":\"Line\""))
        assertFalse(json.contains("\"words\""))
    }

    @Test
    fun backgroundLine_attachesToOverlappingLead_andOrphanIsPromoted() {
        val lead = line(1000, 5000, "Lead", listOf(word("Lead", 1000, 5000, space = false)))
        val bgOverlap = line(2000, 4000, "(ooh)", listOf(word("(ooh)", 2000, 4000, space = false)), bg = true, agent = "bg")
        val bgOrphan = line(9000, 10000, "(echo)", listOf(word("(echo)", 9000, 10000, space = false)), bg = true, agent = "bg")
        val groups = SpicyLyricsJson.groupBackground(listOf(lead, bgOverlap, bgOrphan))
        assertEquals(2, groups.size)
        assertEquals(listOf(bgOverlap), groups[0].second)
        assertEquals(bgOrphan, groups[1].first)
        assertTrue(groups[1].second.isEmpty())
    }

    @Test
    fun secondSinger_isOppositeAligned() {
        val track = LyricTrack(
            isWordSynced = true,
            lines = listOf(
                line(0, 1000, "A", listOf(word("A", 0, 1000, space = false))),
                line(1500, 2500, "B", listOf(word("B", 1500, 2500, space = false)), agent = "v2")
            )
        )
        val json = SpicyLyricsJson.toJson(track)
        assertTrue(json.contains("\"text\":\"A\",\"opposite\":false"))
        assertTrue(json.contains("\"text\":\"B\",\"opposite\":true"))
    }
}
