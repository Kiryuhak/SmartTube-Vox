package com.liskovsoft.smartyoutubetv2.common.vox.audio;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.AudioProcessor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class VoxAudioLevelProcessorTest {

    private VoxAudioLevelProcessor mProcessor;

    @Before
    public void setUp() {
        mProcessor = new VoxAudioLevelProcessor();
    }

    @Test
    public void testPcm16Bit_silenceProducesZeroRms() throws Exception {
        mProcessor.configure(44100, 2, C.ENCODING_PCM_16BIT);
        mProcessor.flush();

        // 100 samples of stereo silence (short 0)
        ByteBuffer buffer = ByteBuffer.allocateDirect(400).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 200; i++) {
            buffer.putShort((short) 0);
        }
        buffer.flip();

        mProcessor.queueInput(buffer);

        Float rms = mProcessor.getLatestRms(500L);
        assertNotNull(rms);
        assertEquals(0.0f, rms, 0.0001f);
    }

    @Test
    public void testPcm16Bit_fullScaleSquareWaveProducesMaxRms() throws Exception {
        mProcessor.configure(44100, 1, C.ENCODING_PCM_16BIT);
        mProcessor.flush();

        // Full scale alternating +32767 and -32768
        ByteBuffer buffer = ByteBuffer.allocateDirect(200).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 50; i++) {
            buffer.putShort((short) 32767);
            buffer.putShort((short) -32768);
        }
        buffer.flip();

        mProcessor.queueInput(buffer);

        Float rms = mProcessor.getLatestRms(500L);
        assertNotNull(rms);
        assertEquals(1.0f, rms, 0.01f);
    }

    @Test
    public void testPcm16Bit_halfScaleProducesExpectedRms() throws Exception {
        mProcessor.configure(44100, 1, C.ENCODING_PCM_16BIT);
        mProcessor.flush();

        // Half scale: ~16384 (amplitude ~0.5)
        ByteBuffer buffer = ByteBuffer.allocateDirect(200).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 100; i++) {
            buffer.putShort((short) 16384);
        }
        buffer.flip();

        mProcessor.queueInput(buffer);

        Float rms = mProcessor.getLatestRms(500L);
        assertNotNull(rms);
        assertEquals(0.5f, rms, 0.01f);
    }

    @Test
    public void testPcmFloat_rmsCalculation() throws Exception {
        mProcessor.configure(48000, 1, C.ENCODING_PCM_FLOAT);
        mProcessor.flush();

        ByteBuffer buffer = ByteBuffer.allocateDirect(400).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 100; i++) {
            buffer.putFloat(0.25f);
        }
        buffer.flip();

        mProcessor.queueInput(buffer);

        Float rms = mProcessor.getLatestRms(500L);
        assertNotNull(rms);
        assertEquals(0.25f, rms, 0.01f);
    }

    @Test
    public void testPassthroughBufferIntegrity() throws Exception {
        mProcessor.configure(44100, 1, C.ENCODING_PCM_16BIT);
        mProcessor.flush();

        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
        inputBuffer.putShort((short) 100);
        inputBuffer.putShort((short) 200);
        inputBuffer.putShort((short) -300);
        inputBuffer.putShort((short) 400);
        inputBuffer.flip();

        mProcessor.queueInput(inputBuffer);

        ByteBuffer outputBuffer = mProcessor.getOutput();
        assertNotNull(outputBuffer);
        assertEquals(8, outputBuffer.remaining());
        assertEquals((short) 100, outputBuffer.getShort());
        assertEquals((short) 200, outputBuffer.getShort());
        assertEquals((short) -300, outputBuffer.getShort());
        assertEquals((short) 400, outputBuffer.getShort());
    }

    @Test
    public void testDisableAndReset_clearsState() throws Exception {
        mProcessor.configure(44100, 1, C.ENCODING_PCM_16BIT);
        mProcessor.flush();

        ByteBuffer buffer = ByteBuffer.allocateDirect(200).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 100; i++) {
            buffer.putShort((short) 16384);
        }
        buffer.flip();
        mProcessor.queueInput(buffer);

        assertNotNull(mProcessor.getLatestRms(500L));

        mProcessor.setEnabled(false);
        assertFalse(mProcessor.isEnabled());
        assertNull(mProcessor.getLatestRms(500L));

        mProcessor.setEnabled(true);
        mProcessor.reset();
        assertNull(mProcessor.getLatestRms(500L));
    }
}
