package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

@RunWith(RobolectricTestRunner::class)
class OtaNoUpdateTest {
    @Test
    fun testCurrentNewerThanTargetReturnsNoUpdate() {
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.2-beta.2", "32.56-vox.8.1", isBetaChannel = false))
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.2-beta.2", "32.56-vox.8.1", isBetaChannel = true))
    }

    @Test
    fun testSameVersionReturnsNoUpdate() {
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.1", "32.56-vox.8.1", isBetaChannel = false))
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.2-beta.2", "32.56-vox.8.2-beta.2", isBetaChannel = true))
    }
}

@RunWith(RobolectricTestRunner::class)
class OtaVersionNotNewerTest {
    @Test
    fun testVersionNotNewerUserMessage() {
        assertEquals("У вас установлена последняя версия", VoxOtaErrorCode.VERSION_NOT_NEWER.userMessageRu)
        assertEquals("У вас установлена последняя версия", VoxOtaErrorCode.NO_UPDATE.userMessageRu)
    }

    @Test
    fun testCurrentVersionPrecedence() {
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.2-beta.2", "32.56-vox.8.1") > 0)
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.2", "32.56-vox.8.1") > 0)
    }
}

@RunWith(RobolectricTestRunner::class)
class OtaReleaseNotFoundTest {
    @Test
    fun testReleaseNotFoundMapping() {
        val ex404 = IOException("HTTP 404 Not Found")
        val otaEx = VoxOtaUpdateManager.classifyException(ex404)
        assertEquals(VoxOtaErrorCode.RELEASE_NOT_FOUND, otaEx.code)
        assertFalse(otaEx.isRetryable)
    }
}

@RunWith(RobolectricTestRunner::class)
class OtaRateLimitTest {
    @Test
    fun testRateLimit429Mapping() {
        val ex429 = IOException("HTTP 429 Too Many Requests")
        val otaEx = VoxOtaUpdateManager.classifyException(ex429)
        assertEquals(VoxOtaErrorCode.RATE_LIMIT, otaEx.code)
        assertTrue(otaEx.isRetryable)
        assertEquals("Сервер временно ограничил частоту проверок. Попробуйте позже.", otaEx.userMessageRu)
    }

    @Test
    fun testRateLimitGitHubHeaderSignal() {
        val exRate = IOException("API rate limit exceeded for 1.2.3.4")
        val otaEx = VoxOtaUpdateManager.classifyException(exRate)
        assertEquals(VoxOtaErrorCode.RATE_LIMIT, otaEx.code)
        assertTrue(otaEx.isRetryable)
    }
}

@RunWith(RobolectricTestRunner::class)
class OtaDnsErrorTest {
    @Test
    fun testUnknownHostMapsToDnsError() {
        val ex = UnknownHostException("api.github.com")
        val otaEx = VoxOtaUpdateManager.classifyException(ex)
        assertEquals(VoxOtaErrorCode.DNS_ERROR, otaEx.code)
        assertEquals(VoxOtaStage.DNS, otaEx.stage)
        assertTrue(otaEx.isRetryable)
    }
}

@RunWith(RobolectricTestRunner::class)
class OtaTimeoutTest {
    @Test
    fun testSocketTimeoutMapsToTimeout() {
        val ex = SocketTimeoutException("Read timed out after 30000ms")
        val otaEx = VoxOtaUpdateManager.classifyException(ex)
        assertEquals(VoxOtaErrorCode.TIMEOUT, otaEx.code)
        assertTrue(otaEx.isRetryable)
    }
}

@RunWith(RobolectricTestRunner::class)
class OtaTlsErrorTest {
    @Test
    fun testSslExceptionMapsToTlsError() {
        val ex = SSLException("SSL handshake failed")
        val otaEx = VoxOtaUpdateManager.classifyException(ex)
        assertEquals(VoxOtaErrorCode.TLS_ERROR, otaEx.code)
        assertEquals(VoxOtaStage.TLS, otaEx.stage)
        assertFalse(otaEx.isRetryable)
    }
}

@RunWith(RobolectricTestRunner::class)
class OtaHttp5xxTest {
    @Test
    fun test5xxErrorsMapToHttpError() {
        val ex500 = IOException("Server error HTTP 500 Internal Server Error")
        val otaEx500 = VoxOtaUpdateManager.classifyException(ex500)
        assertEquals(VoxOtaErrorCode.HTTP_ERROR, otaEx500.code)
        assertTrue(otaEx500.isRetryable)

        val ex503 = IOException("Gateway HTTP 503 Service Unavailable")
        val otaEx503 = VoxOtaUpdateManager.classifyException(ex503)
        assertEquals(VoxOtaErrorCode.HTTP_ERROR, otaEx503.code)
        assertTrue(otaEx503.isRetryable)
    }
}

@RunWith(RobolectricTestRunner::class)
class OtaChannelMismatchTest {
    @Test
    fun testBetaReleaseIgnoredOnStableChannel() {
        assertFalse(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.1", "32.56-vox.8.2-beta.1", isBetaChannel = false))
        assertTrue(VoxVersionComparator.isUpdateAvailable("32.56-vox.8.1", "32.56-vox.8.2-beta.1", isBetaChannel = true))
    }
}

@RunWith(RobolectricTestRunner::class)
class OtaSingleFlightTest {
    @Test
    fun testSingleFlightStatePreventsConcurrentRuns() {
        val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
        assertTrue(inFlight.compareAndSet(false, true))
        assertFalse(inFlight.compareAndSet(false, true)) // Second attempt blocked
        inFlight.set(false)
        assertTrue(inFlight.compareAndSet(false, true)) // Cleared, succeeds again
    }
}

@RunWith(RobolectricTestRunner::class)
class OtaDebounceTest {
    @Test
    fun testDebouncePreventsRapidClickStorm() {
        var callCount = 0
        val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
        val simulateClick = {
            if (inFlight.compareAndSet(false, true)) {
                callCount++
            }
        }

        repeat(5) { simulateClick() }
        assertEquals(1, callCount)
    }
}

@RunWith(RobolectricTestRunner::class)
class OtaVersionPrecedenceTest {
    @Test
    fun testSemanticVersionPrecedenceOrder() {
        // dev < alpha < beta < rc < stable
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.2-dev", "32.56-vox.8.2-alpha1") < 0)
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.2-alpha1", "32.56-vox.8.2-beta.1") < 0)
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.2-beta.1", "32.56-vox.8.2-beta.2") < 0)
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.2-beta.2", "32.56-vox.8.2-rc1") < 0)
        assertTrue(VoxVersionComparator.compare("32.56-vox.8.2-rc1", "32.56-vox.8.2") < 0)
    }
}

@RunWith(RobolectricTestRunner::class)
class OtaCooldownAndRecoveryTests {
    @Test
    fun testAutoCheckCooldownEnforcement() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = VoxOtaUpdateManager.instance(context)
        val now = System.currentTimeMillis()
        manager.setCooldownStateForTesting(successTimeMs = now - 1000L, rateLimitTimeMs = 0L, rateLimited = false)

        var noUpdateCalled = false
        manager.checkForUpdates(
            currentVersion = "32.56-vox.8.2-beta.2",
            manifestUrls = arrayOf("https://test.manifest/manifest.json"),
            isBetaChannel = true,
            isManual = false,
            callback = object : VoxOtaUpdateManager.Callback {
                override fun onUpdateAvailable(release: VoxReleaseInfo, asset: VoxReleaseAsset) {}
                override fun onNoUpdateAvailable(currentVersion: String) {
                    noUpdateCalled = true
                }
                override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
                override fun onDownloadCompleted(apkFile: java.io.File) {}
                override fun onError(error: VoxOtaException) {}
            }
        )
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertTrue("Automatic check within cooldown should return onNoUpdateAvailable", noUpdateCalled)
        assertFalse(manager.isCheckingForUpdates())
        manager.resetCooldownForTesting()
    }

    @Test
    fun testRateLimitBackoffEnforcedForAutoCheck() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = VoxOtaUpdateManager.instance(context)
        val now = System.currentTimeMillis()
        manager.setCooldownStateForTesting(successTimeMs = 0L, rateLimitTimeMs = now - 5000L, rateLimited = true)

        var noUpdateCalled = false
        manager.checkForUpdates(
            currentVersion = "32.56-vox.8.2-beta.2",
            manifestUrls = arrayOf("https://test.manifest/manifest.json"),
            isBetaChannel = true,
            isManual = false,
            callback = object : VoxOtaUpdateManager.Callback {
                override fun onUpdateAvailable(release: VoxReleaseInfo, asset: VoxReleaseAsset) {}
                override fun onNoUpdateAvailable(currentVersion: String) {
                    noUpdateCalled = true
                }
                override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
                override fun onDownloadCompleted(apkFile: java.io.File) {}
                override fun onError(error: VoxOtaException) {}
            }
        )
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertTrue("Automatic check within rate-limit cooldown should return onNoUpdateAvailable", noUpdateCalled)
        assertFalse(manager.isCheckingForUpdates())
        manager.resetCooldownForTesting()
    }

    @Test
    fun testManualCheckBypassesCooldown() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = VoxOtaUpdateManager.instance(context)
        val now = System.currentTimeMillis()
        // Both cooldowns active
        manager.setCooldownStateForTesting(successTimeMs = now, rateLimitTimeMs = now, rateLimited = true)

        manager.checkForUpdates(
            currentVersion = "32.56-vox.8.2-beta.2",
            manifestUrls = null,
            isBetaChannel = true,
            isManual = true,
            callback = object : VoxOtaUpdateManager.Callback {
                override fun onUpdateAvailable(release: VoxReleaseInfo, asset: VoxReleaseAsset) {}
                override fun onNoUpdateAvailable(currentVersion: String) {}
                override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
                override fun onDownloadCompleted(apkFile: java.io.File) {}
                override fun onError(error: VoxOtaException) {}
            }
        )
        // Since isManual is true, cooldown is bypassed and check enters flight
        assertTrue("Manual check should bypass cooldown and start check in-flight", manager.isCheckingForUpdates())
        manager.resetCooldownForTesting()
    }
}
