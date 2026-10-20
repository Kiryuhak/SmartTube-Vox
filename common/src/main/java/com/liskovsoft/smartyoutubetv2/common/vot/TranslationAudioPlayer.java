package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.vox.audio.VoxAudioBooster;
import com.liskovsoft.smartyoutubetv2.common.vox.audio.VoxAudioLevelProcessor;

public class TranslationAudioPlayer implements Player.EventListener {
    private static final String TAG = TranslationAudioPlayer.class.getSimpleName();
    public static final int CONNECT_TIMEOUT_MS = 15_000;
    public static final int READ_TIMEOUT_MS = 15_000;

    public interface PlaybackCallback {
        void onPrepared();
        void onSeekProcessed();
        void onError(Exception error);
    }

    private final Context mContext;
    private final VoxAudioLevelProcessor mLevelProcessor = new VoxAudioLevelProcessor();
    private final VoxAudioBooster mBooster = new VoxAudioBooster(false, 100);
    private SimpleExoPlayer mPlayer;
    private int mSessionId;
    @Nullable
    private PlaybackCallback mCallback;
    private boolean mIsPrepared;
    private boolean mIsPlaying;

    public TranslationAudioPlayer(Context context) {
        mContext = context.getApplicationContext();
    }

    public VoxAudioLevelProcessor getAudioLevelProcessor() {
        return mLevelProcessor;
    }

    public VoxAudioBooster getAudioBooster() {
        return mBooster;
    }

    public void configureBooster(boolean enabled, int boostPercent) {
        mBooster.setEnabled(enabled);
        mBooster.setBoostPercent(boostPercent);
    }

    public void prepare(int sessionId, String url, float speed, PlaybackCallback callback) {
        release();
        mSessionId = sessionId;
        mCallback = callback;
        mIsPrepared = false;
        mIsPlaying = false;

        Log.i(TAG, "VOT_AUDIO session=" + mSessionId + " create");
        String userAgent = Util.getUserAgent(mContext, "SmartTubeVOT");
        DefaultHttpDataSourceFactory httpDataSourceFactory = new DefaultHttpDataSourceFactory(
                userAgent,
                null,
                CONNECT_TIMEOUT_MS,
                READ_TIMEOUT_MS,
                true // allowCrossProtocolRedirects
        );
        DefaultDataSourceFactory dataSourceFactory = new DefaultDataSourceFactory(
                mContext,
                null,
                httpDataSourceFactory
        );
        ExtractorMediaSource mediaSource = new ExtractorMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(new VoxTranslationLoadErrorHandlingPolicy())
                .createMediaSource(Uri.parse(url));
        Log.i(TAG, "VOT_AUDIO session=" + mSessionId + " source_set url=" + url);

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(mContext) {
            @Override
            protected AudioProcessor[] buildAudioProcessors() {
                return new AudioProcessor[] { mLevelProcessor };
            }
        };
        mPlayer = ExoPlayerFactory.newSimpleInstance(mContext, renderersFactory, new DefaultTrackSelector());
        mPlayer.addListener(this);
        mPlayer.addAudioListener(mBooster);

        // Silent pre-buffer barrier: volume is 0 until initial seek completes
        mPlayer.setVolume(0f);
        mPlayer.setPlayWhenReady(false);

        if (speed > 0f && speed != 1f) {
            mPlayer.setPlaybackParameters(new PlaybackParameters(speed, 1f));
        }

        Log.i(TAG, "VOT_AUDIO session=" + mSessionId + " prepare_start");
        mPlayer.prepare(mediaSource);
    }

    public void startPlayback(float volume) {
        if (mPlayer == null) {
            return;
        }
        if (mIsPlaying) {
            Log.i(TAG, "VOT_AUDIO session=" + mSessionId + " duplicate_play_ignored");
            return;
        }
        mIsPlaying = true;
        mPlayer.setVolume(volume);
        mPlayer.setPlayWhenReady(true);
        Log.i(TAG, "VOT_AUDIO session=" + mSessionId + " audible_start");
    }

    public void setVolume(float volume) {
        if (mPlayer != null && mIsPlaying) {
            mPlayer.setVolume(volume);
        }
    }

    public void setPlaybackSpeed(float speed) {
        if (mPlayer == null) {
            return;
        }
        float s = speed > 0f ? speed : 1f;
        mPlayer.setPlaybackParameters(new PlaybackParameters(s, 1f));
    }

    public boolean isReady() {
        return mPlayer != null && mPlayer.getPlaybackState() == Player.STATE_READY;
    }

    public boolean isPlaying() {
        return mIsPlaying && mPlayer != null && mPlayer.getPlayWhenReady();
    }

    public void seekTo(long positionMs) {
        if (mPlayer != null) {
            Log.i(TAG, "VOT_AUDIO session=" + mSessionId + " initial_seek_start target_position=" + positionMs);
            mPlayer.seekTo(positionMs);
        }
    }

    public long getPositionMs() {
        return mPlayer != null ? mPlayer.getCurrentPosition() : 0;
    }

    public long getBufferedPositionMs() {
        return mPlayer != null ? mPlayer.getBufferedPosition() : 0;
    }

    public boolean isBuffering() {
        return mPlayer != null && mPlayer.getPlaybackState() == Player.STATE_BUFFERING;
    }

    public void pause() {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(false);
        }
    }

    public void resume() {
        if (mPlayer != null && mIsPlaying) {
            mPlayer.setPlayWhenReady(true);
        }
    }

    public void release() {
        mCallback = null;
        mBooster.release();
        if (mPlayer != null) {
            Log.i(TAG, "VOT_AUDIO session=" + mSessionId + " release");
            mPlayer.removeAudioListener(mBooster);
            mPlayer.removeListener(this);
            mPlayer.release();
            mPlayer = null;
        }
        mLevelProcessor.reset();
        mIsPrepared = false;
        mIsPlaying = false;
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        if (playbackState == Player.STATE_READY && !mIsPrepared) {
            mIsPrepared = true;
            Log.i(TAG, "VOT_AUDIO session=" + mSessionId + " prepared");
            if (mCallback != null) {
                mCallback.onPrepared();
            }
        }
    }

    @Override
    public void onSeekProcessed() {
        Log.i(TAG, "VOT_AUDIO session=" + mSessionId + " initial_seek_complete");
        if (mCallback != null) {
            mCallback.onSeekProcessed();
        }
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        Log.e(TAG, "Translation player error: %s", error != null ? error.getMessage() : "unknown");
        PlaybackCallback callback = mCallback;
        if (callback != null) {
            callback.onError(error);
        }
    }
}
