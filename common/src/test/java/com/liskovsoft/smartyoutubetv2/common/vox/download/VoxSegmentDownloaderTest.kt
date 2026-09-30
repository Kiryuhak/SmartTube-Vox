package com.liskovsoft.smartyoutubetv2.common.vox.download

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VoxSegmentDownloaderTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempFile = File.createTempFile("vox_test_", ".part")
        tempFile.delete()
    }

    @After
    fun tearDown() {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    @Test
    fun testFreshDownload200() {
        val sampleData = "Hello World! This is full payload data for video segment.".toByteArray()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("video/mp4"), sampleData))
                    .build()
            }.build()

        val downloader = VoxSegmentDownloader(httpClient = client)
        var reportedBytes = 0L
        var reportedTotal: Long? = null

        downloader.download(
            initialUrl = "https://rr1---sn.googlevideo.com/videoplayback",
            targetFile = tempFile,
            onProgress = { bytes, total ->
                reportedBytes = bytes
                reportedTotal = total
            }
        )

        assertTrue(tempFile.exists())
        assertEquals(sampleData.size.toLong(), tempFile.length())
        assertArrayEquals(sampleData, tempFile.readBytes())
        assertEquals(sampleData.size.toLong(), reportedBytes)
        assertEquals(sampleData.size.toLong(), reportedTotal)
    }

    @Test
    fun testRangeResume206() {
        val fullData = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toByteArray()
        val initialPart = "0123456789".toByteArray() // 10 bytes already downloaded
        tempFile.writeBytes(initialPart)

        val capturedRange = ArrayList<String>()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val range = req.header("Range")
                if (range != null) capturedRange.add(range)

                val offset = 10
                val chunk = fullData.copyOfRange(offset, fullData.size)

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes 10-${fullData.size - 1}/${fullData.size}")
                    .body(ResponseBody.create(MediaType.parse("video/mp4"), chunk))
                    .build()
            }.build()

        val downloader = VoxSegmentDownloader(httpClient = client)
        downloader.download(
            initialUrl = "https://rr1---sn.googlevideo.com/videoplayback",
            targetFile = tempFile
        )

        assertEquals("bytes=10-", capturedRange.firstOrNull())
        assertTrue(tempFile.exists())
        assertEquals(fullData.size.toLong(), tempFile.length())
        assertArrayEquals(fullData, tempFile.readBytes())
    }

    @Test
    fun testServerResponds200WhenRangeRequestedTruncatesAndRestartsSafely() {
        // Если клиент запрашивал Range, но сервер не поддержал и вернул 200 OK —
        // файл должен быть безопасно перезаписан целиком без порчи данных
        val fullData = "CompleteNewDataFromZero".toByteArray()
        tempFile.writeBytes("corruptedOldData".toByteArray())

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("video/mp4"), fullData))
                    .build()
            }.build()

        val downloader = VoxSegmentDownloader(httpClient = client)
        downloader.download(
            initialUrl = "https://rr1---sn.googlevideo.com/videoplayback",
            targetFile = tempFile
        )

        assertEquals(fullData.size.toLong(), tempFile.length())
        assertArrayEquals(fullData, tempFile.readBytes())
    }

    @Test
    fun testUrlExpired403RefreshesUrlViaProvider() {
        val fullData = "ValidDataAfterRefresh".toByteArray()
        val requestCount = AtomicInteger(0)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val count = requestCount.incrementAndGet()
                if (count == 1) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body(ResponseBody.create(null, ByteArray(0)))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(MediaType.parse("video/mp4"), fullData))
                        .build()
                }
            }.build()

        val downloader = VoxSegmentDownloader(httpClient = client)
        var refreshedCount = 0

        downloader.download(
            initialUrl = "https://rr1---sn.googlevideo.com/expired_url",
            targetFile = tempFile,
            urlProvider = {
                refreshedCount++
                "https://rr1---sn.googlevideo.com/refreshed_url"
            }
        )

        assertEquals(1, refreshedCount)
        assertEquals(2, requestCount.get())
        assertArrayEquals(fullData, tempFile.readBytes())
    }

    @Test
    fun testRateLimit429RetrySuccess() {
        val fullData = "PayloadAfter429Retry".toByteArray()
        val requestCount = AtomicInteger(0)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val count = requestCount.incrementAndGet()
                if (count == 1) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body(ResponseBody.create(null, ByteArray(0)))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(MediaType.parse("video/mp4"), fullData))
                        .build()
                }
            }.build()

        val downloader = VoxSegmentDownloader(httpClient = client)
        downloader.download(
            initialUrl = "https://vtrans.yandex.net/audio/trans.mp3",
            targetFile = tempFile
        )

        assertEquals(2, requestCount.get())
        assertArrayEquals(fullData, tempFile.readBytes())
    }

    @Test
    fun testCancellationDuringRead() {
        val isCancelled = AtomicBoolean(false)
        val sampleData = ByteArray(1024 * 1024) // 1MB

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("video/mp4"), sampleData))
                    .build()
            }.build()

        val downloader = VoxSegmentDownloader(httpClient = client)
        try {
            downloader.download(
                initialUrl = "https://rr1---sn.googlevideo.com/videoplayback",
                targetFile = tempFile,
                isCancelled = { isCancelled.get() },
                onProgress = { bytes, _ ->
                    if (bytes > 64 * 1024) {
                        isCancelled.set(true)
                    }
                }
            )
            fail("Expected cancellation exception")
        } catch (e: VoxDownloadException) {
            assertEquals(VoxDownloadErrorCode.CANCELLED, e.code)
        }
    }
}
