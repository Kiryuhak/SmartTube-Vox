package com.liskovsoft.smartyoutubetv2.common.vox.badge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxAgeRatingFormatterTest {

    @Test
    fun testExplicitRussianRatings() {
        assertEquals("0+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("0+")))
        assertEquals("6+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("6+")))
        assertEquals("12+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("12+")))
        assertEquals("16+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("16+")))
        assertEquals("18+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("18+")))
    }

    @Test
    fun testWordBasedRatings() {
        assertEquals("18+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("18 лет")))
        assertEquals("16+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("16 years")))
        assertEquals("12+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("12 yo")))
        assertEquals("6+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("6 лет")))
    }

    @Test
    fun testInternationalRatings() {
        assertEquals("0+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("TV-Y")))
        assertEquals("0+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("G")))
        assertEquals("6+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("TV-PG")))
        assertEquals("6+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("PG")))
        assertEquals("6+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("TV-Y7")))
        assertEquals("12+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("TV-14")))
        assertEquals("12+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("PG-13")))
        assertEquals("16+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("TV-16")))
        assertEquals("18+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("TV-MA")))
        assertEquals("18+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("R")))
        assertEquals("18+", VoxAgeRatingFormatter.format(VoxAgeRatingFormatter.parse("NC-17")))
    }

    @Test
    fun testUnknownOrMissingRatingsNeverFallbackToZeroPlus() {
        assertNull(VoxAgeRatingFormatter.parse(null))
        assertNull(VoxAgeRatingFormatter.parse(""))
        assertNull(VoxAgeRatingFormatter.parse("   "))
        assertNull(VoxAgeRatingFormatter.parse("UNKNOWN"))
        assertNull(VoxAgeRatingFormatter.parse("NONE"))
        assertNull(VoxAgeRatingFormatter.parse("4K"))
        assertNull(VoxAgeRatingFormatter.parse("1080p"))
        assertNull(VoxAgeRatingFormatter.parse("100K views"))
        assertNull(VoxAgeRatingFormatter.parse("500 тыс. просмотров"))
        assertNull(VoxAgeRatingFormatter.parse("3 days ago"))
        assertNull(VoxAgeRatingFormatter.parse("5 лет назад"))
    }

    @Test
    fun testIsAgeString() {
        assertTrue(VoxAgeRatingFormatter.isAgeString("18+"))
        assertTrue(VoxAgeRatingFormatter.isAgeString("12+"))
        assertTrue(VoxAgeRatingFormatter.isAgeString("TV-MA"))
        assertFalse(VoxAgeRatingFormatter.isAgeString("1080p"))
        assertFalse(VoxAgeRatingFormatter.isAgeString("4K"))
        assertFalse(VoxAgeRatingFormatter.isAgeString("100K views"))
    }
}
