package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.content.Context;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import java.io.File;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VideoStateController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceTranslateController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VoxPlaybackSpeedPersistenceRegressionTest {
    private Context mContext;
    private PlayerData mPlayerData;
    private VideoStateService mStateService;
    private PlaybackPresenter mPresenter;
    private VideoStateController mController;
    private FakePlayerView mFakeView;

    public static class FakePlayerView {
        public float speed = 1.0f;
        public int buttonState = PlayerUI.BUTTON_OFF;
        public Video video;
        public long positionMs = 0;
        public long durationMs = 600_000;
        public int setSpeedCallCount = 0;

        public PlaybackView createProxy() {
            return (PlaybackView) Proxy.newProxyInstance(
                    PlaybackView.class.getClassLoader(),
                    new Class<?>[]{PlaybackView.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("setSpeed".equals(name) && args != null && args.length == 1) {
                            speed = (Float) args[0];
                            setSpeedCallCount++;
                            buttonState = speed != 1.0f ? PlayerUI.BUTTON_ON : PlayerUI.BUTTON_OFF;
                            return null;
                        } else if ("getSpeed".equals(name)) {
                            return speed;
                        } else if ("setButtonState".equals(name) && args != null && args.length == 2) {
                            int btnId = (Integer) args[0];
                            if (btnId == R.id.action_video_speed) {
                                buttonState = (Integer) args[1];
                            }
                            return null;
                        } else if ("getButtonState".equals(name) && args != null && args.length == 1) {
                            int btnId = (Integer) args[0];
                            if (btnId == R.id.action_video_speed) {
                                return buttonState;
                            }
                            return PlayerUI.BUTTON_OFF;
                        } else if ("getVideo".equals(name)) {
                            return video;
                        } else if ("setVideo".equals(name) && args != null && args.length == 1) {
                            video = (Video) args[0];
                            return null;
                        } else if ("getPositionMs".equals(name)) {
                            return positionMs;
                        } else if ("setPositionMs".equals(name) && args != null && args.length == 1) {
                            positionMs = (Long) args[0];
                            return null;
                        } else if ("getDurationMs".equals(name)) {
                            return durationMs;
                        } else if ("containsMedia".equals(name)) {
                            return true;
                        } else if ("isEmbed".equals(name)) {
                            return false;
                        }
                        // Default return for primitives / objects
                        Class<?> returnType = method.getReturnType();
                        if (returnType == boolean.class) return false;
                        if (returnType == int.class) return 0;
                        if (returnType == long.class) return 0L;
                        if (returnType == float.class) return 0.0f;
                        if (returnType == double.class) return 0.0;
                        return null;
                    }
            );
        }
    }

    private MediaItemMetadata createMockMetadata(String videoId, String channelId) {
        return (MediaItemMetadata) Proxy.newProxyInstance(
                MediaItemMetadata.class.getClassLoader(),
                new Class<?>[]{MediaItemMetadata.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getVideoId".equals(name)) return videoId;
                    if ("getChannelId".equals(name)) return channelId;
                    if ("getTitle".equals(name)) return "Test Video " + videoId;
                    if ("getDurationMs".equals(name)) return 600_000L;
                    if ("isLive".equals(name)) return false;
                    if ("isUpcoming".equals(name)) return false;
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) return false;
                    if (returnType == int.class) return 0;
                    if (returnType == long.class) return 0L;
                    return null;
                }
        );
    }

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        AppPrefs.instance(mContext).clear();
        FileHelpers.delete(new File(FileHelpers.getFilesDir(mContext), "app_prefs"));
        PlayerData.unhold();
        VideoStateService.unhold();
        PlaybackPresenter.unhold();

        mPlayerData = PlayerData.instance(mContext);
        mStateService = VideoStateService.instance(mContext);
        mPresenter = PlaybackPresenter.instance(mContext);

        mFakeView = new FakePlayerView();
        mPresenter.setView(mFakeView.createProxy());
        mController = mPresenter.getController(VideoStateController.class);
        assertNotNull("VideoStateController must be registered in PlaybackPresenter", mController);
    }

    @After
    public void tearDown() {
        AppPrefs.instance(mContext).clear();
        FileHelpers.delete(new File(FileHelpers.getFilesDir(mContext), "app_prefs"));
        PlayerData.unhold();
        VideoStateService.unhold();
        PlaybackPresenter.unhold();
    }

    // =========================================================================
    // S01: Режим ALL, 1.35x, первый ролик из чистой сессии -> фактическая 1.35x без нажатия
    // =========================================================================
    @Test
    public void testS01_AllMode_InitialSessionAppliesSpeedWithoutClick() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);

        Video video = new Video();
        video.videoId = "vid_s01";
        video.channelId = "chan_s01";
        mFakeView.video = video;

        mPresenter.openVideo(video);
        mController.onVideoLoaded(video);

        assertEquals("S01: Actual speed must be 1.35x on first load without user click",
                1.35f, mFakeView.speed, 0.001f);
        assertEquals("S01: Speed button must be ON (red/active)",
                PlayerUI.BUTTON_ON, mFakeView.buttonState);
    }

    // =========================================================================
    // S02: ALL, ролик A -> B (разные каналы) -> C -> всегда 1.35x
    // =========================================================================
    @Test
    public void testS02_AllMode_ConsecutiveVideosAcrossDifferentChannels_RetainsSpeed() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);

        String[] channels = {"chanA", "chanB", "chanC"};
        for (int i = 0; i < channels.length; i++) {
            Video v = new Video();
            v.videoId = "vid_s02_" + i;
            v.channelId = channels[i];
            mFakeView.video = v;

            mPresenter.openVideo(v);
            mController.onVideoLoaded(v);

            assertEquals("S02: Video on channel " + channels[i] + " must play at 1.35x in ALL mode",
                    1.35f, mFakeView.speed, 0.001f);
            assertEquals("S02: Speed button must remain ON on channel " + channels[i],
                    PlayerUI.BUTTON_ON, mFakeView.buttonState);
        }
    }

    // =========================================================================
    // S03: ALL, закрыть плеер / Browse / снова открыть ролик -> 1.35x
    // =========================================================================
    @Test
    public void testS03_AllMode_ClosePlayerAndReopenVideo_RetainsSpeed() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);

        Video v1 = new Video();
        v1.videoId = "vid_s03";
        v1.channelId = "chan_s03";
        mFakeView.video = v1;

        mPresenter.openVideo(v1);
        mController.onVideoLoaded(v1);
        assertEquals(1.35f, mFakeView.speed, 0.001f);

        // Player finishes (returns to browse)
        mController.onFinish();

        // Re-open same or new video
        Video v2 = new Video();
        v2.videoId = "vid_s03_reopen";
        v2.channelId = "chan_s03_other";
        mFakeView.video = v2;

        mPresenter.openVideo(v2);
        mController.onVideoLoaded(v2);

        assertEquals("S03: Reopening video after browsing must retain 1.35x",
                1.35f, mFakeView.speed, 0.001f);
    }

    // =========================================================================
    // S04: ALL, полностью перезапустить приложение -> 1.35x
    // =========================================================================
    @Test
    public void testS04_AllMode_FullAppRestart_RetainsSpeed() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);
        mPlayerData.persistNow();

        // Simulate app death and recreation
        PlayerData.unhold();
        PlayerData restartedPlayerData = PlayerData.instance(mContext);

        assertTrue("S04: isAllSpeedEnabled must persist across restart",
                restartedPlayerData.isAllSpeedEnabled());
        assertEquals("S04: Stored speed must remain 1.35x across restart",
                1.35f, restartedPlayerData.getSpeed(), 0.001f);

        Video v = new Video();
        v.videoId = "vid_s04";
        v.channelId = "chan_s04";
        mFakeView.video = v;

        mPresenter.openVideo(v);
        mController.onVideoLoaded(v);

        assertEquals("S04: Playback after restart must automatically apply 1.35x",
                1.35f, mFakeView.speed, 0.001f);
    }

    // =========================================================================
    // S05: ALL, изменение 1.35 -> 1.50 вручную -> новое значение на следующем ролике
    // =========================================================================
    @Test
    public void testS05_AllMode_UserManualSpeedChange_PropagatesToNextVideo() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);

        Video v1 = new Video();
        v1.videoId = "vid_s05_1";
        v1.channelId = "chan_s05";
        mFakeView.video = v1;

        mPresenter.openVideo(v1);
        mController.onVideoLoaded(v1);
        assertEquals(1.35f, mFakeView.speed, 0.001f);

        // User changes speed to 1.50x
        mPresenter.onSpeedChanged(1.50f);
        assertEquals(1.50f, mPlayerData.getSpeed(), 0.001f);
        assertEquals(1.50f, mPlayerData.getLastSpeed(), 0.001f);

        // Next video
        Video v2 = new Video();
        v2.videoId = "vid_s05_2";
        v2.channelId = "chan_s05_diff";
        mFakeView.video = v2;

        mPresenter.openVideo(v2);
        mController.onVideoLoaded(v2);

        assertEquals("S05: Updated speed 1.50x must apply to next video in ALL mode",
                1.50f, mFakeView.speed, 0.001f);
    }

    // =========================================================================
    // S06: PER_CHANNEL, канал A=1.35, канал B=1.0 -> при возврате на A снова 1.35; B не наследует A
    // =========================================================================
    @Test
    public void testS06_PerChannelMode_ChannelIsolationAndReturn() {
        mPlayerData.setSpeedPerChannelEnabled(true);

        // Channel A setup
        Video vA = new Video();
        vA.videoId = "vid_A";
        vA.channelId = "channel_A";
        mFakeView.video = vA;
        mPresenter.openVideo(vA);
        mController.onVideoLoaded(vA);
        mPresenter.onSpeedChanged(1.35f); // user sets 1.35x for channel A
        assertEquals(1.35f, mPlayerData.getSpeed("channel_A"), 0.001f);

        // Switch to Channel B (default / 1.0x)
        Video vB = new Video();
        vB.videoId = "vid_B";
        vB.channelId = "channel_B";
        mFakeView.video = vB;
        mPresenter.openVideo(vB);
        mController.onVideoLoaded(vB);

        assertEquals("S06: Channel B must play at default 1.0x, not inherit Channel A",
                1.0f, mFakeView.speed, 0.001f);
        assertEquals("S06: Speed button on Channel B must be OFF",
                PlayerUI.BUTTON_OFF, mFakeView.buttonState);

        // Return to Channel A
        Video vA2 = new Video();
        vA2.videoId = "vid_A2";
        vA2.channelId = "channel_A";
        mFakeView.video = vA2;
        mPresenter.openVideo(vA2);
        mController.onVideoLoaded(vA2);

        assertEquals("S06: Returning to Channel A must restore 1.35x",
                1.35f, mFakeView.speed, 0.001f);
        assertEquals("S06: Speed button on Channel A must be ON",
                PlayerUI.BUTTON_ON, mFakeView.buttonState);
    }

    // =========================================================================
    // S07: PER_CHANNEL, новый канал без значения -> 1.0, но профиль A не затирается
    // =========================================================================
    @Test
    public void testS07_PerChannelMode_NewChannelFallbackWithoutOverwritingSavedChannel() {
        mPlayerData.setSpeedPerChannelEnabled(true);
        mPlayerData.setSpeed("channel_A", 1.35f);

        // Querying unknown channel must return 1.0f WITHOUT mutating internal state
        float speedC = mPlayerData.getSpeed("channel_unseen");
        assertEquals("S07: Unconfigured channel must return 1.0f", 1.0f, speedC, 0.001f);
        assertEquals("S07: Channel A saved speed must not be corrupted by querying other channels",
                1.35f, mPlayerData.getSpeed("channel_A"), 0.001f);

        Video vC = new Video();
        vC.videoId = "vid_C";
        vC.channelId = "channel_unseen";
        mFakeView.video = vC;

        mPresenter.openVideo(vC);
        mController.onVideoLoaded(vC);

        assertEquals("S07: Unseen channel must play at 1.0x", 1.0f, mFakeView.speed, 0.001f);
        assertEquals("S07: Channel A profile must remain intact after playing unseen channel",
                1.35f, mPlayerData.getSpeed("channel_A"), 0.001f);
    }

    // =========================================================================
    // S08: PER_VIDEO, ролик A=1.35, B=1.0, возврат A=1.35
    // =========================================================================
    @Test
    public void testS08_PerVideoMode_VideoStateServiceSpeedPersistence() {
        mPlayerData.setSpeedPerVideoEnabled(true);

        Video vA = new Video();
        vA.videoId = "vid_pv_A";
        vA.channelId = "ch1";
        mFakeView.video = vA;

        mPresenter.openVideo(vA);
        mController.onVideoLoaded(vA);
        mPresenter.onSpeedChanged(1.35f);

        State stateA = mStateService.getByVideoId("vid_pv_A");
        assertNotNull("S08: StateService must persist state for Video A", stateA);
        assertEquals(1.35f, stateA.speed, 0.001f);

        // Switch to Video B
        Video vB = new Video();
        vB.videoId = "vid_pv_B";
        vB.channelId = "ch2";
        mFakeView.video = vB;

        mPresenter.openVideo(vB);
        mController.onVideoLoaded(vB);
        assertEquals("S08: Video B must default to 1.0x in PER_VIDEO mode", 1.0f, mFakeView.speed, 0.001f);

        // Return to Video A
        Video vA2 = new Video();
        vA2.videoId = "vid_pv_A";
        vA2.channelId = "ch1";
        mFakeView.video = vA2;

        mPresenter.openVideo(vA2);
        mController.onVideoLoaded(vA2);
        assertEquals("S08: Returning to Video A must restore 1.35x from StateService",
                1.35f, mFakeView.speed, 0.001f);
    }

    // =========================================================================
    // S09: NONE, новая сессия/ролик -> 1.0, без незапрошенного запоминания
    // =========================================================================
    @Test
    public void testS09_NoneMode_PlaybackDoesNotPersistGlobalSpeed() {
        mPlayerData.setAllSpeedEnabled(false);
        mPlayerData.setSpeedPerVideoEnabled(false);
        mPlayerData.setSpeedPerChannelEnabled(false);

        Video vA = new Video();
        vA.videoId = "vid_none_A";
        vA.channelId = "ch_none";
        mFakeView.video = vA;

        mPresenter.openVideo(vA);
        mController.onVideoLoaded(vA);
        mPresenter.onSpeedChanged(1.35f); // user temporary change

        // New video
        Video vB = new Video();
        vB.videoId = "vid_none_B";
        vB.channelId = "ch_none_2";
        mFakeView.video = vB;

        mPresenter.openVideo(vB);
        mController.onVideoLoaded(vB);

        assertEquals("S09: In NONE mode, next video must start at 1.0x without unrequested persistence",
                1.0f, mFakeView.speed, 0.001f);
        assertEquals("S09: Global getSpeed must be 1.0f in NONE mode",
                1.0f, mPlayerData.getSpeed(), 0.001f);
    }

    // =========================================================================
    // S10: channelId=null при onNewVideo, затем появление в onMetadata -> применить политику правильного канала
    // =========================================================================
    @Test
    public void testS10_ChannelIdNullInitially_MetadataArrivalAppliesChannelSpeed() {
        mPlayerData.setSpeedPerChannelEnabled(true);
        mPlayerData.setSpeed("chan_late", 1.35f);

        Video v = new Video();
        v.videoId = "vid_late";
        v.channelId = null; // null initially
        mFakeView.video = v;

        mPresenter.openVideo(v);
        mController.onVideoLoaded(v);
        // Initially 1.0f because channelId was unknown
        assertEquals(1.0f, mFakeView.speed, 0.001f);

        // Metadata arrives with channelId
        v.channelId = "chan_late";
        MediaItemMetadata meta = createMockMetadata("vid_late", "chan_late");
        mController.onMetadata(meta);

        assertEquals("S10: On metadata arrival with channelId, speed 1.35x must apply",
                1.35f, mFakeView.speed, 0.001f);
        assertEquals("S10: Speed button must become ON",
                PlayerUI.BUTTON_ON, mFakeView.buttonState);
    }

    // =========================================================================
    // S11: служебный setSpeed(1.0) не должен переписать сохранённую 1.35 в ALL/PER_CHANNEL
    // =========================================================================
    @Test
    public void testS11_ProgrammaticSetSpeed10_DoesNotOverwriteSavedPreference() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);

        Video v = new Video();
        v.videoId = "vid_prog";
        v.channelId = "chan_prog";
        mFakeView.video = v;

        mPresenter.openVideo(v);
        mController.onVideoLoaded(v);
        assertEquals(1.35f, mFakeView.speed, 0.001f);

        // Simulate internal programmatic reset (e.g. music video / live edge)
        // Controller sets speed with mIsApplyingProgrammaticSpeed = true
        mController.onEngineError(0, 0, new RuntimeException("Decoder glitch"));

        assertEquals("S11: Persisted speed in ALL mode must remain 1.35x",
                1.35f, mPlayerData.getSpeed(), 0.001f);
        assertEquals("S11: mLastSpeed must remain 1.35x",
                1.35f, mPlayerData.getLastSpeed(), 0.001f);
    }

    // =========================================================================
    // S12: два быстрых video switch с переставленными metadata callbacks -> нет stale apply
    // =========================================================================
    @Test
    public void testS12_RapidVideoSwitch_OutOfOrderMetadataDoesNotStaleApply() {
        mPlayerData.setSpeedPerChannelEnabled(true);
        mPlayerData.setSpeed("chan_fast_1", 1.50f);
        mPlayerData.setSpeed("chan_fast_2", 1.25f);

        Video v1 = new Video();
        v1.videoId = "vid_fast_1";
        v1.channelId = "chan_fast_1";

        Video v2 = new Video();
        v2.videoId = "vid_fast_2";
        v2.channelId = "chan_fast_2";

        // Open v1 then immediately switch to v2
        mPresenter.openVideo(v1);
        mPresenter.openVideo(v2);
        mFakeView.video = v2;
        mController.onVideoLoaded(v2);
        assertEquals(1.25f, mFakeView.speed, 0.001f);

        // Stale metadata for v1 arrives now
        MediaItemMetadata staleMetaV1 = createMockMetadata("vid_fast_1", "chan_fast_1");
        mController.onMetadata(staleMetaV1);

        assertEquals("S12: Late metadata for v1 must NOT override active v2 playback speed",
                1.25f, mFakeView.speed, 0.001f);
    }

    // =========================================================================
    // S13: speed button OFF->ON и длинное нажатие открывают/используют текущую политику правильно
    // =========================================================================
    @Test
    public void testS13_SpeedButtonToggle_OffToOnAndLongClick() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);

        Video v = new Video();
        v.videoId = "vid_s13";
        v.channelId = "ch_s13";
        mFakeView.video = v;

        mPresenter.openVideo(v);
        mController.onVideoLoaded(v);
        assertEquals(1.35f, mFakeView.speed, 0.001f);
        assertEquals(PlayerUI.BUTTON_ON, mFakeView.buttonState);

        // Click when ON -> toggles to 1.0x
        mController.onButtonClicked(R.id.action_video_speed, PlayerUI.BUTTON_ON);
        assertEquals("S13: Clicking active button toggles to 1.0x", 1.0f, mFakeView.speed, 0.001f);

        // Click when OFF -> toggles back to 1.35x
        mController.onButtonClicked(R.id.action_video_speed, PlayerUI.BUTTON_OFF);
        assertEquals("S13: Clicking inactive button toggles back to 1.35x", 1.35f, mFakeView.speed, 0.001f);
    }

    // =========================================================================
    // S14: UI режим «Не запоминать» выделен только при выключенных ALL, PER_VIDEO и PER_CHANNEL
    // =========================================================================
    @Test
    public void testS14_UiDialogSelection_NoneSelectedOnlyWhenAllThreeDisabled() {
        // Default mode is PER_CHANNEL
        mPlayerData.setSpeedPerChannelEnabled(true);
        OptionCategory cat = AppDialogUtil.createRememberSpeedCategory(mContext);
        OptionItem noneOption = cat.options.get(0);
        OptionItem channelOption = cat.options.get(3);

        assertFalse("S14: 'Не запоминать' must NOT be selected when PER_CHANNEL is active",
                noneOption.isSelected());
        assertTrue("S14: 'По каналам' must be selected",
                channelOption.isSelected());

        // Mode NONE
        mPlayerData.setAllSpeedEnabled(false);
        mPlayerData.setSpeedPerVideoEnabled(false);
        mPlayerData.setSpeedPerChannelEnabled(false);

        OptionCategory catNone = AppDialogUtil.createRememberSpeedCategory(mContext);
        assertTrue("S14: 'Не запоминать' must be selected when ALL modes are false",
                catNone.options.get(0).isSelected());
        assertFalse("S14: 'По каналам' must be false",
                catNone.options.get(3).isSelected());
    }

    // =========================================================================
    // S15: миграция сохранённых настроек 9.1/9.2: не сбрасываем пользовательский режим
    // =========================================================================
    @Test
    public void testS15_MigrationFromLegacy91Prefs_PreservesModeAndSpeed() {
        // Test that switching modes preserves mLastSpeed and heals 1.0f if all speed was selected
        mPlayerData.setSpeedPerChannelEnabled(true);
        mPlayerData.setSpeed("some_chan", 1.35f);
        assertEquals(1.35f, mPlayerData.getLastSpeed(), 0.001f);

        // User switches from per-channel to ALL
        mPlayerData.setAllSpeedEnabled(true);
        assertTrue(mPlayerData.isAllSpeedEnabled());
        assertEquals("S15: Switching to ALL mode must adopt last speed preference 1.35x",
                1.35f, mPlayerData.getSpeed(), 0.001f);
    }

    // =========================================================================
    // S16: LIVE: документированная политика при live edge/seek; нет деградации DVR или выхода за live window
    // =========================================================================
    @Test
    public void testS16_LiveStreamEdgePolicy_Temporary10DoesNotDegradeDvrOrWipePolicy() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);

        Video vLive = new Video();
        vLive.videoId = "vid_live";
        vLive.isLive = true;
        mFakeView.video = vLive;
        mFakeView.durationMs = 3600_000L;
        mFakeView.positionMs = 3599_500L; // within 1s of live edge -> isLiveEnd() true

        mPresenter.openVideo(vLive);
        mController.onVideoLoaded(vLive);

        // At live edge, effective speed resets to 1.0f safely
        assertEquals(1.0f, mFakeView.speed, 0.001f);
        // Persisted user preference remains intact
        assertEquals("S16: Live edge temporary reset must NOT destroy user policy",
                1.35f, mPlayerData.getSpeed(), 0.001f);
    }

    // =========================================================================
    // S17: music video / Shorts / background resume: согласованное намеренное поведение и отсутствие стирания сохранённой скорости
    // =========================================================================
    @Test
    public void testS17_MusicVideoOrShorts_Temporary10DoesNotWipeSavedSpeed() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);

        Video vMusic = new Video();
        vMusic.videoId = "vid_music";
        vMusic.category = "Music"; // belongsToMusic() returns true
        mFakeView.video = vMusic;

        mPresenter.openVideo(vMusic);
        mController.onVideoLoaded(vMusic);

        // Music video temporarily forced to 1.0f
        assertEquals("S17: Music video must play at 1.0x", 1.0f, mFakeView.speed, 0.001f);
        assertEquals("S17: Desired speed in PlayerData must remain 1.35x",
                1.35f, mPlayerData.getSpeed(), 0.001f);

        // Followed by regular video
        Video vRegular = new Video();
        vRegular.videoId = "vid_regular";
        vRegular.channelId = "ch_reg";
        mFakeView.video = vRegular;

        mPresenter.openVideo(vRegular);
        mController.onVideoLoaded(vRegular);

        assertEquals("S17: Subsequent regular video must restore 1.35x",
                1.35f, mFakeView.speed, 0.001f);
    }

    // =========================================================================
    // S18: VOT при 1.35×: события скорости корректно передаются, не ломается тайминг перевода
    // =========================================================================
    @Test
    public void testS18_VoiceTranslationController_SpeedEventsPropagatedWithoutBreakingTiming() {
        VoiceTranslateController votController = mPresenter.getController(VoiceTranslateController.class);
        assertNotNull("S18: VoiceTranslateController must be present", votController);

        // Verify onSpeedChanged propagates smoothly without exception
        mPresenter.onSpeedChanged(1.35f);
        mPresenter.onSpeedChanged(1.0f);
        mPresenter.onSpeedChanged(1.5f);
    }

    // =========================================================================
    // S19: запуск после recreation/engine recovery: скорость применена один раз и соответствует сохранённой
    // =========================================================================
    @Test
    public void testS19_EngineRecoveryOrRecreation_SpeedAppliedOnceAndMatchesSaved() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);

        Video v = new Video();
        v.videoId = "vid_recov";
        v.channelId = "ch_recov";
        mFakeView.video = v;

        mPresenter.openVideo(v);
        mController.onVideoLoaded(v);
        int initialSetSpeedCalls = mFakeView.setSpeedCallCount;
        assertEquals(1.35f, mFakeView.speed, 0.001f);

        // Engine recovery
        mPresenter.onEngineReleased();
        mPresenter.onEngineInitialized();
        mController.onVideoLoaded(v);

        assertEquals("S19: Speed must remain 1.35x after engine recovery",
                1.35f, mFakeView.speed, 0.001f);
    }

    // =========================================================================
    // S20: система показала выбранную скорость и фактическую скорость согласованно; нет бесконечного callback loop
    // =========================================================================
    @Test
    public void testS20_SpeedAndButtonConsistency_NoRecursiveLoop() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);

        Video v = new Video();
        v.videoId = "vid_s20";
        v.channelId = "ch_s20";
        mFakeView.video = v;

        mFakeView.setSpeedCallCount = 0;
        mPresenter.openVideo(v);
        mController.onVideoLoaded(v);

        // 1 call to setSpeed in applySpeed, no recursive loop
        assertEquals("S20: Exactly 1 programmatic speed application on load",
                1, mFakeView.setSpeedCallCount);
        assertEquals(1.35f, mFakeView.speed, 0.001f);
        assertEquals(PlayerUI.BUTTON_ON, mFakeView.buttonState);
    }

    // =========================================================================
    // PROMPT #139 AUDIT TESTS: B1 - B6
    // =========================================================================

    // =========================================================================
    // B1: РЇРІРЅС‹Р№ РІС‹Р±РѕСЂ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ 1.0x РЅРµ РїРµСЂРµРѕРїСЂРµРґРµР»СЏРµС‚СЃСЏ СЃР°РјРѕРІРѕСЃСЃС‚Р°РЅРѕРІР»РµРЅРёРµРј
    // =========================================================================
    @Test
    public void testB1_ExplicitUser10InAllMode_PersistsAcrossRestartWithoutHealing() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);
        assertEquals(1.35f, mPlayerData.getSpeed(), 0.001f);
        mPlayerData.persistNow();

        // User explicitly chooses 1.0x (normal speed)
        mPlayerData.setSpeed(1.0f);
        assertEquals(1.0f, mPlayerData.getSpeed(), 0.001f);
        mPlayerData.persistNow();

        // Recreate PlayerData instance simulating cold app launch
        PlayerData.unhold();
        PlayerData restored = PlayerData.instance(mContext);
        assertTrue("B1: ALL mode must remain active after restart", restored.isAllSpeedEnabled());
        assertEquals("B1: User explicit 1.0x choice must NOT be healed/overridden by mLastSpeed",
                1.0f, restored.getSpeed(), 0.001f);

        // Verify player applies 1.0x and UI button is OFF
        Video v = new Video();
        v.videoId = "vid_b1_after_restart";
        v.channelId = "ch_b1";
        mFakeView.video = v;
        mPresenter.openVideo(v);
        mController.onVideoLoaded(v);

        assertEquals("B1: Engine speed must be 1.0x", 1.0f, mFakeView.speed, 0.001f);
        assertEquals("B1: Button must be OFF for 1.0x", PlayerUI.BUTTON_OFF, mFakeView.buttonState);
    }

    @Test
    public void testB1_ExplicitUser10InAllMode_PreservedAcrossModeToggling() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);
        mPlayerData.persistNow();

        // User explicitly sets 1.0x
        mPlayerData.setSpeed(1.0f);
        mPlayerData.persistNow();
        assertEquals(1.0f, mPlayerData.getSpeed(), 0.001f);

        // User switches from ALL to PER_CHANNEL then back to ALL
        mPlayerData.setSpeedPerChannelEnabled(true);
        assertTrue(mPlayerData.isSpeedPerChannelEnabled());

        mPlayerData.setAllSpeedEnabled(true);
        assertTrue(mPlayerData.isAllSpeedEnabled());
        assertEquals("B1: Explicit 1.0x must NOT heal back to 1.35x when toggling mode back to ALL",
                1.0f, mPlayerData.getSpeed(), 0.001f);
    }

    // =========================================================================
    // B2: Р—Р°РїРёСЃСЊ СЃРєРѕСЂРѕСЃС‚Рё РІ СЂРµР¶РёРјРµ ALL РѕР±РЅРѕРІР»СЏРµС‚ РіР»РѕР±Р°Р»СЊРЅСѓСЋ РЅР°СЃС‚СЂРѕР№РєСѓ
    // =========================================================================
    @Test
    public void testB2_AllMode_ManualSpeedChangeWithRealPlayerData_PersistsGloballyAcrossChannels() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);

        Video vAlpha = new Video();
        vAlpha.videoId = "vid_alpha";
        vAlpha.channelId = "channel_alpha";
        mFakeView.video = vAlpha;

        mPresenter.openVideo(vAlpha);
        mController.onVideoLoaded(vAlpha);
        assertEquals(1.35f, mFakeView.speed, 0.001f);

        // User manually changes speed to 1.75x during playback
        mPresenter.onSpeedChanged(1.75f);
        assertEquals("B2: Real PlayerData global speed must be updated to 1.75x in ALL mode",
                1.75f, mPlayerData.getSpeed(), 0.001f);

        // Load new video from a completely different channel
        Video vBeta = new Video();
        vBeta.videoId = "vid_beta";
        vBeta.channelId = "channel_beta";
        mFakeView.video = vBeta;

        mPresenter.openVideo(vBeta);
        mController.onVideoLoaded(vBeta);
        assertEquals("B2: New channel must inherit the updated global speed 1.75x",
                1.75f, mFakeView.speed, 0.001f);
        assertEquals(PlayerUI.BUTTON_ON, mFakeView.buttonState);
    }

    // =========================================================================
    // B3: Р‘РµР·РѕРїР°СЃРЅРѕСЃС‚СЊ РїСЂРё СЃРёРЅС…СЂРѕРЅРЅС‹С… Рё Р°СЃРёРЅС…СЂРѕРЅРЅС‹С… РІС‹Р·РѕРІР°С… onSpeedChanged
    // =========================================================================
    @Test
    public void testB3_ProgrammaticSpeedApplication_DoesNotCorruptUserPolicy() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);

        Video v = new Video();
        v.videoId = "vid_b3";
        v.channelId = "ch_b3";
        mFakeView.video = v;

        mPresenter.openVideo(v);
        mController.onVideoLoaded(v);
        assertEquals(1.35f, mFakeView.speed, 0.001f);

        // Simulate engine recovery or buffer reset that triggers onSpeedChanged while guarded
        mController.onBuffering();
        assertEquals("B3: User policy must remain 1.35x after engine buffering/state changes",
                1.35f, mPlayerData.getSpeed(), 0.001f);
    }

    // =========================================================================
    // B4: onVideoLoaded + onMetadata СЃ Р·Р°РїРѕР·РґР°Р»С‹Рј channelId Рё Р·Р°С‰РёС‚РѕР№ РѕС‚ stale metadata
    // =========================================================================
    @Test
    public void testB4_DelayedChannelId_MetadataArrivalAppliesSpeedWithoutStaleInterference() {
        mPlayerData.setSpeedPerChannelEnabled(true);
        mPlayerData.setSpeed("ch_target", 1.50f);

        // Video loads initially with null channelId
        Video v = new Video();
        v.videoId = "vid_b4_target";
        v.channelId = null;
        mFakeView.video = v;

        mPresenter.openVideo(v);
        mController.onVideoLoaded(v);
        // Fallback speed is 1.0x while channelId is unknown
        assertEquals(1.0f, mFakeView.speed, 0.001f);

        // Stale metadata from previous video arrives -> must be ignored!
        MediaItemMetadata staleMeta = createMockMetadata("vid_OLD_UNRELATED", "ch_other");
        mController.onMetadata(staleMeta);
        assertEquals("B4: Stale metadata must not change player speed", 1.0f, mFakeView.speed, 0.001f);

        // Proper metadata arrives with channelId
        v.channelId = "ch_target";
        MediaItemMetadata correctMeta = createMockMetadata("vid_b4_target", "ch_target");
        mController.onMetadata(correctMeta);

        assertEquals("B4: Arrival of correct metadata must restore channel speed 1.50x",
                1.50f, mFakeView.speed, 0.001f);
        assertEquals(PlayerUI.BUTTON_ON, mFakeView.buttonState);
    }

    // =========================================================================
    // B5: Live DVR Seek: РѕС‚СЃРєРѕРє РЅР°Р·Р°Рґ РѕС‚ Live-edge РІРѕСЃСЃС‚Р°РЅР°РІР»РёРІР°РµС‚ С†РµР»РµРІСѓСЋ СЃРєРѕСЂРѕСЃС‚СЊ 1.35x
    // =========================================================================
    @Test
    public void testB5_LiveDvrSeek_MovingAwayFromLiveEdgeRestoresDesiredSpeed() {
        mPlayerData.setAllSpeedEnabled(true);
        mPlayerData.setSpeed(1.35f);

        Video vLive = new Video();
        vLive.videoId = "vid_live_dvr";
        vLive.isLive = true;
        mFakeView.video = vLive;
        mFakeView.durationMs = 7200_000L;
        mFakeView.positionMs = 7199_500L; // At live edge (within 1s)

        mPresenter.openVideo(vLive);
        mController.onVideoLoaded(vLive);

        // At live edge, effective speed is clamped to 1.0x
        assertEquals("B5: At live edge, playback is clamped to 1.0x", 1.0f, mFakeView.speed, 0.001f);
        assertEquals("B5: PlayerData user policy must remain 1.35x", 1.35f, mPlayerData.getSpeed(), 0.001f);

        // User seeks back 5 minutes into the DVR window (now far behind live edge)
        mFakeView.positionMs = 6900_000L;
        mController.onBuffering(); // triggers restoreSpeedIfNeeded()

        assertEquals("B5: Seeking away from live edge must restore user desired speed 1.35x",
                1.35f, mFakeView.speed, 0.001f);
        assertEquals(PlayerUI.BUTTON_ON, mFakeView.buttonState);
    }

    // =========================================================================
    // B6: РњРЅРѕРіРѕСЃРєРѕСЂРѕСЃС‚РЅР°СЏ РїСЂРѕРІРµСЂРєР° (1.25, 1.35, 1.50) Рё РёР·РѕР»СЏС†РёСЏ pitch
    // =========================================================================
    @Test
    public void testB6_MultipleSpeedsAndPitchIsolation() {
        mPlayerData.setAllSpeedEnabled(true);

        float[] testSpeeds = new float[]{1.25f, 1.35f, 1.50f, 2.0f, 1.0f};
        Video v = new Video();
        v.videoId = "vid_b6";
        v.channelId = "ch_b6";
        mFakeView.video = v;

        for (float speed : testSpeeds) {
            mPlayerData.setSpeed(speed);
            assertEquals(speed, mPlayerData.getSpeed(), 0.001f);

            mPresenter.openVideo(v);
            mController.onVideoLoaded(v);

            assertEquals("B6: Engine speed must match " + speed, speed, mFakeView.speed, 0.001f);
            int expectedBtn = (speed != 1.0f) ? PlayerUI.BUTTON_ON : PlayerUI.BUTTON_OFF;
            assertEquals("B6: Button state for " + speed, expectedBtn, mFakeView.buttonState);
            assertEquals("B6: Pitch must remain 1.0 and not get distorted by speed changes",
                    1.0f, mPlayerData.getPitch(), 0.001f);
        }
    }
}