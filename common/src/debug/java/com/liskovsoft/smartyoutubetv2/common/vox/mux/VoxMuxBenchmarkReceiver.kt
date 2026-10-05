package com.liskovsoft.smartyoutubetv2.common.vox.mux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator
import java.io.File

/** Только debug, вызов защищён DUMP. Повторная упаковка копии реального локального MKV. */
class VoxMuxBenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("downloadId") ?: return
        val job = VoxDownloadCoordinator.instance(context).getJob(id) ?: return
        val uri = job.publishedUri ?: return
        Thread({
            val sources = mutableListOf<VoxSampleSource>()
            try {
                val dir = File(context.cacheDir, "p32-mux-benchmark").apply { mkdirs() }
                val input = File(dir, "input.mkv")
                context.contentResolver.openInputStream(Uri.parse(uri))!!.use { src ->
                    input.outputStream().use { dst -> src.copyTo(dst, 256 * 1024) }
                }
                val sizes = listOf(job.videoProgress.bytesDownloaded, job.originalAudioProgress.bytesDownloaded, job.translatedAudioProgress.bytesDownloaded)
                val types = listOf(VoxMuxTrackType.VIDEO, VoxMuxTrackType.AUDIO_ORIGINAL, VoxMuxTrackType.AUDIO_TRANSLATED)
                for (i in 0..2) {
                    val source = VoxMediaExtractorSource(input, i + 1, i + 1L, types[i], types[i].name,
                        if (i == 2) "rus" else "und", i != 1, i)
                    sources.add(object : VoxSampleSource by source { override val sourceSizeBytes = sizes[i] })
                }
                val start = System.nanoTime()
                var callbacks = 0
                val result = VoxMatroskaMuxer().mux(sources, File(dir, "output.mkv"), "Проверка упаковки TCL",
                    progressListener = { p -> callbacks++; Log.i("P32_MUX_BENCHMARK", "bytes=${p.bytesProcessed} samples=${p.processedSamples} elapsedMs=${p.elapsedMs}") })
                val elapsed = (System.nanoTime() - start) / 1_000_000
                Log.i("P32_MUX_BENCHMARK", "RESULT sourceId=$id sourceBytes=${sizes.sum()} outputBytes=${result.totalBytesWritten} elapsedMs=$elapsed callbacks=$callbacks videoSamples=${result.videoSamplesCount} originalSamples=${result.origAudioSamplesCount} translatedSamples=${result.transAudioSamplesCount}")
            } catch (e: Exception) {
                Log.e("P32_MUX_BENCHMARK", "FAILED ${e.javaClass.simpleName}")
            } finally {
                sources.forEach { try { it.close() } catch (ignored: Exception) {} }
            }
        }, "VoxMuxBenchmark").start()
    }
}
