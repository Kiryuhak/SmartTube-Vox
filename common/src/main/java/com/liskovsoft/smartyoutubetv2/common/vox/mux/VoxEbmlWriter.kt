package com.liskovsoft.smartyoutubetv2.common.vox.mux

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Чистая, потокобезопасная реализация EBML (Extensible Binary Meta Language) кодировщика
 * в соответствии со спецификацией RFC 8794 для контейнера Matroska.
 */
class VoxEbmlWriter(private val outputStream: OutputStream) {

    /**
     * Записывает идентификатор элемента EBML (Element ID).
     * Идентификаторы EBML уже содержат VINT-маркер разрядности.
     */
    fun writeElementId(id: Long) {
        when {
            id in 0x80..0xFF -> {
                outputStream.write(id.toInt())
            }
            id in 0x4000..0x7FFF -> {
                outputStream.write((id shr 8).toInt())
                outputStream.write(id.toInt() and 0xFF)
            }
            id in 0x200000..0x3FFFFF -> {
                outputStream.write((id shr 16).toInt())
                outputStream.write((id shr 8).toInt() and 0xFF)
                outputStream.write(id.toInt() and 0xFF)
            }
            id in 0x10000000L..0x1FFFFFFFL -> {
                outputStream.write((id shr 24).toInt())
                outputStream.write((id shr 16).toInt() and 0xFF)
                outputStream.write((id shr 8).toInt() and 0xFF)
                outputStream.write(id.toInt() and 0xFF)
            }
            else -> {
                // Если передано напрямую в виде Int
                var temp = id
                val bytes = mutableListOf<Int>()
                while (temp > 0) {
                    bytes.add(0, (temp and 0xFF).toInt())
                    temp = temp shr 8
                }
                for (b in bytes) {
                    outputStream.write(b)
                }
            }
        }
    }

    /**
     * Записывает размер данных элемента в формате VINT.
     * Если fixedWidth > 0, кодирует VINT заданной фиксированной ширины (от 1 до 8 байт).
     */
    fun writeElementSize(size: Long, fixedWidth: Int = 0) {
        val vintBytes = encodeVint(size, fixedWidth)
        outputStream.write(vintBytes)
    }

    /**
     * Записывает элемент типа Unsigned Integer (u).
     */
    fun writeUInt(id: Long, value: Long, fixedWidth: Int = 0) {
        writeElementId(id)
        val dataBytes = encodeUIntData(value, fixedWidth)
        writeElementSize(dataBytes.size.toLong())
        outputStream.write(dataBytes)
    }

    /**
     * Записывает элемент типа Signed Integer (i).
     */
    fun writeSInt(id: Long, value: Long, fixedWidth: Int = 0) {
        writeElementId(id)
        val dataBytes = encodeSIntData(value, fixedWidth)
        writeElementSize(dataBytes.size.toLong())
        outputStream.write(dataBytes)
    }

    /**
     * Записывает элемент типа Float/Double (f) IEEE-754.
     */
    fun writeFloat(id: Long, value: Double) {
        writeElementId(id)
        val bits = java.lang.Double.doubleToRawLongBits(value)
        val dataBytes = ByteArray(8)
        for (i in 7 downTo 0) {
            dataBytes[7 - i] = ((bits shr (i * 8)) and 0xFF).toByte()
        }
        writeElementSize(8)
        outputStream.write(dataBytes)
    }

    /**
     * Записывает элемент типа UTF-8 String (8) или ASCII String (s).
     */
    fun writeString(id: Long, value: String) {
        writeElementId(id)
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeElementSize(bytes.size.toLong())
        outputStream.write(bytes)
    }

    /**
     * Записывает элемент типа Binary (b).
     */
    fun writeBinary(id: Long, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        writeElementId(id)
        writeElementSize(length.toLong())
        outputStream.write(data, offset, length)
    }

    /**
     * Записывает Master элемент с вложенными подэлементами.
     */
    fun writeMaster(id: Long, block: (VoxEbmlWriter) -> Unit) {
        val bos = ByteArrayOutputStream()
        val nestedWriter = VoxEbmlWriter(bos)
        block(nestedWriter)
        val payload = bos.toByteArray()

        writeElementId(id)
        writeElementSize(payload.size.toLong())
        outputStream.write(payload)
    }

    /**
     * Записывает заголовок открытого Master-элемента неизвестного размера (например, Segment).
     */
    fun writeOpenMasterHeader(id: Long) {
        writeElementId(id)
        // 8-байтовый VINT с неизвестным размером (0x01FFFFFFFFFFFFFFL)
        outputStream.write(byteArrayOf(
            0x01.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        ))
    }

    companion object {
        /**
         * Кодирует числовое значение в VINT-представление переменной или фиксированной длины.
         */
        fun encodeVint(value: Long, fixedWidth: Int = 0): ByteArray {
            if (value < 0) {
                throw IllegalArgumentException("VINT value cannot be negative: $value")
            }

            val width = if (fixedWidth in 1..8) {
                fixedWidth
            } else {
                when {
                    value < 0x7FL -> 1
                    value < 0x3FFFL -> 2
                    value < 0x1FFFFFL -> 3
                    value < 0x0FFFFFFFL -> 4
                    value < 0x07FFFFFFFFL -> 5
                    value < 0x03FFFFFFFFFFL -> 6
                    value < 0x01FFFFFFFFFFFFL -> 7
                    value < 0x00FFFFFFFFFFFFFFL -> 8
                    else -> throw IllegalArgumentException("VINT value too large: $value")
                }
            }

            val result = ByteArray(width)
            val marker = 1L shl (width * 7)
            val fullVal = marker or value

            for (i in (width - 1) downTo 0) {
                result[width - 1 - i] = ((fullVal shr (i * 8)) and 0xFF).toByte()
            }
            return result
        }

        fun encodeUIntData(value: Long, fixedWidth: Int = 0): ByteArray {
            val width = if (fixedWidth in 1..8) {
                fixedWidth
            } else {
                when {
                    value <= 0xFFL -> 1
                    value <= 0xFFFFL -> 2
                    value <= 0xFFFFFFL -> 3
                    value <= 0xFFFFFFFFL -> 4
                    value <= 0xFFFFFFFFFFL -> 5
                    value <= 0xFFFFFFFFFFFFL -> 6
                    value <= 0xFFFFFFFFFFFFFFL -> 7
                    else -> 8
                }
            }
            val result = ByteArray(width)
            for (i in (width - 1) downTo 0) {
                result[width - 1 - i] = ((value shr (i * 8)) and 0xFF).toByte()
            }
            return result
        }

        fun encodeSIntData(value: Long, fixedWidth: Int = 0): ByteArray {
            val width = if (fixedWidth in 1..8) {
                fixedWidth
            } else {
                when (value) {
                    in Byte.MIN_VALUE..Byte.MAX_VALUE -> 1
                    in Short.MIN_VALUE..Short.MAX_VALUE -> 2
                    in -8388608L..8388607L -> 3
                    in Int.MIN_VALUE..Int.MAX_VALUE -> 4
                    else -> 8
                }
            }
            val result = ByteArray(width)
            for (i in (width - 1) downTo 0) {
                result[width - 1 - i] = ((value shr (i * 8)) and 0xFF).toByte()
            }
            return result
        }
    }
}
