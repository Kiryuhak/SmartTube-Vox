package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Minimal interface representing an audio data source for VOT uploads.
 * Enables progressive chunk streaming without loading entire audio files into memory.
 */
public interface VotAudioSource extends Closeable {
    /**
     * Opens the audio source stream for reading.
     *
     * @throws IOException if source fails to open
     */
    void open() throws IOException;

    /**
     * Reads up to len bytes of audio data into the specified buffer starting at offset.
     *
     * @param buffer destination array
     * @param off offset in buffer
     * @param len maximum number of bytes to read
     * @return number of bytes read, or -1 if end of stream reached
     * @throws IOException if reading fails
     */
    int read(byte[] buffer, int off, int len) throws IOException;

    /**
     * Returns known total content length in bytes, or -1 if unknown (e.g. streaming).
     */
    long getContentLength();

    /**
     * Closes the source and releases associated resources.
     */
    @Override
    void close() throws IOException;

    /**
     * Factory method creating a VotAudioSource backed by an in-memory byte array with exact length.
     */
    static VotAudioSource fromBytes(byte[] data) {
        return fromBytes(data, data != null ? data.length : 0);
    }

    /**
     * Factory method creating a VotAudioSource backed by an in-memory byte array with a specified declared length.
     */
    static VotAudioSource fromBytes(byte[] data, long declaredLength) {
        return new ByteArrayAudioSource(data, declaredLength);
    }

    /**
     * Factory method creating a VotAudioSource backed by an InputStream.
     */
    static VotAudioSource fromInputStream(InputStream in, long contentLength) {
        return new InputStreamAudioSource(in, contentLength);
    }
}

class ByteArrayAudioSource implements VotAudioSource {
    private final byte[] mData;
    private final long mContentLength;
    private int mPos = 0;
    private boolean mOpened = false;
    private boolean mClosed = false;

    ByteArrayAudioSource(byte[] data, long contentLength) {
        mData = data != null ? data : new byte[0];
        mContentLength = contentLength;
    }

    @Override
    public void open() throws IOException {
        if (mClosed) {
            throw new IOException("Audio source already closed");
        }
        mOpened = true;
        mPos = 0;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        if (!mOpened) {
            throw new IOException("Audio source not opened");
        }
        if (mClosed) {
            throw new IOException("Audio source is closed");
        }
        if (mPos >= mData.length) {
            return -1;
        }
        int available = mData.length - mPos;
        int toRead = Math.min(len, available);
        System.arraycopy(mData, mPos, buffer, off, toRead);
        mPos += toRead;
        return toRead;
    }

    @Override
    public long getContentLength() {
        return mContentLength;
    }

    @Override
    public void close() throws IOException {
        mClosed = true;
    }
}

class InputStreamAudioSource implements VotAudioSource {
    private final InputStream mIn;
    private final long mContentLength;
    private boolean mOpened = false;
    private boolean mClosed = false;

    InputStreamAudioSource(InputStream in, long contentLength) {
        mIn = in;
        mContentLength = contentLength;
    }

    @Override
    public void open() throws IOException {
        if (mClosed) {
            throw new IOException("Audio source already closed");
        }
        mOpened = true;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        if (!mOpened) {
            throw new IOException("Audio source not opened");
        }
        if (mClosed) {
            throw new IOException("Audio source is closed");
        }
        if (mIn == null) {
            return -1;
        }
        return mIn.read(buffer, off, len);
    }

    @Override
    public long getContentLength() {
        return mContentLength;
    }

    @Override
    public void close() throws IOException {
        mClosed = true;
        if (mIn != null) {
            mIn.close();
        }
    }
}
