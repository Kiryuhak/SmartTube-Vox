package com.liskovsoft.smartyoutubetv2.common.vox.playback

/**
 * Thread-safe test hooks for deterministic reproduction of player failure scenarios
 * during unit testing and emulator E2E verification.
 */
object VoxPlayerRecoveryTestHooks {
    @Volatile
    var bufferFreeze: Boolean = false

    @Volatile
    var dnsFailure: Boolean = false

    @Volatile
    var http403: Boolean = false

    @Volatile
    var sourceRefreshFailure: Boolean = false

    @Volatile
    var slowNetworkWithProgress: Boolean = false

    fun reset() {
        bufferFreeze = false
        dnsFailure = false
        http403 = false
        sourceRefreshFailure = false
        slowNetworkWithProgress = false
    }
}
