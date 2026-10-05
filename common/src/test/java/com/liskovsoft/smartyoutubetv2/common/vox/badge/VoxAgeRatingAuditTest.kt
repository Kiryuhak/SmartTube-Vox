package com.liskovsoft.smartyoutubetv2.common.vox.badge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoxAgeRatingAuditTest {

    @Test
    fun testKidsFlagDoesNotFabricateZeroPlus() {
        // "Made for Kids" / "Детский контент" is a flag, not a trustworthy age rating
        val result = VoxAgeRatingFormatter.parse("Made for Kids")
        assertNull(result)

        val resultRu = VoxAgeRatingFormatter.parse("Для детей")
        assertNull(resultRu)
    }

    @Test
    fun testAgeRestrictedDoesNotFabricateEighteenPlus() {
        // "Age restricted" / "18+ restriction" without explicit age rating should be strictly verified
        val result = VoxAgeRatingFormatter.parse("age restricted video")
        assertNull(result)
    }

    @Test
    fun testExactTrustworthyAgeRatings() {
        assertEquals(VoxAgeRating.AGE_0, VoxAgeRatingFormatter.parse("0+"))
        assertEquals(VoxAgeRating.AGE_6, VoxAgeRatingFormatter.parse("6+"))
        assertEquals(VoxAgeRating.AGE_12, VoxAgeRatingFormatter.parse("12+"))
        assertEquals(VoxAgeRating.AGE_16, VoxAgeRatingFormatter.parse("16+"))
        assertEquals(VoxAgeRating.AGE_18, VoxAgeRatingFormatter.parse("18+"))
    }

    @Test
    fun testRussianAgePhrases() {
        assertEquals(VoxAgeRating.AGE_12, VoxAgeRatingFormatter.parse("12 плюс"))
        assertEquals(VoxAgeRating.AGE_16, VoxAgeRatingFormatter.parse("возраст 16+"))
        assertEquals(VoxAgeRating.AGE_18, VoxAgeRatingFormatter.parse("только для 18+"))
    }
}
