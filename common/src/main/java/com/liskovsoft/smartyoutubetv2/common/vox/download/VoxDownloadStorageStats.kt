package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

data class VoxDownloadStorageSummary(val internalBytes: Long, val publishedBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = internalBytes + publishedBytes
}

object VoxDownloadStorageStats {
    fun sum(internal: List<Long>, published: List<Long>, free: Long): VoxDownloadStorageSummary =
        VoxDownloadStorageSummary(
            internal.sumOf { it.coerceAtLeast(0) },
            published.sumOf { it.coerceAtLeast(0) },
            free.coerceAtLeast(0)
        )

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

    /**
     * Форматирует сводку хранилища в понятный пользователю вид:
     * "Занято SmartTube: 1.2 ГБ · Свободно: 14.5 ГБ"
     */
    fun formatStorageSummary(summary: VoxDownloadStorageSummary): String {
        val usedFormatted = VoxDownloadSizeFormatter.formatBytes(summary.usedBytes)
        val freeFormatted = VoxDownloadSizeFormatter.formatBytes(summary.freeBytes)
        return "Занято SmartTube: $usedFormatted · Свободно: $freeFormatted"
    }

    /**
     * Проверяет, остаётся ли безопасный запас дискового пространства (не менее 200 МБ после завершения).
     */
    fun hasSafeMargin(availableBytes: Long, requiredBytes: Long, safeMarginBytes: Long = 200 * 1024 * 1024L): Boolean {
        return (availableBytes - requiredBytes) >= safeMarginBytes
    }
}
