package com.liskovsoft.smartyoutubetv2.common.exoplayer.errors;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
import com.google.android.exoplayer2.upstream.Loader.UnexpectedLoaderException;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Политика обработки сетевых ошибок DASH / Live потоков.
 *
 * VOX 8 Patch #4:
 * 1. Не блокирует видеодорожку (Track Blacklist) при 404 на краю прямого эфира (transient live-edge 404);
 * 2. Обеспечивает плавный backoff при HTTP 429 (Rate Limit);
 * 3. Ограничивает повторы при серверных сбоях 5xx;
 * 4. Предотвращает бесконечные циклы повторов (Bounded Retry).
 */
public class DashDefaultLoadErrorHandlingPolicy extends DefaultLoadErrorHandlingPolicy {
    public static final int MAX_LIVE_SEGMENT_RETRIES = 3;
    public static final int MAX_SERVER_ERROR_RETRIES = 3;
    public static final int MAX_RATE_LIMIT_RETRIES = 4;
    public static final int MAX_GENERIC_RETRIES = 5;

    @Override
    public long getBlacklistDurationMsFor(int dataType, long loadDurationMs, IOException exception, int errorCount) {
        if (exception instanceof InvalidResponseCodeException) {
            int responseCode = ((InvalidResponseCodeException) exception).responseCode;

            // VOX: Для медиа-сегментов прямого эфира 404 на краю трансляции часто является временной задержкой публикации CDN.
            // Первые MAX_LIVE_SEGMENT_RETRIES попыток НЕ исключают дорожку, позволяя дождаться готовности чанка.
            if (responseCode == 404 && (dataType == C.DATA_TYPE_MEDIA || dataType == C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE)) {
                if (errorCount <= MAX_LIVE_SEGMENT_RETRIES) {
                    return C.TIME_UNSET;
                }
            }

            // HTTP 429: Проблема частоты запросов, а не конкретного трека
            if (responseCode == 429) {
                return C.TIME_UNSET;
            }

            // HTTP 5xx: Временный серверный сбой CDN, исключать трек не имеет смысла
            if (responseCode >= 500 && responseCode <= 599) {
                return C.TIME_UNSET;
            }

            return responseCode == 404 || responseCode == 410
                    ? DEFAULT_TRACK_BLACKLIST_MS
                    : C.TIME_UNSET;
        }
        return C.TIME_UNSET;
    }

    @Override
    public long getRetryDelayMsFor(int dataType, long loadDurationMs, IOException exception, int errorCount) {
        if (exception instanceof ParserException
                || exception instanceof FileNotFoundException
                || exception instanceof UnexpectedLoaderException) {
            return C.TIME_UNSET;
        }

        if (exception instanceof InvalidResponseCodeException) {
            int responseCode = ((InvalidResponseCodeException) exception).responseCode;

            // 429: Экспоненциальная задержка с защитой от шторма запросов
            if (responseCode == 429) {
                if (errorCount > MAX_RATE_LIMIT_RETRIES) {
                    return C.TIME_UNSET;
                }
                return Math.min(1000L * (1L << (errorCount - 1)), 8_000L);
            }

            // 404 на медиа-сегменте края эфира: короткий контролируемый retry (500мс, 1000мс, 1500мс)
            if (responseCode == 404 && (dataType == C.DATA_TYPE_MEDIA || dataType == C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE)) {
                if (errorCount > MAX_LIVE_SEGMENT_RETRIES) {
                    return C.TIME_UNSET;
                }
                return Math.min(errorCount * 500L, 2_000L);
            }

            // 5xx: Серверные ошибки узлов CDN - ограниченные повторы
            if (responseCode >= 500 && responseCode <= 599) {
                if (errorCount > MAX_SERVER_ERROR_RETRIES) {
                    return C.TIME_UNSET;
                }
                return Math.min(errorCount * 1000L, 3_000L);
            }
        }

        // Общее ограничение повторов для предотвращения зацикливания
        if (errorCount > MAX_GENERIC_RETRIES) {
            return C.TIME_UNSET;
        }

        return Math.min((errorCount - 1) * 1000L, 5000L);
    }
}
