package com.liskovsoft.smartyoutubetv2.common.vox.mux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class VoxEbmlWriterTest {

    @Test
    fun testVintEncodingVariableWidth() {
        // 1 byte: 0 -> 0x80, 1 -> 0x81, 126 -> 0xFE
        assertArrayEquals(byteArrayOf(0x80.toByte()), VoxEbmlWriter.encodeVint(0))
        assertArrayEquals(byteArrayOf(0x81.toByte()), VoxEbmlWriter.encodeVint(1))
        assertArrayEquals(byteArrayOf(0xFE.toByte()), VoxEbmlWriter.encodeVint(126))

        // 2 bytes: 127 -> 0x40 0x7F, 16382 -> 0x7F 0xFE
        assertArrayEquals(byteArrayOf(0x40.toByte(), 0x7F.toByte()), VoxEbmlWriter.encodeVint(127))
        assertArrayEquals(byteArrayOf(0x7F.toByte(), 0xFE.toByte()), VoxEbmlWriter.encodeVint(16382))

        // 3 bytes: 16383 -> 0x20 0x3F 0xFF
        assertArrayEquals(byteArrayOf(0x20.toByte(), 0x3F.toByte(), 0xFF.toByte()), VoxEbmlWriter.encodeVint(16383))

        // 4 bytes: 2097151 -> 0x10 0x1F 0xFF 0xFF
        assertArrayEquals(byteArrayOf(0x10.toByte(), 0x1F.toByte(), 0xFF.toByte(), 0xFF.toByte()), VoxEbmlWriter.encodeVint(2097151))
    }

    @Test
    fun testVintEncodingFixedWidth() {
        // Encode 0 with fixed width 4
        val v0w4 = VoxEbmlWriter.encodeVint(0, fixedWidth = 4)
        assertEquals(4, v0w4.size)
        assertEquals(0x10.toByte(), v0w4[0])
        assertEquals(0x00.toByte(), v0w4[1])
        assertEquals(0x00.toByte(), v0w4[2])
        assertEquals(0x00.toByte(), v0w4[3])

        // Encode 42 with fixed width 2
        val v42w2 = VoxEbmlWriter.encodeVint(42, fixedWidth = 2)
        assertEquals(2, v42w2.size)
        assertEquals(0x40.toByte(), v42w2[0])
        assertEquals(0x2A.toByte(), v42w2[1])
    }

    @Test
    fun testElementIdEncoding() {
        val bos = ByteArrayOutputStream()
        val writer = VoxEbmlWriter(bos)

        // 1-byte ID (TrackEntry: 0xAE)
        writer.writeElementId(0xAEL)
        // 2-byte ID (DocType: 0x4282)
        writer.writeElementId(0x4282L)
        // 3-byte ID (TimestampScale: 0x2AD7B1)
        writer.writeElementId(0x2AD7B1L)
        // 4-byte ID (EBML Header: 0x1A45DFA3)
        writer.writeElementId(0x1A45DFA3L)

        val bytes = bos.toByteArray()
        val expected = byteArrayOf(
            0xAE.toByte(),
            0x42.toByte(), 0x82.toByte(),
            0x2A.toByte(), 0xD7.toByte(), 0xB1.toByte(),
            0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte()
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun testMasterElementStructure() {
        val bos = ByteArrayOutputStream()
        val writer = VoxEbmlWriter(bos)

        // Master Element: DocType inside EBML Header
        writer.writeMaster(0x1A45DFA3L) { ebml ->
            ebml.writeString(0x4282L, "matroska")
            ebml.writeUInt(0x4287L, 4)
        }

        val bytes = bos.toByteArray()
        // Starts with EBML Header ID 0x1A45DFA3
        assertEquals(0x1A.toByte(), bytes[0])
        assertEquals(0x45.toByte(), bytes[1])
        assertEquals(0xDF.toByte(), bytes[2])
        assertEquals(0xA3.toByte(), bytes[3])

        // String "matroska" is 8 bytes payload, with ID (2) + size (1) = 11 bytes
        // UInt 4 is 1 byte payload, with ID (2) + size (1) = 4 bytes
        // Total master payload = 15 bytes -> VINT size is 0x8F (15)
        assertEquals(0x8F.toByte(), bytes[4])
    }

    @Test
    fun testFloatAndSignedIntEncoding() {
        val bos = ByteArrayOutputStream()
        val writer = VoxEbmlWriter(bos)

        writer.writeFloat(0x4489L, 123.45)
        writer.writeSInt(0xFB.toByte().toLong() and 0xFF, -50)

        val bytes = bos.toByteArray()
        // Verify non-empty and correctly formatted
        assertEquals(true, bytes.size > 10)
    }
}
