package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

/**
 * In-flight request deduplication coordinator.
 *
 * Ensures that concurrent requests for the exact same canonical translation key
 * share a single active network execution instead of firing redundant requests
 * to Yandex API servers.
 */
object VoxTranslationCoordinator {
    private const val TAG = "VoxTranslationCoordinator"

    private val inFlight = ConcurrentHashMap<String, FutureTask<YandexVotApiClient.TranslationResult>>()

    /**
     * Executes the given action in single-flight mode for the specified key.
     * If an execution is already in flight for [key], subsequent callers wait for
     * the existing execution and receive the shared result.
     */
    fun executeSingleFlight(
        key: String,
        action: () -> YandexVotApiClient.TranslationResult
    ): YandexVotApiClient.TranslationResult {
        var isCreator = false
        var future = inFlight[key]

        if (future == null) {
            val task = FutureTask<YandexVotApiClient.TranslationResult> {
                try {
                    action()
                } finally {
                    inFlight.remove(key)
                }
            }
            val existing = inFlight.putIfAbsent(key, task)
            if (existing == null) {
                future = task
                isCreator = true
            } else {
                future = existing
            }
        }

        if (isCreator) {
            future.run()
        }

        return try {
            future.get()
        } catch (e: Exception) {
            inFlight.remove(key)
            YandexVotLog.w(TAG, "Single-flight execution failed for key: $key - ${e.message}")
            YandexVotApiClient.TranslationResult.networkError(
                e.message ?: "Single-flight task interrupted or failed",
                "coordinator_error"
            )
        }
    }

    /**
     * Checks if there is an active in-flight request for the specified key.
     */
    fun isInFlight(key: String): Boolean {
        return inFlight.containsKey(key)
    }

    /**
     * Clear all in-flight jobs (primarily for testing or reset).
     */
    fun clear() {
        inFlight.clear()
    }
}
