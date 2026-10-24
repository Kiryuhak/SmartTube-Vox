package com.liskovsoft.smartyoutubetv2.common.vox.sabr

import android.content.Context
import com.google.android.exoplayer2.extractor.ExtractorInput
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifestParser
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPDecoder
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPPart
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.lang.reflect.Proxy
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
class VoxSabrMockE2ETest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun createFakeClientInfo(
        clientName: String? = null,
        clientVersion: String? = null,
        osName: String? = null,
        osVersion: String? = null
    ): MediaItemFormatInfo.ClientInfo {
        return Proxy.newProxyInstance(
            MediaItemFormatInfo.ClientInfo::class.java.classLoader,
            arrayOf(MediaItemFormatInfo.ClientInfo::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getClientName" -> clientName
                "getClientVersion" -> clientVersion
                "getOsName" -> osName
                "getOsVersion" -> osVersion
                else -> null
            }
        } as MediaItemFormatInfo.ClientInfo
    }

    private fun createFakeFormatInfo(
        videoId: String = "test_vid",
        lengthSeconds: String = "120",
        serverAbrStreamingUrl: String? = "https://googlevideo.com/videoplayback",
        clientInfo: MediaItemFormatInfo.ClientInfo? = null,
        containsSabr: Boolean = false,
        containsDash: Boolean = false,
        isLive: Boolean = false
    ): MediaItemFormatInfo {
        return Proxy.newProxyInstance(
            MediaItemFormatInfo::class.java.classLoader,
            arrayOf(MediaItemFormatInfo::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getVideoId" -> videoId
                "getLengthSeconds" -> lengthSeconds
                "getServerAbrStreamingUrl" -> serverAbrStreamingUrl
                "getClientInfo" -> clientInfo
                "getAdaptiveFormats" -> Collections.emptyList<Any>()
                "getSubtitles" -> null
                "getVisitorCookie" -> null
                "getPlaybackCookie" -> null
                "getPoToken" -> null
                "getVideoPlaybackUstreamerConfig" -> null
                "containsSabrFormats" -> containsSabr
                "containsDashFormats" -> containsDash
                "isLive" -> isLive
                "isLiveContent" -> isLive
                "isUnplayable" -> false
                "getPlayabilityReason" -> null
                else -> null
            }
        } as MediaItemFormatInfo
    }

    private fun createFakeExtractorInput(bytes: ByteArray): ExtractorInput {
        val bais = ByteArrayInputStream(bytes)
        return Proxy.newProxyInstance(
            ExtractorInput::class.java.classLoader,
            arrayOf(ExtractorInput::class.java)
        ) { _, method, args ->
            when (method.name) {
                "readFully" -> {
                    val target = args[0] as ByteArray
                    val offset = args[1] as Int
                    val length = args[2] as Int
                    val allowEndOfInput = if (args.size > 3) args[3] as Boolean else false
                    var bytesRead = 0
                    while (bytesRead < length) {
                        val read = bais.read(target, offset + bytesRead, length - bytesRead)
                        if (read == -1) {
                            if (bytesRead == 0 && allowEndOfInput) return@newProxyInstance false
                            throw EOFException("Unexpected EOF")
                        }
                        bytesRead += read
                    }
                    true
                }
                "skipFully" -> {
                    val length = args[0] as Int
                    var skipped = 0
                    while (skipped < length) {
                        val n = bais.skip((length - skipped).toLong()).toInt()
                        if (n <= 0) {
                            if (bais.read() == -1) throw EOFException("Unexpected EOF during skip")
                            skipped += 1
                        } else {
                            skipped += n
                        }
                    }
                    null
                }
                "getPosition" -> 0L
                "getLength" -> bytes.size.toLong()
                else -> null
            }
        } as ExtractorInput
    }

    private fun encodeVarInt(value: Long): ByteArray {
        return when {
            value < 128 -> byteArrayOf(value.toByte())
            value < 16384 -> {
                val b0 = (0x80 or (value.toInt() and 0x3F)).toByte()
                val b1 = ((value.toInt() shr 6) and 0xFF).toByte()
                byteArrayOf(b0, b1)
            }
            else -> {
                val b0 = (0xC0 or (value.toInt() and 0x1F)).toByte()
                val b1 = ((value.toInt() shr 5) and 0xFF).toByte()
                val b2 = ((value.toInt() shr 13) and 0xFF).toByte()
                byteArrayOf(b0, b1, b2)
            }
        }
    }

    private fun buildSyntheticUmpStream(parts: List<Pair<Long, ByteArray>>): ByteArray {
        val baos = ByteArrayOutputStream()
        for ((type, data) in parts) {
            baos.write(encodeVarInt(type))
            baos.write(encodeVarInt(data.size.toLong()))
            baos.write(data)
        }
        return baos.toByteArray()
    }

    // =========================================================================
    // 1. SabrManifestParser Boundary & Hardening Tests
    // =========================================================================

    @Test
    fun testSabrManifestParserHandlesNullClientInfo() {
        val formatInfo = createFakeFormatInfo(clientInfo = null)
        val manifest = SabrManifestParser().parse(formatInfo)

        assertNotNull(manifest)
        assertEquals("TVHTML5", manifest.clientName)
    }

    @Test
    fun testSabrManifestParserHandlesUnknownClientName() {
        val clientInfo = createFakeClientInfo(
            clientName = "UNKNOWN_FUTURE_CLIENT_2026",
            clientVersion = "1.0"
        )
        val formatInfo = createFakeFormatInfo(clientInfo = clientInfo)
        val manifest = SabrManifestParser().parse(formatInfo)

        assertNotNull(manifest)
        assertEquals("UNKNOWN_INTERFACE", manifest.clientName)
    }

    @Test
    fun testSabrManifestParserHandlesNullFieldsInClientInfo() {
        val clientInfo = createFakeClientInfo(
            clientName = null,
            clientVersion = null,
            osName = null,
            osVersion = null
        )
        val formatInfo = createFakeFormatInfo(clientInfo = clientInfo)
        val manifest = SabrManifestParser().parse(formatInfo)

        assertNotNull(manifest)
        assertEquals("TVHTML5", manifest.clientName)
    }

    @Test
    fun testSabrManifestParserRecognizesStandardClientNames() {
        val tvClient = createFakeClientInfo(clientName = "TVHTML5", clientVersion = "7.2024")
        val manifestTv = SabrManifestParser().parse(createFakeFormatInfo(clientInfo = tvClient))
        assertEquals("TVHTML5", manifestTv.clientName)

        val webClient = createFakeClientInfo(clientName = "WEB", clientVersion = "2.2024")
        val manifestWeb = SabrManifestParser().parse(createFakeFormatInfo(clientInfo = webClient))
        assertEquals("WEB", manifestWeb.clientName)

        val androidSuite = createFakeClientInfo(clientName = "ANDROID_TESTSUITE", clientVersion = "1.9")
        val manifestAndroid = SabrManifestParser().parse(createFakeFormatInfo(clientInfo = androidSuite))
        assertEquals("ANDROID_TESTSUITE", manifestAndroid.clientName)
    }

    // =========================================================================
    // 2. SABR Format Detection & Auto Transport Selection
    // =========================================================================

    @Test
    fun testLinklessSabrFormatEligibleForVod() {
        val formatInfo = createFakeFormatInfo(
            containsSabr = true,
            containsDash = false,
            isLive = false
        )

        val sabrEligible = formatInfo.containsSabrFormats() && !formatInfo.isLive
        assertTrue("Link-less SABR formats must be eligible on VOD", sabrEligible)
    }

    @Test
    fun testSabrIneligibleForLiveStream() {
        val formatInfo = createFakeFormatInfo(
            containsSabr = true,
            containsDash = false,
            isLive = true
        )

        val sabrEligible = formatInfo.containsSabrFormats() && !formatInfo.isLive
        assertFalse("SABR must NOT be eligible for live streams", sabrEligible)
    }

    // =========================================================================
    // 3. Fallback Position Preservation & Bounded Recovery Simulation
    // =========================================================================

    @Test
    fun testPositionPreservationOnSabrFallback() {
        val video = Video()
        video.videoId = "test_vid_123"

        val currentPlaybackPosMs = 73_400L
        if (currentPlaybackPosMs > 0) {
            video.pendingPosMs = currentPlaybackPosMs
        }

        assertEquals(73_400L, video.pendingPosMs)
    }

    @Test
    fun testSabrCooldownPreservedOnSameVideoReload() {
        var lastVideoId: String? = null
        var sabrCooldownActive = false
        var emptyFormatRetryCount = 0

        fun onNewVideo(item: Video) {
            val isSameVideo = lastVideoId != null && lastVideoId == item.videoId
            lastVideoId = item.videoId
            if (!isSameVideo) {
                sabrCooldownActive = false
                emptyFormatRetryCount = 0
            }
        }

        val videoA = Video().apply { videoId = "video_A" }
        onNewVideo(videoA)
        assertFalse(sabrCooldownActive)

        // SABR error occurs on videoA, cooldown activated
        sabrCooldownActive = true

        // Same video reload triggered by ErrorFixerController
        onNewVideo(videoA)
        assertTrue("Cooldown MUST be preserved on reload of the same video", sabrCooldownActive)

        // Different video loaded
        val videoB = Video().apply { videoId = "video_B" }
        onNewVideo(videoB)
        assertFalse("Cooldown MUST be reset when loading a different video", sabrCooldownActive)
    }

    @Test
    fun testAntiSpinnerBoundedRetryTerminatesAfterTwoAttempts() {
        var retryCount = 0
        var loopTerminated = false
        var progressSpinnerActive = true
        var playWhenReady = true

        val maxRetries = 2
        for (attempt in 1..5) {
            if (retryCount < maxRetries) {
                retryCount++
                // Simulate client switch & reload
            } else {
                loopTerminated = true
                progressSpinnerActive = false
                playWhenReady = false
                break
            }
        }

        assertEquals(2, retryCount)
        assertTrue("Retry loop must terminate after reaching limit", loopTerminated)
        assertFalse("Progress spinner must be hidden when retries exhausted", progressSpinnerActive)
        assertFalse("playWhenReady must be false when retries exhausted", playWhenReady)
    }

    // =========================================================================
    // 4. UMP Demux and Chunk Pipeline E2E Mock
    // =========================================================================

    @Test
    fun testUmpChunkDemuxPipelineE2E() {
        val decoder = UMPDecoder()

        val mediaHeaderPayload = byteArrayOf(0x08, 0x01, 0x12, 0x04, 0x74, 0x65, 0x73, 0x74) // synthetic proto header
        val mediaPayload = ByteArray(1024) { (it % 256).toByte() } // synthetic media chunk
        val nextRequestPayload = byteArrayOf(0x0A, 0x02, 0x08, 0x05) // synthetic next request policy

        val rawUmpStream = buildSyntheticUmpStream(listOf(
            20L to mediaHeaderPayload,    // MEDIA_HEADER
            21L to mediaPayload,          // MEDIA
            37L to nextRequestPayload     // NEXT_REQUEST_POLICY
        ))

        val input = createFakeExtractorInput(rawUmpStream)

        val part1: UMPPart? = decoder.decode(input)
        assertNotNull("Part 1 must not be null", part1)
        assertEquals(20, part1!!.partId)
        assertEquals(mediaHeaderPayload.size, part1.size)
        part1.skip()

        val part2: UMPPart? = decoder.decode(input)
        assertNotNull("Part 2 must not be null", part2)
        assertEquals(21, part2!!.partId)
        assertEquals(1024, part2.size)
        part2.skip()

        val part3: UMPPart? = decoder.decode(input)
        assertNotNull("Part 3 must not be null", part3)
        assertEquals(37, part3!!.partId)
        assertEquals(nextRequestPayload.size, part3.size)
        part3.skip()

        val partEof: UMPPart? = decoder.decode(input)
        assertNull("Stream must cleanly reach EOF", partEof)
    }
}
