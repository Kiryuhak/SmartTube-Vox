/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates partitioned byte-range upload of original audio tracks to Yandex VOT.
 */
public class YandexVotAudioTransfer {
    private static final String TAG = YandexVotAudioTransfer.class.getSimpleName();
    public static final int MAX_PART_RETRIES = 2; // up to 3 attempts total per part
    public static final int PROTOCOL_VERSION = 1;

    public interface TransferListener {
        void onPreparing(long totalBytes, int totalParts);
        void onPartStarted(int partIndex, int totalParts, long startByte, int partLength);
        void onPartCompleted(int partIndex, int totalParts);
        void onCompleted(long totalBytesTransferred);
        void onError(@NonNull YandexVotAudioResult error);
        void onCancelled();
    }

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
        YandexVotLog.d(TAG, "VOT audio transfer cancel requested");
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    @NonNull
    public YandexVotAudioResult transfer(
            @Nullable YandexVotAudioSource source,
            @Nullable String videoUrl,
            @Nullable String translationId,
            @Nullable String fileId,
            @Nullable YandexVotAudioStreamReader reader,
            @Nullable YandexVotAudioUploadTransport transport,
            @Nullable TransferListener listener
    ) {
        if (isCancelled()) {
            notifyCancelled(listener);
            return YandexVotAudioResult.CANCELLED;
        }

        if (source == null || source.getContentLength() <= 0) {
            YandexVotLog.d(TAG, "VOT transfer failed: source unavailable or zero length");
            notifyError(listener, YandexVotAudioResult.SOURCE_UNAVAILABLE);
            return YandexVotAudioResult.SOURCE_UNAVAILABLE;
        }

        if (reader == null) {
            YandexVotLog.d(TAG, "VOT transfer failed: stream reader is null");
            notifyError(listener, YandexVotAudioResult.SOURCE_UNAVAILABLE);
            return YandexVotAudioResult.SOURCE_UNAVAILABLE;
        }

        if (videoUrl == null || videoUrl.isEmpty() ||
                translationId == null || translationId.isEmpty() ||
                fileId == null || fileId.isEmpty()) {
            YandexVotLog.d(TAG, "VOT transfer failed: missing upload identity metadata");
            notifyError(listener, YandexVotAudioResult.UPLOAD_FAILED);
            return YandexVotAudioResult.UPLOAD_FAILED;
        }

        YandexVotAudioUploadTransport effectiveTransport = transport != null
                ? transport
                : YandexVotAudioUploadTransport.DEFAULT;

        long fileSize = source.getContentLength();
        List<YandexVotAudioParts.Part> parts = YandexVotAudioParts.parts(fileSize);
        int totalParts = parts.size();

        if (totalParts == 0) {
            notifyError(listener, YandexVotAudioResult.SOURCE_UNAVAILABLE);
            return YandexVotAudioResult.SOURCE_UNAVAILABLE;
        }

        YandexVotLog.d(TAG, "VOT transfer starting: videoId=" + source.getVideoId() +
                " clen=" + fileSize + " totalParts=" + totalParts);

        if (listener != null) {
            try {
                listener.onPreparing(fileSize, totalParts);
            } catch (Exception e) {
                YandexVotLog.d(TAG, "VOT transfer listener error: " + e.getMessage());
            }
        }

        long totalTransferred = 0;

        for (int i = 0; i < totalParts; i++) {
            if (isCancelled()) {
                notifyCancelled(listener);
                return YandexVotAudioResult.CANCELLED;
            }

            YandexVotAudioParts.Part part = parts.get(i);
            int partIndex = i;
            int partLength = part.length();
            long startByte = part.start();

            YandexVotLog.i(TAG, "upload part=" + (partIndex + 1) + "/" + totalParts);

            if (listener != null) {
                try {
                    listener.onPartStarted(partIndex, totalParts, startByte, partLength);
                } catch (Exception e) {
                    YandexVotLog.d(TAG, "VOT transfer listener error: " + e.getMessage());
                }
            }

            // Step A: Read chunk bytes from source
            byte[] chunkData;
            try {
                chunkData = reader.readRange(startByte, partLength);
                if (chunkData == null || chunkData.length != partLength) {
                    YandexVotLog.d(TAG, "VOT transfer read truncated: part=" + partIndex +
                            " expected=" + partLength + " actual=" + (chunkData != null ? chunkData.length : -1));
                    notifyError(listener, YandexVotAudioResult.SOURCE_READ_FAILED);
                    return YandexVotAudioResult.SOURCE_READ_FAILED;
                }
            } catch (YandexVotContentRange.SourceReadException sre) {
                YandexVotLog.d(TAG, "VOT transfer Content-Range error: " + sre.getMessage());
                notifyError(listener, YandexVotAudioResult.SOURCE_READ_FAILED);
                return YandexVotAudioResult.SOURCE_READ_FAILED;
            } catch (Exception e) {
                YandexVotLog.d(TAG, "VOT transfer read exception: " + e.getMessage());
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                YandexVotAudioResult result = (msg.contains("403") || msg.contains("denied"))
                        ? YandexVotAudioResult.SOURCE_DENIED
                        : YandexVotAudioResult.SOURCE_READ_FAILED;
                notifyError(listener, result);
                return result;
            }

            // Step B: Upload chunk with bounded retries
            boolean partUploaded = false;
            for (int attempt = 0; attempt <= MAX_PART_RETRIES; attempt++) {
                if (isCancelled()) {
                    notifyCancelled(listener);
                    return YandexVotAudioResult.CANCELLED;
                }

                YandexVotAudioUploadTransport.UploadOutcome outcome = effectiveTransport.uploadPart(
                        videoUrl,
                        translationId,
                        fileId,
                        totalParts,
                        PROTOCOL_VERSION,
                        partIndex,
                        chunkData
                );

                if (outcome == YandexVotAudioUploadTransport.UploadOutcome.SUCCESS) {
                    partUploaded = true;
                    totalTransferred += chunkData.length;
                    break;
                } else if (outcome == YandexVotAudioUploadTransport.UploadOutcome.CANCELLED) {
                    notifyCancelled(listener);
                    return YandexVotAudioResult.CANCELLED;
                } else if (outcome == YandexVotAudioUploadTransport.UploadOutcome.PERMANENT_ERROR) {
                    YandexVotLog.d(TAG, "VOT upload part permanent error: part=" + partIndex);
                    notifyError(listener, YandexVotAudioResult.UPLOAD_FAILED);
                    return YandexVotAudioResult.UPLOAD_FAILED;
                } else {
                    YandexVotLog.d(TAG, "VOT upload part transient error, attempt " + attempt + " of " + MAX_PART_RETRIES);
                    if (attempt == MAX_PART_RETRIES) {
                        notifyError(listener, YandexVotAudioResult.UPLOAD_FAILED);
                        return YandexVotAudioResult.UPLOAD_FAILED;
                    }
                }
            }

            if (!partUploaded) {
                notifyError(listener, YandexVotAudioResult.UPLOAD_FAILED);
                return YandexVotAudioResult.UPLOAD_FAILED;
            }

            if (listener != null) {
                try {
                    listener.onPartCompleted(partIndex, totalParts);
                } catch (Exception e) {
                    YandexVotLog.d(TAG, "VOT transfer listener error: " + e.getMessage());
                }
            }
        }

        YandexVotLog.d(TAG, "VOT transfer complete: totalBytes=" + totalTransferred);
        if (listener != null) {
            try {
                listener.onCompleted(totalTransferred);
            } catch (Exception e) {
                YandexVotLog.d(TAG, "VOT transfer listener error: " + e.getMessage());
            }
        }

        return YandexVotAudioResult.SUCCESS;
    }

    private void notifyError(@Nullable TransferListener listener, @NonNull YandexVotAudioResult error) {
        if (listener != null) {
            try {
                listener.onError(error);
            } catch (Exception e) {
                YandexVotLog.d(TAG, "VOT transfer listener error: " + e.getMessage());
            }
        }
    }

    private void notifyCancelled(@Nullable TransferListener listener) {
        if (listener != null) {
            try {
                listener.onCancelled();
            } catch (Exception e) {
                YandexVotLog.d(TAG, "VOT transfer listener error: " + e.getMessage());
            }
        }
    }
}
