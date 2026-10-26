package com.liskovsoft.smartyoutubetv2.common.vox.ui

import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Structured tracer for Player HUD and UI lifecycle state transitions.
 * Prefixes all entries with VOX_UI_STATE for easy logcat filtering and automated verification.
 */
object VoxUiStateTracer {

    const val TAG = "VOX_UI_STATE"
    private const val MAX_ENTRIES = 100

    data class TraceEntry(
        val timestampMs: Long,
        val event: String,
        val details: String
    )

    private val entries = CopyOnWriteArrayList<TraceEntry>()

    @JvmStatic
    @JvmOverloads
    fun trace(event: String, details: String = "") {
        val entry = TraceEntry(System.currentTimeMillis(), event, details)
        if (entries.size >= MAX_ENTRIES) {
            entries.removeAt(0)
        }
        entries.add(entry)

        try {
            Log.d(TAG, "[$TAG] $event: $details")
        } catch (_: Throwable) {
            // Safe in non-android unit test environments
        }
    }

    @Volatile
    private var lastFocusTarget: String? = null
    @Volatile
    private var lastFocusRestoredTimeMs: Long = 0L

    @JvmStatic
    fun onOverlayState(shown: Boolean, reason: String = "") {
        trace(if (shown) "OVERLAY_SHOWN" else "OVERLAY_HIDDEN", reason)
    }

    @JvmStatic
    @JvmOverloads
    fun onFocusRestored(
        target: String,
        eventSource: String = "PlaybackFragment",
        reason: String = "FOCUS_LOST_RECOVERY",
        currentTimeMs: Long = System.currentTimeMillis()
    ) {
        val now = currentTimeMs
        if (target == lastFocusTarget && (now - lastFocusRestoredTimeMs) < 500L) {
            // Deduplicate rapid duplicate focus restorations within 500ms window
            return
        }
        lastFocusTarget = target
        lastFocusRestoredTimeMs = now

        trace("FOCUS_RESTORED", "$target (from=$eventSource, reason=$reason)")
        try {
            VoxSafeLogger.info(
                VoxLogCategory.UI,
                VoxLogCode.HUD_FOCUS_RESTORED,
                "Фокус ввода восстановлен: $target",
                mapOf(
                    "focusTarget" to target,
                    "eventSource" to eventSource,
                    "reason" to reason
                )
            )
        } catch (_: Throwable) {
            // Safe in unit test environments
        }
    }

    @JvmStatic
    fun onSettingsVisibility(open: Boolean, category: String = "") {
        trace(if (open) "SETTINGS_OPENED" else "SETTINGS_CLOSED", category)
    }

    @JvmStatic
    fun onEngineSwitched(oldEngine: String, newEngine: String, manual: Boolean) {
        trace("ENGINE_SWITCHED", "$oldEngine -> $newEngine (manual=$manual)")
    }

    @JvmStatic
    fun onSelfHealAttempted(attempt: Int, success: Boolean) {
        trace("SELF_HEAL", "attempt=$attempt success=$success")
    }

    @JvmStatic
    fun getRecentTraces(): List<TraceEntry> = entries.toList()

    @JvmStatic
    fun resetForTesting() {
        entries.clear()
        lastFocusTarget = null
        lastFocusRestoredTimeMs = 0L
    }
}
