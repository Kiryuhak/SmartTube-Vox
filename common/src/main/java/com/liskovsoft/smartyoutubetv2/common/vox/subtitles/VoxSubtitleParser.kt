package com.liskovsoft.smartyoutubetv2.common.vox.subtitles

import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Парсер субтитров различных форматов (Yandex JSON, YouTube JSON events, SRT/VTT).
 */
object VoxSubtitleParser {

    private val SRT_TIME_PATTERN = Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})")

    @JvmStatic
    fun parseJsonSubtitles(jsonContent: String): List<VoxSubtitleItem> {
        if (jsonContent.isBlank()) return emptyList()
        val trimmed = jsonContent.trim()
        val result = mutableListOf<VoxSubtitleItem>()

        try {
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    parseJsonItem(item)?.let { result.add(it) }
                }
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                if (obj.has("subtitles")) {
                    val array = obj.optJSONArray("subtitles")
                    if (array != null) {
                        for (i in 0 until array.length()) {
                            val item = array.optJSONObject(i) ?: continue
                            parseJsonItem(item)?.let { result.add(it) }
                        }
                    }
                } else if (obj.has("events")) {
                    return parseYoutubeEvents(obj.optJSONArray("events"))
                }
            }
        } catch (e: Exception) {
            // Robust error handling: invalid JSON returns whatever was parsed or empty list
        }

        return result.sortedBy { it.startMs }
    }

    private fun parseJsonItem(obj: JSONObject): VoxSubtitleItem? {
        val text = obj.optString("text", "").trim()
        if (text.isEmpty()) return null
        val startMs = obj.optLong("startMs", obj.optLong("start", 0L))
        val durationMs = obj.optLong("durationMs", obj.optLong("duration", 0L))
        val speakerId = obj.optString("speakerId", "0")

        val tokens = mutableListOf<VoxSubtitleToken>()
        val tokensArray = obj.optJSONArray("tokens")
        if (tokensArray != null) {
            for (i in 0 until tokensArray.length()) {
                val tObj = tokensArray.optJSONObject(i) ?: continue
                val tText = tObj.optString("text", "").trim()
                if (tText.isNotEmpty()) {
                    val tStart = tObj.optLong("startMs", tObj.optLong("start", startMs))
                    val tDur = tObj.optLong("durationMs", tObj.optLong("duration", 0L))
                    tokens.add(VoxSubtitleToken(tText, tStart, tDur))
                }
            }
        }

        return VoxSubtitleItem(text, startMs, durationMs, speakerId, tokens)
    }

    private fun parseYoutubeEvents(events: JSONArray?): List<VoxSubtitleItem> {
        if (events == null) return emptyList()
        val result = mutableListOf<VoxSubtitleItem>()

        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val segs = event.optJSONArray("segs") ?: continue
            val startMs = event.optLong("tStartMs", 0L)
            val durationMs = event.optLong("dDurationMs", 0L)

            val fullText = StringBuilder()
            val tokens = mutableListOf<VoxSubtitleToken>()

            for (j in 0 until segs.length()) {
                val seg = segs.optJSONObject(j) ?: continue
                val segText = seg.optString("utf8", "")
                if (segText.isEmpty()) continue
                val offsetMs = seg.optLong("tOffsetMs", 0L)
                fullText.append(segText)

                val tokenText = segText.trim()
                if (tokenText.isNotEmpty()) {
                    tokens.add(VoxSubtitleToken(tokenText, startMs + offsetMs, 0L))
                }
            }

            val text = fullText.toString().trim()
            if (text.isNotEmpty()) {
                result.add(VoxSubtitleItem(text, startMs, durationMs, "0", tokens))
            }
        }

        return result.sortedBy { it.startMs }
    }

    @JvmStatic
    fun parseSrtSubtitles(srtContent: String): List<VoxSubtitleItem> {
        if (srtContent.isBlank()) return emptyList()
        val result = mutableListOf<VoxSubtitleItem>()
        val lines = srtContent.replace("\r\n", "\n").replace("\r", "\n").split("\n")

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.all { it.isDigit() }) {
                i++
                continue
            }

            val matcher = SRT_TIME_PATTERN.matcher(line)
            if (matcher.find()) {
                val startH = matcher.group(1)?.toLong() ?: 0L
                val startM = matcher.group(2)?.toLong() ?: 0L
                val startS = matcher.group(3)?.toLong() ?: 0L
                val startMs = matcher.group(4)?.toLong() ?: 0L
                val startTimeMs = startH * 3600000L + startM * 60000L + startS * 1000L + startMs

                val endH = matcher.group(5)?.toLong() ?: 0L
                val endM = matcher.group(6)?.toLong() ?: 0L
                val endS = matcher.group(7)?.toLong() ?: 0L
                val endMs = matcher.group(8)?.toLong() ?: 0L
                val endTimeMs = endH * 3600000L + endM * 60000L + endS * 1000L + endMs
                val durationMs = maxOf(0L, endTimeMs - startTimeMs)

                i++
                val textBuilder = StringBuilder()
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                    textBuilder.append(lines[i].trim())
                    i++
                }

                val text = textBuilder.toString().trim()
                if (text.isNotEmpty()) {
                    result.add(VoxSubtitleItem(text, startTimeMs, durationMs))
                }
            } else {
                i++
            }
        }

        return result.sortedBy { it.startMs }
    }
}
