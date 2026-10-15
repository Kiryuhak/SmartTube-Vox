package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.liskovsoft.smartyoutubetv2.common.vot.VotAudioTrackHelper
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxDiagnosticsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxTranslationHardenFinalSuiteTest {

    @Test
    fun testIsSameLanguageExactAndCaseInsensitive() {
        assertTrue(VotAudioTrackHelper.isSameLanguage("ru", "ru"))
        assertTrue(VotAudioTrackHelper.isSameLanguage("RU", "ru"))
        assertTrue(VotAudioTrackHelper.isSameLanguage("en", "EN"))
    }

    @Test
    fun testIsSameLanguageLocalePrefixAndUnderscores() {
        assertTrue(VotAudioTrackHelper.isSameLanguage("en-US", "en-GB"))
        assertTrue(VotAudioTrackHelper.isSameLanguage("en_US", "en-GB"))
        assertTrue(VotAudioTrackHelper.isSameLanguage("ru-RU", "ru"))
        assertTrue(VotAudioTrackHelper.isSameLanguage("ru", "ru-RU"))
    }

    @Test
    fun testIsSameLanguageDistinctOrNull() {
        assertFalse(VotAudioTrackHelper.isSameLanguage("ru", "en"))
        assertFalse(VotAudioTrackHelper.isSameLanguage("es", "it"))
        assertFalse(VotAudioTrackHelper.isSameLanguage(null, "en"))
        assertFalse(VotAudioTrackHelper.isSameLanguage("ru", null))
        assertFalse(VotAudioTrackHelper.isSameLanguage(null, null))
        assertFalse(VotAudioTrackHelper.isSameLanguage("", "en"))
    }

    @Test
    fun testSourceLangAutoMappingLogic() {
        fun resolveEffectiveSourceLang(sourceLang: String, targetLang: String): String {
            return if (VotAudioTrackHelper.isRussianLang(sourceLang) || VotAudioTrackHelper.isSameLanguage(sourceLang, targetLang)) {
                "auto"
            } else if (VotAudioTrackHelper.isUkrainianLang(sourceLang)) {
                "uk"
            } else {
                sourceLang
            }
        }

        assertEquals("auto", resolveEffectiveSourceLang("ru", "ru"))
        assertEquals("auto", resolveEffectiveSourceLang("ru-RU", "ru"))
        assertEquals("auto", resolveEffectiveSourceLang("en", "en"))
        assertEquals("en", resolveEffectiveSourceLang("en", "ru"))
        assertEquals("de", resolveEffectiveSourceLang("de", "ru"))
    }

    @Test
    fun testCanonicalDiagnosticsStatuses() {
        assertEquals(6, VoxDiagnosticsPolicy.CANONICAL_INCIDENT_STATUSES.size)
        assertTrue(VoxDiagnosticsPolicy.CANONICAL_INCIDENT_STATUSES.contains(VoxDiagnosticsPolicy.STATUS_NEW))
        assertTrue(VoxDiagnosticsPolicy.CANONICAL_INCIDENT_STATUSES.contains(VoxDiagnosticsPolicy.STATUS_IN_PROGRESS))
        assertTrue(VoxDiagnosticsPolicy.CANONICAL_INCIDENT_STATUSES.contains(VoxDiagnosticsPolicy.STATUS_FIXED_PENDING_VERIFICATION))
        assertTrue(VoxDiagnosticsPolicy.CANONICAL_INCIDENT_STATUSES.contains(VoxDiagnosticsPolicy.STATUS_RESOLVED))
        assertTrue(VoxDiagnosticsPolicy.CANONICAL_INCIDENT_STATUSES.contains(VoxDiagnosticsPolicy.STATUS_KNOWN_ISSUE))
        assertTrue(VoxDiagnosticsPolicy.CANONICAL_INCIDENT_STATUSES.contains(VoxDiagnosticsPolicy.STATUS_IGNORED_TEST))
    }

    @Test
    fun testNormalizeIncidentStatus() {
        assertEquals(VoxDiagnosticsPolicy.STATUS_NEW, VoxDiagnosticsPolicy.normalizeIncidentStatus(null))
        assertEquals(VoxDiagnosticsPolicy.STATUS_NEW, VoxDiagnosticsPolicy.normalizeIncidentStatus(""))
        assertEquals(VoxDiagnosticsPolicy.STATUS_NEW, VoxDiagnosticsPolicy.normalizeIncidentStatus("new"))
        assertEquals(VoxDiagnosticsPolicy.STATUS_IN_PROGRESS, VoxDiagnosticsPolicy.normalizeIncidentStatus("IN_PROGRESS"))
        assertEquals(VoxDiagnosticsPolicy.STATUS_IN_PROGRESS, VoxDiagnosticsPolicy.normalizeIncidentStatus("reviewed"))
        assertEquals(VoxDiagnosticsPolicy.STATUS_IN_PROGRESS, VoxDiagnosticsPolicy.normalizeIncidentStatus("triaged"))
        assertEquals(VoxDiagnosticsPolicy.STATUS_FIXED_PENDING_VERIFICATION, VoxDiagnosticsPolicy.normalizeIncidentStatus("FIXED"))
        assertEquals(VoxDiagnosticsPolicy.STATUS_FIXED_PENDING_VERIFICATION, VoxDiagnosticsPolicy.normalizeIncidentStatus("pending_verification"))
        assertEquals(VoxDiagnosticsPolicy.STATUS_RESOLVED, VoxDiagnosticsPolicy.normalizeIncidentStatus("RESOLVED"))
        assertEquals(VoxDiagnosticsPolicy.STATUS_RESOLVED, VoxDiagnosticsPolicy.normalizeIncidentStatus("closed"))
        assertEquals(VoxDiagnosticsPolicy.STATUS_KNOWN_ISSUE, VoxDiagnosticsPolicy.normalizeIncidentStatus("KNOWN"))
        assertEquals(VoxDiagnosticsPolicy.STATUS_KNOWN_ISSUE, VoxDiagnosticsPolicy.normalizeIncidentStatus("known_issue"))
        assertEquals(VoxDiagnosticsPolicy.STATUS_IGNORED_TEST, VoxDiagnosticsPolicy.normalizeIncidentStatus("IGNORED"))
        assertEquals(VoxDiagnosticsPolicy.STATUS_IGNORED_TEST, VoxDiagnosticsPolicy.normalizeIncidentStatus("test"))
    }

    @Test
    fun testHistoricalVersionEventFiltering() {
        assertTrue(VoxDiagnosticsPolicy.isHistoricalVersionEvent("32.55-vox.9.0", "32.56-vox.9.1"))
        assertTrue(VoxDiagnosticsPolicy.isHistoricalVersionEvent("30.01", "32.56-vox.9.1"))
        assertFalse(VoxDiagnosticsPolicy.isHistoricalVersionEvent("32.56-vox.9.1", "32.56-vox.9.1"))
        assertFalse(VoxDiagnosticsPolicy.isHistoricalVersionEvent(null, "32.56-vox.9.1"))
        assertFalse(VoxDiagnosticsPolicy.isHistoricalVersionEvent("", "32.56-vox.9.1"))
    }
}
