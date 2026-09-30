package com.liskovsoft.smartyoutubetv2.common.vox.mux

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Высокопроизводительный Kotlin-мультиплексор Matroska (MKV) без транскодирования (Remux-only).
 * Упаковывает видео и аудиодорожки в единый .mkv файл с Cues-индексами для мгновенной перемотки.
 */
class VoxMatroskaMuxer {

    companion object {
        // EBML Root
        const val ID_EBML = 0x1A45DFA3L
        const val ID_EBML_VERSION = 0x4286L
        const val ID_EBML_READ_VERSION = 0x42F7L
        const val ID_EBML_MAX_ID_LENGTH = 0x42F2L
        const val ID_EBML_MAX_SIZE_LENGTH = 0x42F3L
        const val ID_DOC_TYPE = 0x4282L
        const val ID_DOC_TYPE_VERSION = 0x4287L
        const val ID_DOC_TYPE_READ_VERSION = 0x4285L

        // Segment
        const val ID_SEGMENT = 0x18538067L

        // SeekHead
        const val ID_SEEK_HEAD = 0x114D9B74L
        const val ID_SEEK = 0x4DBBL
        const val ID_SEEK_ID = 0x53ABL
        const val ID_SEEK_POSITION = 0x53ACL

        // Info
        const val ID_INFO = 0x1549A966L
        const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
        const val ID_MUXING_APP = 0x4D80L
        const val ID_WRITING_APP = 0x5741L
        const val ID_DURATION = 0x4489L
        const val ID_TITLE = 0x7BA9L

        // Tracks
        const val ID_TRACKS = 0x1654AE6BL
        const val ID_TRACK_ENTRY = 0xAEL
        const val ID_TRACK_NUMBER = 0xD7L
        const val ID_TRACK_UID = 0x73C5L
        const val ID_TRACK_TYPE = 0x83L
        const val ID_FLAG_DEFAULT = 0x88L
        const val ID_FLAG_FORCED = 0x55AAL
        const val ID_FLAG_LACING = 0x9CL
        const val ID_NAME = 0x536EL
        const val ID_LANGUAGE = 0x22B59CL
        const val ID_CODEC_ID = 0x86L
        const val ID_CODEC_PRIVATE = 0x63A2L
        const val ID_VIDEO = 0xE0L
        const val ID_PIXEL_WIDTH = 0xB0L
        const val ID_PIXEL_HEIGHT = 0xBAL
        const val ID_AUDIO = 0xE1L
        const val ID_SAMPLING_FREQUENCY = 0xB5L
        const val ID_CHANNELS = 0x9FL

        // Cluster & Blocks
        const val ID_CLUSTER = 0x1F43B675L
        const val ID_CLUSTER_TIMESTAMP = 0xE7L
        const val ID_SIMPLE_BLOCK = 0xA3L

        // Cues
        const val ID_CUES = 0x1C53BB6BL
        const val ID_CUE_POINT = 0xBBL
        const val ID_CUE_TIME = 0xB3L
        const val ID_CUE_TRACK_POSITIONS = 0xB7L
        const val ID_CUE_TRACK = 0xF7L
        const val ID_CUE_CLUSTER_POSITION = 0xF1L

        const val TIMESTAMP_SCALE_NS = 1_000_000L // 1 ms
        const val CLUSTER_MAX_DURATION_MS = 2000L // 2 seconds per cluster
        const val SEEK_HEAD_RESERVED_SIZE = 128
    }

    private data class CueEntry(
        val timeMs: Long,
        val clusterOffset: Long
    )

    /**
     * Выполняет мультиплексирование источников в целевой .mkv файл.
     */
    fun mux(
        sources: List<VoxSampleSource>,
        outputFile: File,
        videoTitle: String? = null,
        isCancelled: AtomicBoolean = AtomicBoolean(false),
        progressListener: ((VoxMuxProgress) -> Unit)? = null
    ): VoxMuxResult {
        require(sources.isNotEmpty()) { "Sources cannot be empty" }

        val tmpOutputFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
        if (tmpOutputFile.exists()) {
            tmpOutputFile.delete()
        }

        val cues = mutableListOf<CueEntry>()

        var totalInputBytes = 0L
        var totalBytesProcessed = 0L
        var videoSamplesCount = 0L
        var origAudioSamplesCount = 0L
        var transAudioSamplesCount = 0L
        var maxTimestampMs = 0L

        var segmentPayloadOffset: Long
        var infoOffset: Long
        var tracksOffset: Long

        val fos = FileOutputStream(tmpOutputFile)
        val bos = BufferedOutputStream(fos, 256 * 1024)
        val writer = VoxEbmlWriter(bos)

        try {
            // 1. EBML Header
            writer.writeMaster(ID_EBML) { ebml ->
                ebml.writeUInt(ID_EBML_VERSION, 1)
                ebml.writeUInt(ID_EBML_READ_VERSION, 1)
                ebml.writeUInt(ID_EBML_MAX_ID_LENGTH, 4)
                ebml.writeUInt(ID_EBML_MAX_SIZE_LENGTH, 8)
                ebml.writeString(ID_DOC_TYPE, "matroska")
                ebml.writeUInt(ID_DOC_TYPE_VERSION, 4)
                ebml.writeUInt(ID_DOC_TYPE_READ_VERSION, 2)
            }
            bos.flush()

            // 2. Segment (Unknown Size)
            writer.writeOpenMasterHeader(ID_SEGMENT)
            bos.flush()
            segmentPayloadOffset = fos.channel.position()

            // 3. Зарезервированное место под SeekHead
            val seekHeadStartPos = fos.channel.position()
            val seekHeadPlaceholder = ByteArray(SEEK_HEAD_RESERVED_SIZE)
            // Заполняем EBML Void элементом (ID: 0xEC)
            seekHeadPlaceholder[0] = 0xEC.toByte()
            val voidSize = (SEEK_HEAD_RESERVED_SIZE - 2).toLong()
            val voidSizeVint = VoxEbmlWriter.encodeVint(voidSize, 1)
            seekHeadPlaceholder[1] = voidSizeVint[0]
            bos.write(seekHeadPlaceholder)
            bos.flush()

            // 4. Info Element
            infoOffset = fos.channel.position() - segmentPayloadOffset
            writer.writeMaster(ID_INFO) { info ->
                info.writeUInt(ID_TIMESTAMP_SCALE, TIMESTAMP_SCALE_NS)
                info.writeString(ID_MUXING_APP, "SmartTube VOX")
                info.writeString(ID_WRITING_APP, "SmartTube VOX")
                if (!videoTitle.isNullOrBlank()) {
                    info.writeString(ID_TITLE, videoTitle)
                }
                info.writeFloat(ID_DURATION, 0.0) // Будет пропатчено в конце
            }
            bos.flush()

            // 5. Tracks Element
            tracksOffset = fos.channel.position() - segmentPayloadOffset
            writer.writeMaster(ID_TRACKS) { tracks ->
                for (source in sources) {
                    val info = source.trackInfo
                    tracks.writeMaster(ID_TRACK_ENTRY) { track ->
                        track.writeUInt(ID_TRACK_NUMBER, info.trackNumber.toLong())
                        track.writeUInt(ID_TRACK_UID, info.trackUid)
                        val typeVal = if (info.trackType == VoxMuxTrackType.VIDEO) 1L else 2L
                        track.writeUInt(ID_TRACK_TYPE, typeVal)
                        track.writeUInt(ID_FLAG_DEFAULT, if (info.isDefault) 1L else 0L)
                        track.writeUInt(ID_FLAG_FORCED, if (info.isForced) 1L else 0L)
                        track.writeUInt(ID_FLAG_LACING, 0L)
                        track.writeString(ID_NAME, info.name)
                        track.writeString(ID_LANGUAGE, info.language)
                        track.writeString(ID_CODEC_ID, info.codec.matroskaCodecId)

                        if (info.codecPrivate != null && info.codecPrivate.isNotEmpty()) {
                            track.writeBinary(ID_CODEC_PRIVATE, info.codecPrivate)
                        }

                        if (info.trackType == VoxMuxTrackType.VIDEO) {
                            track.writeMaster(ID_VIDEO) { v ->
                                v.writeUInt(ID_PIXEL_WIDTH, info.width.toLong())
                                v.writeUInt(ID_PIXEL_HEIGHT, info.height.toLong())
                            }
                        } else {
                            track.writeMaster(ID_AUDIO) { a ->
                                a.writeFloat(ID_SAMPLING_FREQUENCY, info.sampleRate.coerceAtLeast(44100.0))
                                a.writeUInt(ID_CHANNELS, info.channels.coerceAtLeast(2).toLong())
                            }
                        }
                    }
                }
            }
            bos.flush()

            // 6. Streaming Clusters with Sample Interleaving
            // Буфер текущих сэмплов из каждого источника для timestamp-сортировки
            val currentSamples = Array<VoxMuxSample?>(sources.size) { null }
            for (i in sources.indices) {
                currentSamples[i] = sources[i].readNextSample()
            }

            var currentClusterStartPts = -1L
            var currentClusterPayload = ByteArrayOutputStream(256 * 1024)
            var currentClusterWriter = VoxEbmlWriter(currentClusterPayload)
            var hasClusterVideoKeyFrame = false

            fun flushCluster() {
                if (currentClusterStartPts >= 0 && currentClusterPayload.size() > 0) {
                    bos.flush()
                    val clusterOffset = fos.channel.position() - segmentPayloadOffset

                    val clusterTimeMs = currentClusterStartPts / 1000L
                    if (hasClusterVideoKeyFrame || cues.isEmpty()) {
                        cues.add(CueEntry(clusterTimeMs, clusterOffset))
                    }

                    // Пишем Cluster Master элемент
                    val payloadBytes = currentClusterPayload.toByteArray()
                    val clusterBos = ByteArrayOutputStream(payloadBytes.size + 32)
                    val clusterElemWriter = VoxEbmlWriter(clusterBos)
                    clusterElemWriter.writeUInt(ID_CLUSTER_TIMESTAMP, clusterTimeMs)
                    clusterBos.write(payloadBytes)

                    val clusterFinal = clusterBos.toByteArray()
                    writer.writeElementId(ID_CLUSTER)
                    writer.writeElementSize(clusterFinal.size.toLong())
                    bos.write(clusterFinal)
                    bos.flush()

                    currentClusterPayload.reset()
                    currentClusterWriter = VoxEbmlWriter(currentClusterPayload)
                    currentClusterStartPts = -1L
                    hasClusterVideoKeyFrame = false
                }
            }

            while (true) {
                if (isCancelled.get()) {
                    throw InterruptedException("Multiplexing was cancelled by user")
                }

                // Находим следующий сэмпл с наименьшим presentation timestamp (PTS)
                var minIdx = -1
                var minPts = Long.MAX_VALUE
                for (i in currentSamples.indices) {
                    val s = currentSamples[i] ?: continue
                    if (s.presentationTimeUs < minPts) {
                        minPts = s.presentationTimeUs
                        minIdx = i
                    }
                }

                if (minIdx == -1) {
                    // Все источники завершены
                    break
                }

                val sample = currentSamples[minIdx]!!
                val trackType = sources[minIdx].trackInfo.trackType
                val ptsMs = sample.presentationTimeUs / 1000L
                if (ptsMs > maxTimestampMs) {
                    maxTimestampMs = ptsMs
                }

                val isVideoKey = (trackType == VoxMuxTrackType.VIDEO && sample.isKeyFrame)

                // Проверяем, нужно ли начать новый кластер
                if (currentClusterStartPts < 0) {
                    currentClusterStartPts = sample.presentationTimeUs
                    if (isVideoKey) hasClusterVideoKeyFrame = true
                } else {
                    val clusterElapsedMs = (sample.presentationTimeUs - currentClusterStartPts) / 1000L
                    if (isVideoKey || clusterElapsedMs >= CLUSTER_MAX_DURATION_MS) {
                        flushCluster()
                        currentClusterStartPts = sample.presentationTimeUs
                        if (isVideoKey) hasClusterVideoKeyFrame = true
                    }
                }

                // Кодируем SimpleBlock
                val relativeTimeMs = ((sample.presentationTimeUs - currentClusterStartPts) / 1000L).toShort()
                val blockHeader = ByteArrayOutputStream(16)
                val trackNumVint = VoxEbmlWriter.encodeVint(sample.trackNumber.toLong())
                blockHeader.write(trackNumVint)
                blockHeader.write((relativeTimeMs.toInt() shr 8) and 0xFF)
                blockHeader.write(relativeTimeMs.toInt() and 0xFF)

                // Флаг SimpleBlock: Keyframe = 0x80 (если ключевой кадр), Lacing = 0x00
                val keyFlag = if (sample.isKeyFrame || trackType != VoxMuxTrackType.VIDEO) 0x80 else 0x00
                blockHeader.write(keyFlag)

                val blockHeaderBytes = blockHeader.toByteArray()
                val totalBlockSize = blockHeaderBytes.size + sample.size

                currentClusterWriter.writeElementId(ID_SIMPLE_BLOCK)
                currentClusterWriter.writeElementSize(totalBlockSize.toLong())
                currentClusterPayload.write(blockHeaderBytes)
                currentClusterPayload.write(sample.data, sample.offset, sample.size)

                when (trackType) {
                    VoxMuxTrackType.VIDEO -> videoSamplesCount++
                    VoxMuxTrackType.AUDIO_ORIGINAL -> origAudioSamplesCount++
                    VoxMuxTrackType.AUDIO_TRANSLATED -> transAudioSamplesCount++
                }

                totalBytesProcessed += sample.size

                progressListener?.invoke(
                    VoxMuxProgress(
                        bytesProcessed = totalBytesProcessed,
                        totalInputBytes = totalInputBytes,
                        percent = 0
                    )
                )

                // Читаем следующий сэмпл для этого трека
                currentSamples[minIdx] = sources[minIdx].readNextSample()
            }

            // Сбрасываем последний кластер
            flushCluster()

            // 7. Cues Element
            bos.flush()
            val cuesOffset = fos.channel.position() - segmentPayloadOffset
            writer.writeMaster(ID_CUES) { cuesWriter ->
                for (cue in cues) {
                    cuesWriter.writeMaster(ID_CUE_POINT) { point ->
                        point.writeUInt(ID_CUE_TIME, cue.timeMs)
                        point.writeMaster(ID_CUE_TRACK_POSITIONS) { pos ->
                            pos.writeUInt(ID_CUE_TRACK, 1L) // Video track
                            pos.writeUInt(ID_CUE_CLUSTER_POSITION, cue.clusterOffset)
                        }
                    }
                }
            }
            bos.flush()

            bos.close()
            fos.close()

            // 8. Патчим SeekHead и Duration в начале файла с помощью RandomAccessFile
            val raf = RandomAccessFile(tmpOutputFile, "rw")
            try {
                // Формируем настоящий SeekHead
                val seekBos = ByteArrayOutputStream()
                val seekWriter = VoxEbmlWriter(seekBos)
                seekWriter.writeMaster(ID_SEEK_HEAD) { sh ->
                    // Seek Info
                    sh.writeMaster(ID_SEEK) { s ->
                        s.writeUInt(ID_SEEK_ID, ID_INFO)
                        s.writeUInt(ID_SEEK_POSITION, infoOffset)
                    }
                    // Seek Tracks
                    sh.writeMaster(ID_SEEK) { s ->
                        s.writeUInt(ID_SEEK_ID, ID_TRACKS)
                        s.writeUInt(ID_SEEK_POSITION, tracksOffset)
                    }
                    // Seek Cues
                    sh.writeMaster(ID_SEEK) { s ->
                        s.writeUInt(ID_SEEK_ID, ID_CUES)
                        s.writeUInt(ID_SEEK_POSITION, cuesOffset)
                    }
                }
                val seekBytes = seekBos.toByteArray()
                if (seekBytes.size <= SEEK_HEAD_RESERVED_SIZE) {
                    raf.seek(seekHeadStartPos)
                    raf.write(seekBytes)
                    // Если осталось место в зарезервированном блоке, заполняем Void
                    val remaining = SEEK_HEAD_RESERVED_SIZE - seekBytes.size
                    if (remaining >= 2) {
                        raf.write(0xEC)
                        val vSize = (remaining - 2).toLong()
                        raf.write(VoxEbmlWriter.encodeVint(vSize, 1))
                        for (k in 0 until (remaining - 2)) {
                            raf.write(0)
                        }
                    }
                }
            } finally {
                raf.close()
            }

            // Атомарное переименование во вспомогательный финальный файл
            if (outputFile.exists()) {
                outputFile.delete()
            }
            if (!tmpOutputFile.renameTo(outputFile)) {
                throw IllegalStateException("Failed to rename ${tmpOutputFile.name} to ${outputFile.name}")
            }

            return VoxMuxResult(
                outputFile = outputFile,
                durationMs = maxTimestampMs,
                videoSamplesCount = videoSamplesCount,
                origAudioSamplesCount = origAudioSamplesCount,
                transAudioSamplesCount = transAudioSamplesCount,
                totalBytesWritten = outputFile.length()
            )
        } catch (e: Exception) {
            try {
                bos.close()
            } catch (ignored: Exception) {}
            try {
                fos.close()
            } catch (ignored: Exception) {}
            if (tmpOutputFile.exists()) {
                tmpOutputFile.delete()
            }
            throw e
        } finally {
            for (source in sources) {
                try {
                    source.close()
                } catch (ignored: Exception) {}
            }
        }
    }
}
