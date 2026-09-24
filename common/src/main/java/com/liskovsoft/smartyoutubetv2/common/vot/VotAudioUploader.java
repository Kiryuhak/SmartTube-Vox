package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.Map;

/**
 * Handles progressive audio streaming upload to Yandex VOT endpoint (/video-translation/audio).
 * Supports single-part and multi-part chunked uploads using Batch #66 protobuf wire layer.
 * Strictly bounds peak memory on ARMv7 devices by never buffering complete long-form audio.
 */
public class VotAudioUploader {
    private static final int MAX_CHUNK_RETRIES = 3;

    private final VotHttp mHttp;
    private final int mChunkSize;
    private volatile boolean mCancelled = false;
    private VotHttp.SimpleCallHolder mCurrentCallHolder;
    private VotAudioSource mCurrentSource;

    public VotAudioUploader() {
        this(new VotHttp(), VotConfig.AUDIO_MIN_CHUNK_SIZE);
    }

    public VotAudioUploader(VotHttp http) {
        this(http, VotConfig.AUDIO_MIN_CHUNK_SIZE);
    }

    VotAudioUploader(VotHttp http, int chunkSize) {
        if (http == null) {
            throw new IllegalArgumentException("http client must not be null");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        mHttp = http;
        mChunkSize = chunkSize;
    }

    /**
     * Uploads audio from audioSource to Yandex VOT audio endpoint.
     *
     * @param url           video URL
     * @param translationId active translation identifier
     * @param fileId        audio file identifier (e.g. smarttube-android-videoId)
     * @param session       active cryptographic session
     * @param oauthToken    optional OAuth token for Lively Voice
     * @param audioSource   audio data stream source
     * @return validated server response
     * @throws IOException  if transport or source error occurs
     * @throws VotException if server rejects or protocol violation occurs
     */
    public VotTranslationAudioResponse uploadAudio(
            String url,
            String translationId,
            String fileId,
            VotSession session,
            @Nullable String oauthToken,
            VotAudioSource audioSource
    ) throws IOException, VotException {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("url must not be empty");
        }
        if (translationId == null || translationId.trim().isEmpty()) {
            throw new IllegalArgumentException("translationId must not be empty");
        }
        if (fileId == null || fileId.trim().isEmpty()) {
            throw new IllegalArgumentException("fileId must not be empty");
        }
        if (session == null || session.secretKey == null || session.uuid == null) {
            throw new IllegalArgumentException("valid session is required");
        }
        if (audioSource == null) {
            throw new IllegalArgumentException("audioSource must not be null");
        }

        checkCancelled();

        synchronized (this) {
            if (mCancelled) {
                throw new VotCancellationException("Upload cancelled before opening source");
            }
            mCurrentSource = audioSource;
            mCurrentCallHolder = new VotHttp.SimpleCallHolder();
        }

        try {
            try {
                audioSource.open();
            } catch (IOException e) {
                checkCancelled();
                throw new VotAudioSourceException("Failed to open audio source: " + e.getMessage(), e);
            }

            long contentLength = audioSource.getContentLength();
            long totalRead = 0;

            byte[] chunkBuffer = new byte[mChunkSize];
            int read = readBlock(audioSource, chunkBuffer, 0, mChunkSize);

            if (read == 0) {
                throw new IllegalArgumentException("Audio source is empty (0 bytes read)");
            }
            totalRead += read;

            if (read < mChunkSize) {
                // Whole stream fit into first block -> Single-part upload
                if (contentLength > 0 && totalRead != contentLength) {
                    throw new VotAudioSourceException("Premature EOF: expected " + contentLength + " bytes, but read " + totalRead);
                }
                byte[] payload = new byte[read];
                System.arraycopy(chunkBuffer, 0, payload, 0, read);
                chunkBuffer = null; // release immediately

                byte[] body = VotProtobuf.encodeAudioRequestSinglePart(url, translationId, fileId, payload);
                VotTranslationAudioResponse resp = uploadChunkWithRetry(body, session, oauthToken, 0);

                validateFinalResponse(resp, 1);
                checkCancelled();
                return resp;
            }

            // read == mChunkSize: peek 1 byte to determine if there are subsequent chunks
            byte[] peekBuf = new byte[1];
            int peek = readBlock(audioSource, peekBuf, 0, 1);

            if (peek == 0) {
                // Stream ended at exact chunk boundary -> Single-part upload
                if (contentLength > 0 && totalRead != contentLength) {
                    throw new VotAudioSourceException("Premature EOF: expected " + contentLength + " bytes, but read " + totalRead);
                }
                byte[] body = VotProtobuf.encodeAudioRequestSinglePart(url, translationId, fileId, chunkBuffer);
                chunkBuffer = null; // release immediately

                VotTranslationAudioResponse resp = uploadChunkWithRetry(body, session, oauthToken, 0);
                validateFinalResponse(resp, 1);
                checkCancelled();
                return resp;
            }

            // More bytes exist -> Multi-part upload
            // Upload chunk 0 as intermediate (audioPartsLength = 0)
            totalRead += 1;
            byte[] chunk0Body = VotProtobuf.encodeAudioRequestChunk(url, translationId, fileId, 0, 0, chunkBuffer);
            VotTranslationAudioResponse resp0 = uploadChunkWithRetry(chunk0Body, session, oauthToken, 0);
            validateIntermediateResponse(resp0, 0);

            int chunkIndex = 1;
            while (true) {
                checkCancelled();
                chunkBuffer[0] = peekBuf[0];
                int remainingInChunk = mChunkSize - 1;
                int currentRead = readBlock(audioSource, chunkBuffer, 1, remainingInChunk);
                int currentChunkSize = 1 + currentRead;
                totalRead += currentRead;

                if (currentChunkSize < mChunkSize) {
                    // Terminal chunk
                    if (contentLength > 0 && totalRead != contentLength) {
                        throw new VotAudioSourceException("Premature EOF: expected " + contentLength + " bytes, but read " + totalRead);
                    }
                    int totalChunks = chunkIndex + 1;
                    byte[] payload = new byte[currentChunkSize];
                    System.arraycopy(chunkBuffer, 0, payload, 0, currentChunkSize);
                    chunkBuffer = null; // release

                    byte[] body = VotProtobuf.encodeAudioRequestChunk(url, translationId, fileId, chunkIndex, totalChunks, payload);
                    VotTranslationAudioResponse resp = uploadChunkWithRetry(body, session, oauthToken, chunkIndex);
                    validateFinalResponse(resp, totalChunks);
                    checkCancelled();
                    return resp;
                }

                // Chunk is full (currentChunkSize == mChunkSize), peek next byte
                peek = readBlock(audioSource, peekBuf, 0, 1);
                if (peek == 0) {
                    // Terminal chunk of exact chunk size
                    if (contentLength > 0 && totalRead != contentLength) {
                        throw new VotAudioSourceException("Premature EOF: expected " + contentLength + " bytes, but read " + totalRead);
                    }
                    int totalChunks = chunkIndex + 1;
                    byte[] body = VotProtobuf.encodeAudioRequestChunk(url, translationId, fileId, chunkIndex, totalChunks, chunkBuffer);
                    chunkBuffer = null; // release

                    VotTranslationAudioResponse resp = uploadChunkWithRetry(body, session, oauthToken, chunkIndex);
                    validateFinalResponse(resp, totalChunks);
                    checkCancelled();
                    return resp;
                }

                // More chunks follow -> intermediate chunk
                totalRead += 1;
                byte[] body = VotProtobuf.encodeAudioRequestChunk(url, translationId, fileId, chunkIndex, 0, chunkBuffer);
                VotTranslationAudioResponse resp = uploadChunkWithRetry(body, session, oauthToken, chunkIndex);
                validateIntermediateResponse(resp, chunkIndex);

                chunkIndex++;
            }
        } finally {
            synchronized (this) {
                mCurrentSource = null;
                mCurrentCallHolder = null;
            }
            try {
                audioSource.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Cancels the active upload immediately, aborting in-flight HTTP request and closing source.
     */
    public void cancel() {
        mCancelled = true;
        synchronized (this) {
            if (mCurrentCallHolder != null) {
                mCurrentCallHolder.cancel();
            }
            if (mCurrentSource != null) {
                try {
                    mCurrentSource.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public boolean isCancelled() {
        return mCancelled;
    }

    private int readBlock(VotAudioSource source, byte[] buffer, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            checkCancelled();
            int r;
            try {
                r = source.read(buffer, offset + total, length - total);
            } catch (IOException e) {
                if (mCancelled) {
                    throw new VotCancellationException("Read cancelled");
                }
                throw new VotAudioSourceException("Failed reading from audio source: " + e.getMessage(), e);
            }
            if (r == -1) {
                break;
            }
            total += r;
        }
        return total;
    }

    private VotTranslationAudioResponse uploadChunkWithRetry(
            byte[] body,
            VotSession session,
            @Nullable String oauthToken,
            int chunkIndex
    ) throws IOException, VotException {
        int attempts = 0;
        while (true) {
            checkCancelled();
            attempts++;
            try {
                Map<String, String> headers = VotHeaders.sessionTranslate(session, body, "/video-translation/audio");
                if (oauthToken != null && !oauthToken.isEmpty()) {
                    headers = VotHeaders.merge(headers, VotHeaders.oauthHeader(oauthToken));
                }

                VotHttp.CallHolder callHolder;
                synchronized (this) {
                    callHolder = mCurrentCallHolder;
                }

                byte[] rawResponse = mHttp.putProtobuf("/video-translation/audio", body, headers, callHolder);
                checkCancelled();

                if (rawResponse == null || rawResponse.length == 0) {
                    throw new VotException("Empty audio upload response from server");
                }

                return VotProtobuf.decodeTranslationAudioResponse(rawResponse);
            } catch (IOException e) {
                checkCancelled();
                if (e instanceof VotHttpException) {
                    VotHttpException he = (VotHttpException) e;
                    if (!VotClient.isTransientException(he)) {
                        throw he;
                    }
                } else if (!VotClient.isTransientException(e)) {
                    throw e;
                }

                if (attempts >= MAX_CHUNK_RETRIES) {
                    throw new IOException("Failed uploading chunk " + chunkIndex + " after " + attempts + " attempts: " + e.getMessage(), e);
                }

                try {
                    Thread.sleep(100L * attempts);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new VotCancellationException("Interrupted during chunk retry backoff");
                }
            }
        }
    }

    private void validateIntermediateResponse(VotTranslationAudioResponse resp, int chunkIndex) throws VotException {
        if (resp.status == VotTranslationAudioResponse.STATUS_DONE) {
            throw new VotException("Premature STATUS_DONE from server on intermediate chunk " + chunkIndex);
        }
        if (resp.status != VotTranslationAudioResponse.STATUS_WAITING_CHUNKS) {
            throw new VotException("Unexpected audio upload response status on chunk " + chunkIndex + ": " + resp.status);
        }
    }

    private void validateFinalResponse(VotTranslationAudioResponse resp, int totalChunks) throws VotException {
        if (resp.status == VotTranslationAudioResponse.STATUS_WAITING_CHUNKS) {
            throw new VotException("Upload incomplete: server still waiting for chunks: " + resp.remainingChunks);
        }
        if (resp.status != VotTranslationAudioResponse.STATUS_DONE) {
            throw new VotException("Unexpected final audio upload response status: " + resp.status);
        }
    }

    private void checkCancelled() throws VotCancellationException {
        if (mCancelled) {
            throw new VotCancellationException("Audio upload was cancelled");
        }
    }
}
