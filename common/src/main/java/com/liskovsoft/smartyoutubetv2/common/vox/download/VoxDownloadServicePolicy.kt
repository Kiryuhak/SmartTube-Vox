package com.liskovsoft.smartyoutubetv2.common.vox.download

/**
 * Чистая логика и правила управления состоянием фонового сервиса скачивания VOX.
 * Вынесена отдельно для гарантированного прямого тестирования unit-тестами.
 */
object VoxDownloadServicePolicy {

    private val ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")

    /**
     * Проверяет синтаксическую корректность идентификатора загрузки.
     * Запрещает path traversal (..), пробелы, спецсимволы и строки длиннее 64 символов.
     */
    @JvmStatic
    fun isValidDownloadId(downloadId: String?): Boolean {
        if (downloadId.isNullOrBlank()) return false
        return downloadId.matches(ID_REGEX)
    }

    /**
     * Возвращает true, если состояние задания является активным (в процессе подготовки, скачивания, сшивки или сохранения).
     */
    @JvmStatic
    fun isActiveState(state: VoxDownloadState): Boolean {
        return when (state) {
            VoxDownloadState.PREPARING_TRANSLATION,
            VoxDownloadState.RESOLVING_STREAMS,
            VoxDownloadState.DOWNLOADING_MEDIA,
            VoxDownloadState.DOWNLOADING_VIDEO,
            VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO,
            VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO,
            VoxDownloadState.READY_FOR_MUX,
            VoxDownloadState.MUXING,
            VoxDownloadState.FINALIZING,
            VoxDownloadState.MUXED,
            VoxDownloadState.PUBLISHING -> true
            VoxDownloadState.IDLE,
            VoxDownloadState.QUEUED,
            VoxDownloadState.PAUSED,
            VoxDownloadState.COMPLETED,
            VoxDownloadState.FAILED,
            VoxDownloadState.CANCELLED -> false
        }
    }

    /**
     * Возвращает true, если состояние задания является терминальным.
     */
    @JvmStatic
    fun isTerminalState(state: VoxDownloadState): Boolean {
        return when (state) {
            VoxDownloadState.COMPLETED,
            VoxDownloadState.FAILED,
            VoxDownloadState.CANCELLED -> true
            else -> false
        }
    }

    /**
     * Определяет, нужно ли запускать/возобновлять загрузку при получении общего намерения ACTION_START_DOWNLOAD.
     *
     * - Уже активное задание: false (только наблюдение, без повторного запуска).
     * - IDLE / QUEUED / активные недовыполненные состояния: true (начать или подхватить выполнение).
     * - PAUSED: false (требуется явное действие пользователя / ACTION_RESUME_DOWNLOAD).
     * - FAILED: false (требуется явный перезапуск через диалог / retry).
     * - CANCELLED / COMPLETED: false (терминальные состояния не перезапускаются автоматически).
     */
    @JvmStatic
    fun shouldAutoResumeOnStart(state: VoxDownloadState, isAlreadyActive: Boolean): Boolean {
        if (isAlreadyActive) return false
        return when (state) {
            VoxDownloadState.IDLE,
            VoxDownloadState.QUEUED -> true
            VoxDownloadState.PREPARING_TRANSLATION,
            VoxDownloadState.RESOLVING_STREAMS,
            VoxDownloadState.DOWNLOADING_MEDIA,
            VoxDownloadState.DOWNLOADING_VIDEO,
            VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO,
            VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO,
            VoxDownloadState.READY_FOR_MUX,
            VoxDownloadState.MUXING,
            VoxDownloadState.FINALIZING,
            VoxDownloadState.MUXED,
            VoxDownloadState.PUBLISHING -> true
            VoxDownloadState.PAUSED,
            VoxDownloadState.FAILED,
            VoxDownloadState.CANCELLED,
            VoxDownloadState.COMPLETED -> false
        }
    }

    /**
     * Определяет, нужно ли возобновлять загрузку при явном вызове ACTION_RESUME_DOWNLOAD.
     */
    @JvmStatic
    fun shouldResumeOnExplicitResume(state: VoxDownloadState, isAlreadyActive: Boolean): Boolean {
        if (isAlreadyActive) return false
        return when (state) {
            VoxDownloadState.PAUSED,
            VoxDownloadState.IDLE,
            VoxDownloadState.QUEUED,
            VoxDownloadState.PREPARING_TRANSLATION,
            VoxDownloadState.RESOLVING_STREAMS,
            VoxDownloadState.DOWNLOADING_MEDIA,
            VoxDownloadState.DOWNLOADING_VIDEO,
            VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO,
            VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO,
            VoxDownloadState.READY_FOR_MUX,
            VoxDownloadState.MUXING,
            VoxDownloadState.FINALIZING,
            VoxDownloadState.MUXED,
            VoxDownloadState.PUBLISHING -> true
            VoxDownloadState.FAILED,
            VoxDownloadState.CANCELLED,
            VoxDownloadState.COMPLETED -> false
        }
    }
}
