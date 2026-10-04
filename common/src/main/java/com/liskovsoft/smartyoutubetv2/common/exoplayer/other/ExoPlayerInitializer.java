package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.SeekParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.ExoMediaDrm.KeyRequest;
import com.google.android.exoplayer2.drm.ExoMediaDrm.ProvisionRequest;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.drm.MediaDrmCallback;
import com.google.android.exoplayer2.drm.UnsupportedDrmException;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.liskovsoft.sharedutils.helpers.DeviceHelpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import java.util.UUID;

public class ExoPlayerInitializer {
    private final int mMaxBufferBytes;
    private final PlayerData mPlayerData;
    private final PlayerTweaksData mPlayerTweaksData;
    private VolumeBooster mVolumeBooster;
    private float mVolumeBoost;
    private SimpleExoPlayer mPlayer;
    private static AudioAttributes sAudioAttributes;

    private final Context mContext;

    public ExoPlayerInitializer(Context context) {
        mContext = context.getApplicationContext();
        mPlayerData = PlayerData.instance(context);
        mPlayerTweaksData = PlayerTweaksData.instance(context);

        long deviceRam = DeviceHelpers.getDeviceRam(context);

        // If ram is too big, bigger then max int value DeviceRam will return a negative number...
        // use 196MB as that can only happens if device has more than 17GB of RAM, so 196 is enough and safe
        // https://github.com/yuliskov/SmartYouTubeTV/issues/532
        mMaxBufferBytes = deviceRam <= 0 ? 196_000_000 : (int)(deviceRam / 18);
    }

    public SimpleExoPlayer createPlayer(Context context, DefaultRenderersFactory renderersFactory, DefaultTrackSelector trackSelector) {
        DefaultLoadControl loadControl = createLoadControl();

        // HDR fix?
        //trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingAudioSessionId(C.generateAudioSessionIdV21(context)));

        // Old initializer
        SimpleExoPlayer player = ExoPlayerFactory.newSimpleInstance(context, renderersFactory, trackSelector, loadControl);

        // New initializer
        //SimpleExoPlayer player = ExoPlayerFactory.newSimpleInstance(
        //        context, renderersFactory, trackSelector, loadControl,
        //        null, new DummyBandwidthMeter(), new AnalyticsCollector.Factory(), Util.getLooper()
        //);

        //enableAudioFocus(player);

        // Lead to numbered errors
        //player.setRepeatMode(Player.REPEAT_MODE_ONE);

        // Fix still image while audio is playing (happens after format change or exit from sleep)
        //player.setPlayWhenReady(true);

        applyPlaybackFixes(player);

        setupAudioFocus(player);

        setupVolumeBoost(player);

        mPlayer = player;

        return player;
    }

    private static AudioAttributes getAudioAttributes() {
        if (sAudioAttributes == null) {
            sAudioAttributes = new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.CONTENT_TYPE_MOVIE)
                    .build();
        }

        return sAudioAttributes;
    }

    /**
     * Increase player's min/max buffer size to 60 secs
     * @return load control
     */
    private DefaultLoadControl createLoadControl() {
        DefaultLoadControl.Builder baseBuilder = new DefaultLoadControl.Builder();

        com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxBufferConfiguration voxCfg =
                com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPlaybackBufferPolicy.resolve(mContext, false, false, 0);

        int minBufferMs = voxCfg.getMinBufferMs();
        int maxBufferMs = voxCfg.getMaxBufferMs();
        int bufferForPlaybackMs = voxCfg.getBufferForPlaybackMs();
        int bufferForPlaybackAfterRebufferMs = voxCfg.getBufferForPlaybackAfterRebufferMs();

        switch (mPlayerData.getVideoBufferType()) {
            case PlayerData.BUFFER_HIGHEST:
                minBufferMs = 50_000;
                maxBufferMs = 100_000;
                baseBuilder.setTargetBufferBytes(mMaxBufferBytes);
                baseBuilder.setBackBuffer(minBufferMs, true);
                break;
            case PlayerData.BUFFER_HIGH:
                minBufferMs = 50_000;
                maxBufferMs = 50_000;
                baseBuilder.setBackBuffer(minBufferMs, true);
                break;
            case PlayerData.BUFFER_MEDIUM:
                baseBuilder.setTargetBufferBytes(voxCfg.getTargetBufferBytes());
                if (voxCfg.getBackBufferMs() > 0) {
                    baseBuilder.setBackBuffer(voxCfg.getBackBufferMs(), true);
                }
                break;
            case PlayerData.BUFFER_LOW:
                minBufferMs = 5_000; // LIVE fix
                maxBufferMs = 5_000; // LIVE fix
                break;
        }

        baseBuilder
                .setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs);

        return baseBuilder.createDefaultLoadControl();
    }

    private void setupVolumeBoost(SimpleExoPlayer player) {
        // 5.1 audio cannot be boosted (format isn't supported error)
        // also, other 2.0 tracks in 5.1 group is already too loud. so cancel them too.
        float volume = mPlayerTweaksData.isPlayerAutoVolumeEnabled() ? mPlayerData.getPlayerVolume() * 2.0f : mPlayerData.getPlayerVolume();
        if (volume > 1f && Build.VERSION.SDK_INT >= 19) {
            mVolumeBooster = new VolumeBooster(true, volume, player);
            player.addAudioListener(mVolumeBooster);
        }
        mVolumeBoost = Math.max(volume, 1f);
    }

    /**
     * Manage audio focus. E.g. use Spotify when audio is disabled.
     */
    private void setupAudioFocus(SimpleExoPlayer player) {
        if (player != null && mPlayerTweaksData.isAudioFocusEnabled()) {
            try {
                player.setAudioAttributes(getAudioAttributes(), true);
            } catch (SecurityException e) { // uid 10390 not allowed to perform TAKE_AUDIO_FOCUS
                e.printStackTrace();
            }
        }
    }

    private void applyPlaybackFixes(SimpleExoPlayer player) {
        // Try to fix decoder error on Nvidia Shield 2019.
        // Init resources as early as possible.
        //player.setForegroundMode(true);
        // NOTE: Avoid using seekParameters. ContentBlock hangs because of constant skipping to the segment start.
        // ContentBlock hangs on the last segment: https://www.youtube.com/watch?v=pYymRbfjKv8

        // Fix seeking on TextureView (some devices only)
        if (mPlayerTweaksData.isTextureViewEnabled()) {
            // Also, live stream (dash) seeking fix
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
        }
    }

    public float getVolumeBoost() {
        return mVolumeBoost;
    }

    public void release() {
        if (mVolumeBooster != null) {
            mVolumeBooster.release();
        }

        if (mPlayer != null && mVolumeBooster != null) {
            mPlayer.removeAudioListener(mVolumeBooster);
        }

        mVolumeBooster = null;
        mPlayer = null;
    }

    private DrmSessionManager<FrameworkMediaCrypto> createDrmManager() {
        try {
            return DefaultDrmSessionManager.newWidevineInstance(new MediaDrmCallback() {
                @Override
                public byte[] executeProvisionRequest(UUID uuid, ProvisionRequest request) {
                    return new byte[0];
                }

                @Override
                public byte[] executeKeyRequest(UUID uuid, KeyRequest request) {
                    return new byte[0];
                }
            }, null);
        } catch (UnsupportedDrmException e) {
            e.printStackTrace();
        }

        return null;
    }

    private static final class DummyBandwidthMeter implements BandwidthMeter {
        @Override
        public long getBitrateEstimate() {
            return 0;
        }

        @Nullable
        @Override
        public TransferListener getTransferListener() {
            return null;
        }

        @Override
        public void addEventListener(Handler eventHandler, EventListener eventListener) {
            // Do nothing.
        }

        @Override
        public void removeEventListener(EventListener eventListener) {
            // Do nothing.
        }
    }
}
