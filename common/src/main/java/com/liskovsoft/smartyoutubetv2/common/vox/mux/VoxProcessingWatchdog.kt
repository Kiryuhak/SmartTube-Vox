package com.liskovsoft.smartyoutubetv2.common.vox.mux

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Следит за движением данных, а не за полной длительностью упаковки. */
class VoxProcessingWatchdog(
    private val timeoutMs: Long = 120_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : AutoCloseable {
    private var lastProgress = clock()
    private var samples = 0L
    private var bytes = 0L
    @Volatile var stalled = false
        private set
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "VoxProcessingWatchdog").apply { isDaemon = true }
    }

    @Synchronized fun progress(processedSamples: Long, outputBytes: Long) {
        if (processedSamples > samples || outputBytes > bytes) {
            samples = maxOf(samples, processedSamples)
            bytes = maxOf(bytes, outputBytes)
            lastProgress = clock()
        }
    }

    @Synchronized fun check(): Boolean {
        if (clock() - lastProgress >= timeoutMs) stalled = true
        return stalled
    }

    fun start(abort: () -> Unit) {
        scheduler.scheduleWithFixedDelay({ if (check()) { close(); abort() } },
            minOf(timeoutMs, 1_000L), minOf(timeoutMs, 1_000L), TimeUnit.MILLISECONDS)
    }

    override fun close() { scheduler.shutdownNow() }
}

class VoxProcessingStalledException(cause: Throwable? = null) :
    java.io.IOException("Processing stopped making progress", cause)
