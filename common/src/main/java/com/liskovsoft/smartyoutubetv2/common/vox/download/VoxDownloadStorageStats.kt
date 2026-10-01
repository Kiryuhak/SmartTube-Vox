package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

data class VoxDownloadStorageSummary(val internalBytes: Long, val publishedBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = internalBytes + publishedBytes
}

object VoxDownloadStorageStats {
    fun sum(internal: List<Long>, published: List<Long>, free: Long): VoxDownloadStorageSummary =
        VoxDownloadStorageSummary(internal.sumOf { it.coerceAtLeast(0) },
            published.sumOf { it.coerceAtLeast(0) }, free.coerceAtLeast(0))

    /** Вызывается из фонового потока; только собственные директории заданий и сохранённые URI. */
    fun collect(context: Context, storage: VoxDownloadStorage, jobs: List<VoxDownloadJob>): VoxDownloadStorageSummary {
        val internal = jobs.map { job ->
            storage.getJobDir(job.downloadId).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }
        val published = jobs.mapNotNull { job ->
            val uri = job.publishedUri ?: return@mapNotNull null
            try {
                context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
                }
            } catch (ignored: Exception) { null }
        }
        return sum(internal, published, storage.getAvailableBytes())
    }
}
