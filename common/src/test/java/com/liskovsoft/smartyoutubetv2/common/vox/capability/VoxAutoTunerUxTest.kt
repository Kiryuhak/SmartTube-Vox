package com.liskovsoft.smartyoutubetv2.common.vox.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxAutoTunerUxTest {

    @Test
    fun testApplyResultStatusSemantics() {
        val initialPolicy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.AUTO,
            maxQualityHeight = 1080,
            preferredVideoCodec = VoxVideoCodecPreference.VP9,
            preferredAudioCodec = VoxAudioCodecPreference.AUTO,
            passthroughEnabled = false
        )

        val newPolicy = VoxCodecPolicy(
            mode = VoxCodecPolicyMode.AUTO,
            maxQualityHeight = 2160,
            preferredVideoCodec = VoxVideoCodecPreference.VP9,
            preferredAudioCodec = VoxAudioCodecPreference.AUTO,
            passthroughEnabled = false
        )

        val successResult = VoxApplyResult(
            status = VoxApplyStatus.APPLIED,
            previousPolicy = initialPolicy,
            appliedPolicy = newPolicy
        )

        assertEquals(VoxApplyStatus.APPLIED, successResult.status)
        assertEquals(2160, successResult.appliedPolicy?.maxQualityHeight)

        val partialResult = VoxApplyResult(
            status = VoxApplyStatus.PARTIAL,
            previousPolicy = initialPolicy,
            appliedPolicy = newPolicy
        )
        assertEquals(VoxApplyStatus.PARTIAL, partialResult.status)

        val failedResult = VoxApplyResult(
            status = VoxApplyStatus.FAILED,
            previousPolicy = initialPolicy,
            appliedPolicy = initialPolicy
        )
        assertEquals(VoxApplyStatus.FAILED, failedResult.status)
    }

    @Test
    fun testRecommendedSettingsSummaryFormatting() {
        val recommended = VoxRecommendedSettings(
            tier = VoxPerformanceTier.POWERFUL,
            policyMode = VoxCodecPolicyMode.AUTO,
            maxQualityHeight = 2160,
            preferredVideoCodec = VoxVideoCodecPreference.VP9,
            preferredAudioCodec = VoxAudioCodecPreference.AUTO,
            passthroughEnabled = false,
            reasonCodes = listOf("DISPLAY_4K", "VP9_HW_SUPPORTED")
        )

        val summaryRu = recommended.getSummaryRu()
        assertNotNull(summaryRu)
        assertTrue(summaryRu.contains("2160p") || summaryRu.contains("4K") || summaryRu.contains("Высокий"))
        assertTrue(summaryRu.contains("VP9"))
    }
}
