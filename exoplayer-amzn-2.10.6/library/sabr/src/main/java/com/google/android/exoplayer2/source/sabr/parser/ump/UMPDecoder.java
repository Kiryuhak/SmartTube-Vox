package com.google.android.exoplayer2.source.sabr.parser.ump;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.extractor.ExtractorInput;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;

public class UMPDecoder {
    public static final int DEFAULT_MAX_PART_BYTES = 32 * 1024 * 1024; // 32 MB maximum part size
    public static final int MAX_CONTROL_PART_BYTES = 1024 * 1024;      // 1 MB maximum control part size

    private final int mMaxPartBytes;

    public UMPDecoder() {
        this(DEFAULT_MAX_PART_BYTES);
    }

    public UMPDecoder(int maxPartBytes) {
        if (maxPartBytes <= 0) {
            throw new IllegalArgumentException("Invalid part limit: " + maxPartBytes);
        }
        this.mMaxPartBytes = maxPartBytes;
    }

    public int getMaxPartBytes() {
        return mMaxPartBytes;
    }

    public UMPPart decode(@NonNull ExtractorInput extractorInput) {
        try {
            long partType = readVarInt(extractorInput);
            if (partType == -1) {
                return null;
            }
            if (partType < 0 || partType > Integer.MAX_VALUE) {
                throw new IllegalStateException("Invalid UMP part type: " + partType);
            }

            long partSize = readVarInt(extractorInput);
            if (partSize == -1) {
                throw new IllegalStateException("Unexpected EOF while reading part size for part " + partType);
            }
            if (partSize < 0 || partSize > mMaxPartBytes) {
                throw new IllegalStateException("UMP part size out of bounds: " + partSize + " (max=" + mMaxPartBytes + ") for part " + partType);
            }

            return new UMPPart((int) partType, (int) partSize, extractorInput);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private long readVarInt(StreamWrapper input) throws IOException, InterruptedException {
        // https://web.archive.org/web/20250430054327/https://github.com/gsuberland/UMP_Format/blob/main/UMP_Format.md
        // https://web.archive.org/web/20250429151021/https://github.com/davidzeng0/innertube/blob/main/googlevideo/ump.md
        byte[] buffer = new byte[1];
        boolean success = input.readFully(buffer, 0, 1, true);
        if (!success) {
            // Expected EOF at start of VarInt
            return -1;
        }

        long byteInt = buffer[0] & 0xFF; // convert to unsigned (0..255)
        int size = varIntSize(byteInt);
        if (size < 1 || size > 5) {
            throw new IOException("Invalid VarInt size prefix: " + size);
        }

        long result = 0;
        int shift = 0;

        if (size != 5) {
            shift = 8 - size;
            int mask = (1 << shift) - 1;
            result |= byteInt & mask;
        }

        for (int i = 1; i < size; i++) {
            success = input.readFully(buffer, 0, 1, true);
            if (!success) {
                throw new EOFException("Unexpected EOF while reading multi-byte VarInt (byte " + i + " of " + size + ")");
            }
            byteInt = buffer[0] & 0xFF; // convert to unsigned (0..255)
            result |= byteInt << shift;
            shift += 8;
        }

        return result;
    }

    public long readVarInt(ExtractorInput input) throws IOException, InterruptedException {
        return readVarInt(input::readFully);
    }

    public long readVarInt(ByteArrayInputStream inputStream) throws IOException, InterruptedException {
        return readVarInt((target, offset, length, allowEndOfInput) -> {
            int numRead = inputStream.read(target, offset, length);
            return numRead != -1;
        });
    }

    public static int varIntSize(long byteInt) {
        return byteInt < 128 ? 1 : byteInt < 192 ? 2 : byteInt < 224 ? 3 : byteInt < 240 ? 4 : 5;
    }

    private interface StreamWrapper {
        boolean readFully(byte[] target, int offset, int length, boolean allowEndOfInput)
                throws IOException, InterruptedException;
    }
}
