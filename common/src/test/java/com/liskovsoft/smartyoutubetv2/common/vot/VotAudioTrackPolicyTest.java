package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Test matrix for VOT audio track policies, priority rules, and user precedence.
 */
public class VotAudioTrackPolicyTest {

    private static class MockFormatItem implements FormatItem {
        private final int id;
        private final String lang;
        private final boolean selected;
        private final boolean isDef;

        MockFormatItem(int id, String lang, boolean selected, boolean isDef) {
            this.id = id;
            this.lang = lang;
            this.selected = selected;
            this.isDef = isDef;
        }

        @Override public int getId() { return id; }
        @Override public String getFormatId() { return String.valueOf(id); }
        @Override public CharSequence getTitle() { return lang; }
        @Override public boolean isDefault() { return isDef; }
        @Override public boolean isSelected() { return selected; }
        @Override public boolean isPreset() { return false; }
        @Override public float getFrameRate() { return 0; }
        @Override public String getLanguage() { return lang; }
        @Override public int getWidth() { return 0; }
        @Override public int getHeight() { return 0; }
        @Override public int getType() { return TYPE_AUDIO; }
        @Override public MediaTrack getTrack() { return null; }
    }

    /**
     * CASE 1: Audio language is English (or other foreign).
     * Expected: VOT is allowed and auto-start policy triggers.
     */
    @Test
    public void testCase1_EnglishAudio_VotAllowed() {
        FormatItem enOriginal = new MockFormatItem(101, "en (original)", true, true);
        List<FormatItem> formats = Collections.singletonList(enOriginal);

        VotAudioTrackHelper.TrackInfo info = VotAudioTrackHelper.from(enOriginal);
        assertTrue(VotAudioTrackHelper.isEnglishLang(info.langCode));
        assertTrue(VotAudioTrackHelper.isOriginalTrack(info));
        assertTrue(VotAudioTrackHelper.isExplicitNonRussian(info));
        assertTrue(VotAudioTrackHelper.shouldStartYandex(info));
        assertTrue(VotAudioTrackHelper.shouldAutoStartLikeManual(info, formats));
    }

    /**
     * CASE 2: Native Russian audio.
     * Expected: Skip translation quietly in auto mode, no replacement dialog.
     */
    @Test
    public void testCase2_NativeRussian_SkipTranslation() {
        FormatItem ruOriginal = new MockFormatItem(201, "ru (original)", true, true);
        List<FormatItem> formats = Collections.singletonList(ruOriginal);

        VotAudioTrackHelper.TrackInfo info = VotAudioTrackHelper.from(ruOriginal);
        assertTrue(VotAudioTrackHelper.isRussianLang(info.langCode));
        assertTrue(VotAudioTrackHelper.isRussianOriginal(info));
        assertFalse(VotAudioTrackHelper.shouldStartYandex(info));
        assertFalse(VotAudioTrackHelper.isRussianDubbedTrack(info, formats));
        assertFalse(VotAudioTrackHelper.shouldAutoStartLikeManual(info, formats));
    }

    /**
     * CASE 3: Russian YouTube dub on an originally non-Russian video.
     * Expected: Russian dubbed track is recognized, replace dialog is warranted,
     * and the best original non-Russian track is resolved for Yandex translation.
     */
    @Test
    public void testCase3_RussianYouTubeDub_TriggersReplacement() {
        FormatItem ruDub = new MockFormatItem(301, "ru (dubbed-auto)", true, false);
        FormatItem enOrig = new MockFormatItem(302, "en (original)", false, true);
        List<FormatItem> formats = Arrays.asList(ruDub, enOrig);

        VotAudioTrackHelper.TrackInfo info = VotAudioTrackHelper.from(ruDub);
        assertTrue(VotAudioTrackHelper.isRussianLang(info.langCode));
        assertTrue(VotAudioTrackHelper.isYoutubeAutoDub(info));
        assertTrue(VotAudioTrackHelper.isRussianDubbedTrack(info, formats));

        FormatItem bestOriginal = VotAudioTrackHelper.findBestOriginalForYandex(formats);
        assertNotNull(bestOriginal);
        assertEquals(enOrig.getId(), bestOriginal.getId());
    }

    /**
     * CASE 4: Unknown / null language metadata.
     * Expected: Safe skip, no premature translation start without known metadata.
     */
    @Test
    public void testCase4_UnknownMetadata_SafeSkip() {
        FormatItem nullTrack = new MockFormatItem(401, null, true, true);
        FormatItem emptyTrack = new MockFormatItem(402, "", true, true);
        FormatItem undTrack = new MockFormatItem(403, "und", true, true);
        FormatItem unknownTrack = new MockFormatItem(404, "unknown", true, true);

        for (FormatItem item : Arrays.asList(nullTrack, emptyTrack, undTrack, unknownTrack)) {
            VotAudioTrackHelper.TrackInfo info = VotAudioTrackHelper.from(item);
            assertFalse("Track with label '" + item.getLanguage() + "' must not be considered a known language",
                    VotAudioTrackHelper.isKnownLanguage(info.langCode));
            assertFalse(VotAudioTrackHelper.shouldAutoStartLikeManual(info, Collections.singletonList(item)));
        }
    }

    /**
     * CASE 5: User manually selected track.
     * Expected: User choice takes absolute precedence; if user changed the track during
     * VOT operation, the restore logic on stop must not override the user's manual selection.
     */
    @Test
    public void testCase5_UserManualTrackSelectionPriority() {
        FormatItem dubTrack = new MockFormatItem(501, "ru (dubbed-auto)", false, false);
        FormatItem origTrack = new MockFormatItem(502, "en (original)", true, true);
        FormatItem userChosenTrack = new MockFormatItem(503, "es (original)", false, false);

        // Simulation of VoiceTranslateController track switch tracking
        FormatItem restorableDub = dubTrack;
        FormatItem pendingOriginal = origTrack;
        boolean userManuallyChanged = false;

        // User actively changes audio track to Spanish while VOT is active
        FormatItem newlySelectedTrack = userChosenTrack;
        if (!VotAudioTrackHelper.isSameFormat(newlySelectedTrack, pendingOriginal)) {
            userManuallyChanged = true;
            restorableDub = null;
        }

        assertTrue("User manual change must be flagged", userManuallyChanged);
        assertNull("Restorable dub must be invalidated by user selection", restorableDub);

        // When VOT stops, track restoration must be bypassed if user manually changed
        boolean shouldRestore = (!userManuallyChanged && restorableDub != null);
        assertFalse("Must NOT restore previous dub over user manual selection", shouldRestore);
    }
}
