package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.mediaserviceinterfaces.MediaItemService
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class VoxPrompt050DownloadRecoveryTest {

    private lateinit var tempDir: File
    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "vox_050_test_" + System.currentTimeMillis())
        tempDir.mkdirs()
        tempFile = File(tempDir, "test_download.mp4")
    }

    @After
    fun tearDown() {
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // Test 1: 2160p selection end-to-end (selection -> format resolution -> output dimension validation: 3840x2160)
    // =========================================================================
    @Test
    fun test01_Selection2160pEndToEndWithDimensionsValidation() {
        val f4k = MockFormat(
            width = 3840,
            height = 2160,
            mimeType = "video/mp4; codecs=\"avc1.640033\"",
            url = "https://rr1---sn-4g5edn6s.googlevideo.com/videoplayback?itag=313",
            itag = "313",
            bitrate = "20000000"
        )
        val f1080 = MockFormat(
            width = 1920,
            height = 1080,
            mimeType = "video/mp4; codecs=\"avc1.640028\"",
            url = "https://rr1---sn-4g5edn6s.googlevideo.com/videoplayback?itag=137",
            itag = "137",
            bitrate = "5000000"
        )
        val audioFormat = MockFormat(
            width = 0,
            height = 0,
            mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
            url = "https://rr1---sn-4g5edn6s.googlevideo.com/videoplayback?itag=140",
            itag = "140",
            bitrate = "128000"
        )

        val service = createMockService(createMockFormatInfo(listOf(f4k, f1080, audioFormat)))
        val resolver = DefaultVoxStreamResolver { service }

        val resolved = resolver.resolveVideoStream("vid_4k_test", VoxQualityPreference.QUALITY_2160P)
        assertNotNull(resolved)
        assertEquals(2160, resolved.height)
        assertEquals(3840, resolved.width)
        assertEquals(313, resolved.itag)

        // Validate strictly that only 2160p media dimensions qualify as 4K
        val isTrue4K = (resolved.width == 3840 && resolved.height == 2160) || (resolved.height == 2160)
        assertTrue("Resolved stream must have true 2160p height", isTrue4K)
        assertFalse("1080p must not be mistaken for 4K", resolved.height == 1080)
    }

    // =========================================================================
    // Test 2: Reject 720p/1080p disguised as 2160p
    // =========================================================================
    @Test
    fun test02_RejectDisguisedResolutionsAs2160p() {
        val f1080 = MockFormat(
            width = 1920,
            height = 1080,
            mimeType = "video/mp4; codecs=\"avc1.640028\"",
            url = "https://rr1---sn-4g5edn6s.googlevideo.com/videoplayback?itag=137",
            itag = "137"
        )
        val f720 = MockFormat(
            width = 1280,
            height = 720,
            mimeType = "video/mp4; codecs=\"avc1.4d401f\"",
            url = "https://rr1---sn-4g5edn6s.googlevideo.com/videoplayback?itag=136",
            itag = "136"
        )

        val service = createMockService(createMockFormatInfo(listOf(f1080, f720)))
        val resolver = DefaultVoxStreamResolver { service }

        try {
            resolver.resolveVideoStream("vid_no_4k", VoxQualityPreference.QUALITY_2160P)
            fail("Expected VoxDownloadException when 2160p stream is absent")
        } catch (e: VoxDownloadException) {
            assertTrue(
                "Error code must be NO_FORMAT_2160P or QUALITY_UNAVAILABLE, was: ${e.code}",
                e.code == VoxDownloadErrorCode.NO_FORMAT_2160P || e.code == VoxDownloadErrorCode.QUALITY_UNAVAILABLE
            )
            assertTrue("Message must indicate 4K/2160p is unavailable", e.message?.contains("2160p") == true || e.message?.contains("4K") == true)
        }
    }

    // =========================================================================
    // Test 3: Expired URL / 410 Gone / 403 Forbidden handling
    // =========================================================================
    @Test
    fun test03_ExpiredUrl410And403RetryPolicyBounded() {
        val policy = VoxYouTubeRetryPolicy(maxRetries = 3, initialBackoffMs = 500L, backoffMultiplier = 2.0)

        // HTTP 410 with URL refresh provider -> RefreshUrlAndRetry
        val refreshDecision410 = policy.evaluate(VoxYouTubeErrorCategory.HTTP_410, currentAttempt = 0, hasUrlRefreshProvider = true)
        assertEquals(VoxRetryDecision.RefreshUrlAndRetry, refreshDecision410)

        // HTTP 403 with URL refresh provider -> RefreshUrlAndRetry
        val refreshDecision403 = policy.evaluate(VoxYouTubeErrorCategory.HTTP_403, currentAttempt = 1, hasUrlRefreshProvider = true)
        assertEquals(VoxRetryDecision.RefreshUrlAndRetry, refreshDecision403)

        // Reaching max retries -> Fatal (bounded, strictly no infinite loop)
        val fatalDecision = policy.evaluate(VoxYouTubeErrorCategory.HTTP_410, currentAttempt = 3, hasUrlRefreshProvider = true)
        assertTrue("Must be Fatal once max retries exceeded", fatalDecision is VoxRetryDecision.Fatal)

        // Downloader HTTP 410 -> Throws MANIFEST_EXPIRED
        val client410 = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(410)
                .message("Gone")
                .body(ResponseBody.create(MediaType.parse("text/plain"), "Gone"))
                .build()
        }.build()

        val downloader410 = VoxSegmentDownloader(httpClient = client410)
        try {
            downloader410.download("https://googlevideo.com/expired", tempFile)
            fail("Expected exception on HTTP 410")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.MANIFEST_EXPIRED, e.code)
        }

        // Downloader HTTP 403 -> Throws HTTP_403
        val client403 = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Forbidden")
                .body(ResponseBody.create(MediaType.parse("text/plain"), "Forbidden"))
                .build()
        }.build()

        val downloader403 = VoxSegmentDownloader(httpClient = client403)
        try {
            downloader403.download("https://googlevideo.com/forbidden", tempFile)
            fail("Expected exception on HTTP 403")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.HTTP_403, e.code)
        }
    }

    // =========================================================================
    // Test 4: Network timeout differentiation
    // =========================================================================
    @Test
    fun test04_NetworkTimeoutDifferentiation() {
        val policy = VoxYouTubeRetryPolicy(maxRetries = 3, initialBackoffMs = 1000L, backoffMultiplier = 2.0)

        // Connect timeout -> CDN_CONNECT_TIMEOUT
        val catConnect = policy.classifyError(SocketTimeoutException("Connect timed out to googlevideo.com"), null)
        assertEquals(VoxYouTubeErrorCategory.CDN_CONNECT_TIMEOUT, catConnect)

        // Read timeout -> CDN_READ_TIMEOUT
        val catRead = policy.classifyError(SocketTimeoutException("Read timed out from googlevideo.com"), null)
        assertEquals(VoxYouTubeErrorCategory.CDN_READ_TIMEOUT, catRead)

        // Both categories respect max_retries with backoff
        val decisionConnect0 = policy.evaluate(VoxYouTubeErrorCategory.CDN_CONNECT_TIMEOUT, currentAttempt = 0)
        assertTrue(decisionConnect0 is VoxRetryDecision.Retry)
        assertEquals(1000L, (decisionConnect0 as VoxRetryDecision.Retry).delayMs)

        val decisionConnect1 = policy.evaluate(VoxYouTubeErrorCategory.CDN_CONNECT_TIMEOUT, currentAttempt = 1)
        assertTrue(decisionConnect1 is VoxRetryDecision.Retry)
        assertEquals(2000L, (decisionConnect1 as VoxRetryDecision.Retry).delayMs)

        val decisionRead2 = policy.evaluate(VoxYouTubeErrorCategory.CDN_READ_TIMEOUT, currentAttempt = 2)
        assertTrue(decisionRead2 is VoxRetryDecision.Retry)
        assertEquals(4000L, (decisionRead2 as VoxRetryDecision.Retry).delayMs)

        val decisionRead3 = policy.evaluate(VoxYouTubeErrorCategory.CDN_READ_TIMEOUT, currentAttempt = 3)
        assertTrue("Exceeding max retries must yield Fatal", decisionRead3 is VoxRetryDecision.Fatal)
    }

    // =========================================================================
    // Test 5: Partial/resumable download: range support, mismatch & 416
    // =========================================================================
    @Test
    fun test05_PartialResumableDownloadRangeHandling() {
        // Scenario A: Resuming with valid 206 Content-Range
        tempFile.writeBytes("12345".toByteArray()) // 5 existing bytes
        val appendedPayload = "67890".toByteArray() // 5 more bytes

        val valid206Client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Content-Range", "bytes 5-9/10")
                .body(ResponseBody.create(MediaType.parse("video/mp4"), appendedPayload))
                .build()
        }.build()

        val downloaderA = VoxSegmentDownloader(httpClient = valid206Client)
        downloaderA.download("https://googlevideo.com/resume", tempFile)
        assertEquals("1234567890", String(tempFile.readBytes()))

        // Scenario B: Server returns mismatched start range (Content-Range start != existingOffset)
        // Downloader must delete corrupted file, re-start from 0, and succeed
        val mismatch206Client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Content-Range", "bytes 0-9/10")
                .body(ResponseBody.create(MediaType.parse("video/mp4"), "bad_bytes_".toByteArray()))
                .build()
        }.build()

        val downloaderB = VoxSegmentDownloader(httpClient = mismatch206Client)
        downloaderB.download("https://googlevideo.com/mismatch", tempFile)
        assertEquals("bad_bytes_", String(tempFile.readBytes()))

        // Scenario B2: Persistent range mismatch where server never satisfies range -> throws RANGE_MISMATCH
        val persistentMismatchClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Content-Range", "bytes 50-99/100") // always starts at 50, but file has 0
                .body(ResponseBody.create(MediaType.parse("video/mp4"), "unaligned".toByteArray()))
                .build()
        }.build()

        tempFile.delete()
        val downloaderPersistent = VoxSegmentDownloader(httpClient = persistentMismatchClient)
        try {
            downloaderPersistent.download("https://googlevideo.com/persistent_mismatch", tempFile)
            fail("Expected RANGE_MISMATCH on persistent start range mismatch")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.RANGE_MISMATCH, e.code)
            assertFalse("Corrupted/unaligned file must not remain on disk", tempFile.exists())
        }

        // Scenario C: HTTP 416 (Range Not Satisfiable)
        tempFile.writeBytes("dummy".toByteArray())
        val client416 = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(416)
                .message("Range Not Satisfiable")
                .body(ResponseBody.create(MediaType.parse("text/plain"), "Range Not Satisfiable"))
                .build()
        }.build()

        val downloaderC = VoxSegmentDownloader(httpClient = client416)
        try {
            downloaderC.download("https://googlevideo.com/range416", tempFile)
            fail("Expected exception on HTTP 416")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.RANGE_MISMATCH, e.code)
            assertFalse("File must be reset/deleted on 416 range mismatch", tempFile.exists())
        }
    }

    // =========================================================================
    // Test 6: Cancellation and cleanup: immediate abort and clean temp files
    // =========================================================================
    @Test
    fun test06_CancellationAndCleanupAtAnyStage() {
        val cancelled = AtomicBoolean(false)
        val infiniteStream = object : InputStream() {
            private var count = 0
            override fun read(): Int {
                if (count++ > 50) {
                    cancelled.set(true)
                }
                return 42
            }
        }

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.parse("video/mp4"), -1L, infiniteStream.source().buffer()))
                .build()
        }.build()

        val downloader = VoxSegmentDownloader(httpClient = client)
        try {
            downloader.download(
                initialUrl = "https://googlevideo.com/stream",
                targetFile = tempFile,
                isCancelled = { cancelled.get() }
            )
            fail("Expected cancellation exception")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.CANCELLED, e.code)
        }

        // Verify no leftover partial files exist in destination
        assertFalse("Target file must be cleaned up on cancellation", tempFile.exists())
    }

    // =========================================================================
    // Test 7: Zero-byte file prevention
    // =========================================================================
    @Test
    fun test07_ZeroByteFilePrevention() {
        val emptyClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.parse("video/mp4"), ByteArray(0)))
                .build()
        }.build()

        val downloader = VoxSegmentDownloader(httpClient = emptyClient)
        try {
            downloader.download("https://googlevideo.com/empty", tempFile)
            fail("Expected exception on zero-byte stream")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.PARTIAL_FILE, e.code)
            assertTrue("Message must mention empty or zero-byte file", e.message?.contains("0 bytes", ignoreCase = true) == true || e.message?.contains("0 байт") == true || e.message?.contains("empty", ignoreCase = true) == true)
        }

        assertFalse("Zero-byte file must be cleaned up and not remain on disk", tempFile.exists())
    }

    // =========================================================================
    // Test 8: State transitions and specific error codes
    // =========================================================================
    @Test
    fun test08_StateTransitionsAndErrorCodes() {
        // Verify stageName strings across the lifecycle
        assertEquals("QUEUED", VoxDownloadState.IDLE.stageName)
        assertEquals("RESOLVING", VoxDownloadState.RESOLVING_STREAMS.stageName)
        assertEquals("CONNECTING", VoxDownloadState.CONNECTING.stageName)
        assertEquals("DOWNLOADING", VoxDownloadState.DOWNLOADING_MEDIA.stageName)
        assertEquals("VERIFYING", VoxDownloadState.VERIFYING.stageName)
        assertEquals("COMPLETED", VoxDownloadState.COMPLETED.stageName)
        assertEquals("FAILED", VoxDownloadState.FAILED.stageName)
        assertEquals("CANCELLED", VoxDownloadState.CANCELLED.stageName)

        // Verify all required error codes exist and map correctly in failure classifier
        val expectedCodes = listOf(
            VoxDownloadErrorCode.NO_FORMAT_2160P,
            VoxDownloadErrorCode.CDN_CONNECT_TIMEOUT,
            VoxDownloadErrorCode.CDN_READ_TIMEOUT,
            VoxDownloadErrorCode.HTTP_403,
            VoxDownloadErrorCode.HTTP_429,
            VoxDownloadErrorCode.RANGE_MISMATCH,
            VoxDownloadErrorCode.MANIFEST_EXPIRED,
            VoxDownloadErrorCode.PARTIAL_FILE
        )

        for (code in expectedCodes) {
            val ex = VoxDownloadException(code, "Test error")
            val cat = VoxDownloadFailureClassifier.classify(ex)
            assertNotNull("Category must be resolved for $code", cat)
            assertNotNull("User friendly message must exist for $code", cat.userMessageRu)
            assertFalse("Message must not be blank for $code", cat.userMessageRu.isBlank())
        }
    }

    // =========================================================================
    // Test 9: TV lifecycle / app backgrounding policy
    // =========================================================================
    @Test
    fun test09_TvLifecycleAppBackgroundingPolicy() {
        // In background or foreground, active states must remain active
        assertTrue(VoxDownloadServicePolicy.isActiveState(VoxDownloadState.CONNECTING))
        assertTrue(VoxDownloadServicePolicy.isActiveState(VoxDownloadState.DOWNLOADING_MEDIA))
        assertTrue(VoxDownloadServicePolicy.isActiveState(VoxDownloadState.VERIFYING))
        assertFalse(VoxDownloadServicePolicy.isActiveState(VoxDownloadState.COMPLETED))
        assertFalse(VoxDownloadServicePolicy.isActiveState(VoxDownloadState.FAILED))

        // When returning to app (ACTION_START_DOWNLOAD / ACTION_RESUME_DOWNLOAD):
        // If already active, should NOT duplicate
        assertFalse(VoxDownloadServicePolicy.shouldAutoResumeOnStart(VoxDownloadState.DOWNLOADING_MEDIA, isAlreadyActive = true))
        assertFalse(VoxDownloadServicePolicy.shouldResumeOnExplicitResume(VoxDownloadState.DOWNLOADING_MEDIA, isAlreadyActive = true))

        // If interrupted / queued / paused, can resume cleanly
        assertTrue(VoxDownloadServicePolicy.shouldAutoResumeOnStart(VoxDownloadState.QUEUED, isAlreadyActive = false))
        assertTrue(VoxDownloadServicePolicy.shouldResumeOnExplicitResume(VoxDownloadState.PAUSED, isAlreadyActive = false))

        // Terminal states never auto-resume
        assertFalse(VoxDownloadServicePolicy.shouldAutoResumeOnStart(VoxDownloadState.COMPLETED, isAlreadyActive = false))
        assertFalse(VoxDownloadServicePolicy.shouldAutoResumeOnStart(VoxDownloadState.FAILED, isAlreadyActive = false))
        assertFalse(VoxDownloadServicePolicy.shouldResumeOnExplicitResume(VoxDownloadState.CANCELLED, isAlreadyActive = false))
    }

    // =========================================================================
    // Test Helpers
    // =========================================================================
    private class MockFormat(
        private val url: String? = "https://rr.googlevideo.com/videoplayback?id=test",
        private val mimeType: String? = "video/mp4; codecs=\"avc1.640028\"",
        private val height: Int = 1080,
        private val width: Int = 1920,
        private val bitrate: String? = "5000000",
        private val clen: String? = "100000000",
        private val itag: String? = "137",
        private val audioTrackId: String? = null,
        private val language: String? = null
    ) : MediaFormat {
        override fun getFormatType(): Int = MediaFormat.FORMAT_TYPE_DASH
        override fun getUrl(): String? = url
        override fun getMimeType(): String? = mimeType
        override fun getITag(): String? = itag
        override fun isDrc(): Boolean = false
        override fun getClen(): String? = clen
        override fun getBitrate(): String? = bitrate
        override fun getProjectionType(): String? = null
        override fun getXtags(): String? = null
        override fun getAudioTrackId(): String? = audioTrackId
        override fun getWidth(): Int = width
        override fun getHeight(): Int = height
        override fun getIndex(): String? = null
        override fun getInit(): String? = null
        override fun getFps(): String? = "30"
        override fun getLmt(): String? = null
        override fun getQualityLabel(): String? = "${height}p"
        override fun getFormat(): String? = null
        override fun isOtf(): Boolean = false
        override fun getOtfInitUrl(): String? = null
        override fun getOtfTemplateUrl(): String? = null
        override fun getLanguage(): String? = language
        override fun getTargetDurationSec(): Int = 0
        override fun getMaxDvrDurationSec(): Int = 0
        override fun getApproxDurationMs(): Int = 0
        override fun getQuality(): String? = null
        override fun getSignature(): String? = null
        override fun getAudioSamplingRate(): String? = null
        override fun getSourceUrl(): String? = null
        override fun getSegmentUrlList(): List<String>? = null
        override fun getGlobalSegmentList(): List<String>? = null
        override fun compareTo(other: MediaFormat?): Int = 0
    }

    private fun createMockFormatInfo(adaptive: List<MediaFormat>): MediaItemFormatInfo {
        return Proxy.newProxyInstance(
            MediaItemFormatInfo::class.java.classLoader,
            arrayOf(MediaItemFormatInfo::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getAdaptiveFormats" -> adaptive
                "getUrlFormats" -> emptyList<MediaFormat>()
                "containsMedia" -> true
                "containsDashFormats" -> true
                else -> null
            }
        } as MediaItemFormatInfo
    }

    private fun createMockService(formatInfo: MediaItemFormatInfo?): MediaItemService {
        return Proxy.newProxyInstance(
            MediaItemService::class.java.classLoader,
            arrayOf(MediaItemService::class.java)
        ) { _, method, _ ->
            if (method.name == "getFormatInfo") {
                formatInfo
            } else {
                null
            }
        } as MediaItemService
    }
}
