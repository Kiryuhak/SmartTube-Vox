package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тесты алгоритма удержания и восстановления позиции перемотки (Prompt #028 Задача C: History / Live DVR Seek Fix).
 */
class Vox028LiveDvrSeekRecoveryTest {

    private lateinit var coordinator: VoxPendingSeekCoordinator

    @Before
    fun setUp() {
        coordinator = VoxPendingSeekCoordinator.instance()
        coordinator.reset()
    }

    @Test
    fun testExactSeekWithinWindow() {
        // Стрим длительностью 2 часа (7_200_000 мс). Окно начинается с 0.
        // Пользователь перематывает на -20 минут (положение 6_000_000 мс).
        val target = 6_000_000L
        val fromPos = 7_000_000L
        val windowDuration = 7_200_000L

        val seek = coordinator.startSeek(
            requestedTargetMs = target,
            fromPositionMs = fromPos,
            mediaKind = VoxMediaKind.LIVE_DVR,
            windowStartMs = 0L,
            windowDurationMs = windowDuration
        )

        assertEquals(target, seek.requestedTargetMs)
        assertEquals(target, seek.clampedTargetMs)
        assertEquals(VoxSeekState.PENDING, seek.state)
        assertTrue(coordinator.hasActivePendingSeek())

        // Буферизация чанков
        coordinator.onBuffering(seek.generation)
        assertEquals(VoxSeekState.BUFFERING, seek.state)

        // Воспроизведение успешно началось на целевой позиции
        val settled = coordinator.onPlaybackProgress(target + 500L, seek.generation)
        assertTrue(settled)
        assertEquals(VoxSeekState.SETTLED, seek.state)
        assertEquals(VoxSeekOutcome.SUCCESS_EXACT, seek.outcome)
        assertEquals(target + 500L, seek.settledPositionMs)
        assertFalse(coordinator.hasActivePendingSeek())
    }

    @Test
    fun testSeekClampedToWindowStartWhenTooOld() {
        // Окно трансляции сдвинулось: windowStartMs = 1_000_000 мс (первые 16 минут уже удалены сервером).
        // Пользователь запрашивает 500_000 мс (за пределами окна).
        val requestedTarget = 500_000L
        val windowStart = 1_000_000L
        val windowDuration = 5_000_000L

        val seek = coordinator.startSeek(
            requestedTargetMs = requestedTarget,
            fromPositionMs = 3_000_000L,
            mediaKind = VoxMediaKind.LIVE_DVR,
            windowStartMs = windowStart,
            windowDurationMs = windowDuration
        )

        val minSafe = windowStart + VoxPendingSeekCoordinator.MIN_LIVE_WINDOW_CLAMP_BUFFER_MS
        assertEquals(minSafe, seek.clampedTargetMs)
        assertEquals(VoxSeekState.CLAMPED, seek.state)

        // Воспроизведение зафиксировалось на скорректированной позиции
        val settled = coordinator.onPlaybackProgress(minSafe, seek.generation)
        assertTrue(settled)
        assertEquals(VoxSeekOutcome.SUCCESS_CLAMPED, seek.outcome)
    }

    @Test
    fun testBehindLiveWindowRecoveryClampsToUpdatedWindow() {
        // Пользователь перематывает в стриме на 3_000_000 мс.
        val seek = coordinator.startSeek(
            requestedTargetMs = 3_000_000L,
            fromPositionMs = 5_000_000L,
            mediaKind = VoxMediaKind.LIVE_DVR,
            windowStartMs = 2_000_000L,
            windowDurationMs = 6_000_000L
        )

        coordinator.onBuffering(seek.generation)

        // Внезапно ExoPlayer выбрасывает BehindLiveWindowException: сервер сместил окно
        // Новое окно: windowStartMs = 3_200_000 мс, windowDuration = 7_000_000 мс
        val recoveredTarget = coordinator.onBehindLiveWindow(
            windowStartMs = 3_200_000L,
            windowDurationMs = 7_000_000L,
            generation = seek.generation
        )

        // Должно скорректироваться на начало обновленного окна, а не сбрасываться в 0 / old position!
        val expectedMinSafe = 3_200_000L + VoxPendingSeekCoordinator.MIN_LIVE_WINDOW_CLAMP_BUFFER_MS
        assertEquals(expectedMinSafe, recoveredTarget)
        assertEquals(expectedMinSafe, seek.clampedTargetMs)
        assertEquals(1, seek.recoveryAttemptCount)
    }

    @Test
    fun testMultipleSuccessiveSeeksCancelStaleGenerations() {
        // Быстрые нажатия перемотки: -10, -20, -30 мин
        val seek1 = coordinator.startSeek(5_000_000L, 6_000_000L, VoxMediaKind.LIVE_DVR, 0L, 8_000_000L)
        val seek2 = coordinator.startSeek(4_000_000L, 5_000_000L, VoxMediaKind.LIVE_DVR, 0L, 8_000_000L)
        val seek3 = coordinator.startSeek(3_000_000L, 4_000_000L, VoxMediaKind.LIVE_DVR, 0L, 8_000_000L)

        // Первые две операции должны быть отменены
        assertEquals(VoxSeekState.CANCELLED, seek1.state)
        assertEquals(VoxSeekOutcome.USER_CANCELLED, seek1.outcome)
        assertEquals(VoxSeekState.CANCELLED, seek2.state)
        assertEquals(VoxSeekOutcome.USER_CANCELLED, seek2.outcome)

        // Активна только третья
        assertEquals(VoxSeekState.PENDING, seek3.state)
        assertEquals(seek3.generation, coordinator.getActiveSeek()?.generation)

        // Устаревший callback от seek1 должен игнорироваться
        assertFalse(coordinator.onPlaybackProgress(5_000_000L, seek1.generation))
        assertEquals(VoxSeekState.PENDING, seek3.state)

        // Актуальный callback завершает перемотку
        assertTrue(coordinator.onPlaybackProgress(3_000_000L, seek3.generation))
        assertEquals(VoxSeekState.SETTLED, seek3.state)
    }

    @Test
    fun testBoundedRetriesExhaustion() {
        val seek = coordinator.startSeek(2_000_000L, 5_000_000L, VoxMediaKind.LIVE_DVR, 0L, 6_000_000L)

        // Симулируем постоянные сбои окна превышающие лимит MAX_RECOVERY_ATTEMPTS (3)
        coordinator.onBehindLiveWindow(2_500_000L, 6_000_000L, seek.generation)
        coordinator.onBehindLiveWindow(2_600_000L, 6_000_000L, seek.generation)
        coordinator.onBehindLiveWindow(2_700_000L, 6_000_000L, seek.generation)

        // 4-я попытка превышает лимит -> FAILED_UNRECOVERABLE
        val target = coordinator.onBehindLiveWindow(2_800_000L, 6_000_000L, seek.generation)
        assertEquals(-1L, target)
        assertEquals(VoxSeekState.FAILED, seek.state)
        assertEquals(VoxSeekOutcome.FAILED_UNRECOVERABLE, seek.outcome)
    }

    @Test
    fun testPendingPosMsPreservedInVideoModel() {
        val video = Video()
        video.videoId = "live_stream_001"
        video.isLive = true
        video.pendingPosMs = 0L

        // Команда перемотки устанавливает pendingPosMs
        val targetPos = 4_500_000L
        coordinator.startSeek(targetPos, 5_500_000L, VoxMediaKind.LIVE_DVR, 0L, 6_000_000L)
        video.pendingPosMs = targetPos

        assertEquals(targetPos, video.pendingPosMs)
        // Даже если reloadVideo() сработал, pendingPosMs гарантирует восстановление цели перемотки
    }

    @Test
    fun testNegativeTargetClampedToZero() {
        // Запрос перемотки назад, уходящий в отрицательные координаты (-10 мин при позиции 2 мин)
        val requestedTarget = -480_000L
        val seek = coordinator.startSeek(
            requestedTargetMs = requestedTarget,
            fromPositionMs = 120_000L,
            mediaKind = VoxMediaKind.LIVE_DVR,
            windowStartMs = 0L,
            windowDurationMs = 3_600_000L
        )

        // Позиция должна быть безопасно ограничена 0L
        assertEquals(0L, seek.clampedTargetMs)
        assertEquals(VoxSeekState.CLAMPED, seek.state)
    }

    @Test
    fun testUnknownDurationDynamicWindowDoesNotPrematurelyFail() {
        // Прямой эфир с неизвестной длительностью (windowDurationMs = -1L / C.TIME_UNSET)
        // Окно трансляции начинается с 500_000 мс. Пользователь перематывает на 1_200_000 мс.
        val target = 1_200_000L
        val seek = coordinator.startSeek(
            requestedTargetMs = target,
            fromPositionMs = 2_000_000L,
            mediaKind = VoxMediaKind.LIVE_DVR,
            windowStartMs = 500_000L,
            windowDurationMs = -1L
        )

        assertEquals(target, seek.clampedTargetMs)
        assertEquals(VoxSeekState.PENDING, seek.state)

        // BehindLiveWindow на стриме с неизвестной длительностью
        val recovered = coordinator.onBehindLiveWindow(
            windowStartMs = 600_000L,
            windowDurationMs = 0L,
            generation = seek.generation
        )
        // Не должно аварийно завершаться - позиция 1_200_000 валидна в окне > 600_000
        assertEquals(target, recovered)
        assertEquals(VoxSeekState.RECOVERING, seek.state)
    }

    @Test
    fun testVideoIdIsolationAndCancellation() {
        val seek = coordinator.startSeek(
            requestedTargetMs = 500_000L,
            fromPositionMs = 1_000_000L,
            mediaKind = VoxMediaKind.LIVE_DVR,
            windowStartMs = 0L,
            windowDurationMs = 5_000_000L,
            videoId = "video_alpha"
        )
        assertEquals("video_alpha", seek.videoId)
        assertTrue(coordinator.hasActivePendingSeek())

        // Смена видео отменяет операцию
        coordinator.cancel("Video changed")
        assertEquals(VoxSeekState.CANCELLED, seek.state)
        assertEquals(VoxSeekOutcome.USER_CANCELLED, seek.outcome)
        assertFalse(coordinator.hasActivePendingSeek())
    }
}
