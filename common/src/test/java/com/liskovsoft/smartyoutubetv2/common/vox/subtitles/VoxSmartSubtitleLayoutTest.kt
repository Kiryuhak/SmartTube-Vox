package com.liskovsoft.smartyoutubetv2.common.vox.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VoxSmartSubtitleLayout] verifying 1:1 compliance with userscript subtitlesSmartLayout math.
 */
class VoxSmartSubtitleLayoutTest {

    @Test
    fun testTrimEdgePunctuation() {
        assertEquals("Hello world", VoxSmartSubtitleLayout.trimEdgePunctuation("...Hello world!"))
        assertEquals("Sample text", VoxSmartSubtitleLayout.trimEdgePunctuation("- Sample text;"))
        assertEquals("Nested", VoxSmartSubtitleLayout.trimEdgePunctuation("[Nested]"))
        assertEquals("Clean text", VoxSmartSubtitleLayout.trimEdgePunctuation("Clean text"))
        assertEquals("", VoxSmartSubtitleLayout.trimEdgePunctuation("...---!!!"))
        assertEquals("", VoxSmartSubtitleLayout.trimEdgePunctuation(""))
    }

    @Test
    fun testShortText_notWrapped() {
        val short = "Short subtitle line"
        val formatted = VoxSmartSubtitleLayout.formatSubtitleText(short, maxLineLength = 45)
        assertEquals(short, formatted)
        assertFalse(formatted.contains("\n"))
    }

    @Test
    fun testLongText_wrapsAroundPunctuationNearCenter() {
        val input = "This is the first clause of the sentence, and this is the second clause that follows."
        val formatted = VoxSmartSubtitleLayout.formatSubtitleText(input, maxLineLength = 45)

        assertTrue(formatted.contains("\n"))
        val lines = formatted.split("\n")
        assertEquals(2, lines.size)

        // Line 1 should break at the comma
        assertEquals("This is the first clause of the sentence,", lines[0].trim())
        assertEquals("and this is the second clause that follows.", lines[1].trim())
    }

    @Test
    fun testLongText_noPunctuation_wrapsAtSpaceNearCenter() {
        val input = "The quick brown fox jumps over the lazy dog and then decides to run through the green forest"
        val formatted = VoxSmartSubtitleLayout.formatSubtitleText(input, maxLineLength = 45)

        assertTrue(formatted.contains("\n"))
        val lines = formatted.split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].length <= 55)
        assertTrue(lines[1].length <= 55)
    }
}
