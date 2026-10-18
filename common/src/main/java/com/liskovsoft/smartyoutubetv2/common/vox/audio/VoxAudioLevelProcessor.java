package com.liskovsoft.smartyoutubetv2.common.vox.audio;

import android.os.SystemClock;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

/**
 * High-performance, zero-allocation native PCM tap for translated audio level detection.
 * Extends ExoPlayer's BaseAudioProcessor to passively compute RMS of 16-bit or float PCM frames
 * while passing audio untouched to downstream renderers.
 */
public class VoxAudioLevelProcessor extends BaseAudioProcessor {
    private volatile float mLatestRms = 0f;
    private volatile long mLatestRmsTimestampMs = 0L;
    private volatile boolean mIsEnabled = true;

    public void setEnabled(boolean enabled) {
        mIsEnabled = enabled;
        if (!enabled) {
            mLatestRms = 0f;
            mLatestRmsTimestampMs = 0L;
        }
    }

    public boolean isEnabled() {
        return mIsEnabled;
    }

    @Nullable
    public Float getLatestRms(long maxAgeMs) {
        if (!mIsEnabled) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        if (mLatestRmsTimestampMs > 0L && (now - mLatestRmsTimestampMs) <= maxAgeMs) {
            return mLatestRms;
        }
        return null;
    }

    public float getLatestRmsOrZero(long maxAgeMs) {
        Float rms = getLatestRms(maxAgeMs);
        return rms != null ? rms : 0f;
    }

    @Override
    public boolean configure(int sampleRateHz, int channelCount, @C.PcmEncoding int encoding)
            throws UnhandledFormatException {
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw new UnhandledFormatException(sampleRateHz, channelCount, encoding);
        }
        return setInputFormat(sampleRateHz, channelCount, encoding);
    }

    @Override
    public boolean isActive() {
        return sampleRateHz != Format.NO_VALUE;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int position = inputBuffer.position();
        int limit = inputBuffer.limit();
        int remaining = limit - position;
        if (remaining <= 0) {
            return;
        }

        if (mIsEnabled) {
            computeRms(inputBuffer, position, limit);
        }

        // Forward samples to output buffer unchanged (zero audio alteration)
        ByteBuffer outputBuffer = replaceOutputBuffer(remaining);
        outputBuffer.put(inputBuffer);
        outputBuffer.flip();
    }

    private void computeRms(ByteBuffer buffer, int position, int limit) {
        int length = limit - position;
        if (encoding == C.ENCODING_PCM_16BIT) {
            int sampleCount = length / 2;
            if (sampleCount <= 0) return;
            double sum = 0.0;
            for (int i = position; i < limit; i += 2) {
                short sample = buffer.getShort(i);
                double norm = sample / 32768.0;
                sum += norm * norm;
            }
            double rms = Math.sqrt(sum / sampleCount);
            mLatestRms = (float) Math.min(1.0, Math.max(0.0, rms));
            mLatestRmsTimestampMs = SystemClock.elapsedRealtime();
        } else if (encoding == C.ENCODING_PCM_FLOAT) {
            int sampleCount = length / 4;
            if (sampleCount <= 0) return;
            double sum = 0.0;
            for (int i = position; i < limit; i += 4) {
                float sample = buffer.getFloat(i);
                double norm = Math.min(1.0, Math.max(-1.0, (double) sample));
                sum += norm * norm;
            }
            double rms = Math.sqrt(sum / sampleCount);
            mLatestRms = (float) Math.min(1.0, Math.max(0.0, rms));
            mLatestRmsTimestampMs = SystemClock.elapsedRealtime();
        }
    }

    @Override
    protected void onFlush() {
        mLatestRms = 0f;
        mLatestRmsTimestampMs = 0L;
    }

    @Override
    protected void onReset() {
        mLatestRms = 0f;
        mLatestRmsTimestampMs = 0L;
    }
}
