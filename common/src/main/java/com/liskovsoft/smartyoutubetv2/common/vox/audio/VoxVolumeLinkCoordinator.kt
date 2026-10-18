package com.liskovsoft.smartyoutubetv2.common.vox.audio

import kotlin.math.max
import kotlin.math.min

/**
 * Pure, deterministic Volume Sync (Delta Linking) coordinator implementing exact 1:1 behavior with
 * VOT userscript 1.11.17 (src/videoHandler/volumeLink.ts).
 *
 * Rules:
 * - "video" change: translation changes by the exact same delta as video.
 * - "translation" change: video changes by the exact same delta as translation.
 * - Preserves relative slider balance until clamped by bounds, instead of mirroring values.
 * - Guarded against feedback loops and reentrant callbacks.
 */
class VoxVolumeLinkCoordinator(
    private val translationMin: Int = 0,
    private val translationMax: Int = 100
) {

    data class State(
        val initialized: Boolean = false,
        val lastVideoPercent: Int = 0,
        val lastTranslationPercent: Int = 0
    )

    data class LinkResult(
        val nextVideoPercent: Int? = null,
        val nextTranslationPercent: Int? = null,
        val newState: State
    )

    private var currentState: State = State()
    private var isProgrammaticUpdate: Boolean = false

    fun getState(): State = currentState

    fun reset() {
        currentState = State()
        isProgrammaticUpdate = false
    }

    fun syncSnapshots(videoPercent: Int, translationPercent: Int) {
        currentState = State(
            initialized = true,
            lastVideoPercent = clampPercent(videoPercent),
            lastTranslationPercent = clampTranslation(translationPercent)
        )
    }

    /**
     * Called when the video volume slider or TV remote adjustments occur.
     */
    fun onVideoVolumeChanged(
        newVideoPercent: Int,
        currentTranslationPercent: Int
    ): LinkResult {
        if (isProgrammaticUpdate) {
            return LinkResult(newState = currentState)
        }

        var state = currentState
        if (!state.initialized) {
            state = State(
                initialized = true,
                lastVideoPercent = clampPercent(newVideoPercent),
                lastTranslationPercent = clampTranslation(currentTranslationPercent)
            )
        }

        val normalizedVideo = clampPercent(newVideoPercent)
        val delta = normalizedVideo - state.lastVideoPercent
        state = state.copy(lastVideoPercent = normalizedVideo)

        val nextTranslation = clampTranslation(state.lastTranslationPercent + delta)
        state = state.copy(lastTranslationPercent = nextTranslation)
        currentState = state

        return LinkResult(
            nextTranslationPercent = nextTranslation,
            newState = state
        )
    }

    /**
     * Called when the translation volume slider adjustments occur.
     */
    fun onTranslationVolumeChanged(
        newTranslationPercent: Int,
        currentVideoPercent: Int
    ): LinkResult {
        if (isProgrammaticUpdate) {
            return LinkResult(newState = currentState)
        }

        var state = currentState
        if (!state.initialized) {
            state = State(
                initialized = true,
                lastVideoPercent = clampPercent(currentVideoPercent),
                lastTranslationPercent = clampTranslation(newTranslationPercent)
            )
        }

        val normalizedTranslation = clampTranslation(newTranslationPercent)
        val delta = normalizedTranslation - state.lastTranslationPercent
        state = state.copy(lastTranslationPercent = normalizedTranslation)

        val nextVideo = clampPercent(state.lastVideoPercent + delta)
        state = state.copy(lastVideoPercent = nextVideo)
        currentState = state

        return LinkResult(
            nextVideoPercent = nextVideo,
            newState = state
        )
    }

    /**
     * Runs programmatic volume changes with reentrancy guard active.
     */
    fun <T> withProgrammaticGuard(action: () -> T): T {
        val prev = isProgrammaticUpdate
        isProgrammaticUpdate = true
        try {
            return action()
        } finally {
            isProgrammaticUpdate = prev
        }
    }

    fun isProgrammatic(): Boolean = isProgrammaticUpdate

    private fun clampPercent(value: Int): Int = max(0, min(100, value))

    private fun clampTranslation(value: Int): Int = max(translationMin, min(translationMax, value))
}
