package com.liskovsoft.smartyoutubetv2.common.vox.mux

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Источник сэмплов на базе Android MediaExtractor для MP4/AAC/AVC и MP3.
 */
class VoxMediaExtractorSource(
    private val mediaFile: File,
    private val assignedTrackNumber: Int,
    private val assignedTrackUid: Long,
    private val trackType: VoxMuxTrackType,
    private val trackName: String,
    private val language: String,
    private val isDefaultTrack: Boolean,
    private val sourceTrackIndex: Int? = null
) : VoxSampleSource {

    private val extractor: MediaExtractor = MediaExtractor()
    private val selectedTrackIndex: Int
    override val trackInfo: VoxMuxTrackInfo
    override val sourceSizeBytes: Long = if (mediaFile.exists()) mediaFile.length() else 0L
    private val buffer: ByteBuffer
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private var ended = false

    init {
        try {
        extractor.setDataSource(mediaFile.absolutePath)
        val numTracks = extractor.trackCount
        var foundTrackIndex = -1
        var selectedFormat: MediaFormat? = null

        for (i in 0 until numTracks) {
            if (sourceTrackIndex != null && i != sourceTrackIndex) continue
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val isVideo = mime.startsWith("video/")
            val isAudio = mime.startsWith("audio/")

            if (trackType == VoxMuxTrackType.VIDEO && isVideo) {
                foundTrackIndex = i
                selectedFormat = format
                break
            } else if ((trackType == VoxMuxTrackType.AUDIO_ORIGINAL || trackType == VoxMuxTrackType.AUDIO_TRANSLATED) && isAudio) {
                foundTrackIndex = i
                selectedFormat = format
                break
            }
        }

        if (foundTrackIndex < 0 || selectedFormat == null) {
            throw IllegalArgumentException("No matching ${trackType.name} track found in ${mediaFile.name}")
        }

        selectedTrackIndex = foundTrackIndex
        extractor.selectTrack(selectedTrackIndex)

        val mime = selectedFormat.getString(MediaFormat.KEY_MIME) ?: ""
        val codec = VoxMuxCodec.fromMimeType(mime)
        val durationUs = if (selectedFormat.containsKey(MediaFormat.KEY_DURATION)) {
            selectedFormat.getLong(MediaFormat.KEY_DURATION)
        } else 0L

        var width = 0
        var height = 0
        var sampleRate = 0.0
        var channels = 0

        if (trackType == VoxMuxTrackType.VIDEO) {
            if (selectedFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                width = selectedFormat.getInteger(MediaFormat.KEY_WIDTH)
            }
            if (selectedFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                height = selectedFormat.getInteger(MediaFormat.KEY_HEIGHT)
            }
        } else {
            if (selectedFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = selectedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).toDouble()
            }
            if (selectedFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channels = selectedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }

        val codecPrivate = buildCodecPrivate(codec, selectedFormat)

        val maxInputSize = if (selectedFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            selectedFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
        } else {
            1024 * 1024
        }
        buffer = ByteBuffer.allocateDirect(maxInputSize)

        trackInfo = VoxMuxTrackInfo(
            trackNumber = assignedTrackNumber,
            trackUid = assignedTrackUid,
            trackType = trackType,
            codec = codec,
            mimeType = mime,
            name = trackName,
            language = language,
            isDefault = isDefaultTrack,
            width = width,
            height = height,
            sampleRate = sampleRate,
            channels = channels,
            codecPrivate = codecPrivate,
            durationUs = durationUs
        )
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun readNextSample(): VoxMuxSample? {
        if (ended || closed.get()) return null
        buffer.clear()
        val sampleSize = extractor.readSampleData(buffer, 0)
        if (sampleSize < 0) {
            ended = true
            return null
        }

        val pts = extractor.sampleTime
        val flags = extractor.sampleFlags
        val isKeyFrame = (flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0

        val bytes = ByteArray(sampleSize)
        buffer.get(bytes, 0, sampleSize)

        // Преобразуем AVC NAL Annex B start code в 4-байтовые длины, если MediaExtractor вернул Annex B
        val finalBytes = if (trackInfo.codec == VoxMuxCodec.AVC) {
            normalizeAvcSample(bytes)
        } else {
            bytes
        }

        ended = !extractor.advance()

        return VoxMuxSample(
            trackNumber = assignedTrackNumber,
            presentationTimeUs = pts,
            durationUs = 0L,
            isKeyFrame = isKeyFrame,
            data = finalBytes,
            offset = 0,
            size = finalBytes.size
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            extractor.release()
        } catch (ignored: Exception) {
        }
    }

    companion object {
        /**
         * Формирует CodecPrivate для кодеков AVC (AVCDecoderConfigurationRecord / avcC) и AAC (AudioSpecificConfig).
         */
        fun buildCodecPrivate(codec: VoxMuxCodec, format: MediaFormat): ByteArray? {
            return when (codec) {
                VoxMuxCodec.AVC -> {
                    val csd0 = getCsdBytes(format, "csd-0")
                    val csd1 = getCsdBytes(format, "csd-1")
                    if (csd0 != null) {
                        buildAvcDecoderConfig(csd0, csd1)
                    } else {
                        null
                    }
                }
                VoxMuxCodec.AAC -> {
                    getCsdBytes(format, "csd-0")
                }
                VoxMuxCodec.OPUS -> {
                    val csd0 = getCsdBytes(format, "csd-0")
                    if (csd0 != null && csd0.size >= 19 && String(csd0, 0, 8, Charsets.US_ASCII) == "OpusHead") {
                        csd0
                    } else {
                        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
                        val csd1 = getCsdBytes(format, "csd-1")
                        val preSkip = if (csd1 != null && csd1.size == 8) {
                            val codecDelayNs = ByteBuffer.wrap(csd1).order(java.nio.ByteOrder.nativeOrder()).long
                            ((codecDelayNs * 48000) / 1000000000L).toInt()
                        } else {
                            312
                        }
                        buildOpusHead(channels, preSkip, sampleRate)
                    }
                }
                else -> null
            }
        }

        fun buildOpusHead(channels: Int, preSkip: Int, inputSampleRate: Int): ByteArray {
            if (channels > 2) {
                throw IllegalArgumentException("Opus channels > 2 requires mapping family > 0, which is currently unsupported.")
            }
            if (inputSampleRate <= 0) {
                throw IllegalArgumentException("Invalid Opus sample rate: $inputSampleRate")
            }
            val clampedPreSkip = if (preSkip < 0) 0 else if (preSkip > 48000) 48000 else preSkip

            val bos = ByteArrayOutputStream(19)
            bos.write("OpusHead".toByteArray(Charsets.US_ASCII))
            bos.write(1) // version
            bos.write(channels)
            bos.write(clampedPreSkip and 0xFF) // pre-skip (little endian)
            bos.write((clampedPreSkip shr 8) and 0xFF)
            bos.write(inputSampleRate and 0xFF) // sample rate (little endian)
            bos.write((inputSampleRate shr 8) and 0xFF)
            bos.write((inputSampleRate shr 16) and 0xFF)
            bos.write((inputSampleRate shr 24) and 0xFF)
            bos.write(0) // output gain
            bos.write(0)
            bos.write(0) // channel mapping family
            return bos.toByteArray()
        }

        private fun getCsdBytes(format: MediaFormat, key: String): ByteArray? {
            if (!format.containsKey(key)) return null
            val byteBuffer = format.getByteBuffer(key) ?: return null
            val bytes = ByteArray(byteBuffer.remaining())
            val duplicate = byteBuffer.duplicate()
            duplicate.get(bytes)
            return bytes
        }

        /**
         * Строит AVCDecoderConfigurationRecord (avcC) согласно ISO/IEC 14496-15.
         */
        fun buildAvcDecoderConfig(spsRaw: ByteArray, ppsRaw: ByteArray?): ByteArray {
            val sps = stripStartCode(spsRaw)
            val pps = if (ppsRaw != null) stripStartCode(ppsRaw) else ByteArray(0)

            val profile = if (sps.size >= 4) sps[1] else 0x64.toByte() // High profile
            val compat = if (sps.size >= 4) sps[2] else 0x00.toByte()
            val level = if (sps.size >= 4) sps[3] else 0x1F.toByte()   // Level 3.1

            val bos = ByteArrayOutputStream()
            val dos = DataOutputStream(bos)

            dos.writeByte(1)               // configurationVersion = 1
            dos.writeByte(profile.toInt()) // AVCProfileIndication
            dos.writeByte(compat.toInt())  // profile_compatibility
            dos.writeByte(level.toInt())   // AVCLevelIndication
            dos.writeByte(0xFF)            // lengthSizeMinusOne = 3 (4-byte NAL length) with reserved bits 111111
            dos.writeByte(0xE1)            // numOfSequenceParameterSets = 1 with reserved bits 111
            dos.writeShort(sps.size)       // sequenceParameterSetLength
            dos.write(sps)                 // SPS NAL unit

            dos.writeByte(1)               // numOfPictureParameterSets = 1
            dos.writeShort(pps.size)       // pictureParameterSetLength
            dos.write(pps)                 // PPS NAL unit

            dos.flush()
            return bos.toByteArray()
        }

        private fun stripStartCode(bytes: ByteArray): ByteArray {
            if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) {
                val dest = ByteArray(bytes.size - 4)
                System.arraycopy(bytes, 4, dest, 0, dest.size)
                return dest
            }
            if (bytes.size >= 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte()) {
                val dest = ByteArray(bytes.size - 3)
                System.arraycopy(bytes, 3, dest, 0, dest.size)
                return dest
            }
            return bytes
        }

        /**
         * Нормализует сэмпл AVC к формату с 4-байтовыми префиксами длины NALU (AVCC format).
         */
        fun normalizeAvcSample(bytes: ByteArray): ByteArray {
            if (bytes.size < 4) return bytes

            // Проверяем, начинается ли с Annex B start code (0x00 0x00 0x00 0x01 или 0x00 0x00 0x01)
            var hasAnnexB = false
            if (bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && (bytes[2] == 1.toByte() || (bytes[2] == 0.toByte() && bytes[3] == 1.toByte()))) {
                hasAnnexB = true
            }

            if (!hasAnnexB) {
                // Уже в формате с 4-байтовыми длинами
                return bytes
            }

            // Конвертируем Annex B в AVCC (4-байтовые длины)
            val nalStarts = mutableListOf<Int>()
            val nalPrefixSizes = mutableListOf<Int>()

            var i = 0
            while (i < bytes.size - 2) {
                if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                    if (bytes[i + 2] == 1.toByte()) {
                        nalStarts.add(i + 3)
                        nalPrefixSizes.add(3)
                        i += 3
                        continue
                    } else if (i < bytes.size - 3 && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) {
                        nalStarts.add(i + 4)
                        nalPrefixSizes.add(4)
                        i += 4
                        continue
                    }
                }
                i++
            }

            if (nalStarts.isEmpty()) return bytes

            val bos = ByteArrayOutputStream(bytes.size + nalStarts.size)
            val dos = DataOutputStream(bos)

            for (idx in nalStarts.indices) {
                val start = nalStarts[idx]
                val end = if (idx + 1 < nalStarts.size) nalStarts[idx + 1] - nalPrefixSizes[idx + 1] else bytes.size
                val naluLen = end - start
                if (naluLen > 0) {
                    dos.writeInt(naluLen)
                    dos.write(bytes, start, naluLen)
                }
            }

            dos.flush()
            return bos.toByteArray()
        }
    }
}
