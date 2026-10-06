package com.almog.spotifytablet.lyrics.parser

import com.almog.spotifytablet.DebugLog
import com.almog.spotifytablet.lyrics.model.LyricLine
import com.almog.spotifytablet.lyrics.model.LyricTrack
import com.almog.spotifytablet.lyrics.model.WordSync
import com.almog.spotifytablet.lyrics.model.clampWordOverlaps
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Enhanced parser handling:
 * 1. Syllable/Word-level Enhanced LRC: `[mm:ss.xx]<mm:ss.xx> Word <mm:ss.xx> NextWord`
 * 2. Musixmatch RichSync JSON
 * 3. LyricsPlus / Lrcmux JSON formats
 * 4. Standard Line-Level LRC with millisecond precision
 * 5. Jellyfin JSON lyric arrays
 *
 * Implements:
 * - Proper `isSynthesized` tracking so synthesized line-level words do not spoof `isWordSynced`.
 * - Global word overlap clamping.
 * - Unified time parsing via [LyricTimeUtils].
 */
object EnhancedLrcParser {

    private const val TAG = "EnhancedLrcParser"
    private val LINE_TIMESTAMP_REGEX = Pattern.compile("\\[(\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?)\\]")
    private val WORD_TIMESTAMP_REGEX = Pattern.compile("<(\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?)>([^<]*)")

    /**
     * Parses any text or JSON lyric payload into a unified [LyricTrack].
     */
    fun parse(payload: String): LyricTrack? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null

        // 1. If payload contains TTML tags, forward to TTMLParser
        if (trimmed.contains("<tt", ignoreCase = true) || (trimmed.contains("<p") && trimmed.contains("<span"))) {
            TTMLParser.parse(trimmed)?.let { return it }
        }

        // 2. Check for JSON structures (ensure it is actual JSON object or array of objects, not standard LRC timestamp [00:00.00])
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || trimmed.startsWith("[{")) {
            val jsonResult = parseJsonPayload(trimmed)
            if (jsonResult != null) return jsonResult
        }

        // 3. Fallback to Enhanced or Standard LRC
        return parseLrcString(trimmed)
    }

    private fun parseJsonPayload(jsonStr: String): LyricTrack? {
        return try {
            val trimmed = jsonStr.trim()
            if (trimmed.startsWith("[")) {
                if (trimmed.contains("\"ts\"") && trimmed.contains("\"l\"")) {
                    parseMusixmatchRichsync(JSONArray(trimmed))
                } else {
                    parseJellyfinOrGenericJsonArray(JSONArray(trimmed))
                }
            } else {
                val root = JSONObject(trimmed)

                // 1. Unwrap {"data": [...]} or {"data": "..."} (e.g. ThetaDev / Lrcmux responses)
                if (root.has("data")) {
                    val dataObj = root.get("data")
                    if (dataObj is String && dataObj.isNotEmpty()) {
                        return parse(dataObj)
                    } else if (dataObj is JSONArray) {
                        return parseJsonPayload(dataObj.toString())
                    } else if (dataObj is JSONObject) {
                        return parseJsonPayload(dataObj.toString())
                    }
                }

                // 2. Musixmatch RichSync nested payload
                val richsyncBody = root.optJSONObject("message")?.optJSONObject("body")?.optJSONObject("richsync")?.optString("richsync_body", "")
                    ?: root.optJSONObject("richsync")?.optString("richsync_body", "")
                    ?: root.optString("richsync_body", "")

                if (richsyncBody.isNotEmpty()) {
                    return parseJsonPayload(richsyncBody)
                }

                // 3. TTML embedded in JSON
                val ttmlContent = root.optString("ttml", "")
                if (ttmlContent.isNotEmpty()) {
                    TTMLParser.parse(ttmlContent)?.let { return it }
                }

                // 4. LyricsPlus nested format
                if (root.has("lyrics")) {
                    val lyricsObj = root.get("lyrics")
                    if (lyricsObj is String) {
                        return parse(lyricsObj)
                    } else if (lyricsObj is JSONArray) {
                        return parseLyricsPlusArray(lyricsObj)
                    }
                }

                // 0. lrcmux structured lines array with nested words
                val linesArray = root.optJSONArray("lines")
                if (linesArray != null && linesArray.length() > 0) {
                    val metaObj = root.optJSONObject("meta")
                    val syncLevel = metaObj?.optString("level", "") ?: ""
                    val lrcmuxTrack = parseLrcmuxJson(linesArray)
                    if (lrcmuxTrack != null && lrcmuxTrack.lines.isNotEmpty()) {
                        // If meta.level explicitly says "line" or "none", ensure isWordSynced matches
                        val isWord = if (syncLevel.isNotEmpty()) syncLevel.equals("word", ignoreCase = true) else lrcmuxTrack.isWordSynced
                        return lrcmuxTrack.copy(isWordSynced = isWord)
                    }
                }

                // 5. wordSyncedLyrics (e.g. from lrcmux word-level or extended services)
                val wordSynced = root.optString("wordSyncedLyrics", "")
                if (wordSynced.isNotEmpty()) {
                    return parse(wordSynced)
                }

                val syncedLyrics = root.optString("syncedLyrics", root.optString("lrc", ""))
                if (syncedLyrics.isNotEmpty()) {
                    return parse(syncedLyrics)
                }

                val plainLyrics = root.optString("plainLyrics", "")
                if (plainLyrics.isNotEmpty()) {
                    return parse(plainLyrics)
                }

                null
            }
        } catch (e: Throwable) {
            DebugLog.e(TAG, "JSON lyric parse error: ${e.message}")
            null
        }
    }

    private fun parseLrcmuxJson(linesArray: JSONArray): LyricTrack? {
        return try {
            val lyricLines = mutableListOf<LyricLine>()

            for (i in 0 until linesArray.length()) {
                val lineObj = linesArray.optJSONObject(i) ?: continue
                val lineText = lineObj.optString("text", "").trim()
                val lineStartMs = lineObj.optLong("start", 0L)
                val lineEndMs = lineObj.optLong("end", lineStartMs + 1000L)

                val wordSyncList = mutableListOf<WordSync>()
                val wordsArray = lineObj.optJSONArray("words")

                if (wordsArray != null) {
                    for (j in 0 until wordsArray.length()) {
                        val wordObj = wordsArray.optJSONObject(j) ?: continue
                        val rawWordText = wordObj.optString("text", "")
                        val wordStartMs = wordObj.optLong("start", 0L)
                        val wordEndMs = wordObj.optLong("end", wordStartMs + 50L)

                        val hasTrailingSpace = rawWordText.endsWith(" ")
                        val trimmedWord = rawWordText.trim()

                        if (trimmedWord.isNotEmpty()) {
                            wordSyncList.add(
                                WordSync(
                                    startTimeMs = wordStartMs,
                                    endTimeMs = maxOf(wordEndMs, wordStartMs + 10L),
                                    text = trimmedWord,
                                    trailingSpace = hasTrailingSpace
                                )
                            )
                        }
                    }
                }

                val clampedWords = wordSyncList.clampWordOverlaps(maxOf(lineEndMs, lineStartMs + 500L))

                lyricLines.add(
                    LyricLine(
                        startTimeMs = lineStartMs,
                        endTimeMs = maxOf(lineEndMs, lineStartMs + 500L),
                        rawText = lineText,
                        words = clampedWords,
                        isSynthesized = clampedWords.isEmpty()
                    )
                )
            }

            if (lyricLines.isNotEmpty()) {
                val hasWordSync = lyricLines.any { it.isWordSynced }
                LyricTrack(
                    isWordSynced = hasWordSync,
                    lines = lyricLines,
                    source = "api.lrcmux.dev"
                )
            } else null
        } catch (e: Exception) {
            DebugLog.e(TAG, "Error parsing lrcmux structured JSON: ${e.message}")
            null
        }
    }

    private fun parseMusixmatchRichsync(linesArray: JSONArray): LyricTrack? {
        val lines = mutableListOf<LyricLine>()
        for (i in 0 until linesArray.length()) {
            val lineObj = linesArray.optJSONObject(i) ?: continue
            val ts = lineObj.optDouble("ts", 0.0)
            val te = lineObj.optDouble("te", 0.0)
            val lineStartMs = (ts * 1000).toLong()
            val lineEndMs = if (te > ts) (te * 1000).toLong() else lineStartMs + 3500L

            val chunksArray = lineObj.optJSONArray("l")
            val words = mutableListOf<WordSync>()
            val lineText = StringBuilder()

            if (chunksArray != null && chunksArray.length() > 0) {
                var currentWordBuf = StringBuilder()
                var currentWordStartMs = -1L
                var currentWordEndMs = -1L

                for (w in 0 until chunksArray.length()) {
                    val wObj = chunksArray.optJSONObject(w) ?: continue
                    val chunkStr = wObj.optString("c", "")
                    val offsetSec = wObj.optDouble("o", 0.0)
                    val chunkStartMs = lineStartMs + (offsetSec * 1000).toLong()

                    val chunkEndMs = if (w + 1 < chunksArray.length()) {
                        val nextOffset = chunksArray.getJSONObject(w + 1).optDouble("o", offsetSec + 0.3)
                        lineStartMs + (nextOffset * 1000).toLong()
                    } else if (te > ts) {
                        (te * 1000).toLong()
                    } else {
                        chunkStartMs + 350L
                    }

                    if (chunkStr.trim().isEmpty()) {
                        if (currentWordBuf.isNotEmpty()) {
                            val cleanW = currentWordBuf.toString().trim()
                            lineText.append(cleanW).append(" ")
                            words.add(WordSync(cleanW, currentWordStartMs, maxOf(currentWordStartMs + 180L, chunkStartMs), true))
                            currentWordBuf.setLength(0)
                            currentWordStartMs = -1L
                        }
                    } else {
                        if (currentWordStartMs == -1L) {
                            currentWordStartMs = chunkStartMs
                        }
                        currentWordEndMs = chunkEndMs
                        currentWordBuf.append(chunkStr)

                        if (chunkStr.endsWith(" ")) {
                            val cleanW = currentWordBuf.toString().trim()
                            lineText.append(cleanW).append(" ")
                            words.add(WordSync(cleanW, currentWordStartMs, currentWordEndMs, true))
                            currentWordBuf.setLength(0)
                            currentWordStartMs = -1L
                        }
                    }
                }

                if (currentWordBuf.isNotEmpty()) {
                    val cleanW = currentWordBuf.toString().trim()
                    lineText.append(cleanW)
                    words.add(WordSync(cleanW, currentWordStartMs, currentWordEndMs, false))
                }
            }

            val fullText = lineText.toString().trim()
            if (fullText.isNotEmpty()) {
                val effectiveEnd = maxOf(lineEndMs, words.lastOrNull()?.endTimeMs ?: lineEndMs)
                val clampedWords = words.clampWordOverlaps(effectiveEnd)
                lines.add(
                    LyricLine(
                        startTimeMs = lineStartMs,
                        endTimeMs = effectiveEnd,
                        words = clampedWords,
                        rawText = fullText,
                        isSynthesized = false
                    )
                )
            }
        }

        if (lines.isEmpty()) return null
        lines.sortBy { it.startTimeMs }
        return LyricTrack(isWordSynced = lines.any { it.isWordSynced }, lines = lines, source = "Musixmatch RichSync")
    }

    private fun parseLyricsPlusArray(lyricsArray: JSONArray): LyricTrack? {
        val lines = mutableListOf<LyricLine>()
        for (i in 0 until lyricsArray.length()) {
            val lineObj = lyricsArray.optJSONObject(i) ?: continue
            val rawLineTime = lineObj.optDouble("time", lineObj.optDouble("start", -1.0))
            if (rawLineTime < 0) continue

            val rawLineEnd = lineObj.optDouble("end", -1.0)
            val lineStartMs = normalizeTimeMs(rawLineTime)
            val lineEndMsExplicit = if (rawLineEnd > rawLineTime) normalizeTimeMs(rawLineEnd) else -1L
            var lineText = lineObj.optString("text", lineObj.optString("line", "")).trim()

            val wordsArr = lineObj.optJSONArray("words")
                ?: lineObj.optJSONArray("syllables")
                ?: lineObj.optJSONArray("l")

            val words = mutableListOf<WordSync>()
            val rawBuilder = StringBuilder()

            if (wordsArr != null && wordsArr.length() > 0) {
                for (w in 0 until wordsArr.length()) {
                    val wObj = wordsArr.optJSONObject(w) ?: continue
                    val wordStr = wObj.optString("text", wObj.optString("word", wObj.optString("c", "")))
                    if (wordStr.isEmpty()) continue

                    val rawWStart = wObj.optDouble("time", wObj.optDouble("start", -1.0))
                    val rawWEnd = wObj.optDouble("end", -1.0)
                    val rawWDur = wObj.optDouble("duration", -1.0)

                    val wStart = if (rawWStart >= 0) normalizeTimeMs(rawWStart) else lineStartMs
                    val wEnd = if (rawWEnd > rawWStart) {
                        normalizeTimeMs(rawWEnd)
                    } else if (rawWDur > 0) {
                        wStart + (rawWDur * 1000).toLong().coerceAtLeast(80L)
                    } else {
                        wStart + 350L
                    }

                    val hasSpace = wordStr.endsWith(" ") || (w + 1 < wordsArr.length())
                    val cleanW = wordStr.trim()
                    if (cleanW.isNotEmpty()) {
                        words.add(
                            WordSync(
                                text = cleanW,
                                startTimeMs = wStart,
                                endTimeMs = wEnd,
                                trailingSpace = hasSpace
                            )
                        )
                        rawBuilder.append(cleanW)
                        if (hasSpace) rawBuilder.append(" ")
                    }
                }
            }

            if (rawBuilder.isNotEmpty()) {
                lineText = rawBuilder.toString().trim()
            }

            if (lineText.isNotEmpty()) {
                val endMs = if (words.isNotEmpty()) {
                    words.last().endTimeMs + 500L
                } else if (lineEndMsExplicit > lineStartMs) {
                    lineEndMsExplicit
                } else {
                    lineStartMs + 4000L
                }
                val clampedWords = words.clampWordOverlaps(endMs)
                lines.add(
                    LyricLine(
                        startTimeMs = lineStartMs,
                        endTimeMs = endMs,
                        words = clampedWords,
                        rawText = lineText,
                        isSynthesized = words.isEmpty()
                    )
                )
            }
        }

        if (lines.isEmpty()) return null
        lines.sortBy { it.startTimeMs }
        return LyricTrack(isWordSynced = lines.any { it.isWordSynced }, lines = lines, source = "LyricsPlus")
    }

    private fun parseJellyfinOrGenericJsonArray(array: JSONArray): LyricTrack? {
        val lines = mutableListOf<LyricLine>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val text = item.optString("Text", item.optString("text", "")).trim()
            val startTime = item.optLong("StartTime", item.optLong("Start", -1L))
            if (startTime >= 0 && text.isNotEmpty()) {
                val timeMs = if (startTime > 10_000_000L) startTime / 10_000L else startTime
                lines.add(
                    LyricLine(
                        startTimeMs = timeMs,
                        endTimeMs = timeMs + 4000L,
                        words = emptyList(),
                        rawText = text,
                        isSynthesized = false
                    )
                )
            }
        }
        if (lines.isEmpty()) return null
        lines.sortBy { it.startTimeMs }
        return LyricTrack(isWordSynced = false, lines = lines, source = "Jellyfin")
    }

    /**
     * Parses word-synced or line-synced LRC text format.
     */
    private fun parseLrcString(lrcText: String): LyricTrack? {
        val rawLines = lrcText.split("\n")
        val lines = mutableListOf<LyricLine>()

        for (line in rawLines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            val lineMatcher = LINE_TIMESTAMP_REGEX.matcher(trimmedLine)
            val lineTimestamps = mutableListOf<Long>()
            var lastIndex = 0

            while (lineMatcher.find()) {
                val timeStr = lineMatcher.group(1) ?: continue
                LyricTimeUtils.parseTime(timeStr)?.let { lineTimestamps.add(it) }
                lastIndex = lineMatcher.end()
            }

            if (lineTimestamps.isEmpty()) continue

            val contentAfterLineTime = trimmedLine.substring(lastIndex)
            val wordMatcher = WORD_TIMESTAMP_REGEX.matcher(contentAfterLineTime)
            val wordTokens = mutableListOf<WordSync>()
            val rawBuilder = StringBuilder()

            var wordMatched = false
            var currentWordStart = 0L

            while (wordMatcher.find()) {
                wordMatched = true
                val wTimeStr = wordMatcher.group(1) ?: continue
                val wTimestamp = LyricTimeUtils.parseTime(wTimeStr) ?: currentWordStart
                val wordText = wordMatcher.group(2) ?: ""

                if (wordTokens.isNotEmpty() && currentWordStart > 0) {
                    val prevWord = wordTokens.removeAt(wordTokens.size - 1)
                    wordTokens.add(prevWord.copy(endTimeMs = wTimestamp))
                }

                currentWordStart = wTimestamp
                val hasTrailingSpace = wordText.endsWith(" ") || wordText.startsWith(" ")
                val cleanWord = wordText.trim()

                if (cleanWord.isNotEmpty()) {
                    wordTokens.add(
                        WordSync(
                            text = cleanWord,
                            startTimeMs = wTimestamp,
                            endTimeMs = wTimestamp + 350L,
                            trailingSpace = hasTrailingSpace
                        )
                    )
                    rawBuilder.append(cleanWord)
                    if (hasTrailingSpace) rawBuilder.append(" ")
                }
            }

            val finalRawText = if (wordMatched && rawBuilder.isNotEmpty()) {
                rawBuilder.toString().trim()
            } else {
                contentAfterLineTime.trim()
            }

            if (finalRawText.isNotEmpty()) {
                val isBg = finalRawText.startsWith("(") && finalRawText.endsWith(")")
                val primaryLineTime = lineTimestamps.firstOrNull() ?: 0L

                for (time in lineTimestamps) {
                    val timeDelta = time - primaryLineTime
                    // Adjust word token timestamps relative to each multi-timestamp instance
                    val adjustedWords = if (wordMatched && wordTokens.isNotEmpty()) {
                        wordTokens.map { w ->
                            w.copy(
                                startTimeMs = w.startTimeMs + timeDelta,
                                endTimeMs = w.endTimeMs + timeDelta,
                                characters = w.characters
                            )
                        }
                    } else emptyList()

                    val endMs = if (adjustedWords.isNotEmpty()) {
                        adjustedWords.last().endTimeMs + 500L
                    } else {
                        time + 4000L
                    }
                    val clampedWords = adjustedWords.clampWordOverlaps(endMs)
                    lines.add(
                        LyricLine(
                            startTimeMs = time,
                            endTimeMs = endMs,
                            words = clampedWords,
                            rawText = finalRawText,
                            isSynthesized = !wordMatched,
                            isBackground = isBg
                        )
                    )
                }
            }
        }

        if (lines.isEmpty()) return null
        lines.sortBy { it.startTimeMs }

        // For lines without native word-sync, synthesize word tokens proportionally (flagged as isSynthesized)
        for (i in 0 until lines.size) {
            val curr = lines[i]
            val nextStart = if (i + 1 < lines.size) lines[i + 1].startTimeMs else curr.endTimeMs
            val effectiveEnd = if (curr.endTimeMs > curr.startTimeMs && curr.endTimeMs <= nextStart) curr.endTimeMs else nextStart
            val lineDuration = (effectiveEnd - curr.startTimeMs).coerceIn(1000L, 8000L)
            lines[i] = curr.copy(endTimeMs = curr.startTimeMs + lineDuration)

            if (lines[i].words.isEmpty()) {
                val wordsList = lines[i].rawText.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (wordsList.isNotEmpty()) {
                    val totalChars = wordsList.sumOf { it.length }.coerceAtLeast(1)
                    val generatedWords = mutableListOf<WordSync>()
                    var currentStart = lines[i].startTimeMs

                    for (wIdx in wordsList.indices) {
                        val wordStr = wordsList[wIdx]
                        val wordFraction = wordStr.length.toDouble() / totalChars.toDouble()
                        val wordDur = (lineDuration * wordFraction).toLong().coerceAtLeast(150L)
                        val wordEnd = currentStart + wordDur
                        val isLast = (wIdx == wordsList.lastIndex)

                        generatedWords.add(
                            WordSync(
                                text = wordStr,
                                startTimeMs = currentStart,
                                endTimeMs = wordEnd,
                                trailingSpace = !isLast
                            )
                        )
                        currentStart = wordEnd
                    }
                    lines[i] = lines[i].copy(words = generatedWords, isSynthesized = true)
                }
            }
        }

        val isWordSynced = lines.any { it.isWordSynced }
        return LyricTrack(isWordSynced = isWordSynced, lines = lines, source = if (isWordSynced) "Enhanced LRC" else "Standard LRC")
    }

    private fun normalizeTimeMs(rawTime: Double): Long {
        if (rawTime <= 0.0) return 0L
        // If rawTime is already >= 1000.0 and essentially an integer (e.g. 13310.0 or 106085.0 from lrcmux), it's already in milliseconds!
        // If rawTime is a small decimal number (e.g. 12.34 or 199.5), it's in decimal seconds.
        return if (rawTime >= 1000.0) {
            rawTime.toLong()
        } else if (rawTime != Math.floor(rawTime)) {
            (rawTime * 1000.0).toLong()
        } else {
            rawTime.toLong()
        }
    }
}
