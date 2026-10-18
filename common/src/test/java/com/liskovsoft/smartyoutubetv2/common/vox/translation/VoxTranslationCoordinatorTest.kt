package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VoxTranslationCoordinatorTest {

    @Before
    fun setUp() {
        VoxTranslationCoordinator.clear()
    }

    @Test
    fun testSingleFlightDeduplication() {
        val counter = AtomicInteger(0)
        val threadCount = 10
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val results = arrayOfNulls<YandexVotApiClient.TranslationResult>(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (i in 0 until threadCount) {
            val index = i
            executor.submit {
                try {
                    startLatch.await()
                    results[index] = VoxTranslationCoordinator.executeSingleFlight("dedup-key-1") {
                        counter.incrementAndGet()
                        Thread.sleep(50) // simulate network latency
                        YandexVotApiClient.TranslationResult(
                            YandexVotApiClient.STATUS_FINISHED,
                            "https://storage.yandex.net/audio.mp3",
                            0,
                            "trans-123",
                            "OK"
                        )
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // Release all threads simultaneously
        startLatch.countDown()
        doneLatch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        // Counter must be exactly 1 despite 10 concurrent requests
        assertEquals(1, counter.get())

        // All threads must have received the same non-null result
        for (i in 0 until threadCount) {
            val res = results[i]
            assertNotNull(res)
            assertEquals("https://storage.yandex.net/audio.mp3", res!!.audioUrl)
            assertEquals("trans-123", res.translationId)
        }
    }

    @Test
    fun testDistinctKeysExecuteSeparately() {
        val counter = AtomicInteger(0)
        val res1 = VoxTranslationCoordinator.executeSingleFlight("key-A") {
            counter.incrementAndGet()
            YandexVotApiClient.TranslationResult(1, "https://audio-a.mp3", 0, "id-a", "OK")
        }
        val res2 = VoxTranslationCoordinator.executeSingleFlight("key-B") {
            counter.incrementAndGet()
            YandexVotApiClient.TranslationResult(1, "https://audio-b.mp3", 0, "id-b", "OK")
        }

        assertEquals(2, counter.get())
        assertEquals("https://audio-a.mp3", res1.audioUrl)
        assertEquals("https://audio-b.mp3", res2.audioUrl)
    }
}
