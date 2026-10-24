package com.liskovsoft.smartyoutubetv2.common.vox.sabr

import android.content.Context
import com.google.android.exoplayer2.extractor.ExtractorInput
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPDecoder
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPPart
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class VoxUmpParserTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
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
                            throw EOFException("Unexpected EOF while reading $length bytes (read $bytesRead)")
                        }
                        bytesRead += read
                    }
                    true
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
            value < 16384 -> { // 2 bytes: 6 bits in first byte, 8 bits in second
                val b0 = (0x80 or (value.toInt() and 0x3F)).toByte()
                val b1 = ((value.toInt() shr 6) and 0xFF).toByte()
                byteArrayOf(b0, b1)
            }
            value < 2097152 -> { // 3 bytes: 5 bits in first byte, 8 bits in next 2
                val b0 = (0xC0 or (value.toInt() and 0x1F)).toByte()
                val b1 = ((value.toInt() shr 5) and 0xFF).toByte()
                val b2 = ((value.toInt() shr 13) and 0xFF).toByte()
                byteArrayOf(b0, b1, b2)
            }
            else -> {
                // Generic 4-byte encoding
                val b0 = (0xE0 or (value.toInt() and 0x0F)).toByte()
                val b1 = ((value.toInt() shr 4) and 0xFF).toByte()
                val b2 = ((value.toInt() shr 12) and 0xFF).toByte()
                val b3 = ((value.toInt() shr 20) and 0xFF).toByte()
                byteArrayOf(b0, b1, b2, b3)
            }
        }
    }

    @Test
    fun testVarIntSizePrefixCalculation() {
        assertEquals(1, UMPDecoder.varIntSize(0))
        assertEquals(1, UMPDecoder.varIntSize(127))
        assertEquals(2, UMPDecoder.varIntSize(128))
        assertEquals(2, UMPDecoder.varIntSize(191))
        assertEquals(3, UMPDecoder.varIntSize(192))
        assertEquals(3, UMPDecoder.varIntSize(223))
        assertEquals(4, UMPDecoder.varIntSize(224))
        assertEquals(4, UMPDecoder.varIntSize(239))
        assertEquals(5, UMPDecoder.varIntSize(240))
        assertEquals(5, UMPDecoder.varIntSize(255))
    }

    @Test
    fun testDecodeSingleByteVarInt() {
        val decoder = UMPDecoder()
        val stream = ByteArrayInputStream(byteArrayOf(42))
        val value = decoder.readVarInt(stream)
        assertEquals(42L, value)
    }

    @Test
    fun testDecodeTwoByteVarInt() {
        val decoder = UMPDecoder()
        val encoded = encodeVarInt(300L)
        val stream = ByteArrayInputStream(encoded)
        val value = decoder.readVarInt(stream)
        assertEquals(300L, value)
    }

    @Test
    fun testCleanEofReturnsNull() {
        val decoder = UMPDecoder()
        val emptyInput = createFakeExtractorInput(ByteArray(0))
        val part = decoder.decode(emptyInput)
        assertNull("Decoder must return null on clean EOF", part)
    }

    @Test
    fun testTruncatedVarIntThrowsException() {
        val decoder = UMPDecoder()
        // 0x80 indicates 2-byte varint, but second byte is missing
        val truncatedInput = createFakeExtractorInput(byteArrayOf(0x80.toByte()))
        try {
            decoder.decode(truncatedInput)
            fail("Decoder must throw on truncated VarInt in part type")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception cause must be EOFException or IOException",
                e.cause is EOFException || e.cause is IOException
            )
        }
    }

    @Test
    fun testMissingPartSizeThrowsException() {
        val decoder = UMPDecoder()
        // Part type = 20 (valid 1-byte), but no size follows
        val truncatedInput = createFakeExtractorInput(byteArrayOf(20))
        try {
            decoder.decode(truncatedInput)
            fail("Decoder must throw on missing part size")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception message must mention EOF while reading part size",
                e.message?.contains("Unexpected EOF") == true
            )
        }
    }

    @Test
    fun testOversizedPartSizeThrowsException() {
        val decoder = UMPDecoder(1024) // Limit to 1 KB
        val partType = encodeVarInt(20)
        val oversized = encodeVarInt(2048) // Exceeds 1024
        val input = createFakeExtractorInput(partType + oversized)
        try {
            decoder.decode(input)
            fail("Decoder must throw when part size exceeds max limit")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception message must indicate out of bounds size",
                e.message?.contains("out of bounds") == true
            )
        }
    }

    @Test
    fun testValidUmpPartDecoding() {
        val decoder = UMPDecoder()
        val partType = encodeVarInt(20) // MediaHeader (20)
        val partSize = encodeVarInt(4)
        val payload = byteArrayOf(10, 20, 30, 40)
        val input = createFakeExtractorInput(partType + partSize + payload)

        val part: UMPPart? = decoder.decode(input)
        assertNotNull("Decoded part must not be null", part)
        assertEquals(20, part!!.partId)
        assertEquals(4, part.size)
    }

    @Test
    fun testVideoTransportPreferenceDefaultsAndPersistence() {
        val tweaks = PlayerTweaksData.instance(context)
        assertEquals(
            "Default transport must be AUTO",
            PlayerTweaksData.VIDEO_TRANSPORT_AUTO,
            tweaks.videoTransportPreference
        )

        tweaks.videoTransportPreference = PlayerTweaksData.VIDEO_TRANSPORT_DASH
        assertEquals(PlayerTweaksData.VIDEO_TRANSPORT_DASH, tweaks.videoTransportPreference)

        tweaks.videoTransportPreference = PlayerTweaksData.VIDEO_TRANSPORT_SABR
        assertEquals(PlayerTweaksData.VIDEO_TRANSPORT_SABR, tweaks.videoTransportPreference)

        tweaks.videoTransportPreference = PlayerTweaksData.VIDEO_TRANSPORT_AUTO
        assertEquals(PlayerTweaksData.VIDEO_TRANSPORT_AUTO, tweaks.videoTransportPreference)
    }

    @Test
    fun testSabrTelemetryLogCodesExistAndDistinct() {
        val codes = listOf(
            VoxLogCode.SABR_ELIGIBLE,
            VoxLogCode.SABR_SELECTED,
            VoxLogCode.SABR_INIT,
            VoxLogCode.SABR_MEDIA_PROGRESS,
            VoxLogCode.SABR_PACING,
            VoxLogCode.SABR_SESSION_RESET,
            VoxLogCode.SABR_RECOVERY_STARTED,
            VoxLogCode.SABR_RECOVERED,
            VoxLogCode.SABR_FALLBACK,
            VoxLogCode.SABR_TERMINAL_ERROR
        )

        // All 10 must be non-empty strings
        for (code in codes) {
            assertTrue("SABR log code must not be empty", code.isNotEmpty())
            assertTrue("SABR log code must start with SABR_", code.startsWith("SABR_"))
        }

        // All 10 must be unique
        val uniqueCodes = codes.toSet()
        assertEquals("All 10 SABR log codes must be unique", 10, uniqueCodes.size)
    }
}
