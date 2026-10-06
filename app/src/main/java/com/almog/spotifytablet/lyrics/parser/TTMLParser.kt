package com.almog.spotifytablet.lyrics.parser

import android.util.Xml
import com.almog.spotifytablet.DebugLog
import com.almog.spotifytablet.lyrics.model.LyricLine
import com.almog.spotifytablet.lyrics.model.LyricTrack
import com.almog.spotifytablet.lyrics.model.WordSync
import com.almog.spotifytablet.lyrics.model.clampWordOverlaps
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.regex.Pattern

/**
 * Robust XML Pull Parser for Apple Music TTML lyric payloads.
 *
 * Implements:
 * 1. Safe parsing of nested tags, multiline spans, and XML entities (&amp;, &quot;, etc.).
 * 2. Extraction of `ttm:role="x-bg"` to distinguish background vocal tracks.
 * 3. Unified time parsing via [LyricTimeUtils].
 * 4. Word boundary clamping via [clampWordOverlaps] to prevent animation overlap delays.
 */
object TTMLParser {

    private const val TAG = "TTMLParser"
    private val ZERO_WIDTH_REGEX = Pattern.compile("[\\u200B-\\u200D\\uFEFF]")

    private fun createPullParser(): XmlPullParser {
        try {
            val factory = XmlPullParserFactory.newInstance("org.kxml2.io.KXmlParser,org.kxml2.io.KXmlSerializer", null)
            factory.isNamespaceAware = false
            return factory.newPullParser()
        } catch (_: Throwable) {}

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            return factory.newPullParser()
        } catch (_: Throwable) {}

        try {
            val kxmlClass = Class.forName("org.kxml2.io.KXmlParser")
            val parser = kxmlClass.getDeclaredConstructor().newInstance() as XmlPullParser
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            return parser
        } catch (_: Throwable) {}

        val parser = Xml.newPullParser()
        parser?.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        return parser ?: throw IllegalStateException("No XmlPullParser available")
    }

    fun parse(ttmlXml: String): LyricTrack? {
        if (ttmlXml.isBlank()) return null

        val cleanXml = ZERO_WIDTH_REGEX.matcher(ttmlXml).replaceAll("")

        return try {
            val parser = createPullParser()
            parser.setInput(StringReader(cleanXml))

            val lines = mutableListOf<LyricLine>()

            var currentPStartMs: Long = 0L
            var currentPEndMs: Long = 0L
            var currentPBackground = false
            var currentPAgent: String? = null
            var currentWords = mutableListOf<WordSync>()
            var currentRawBuilder = StringBuilder()
            var insideP = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tagName = parser.name.lowercase()
                        if (tagName == "p") {
                            insideP = true
                            currentWords = mutableListOf()
                            currentRawBuilder = StringBuilder()

                            val beginAttr = getAttributeValueIgnoreCase(parser, "begin")
                            val endAttr = getAttributeValueIgnoreCase(parser, "end")
                            val roleAttr = getAttributeValueIgnoreCase(parser, "role") ?: getAttributeValueIgnoreCase(parser, "ttm:role")
                            val agentAttr = getAttributeValueIgnoreCase(parser, "agent") ?: getAttributeValueIgnoreCase(parser, "ttm:agent")

                            currentPStartMs = LyricTimeUtils.parseTime(beginAttr ?: "") ?: 0L
                            currentPEndMs = LyricTimeUtils.parseTime(endAttr ?: "") ?: (currentPStartMs + 4000L)
                            currentPBackground = roleAttr?.contains("bg", ignoreCase = true) == true
                            currentPAgent = agentAttr
                        } else if (tagName == "span" && insideP) {
                            val beginAttr = getAttributeValueIgnoreCase(parser, "begin")
                            val endAttr = getAttributeValueIgnoreCase(parser, "end")
                            val roleAttr = getAttributeValueIgnoreCase(parser, "role") ?: getAttributeValueIgnoreCase(parser, "ttm:role")

                            val spanStartMs = LyricTimeUtils.parseTime(beginAttr ?: "") ?: currentPStartMs
                            val spanEndMs = LyricTimeUtils.parseTime(endAttr ?: "") ?: (spanStartMs + 300L)

                            // Read span text
                            val spanText = readTagText(parser)
                            val hasSpace = spanText.endsWith(" ") || spanText.startsWith(" ")
                            val cleanSpan = spanText.trim()

                            if (cleanSpan.isNotEmpty()) {
                                currentWords.add(
                                    WordSync(
                                        text = cleanSpan,
                                        startTimeMs = spanStartMs,
                                        endTimeMs = spanEndMs,
                                        trailingSpace = hasSpace
                                    )
                                )
                                currentRawBuilder.append(cleanSpan)
                                if (hasSpace) currentRawBuilder.append(" ")
                            }
                            eventType = parser.eventType
                            continue
                        }
                    }

                    XmlPullParser.TEXT -> {
                        if (insideP) {
                            val text = parser.text?.trim() ?: ""
                            if (text.isNotEmpty() && currentWords.isEmpty()) {
                                currentRawBuilder.append(text).append(" ")
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val tagName = parser.name.lowercase()
                        if (tagName == "p" && insideP) {
                            insideP = false
                            val rawText = currentRawBuilder.toString().trim()
                            if (rawText.isNotEmpty()) {
                                val effectiveEnd = if (currentWords.isNotEmpty()) {
                                    maxOf(currentPEndMs, currentWords.last().endTimeMs)
                                } else {
                                    currentPEndMs
                                }

                                val clampedWords = currentWords.clampWordOverlaps(effectiveEnd)

                                lines.add(
                                    LyricLine(
                                        startTimeMs = currentPStartMs,
                                        endTimeMs = effectiveEnd,
                                        words = clampedWords,
                                        rawText = rawText,
                                        isSynthesized = false,
                                        isBackground = currentPBackground,
                                        agentId = currentPAgent
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = try {
                    parser.next()
                } catch (e: Exception) {
                    DebugLog.w(TAG, "Encountered malformed XML node in TTML stream: ${e.message}, attempting recovery")
                    XmlPullParser.END_DOCUMENT
                }
            }

            // Flush unclosed <p> block if document ended prematurely
            if (insideP) {
                val rawText = currentRawBuilder.toString().trim()
                if (rawText.isNotEmpty()) {
                    val effectiveEnd = if (currentWords.isNotEmpty()) {
                        maxOf(currentPEndMs, currentWords.last().endTimeMs)
                    } else {
                        currentPEndMs
                    }
                    val clampedWords = currentWords.clampWordOverlaps(effectiveEnd)
                    lines.add(
                        LyricLine(
                            startTimeMs = currentPStartMs,
                            endTimeMs = effectiveEnd,
                            words = clampedWords,
                            rawText = rawText,
                            isSynthesized = false,
                            isBackground = currentPBackground,
                            agentId = currentPAgent
                        )
                    )
                }
            }

            if (lines.isEmpty()) return null

            lines.sortBy { it.startTimeMs }
            val isWordSynced = lines.any { it.isWordSynced }
            LyricTrack(isWordSynced = isWordSynced, lines = lines, source = "Apple TTML")
        } catch (e: Exception) {
            e.printStackTrace()
            DebugLog.e(TAG, "Failed to parse TTML XML: ${e.message}")
            null
        }
    }

    private fun readTagText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var event = parser.next()
        while (event == XmlPullParser.TEXT || event == XmlPullParser.ENTITY_REF) {
            sb.append(parser.text ?: "")
            event = parser.next()
        }
        return sb.toString()
    }

    private fun getAttributeValueIgnoreCase(parser: XmlPullParser, attrName: String): String? {
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)
            if (name.equals(attrName, ignoreCase = true) || name.endsWith(":$attrName", ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }
}
