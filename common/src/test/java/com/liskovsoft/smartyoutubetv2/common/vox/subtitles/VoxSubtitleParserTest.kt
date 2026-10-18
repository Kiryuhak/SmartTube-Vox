package com.liskovsoft.smartyoutubetv2.common.vox.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VoxSubtitleParser] covering Yandex JSON, YouTube JSON, and SRT formats.
 */
class VoxSubtitleParserTest {

    @Test
    fun testParseJsonSubtitles_yandexFormat() {
        val json = """
            [
              {
                "text": "Hello world",
                "startMs": 1200,
                "durationMs": 3000,
                "tokens": [
                  {"text": "Hello", "startMs": 1200, "durationMs": 800},
                  {"text": "world", "startMs": 2100, "durationMs": 900}
                ]
              },
              {
                "text": "Second sentence.",
                "startMs": 5000,
                "durationMs": 2500
              }
            ]
        """.trimIndent()

        val items = VoxSubtitleParser.parseJsonSubtitles(json)
        assertEquals(2, items.size)

        val first = items[0]
        assertEquals("Hello world", first.text)
        assertEquals(1200L, first.startMs)
        assertEquals(4200L, first.endMs)
        assertEquals(2, first.tokens.size)
        assertEquals("Hello", first.tokens[0].text)
        assertEquals(1200L, first.tokens[0].startMs)
        assertEquals(2000L, first.tokens[0].endMs)

        val second = items[1]
        assertEquals("Second sentence.", second.text)
        assertEquals(5000L, second.startMs)
        assertEquals(7500L, second.endMs)
    }

    @Test
    fun testParseJsonSubtitles_youtubeEventFormat() {
        val json = """
            {
              "events": [
                {
                  "tStartMs": 1000,
                  "dDurationMs": 2000,
                  "segs": [
                    {"utf8": "This "},
                    {"utf8": "is a "},
                    {"utf8": "test."}
                  ]
                }
              ]
            }
        """.trimIndent()

        val items = VoxSubtitleParser.parseJsonSubtitles(json)
        assertEquals(1, items.size)
        assertEquals("This is a test.", items[0].text)
        assertEquals(1000L, items[0].startMs)
        assertEquals(3000L, items[0].endMs)
    }

    @Test
    fun testParseSrtSubtitles() {
        val srt = """
            1
            00:00:01,234 --> 00:00:04,567
            First subtitle line
            Second line of first cue

            2
            00:01:10,000 --> 00:01:15,500
            Second cue text.
        """.trimIndent()

        val items = VoxSubtitleParser.parseSrtSubtitles(srt)
        assertEquals(2, items.size)

        val first = items[0]
        assertEquals(1234L, first.startMs)
        assertEquals(4567L, first.endMs)
        assertEquals("First subtitle line\nSecond line of first cue", first.text)

        val second = items[1]
        assertEquals(70000L, second.startMs)
        assertEquals(75500L, second.endMs)
        assertEquals("Second cue text.", second.text)
    }

    @Test
    fun testParseSubtitles_corruptOrEmptyInput() {
        assertTrue(VoxSubtitleParser.parseJsonSubtitles("").isEmpty())
        assertTrue(VoxSubtitleParser.parseJsonSubtitles("   ").isEmpty())
        assertTrue(VoxSubtitleParser.parseJsonSubtitles("{invalid_json:").isEmpty())
        assertTrue(VoxSubtitleParser.parseSrtSubtitles("").isEmpty())
        assertTrue(VoxSubtitleParser.parseSrtSubtitles("not an srt format").isEmpty())
    }
}
