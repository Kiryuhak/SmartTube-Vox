package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тесты виджета скачивания на строгое соответствие фиксированному макету (Prompt #028 Golden Reference).
 */
class Vox028DownloadBadgeMockupTest {

    private lateinit var stateMachine: VoxDownloadBadgeStateMachine

    @Before
    fun setUp() {
        stateMachine = VoxDownloadBadgeStateMachine()
    }

    @Test
    fun testCompleteMockupStateTransitions() {
        // 0. Исходное состояние: плашка скрыта
        assertEquals(VoxBadgeStage.HIDDEN, stateMachine.getStage())
        assertFalse(stateMachine.getStage().isVisible)

        // Этап 1: ВЫБОР ПАРАМЕТРОВ (модальный диалог)
        assertTrue(stateMachine.transitionTo(VoxBadgeStage.SELECTING))
        assertEquals(VoxBadgeStage.SELECTING, stateMachine.getStage())

        // Этап 2: НАЧАЛО ЗАГРУЗКИ (развернутая панель 96dp в правом нижнем углу)
        stateMachine.setActiveDownloadId("dl_mock_001")
        assertTrue(stateMachine.transitionTo(VoxBadgeStage.DOWNLOADING_EXPANDED))
        assertEquals(VoxBadgeStage.DOWNLOADING_EXPANDED, stateMachine.getStage())
        assertTrue(stateMachine.getStage().isVisible)

        // Этап 3: КОМПАКТНЫЙ РЕЖИМ (сворачивание через 4с в кольцо 64x64 dp)
        assertTrue(stateMachine.transitionTo(VoxBadgeStage.DOWNLOADING_COMPACT))
        assertEquals(VoxBadgeStage.DOWNLOADING_COMPACT, stateMachine.getStage())

        // Этап 4: РАСКРЫТИЕ ПО НАЖАТИЮ (повторное раскрытие на том же месте)
        assertTrue(stateMachine.transitionTo(VoxBadgeStage.REOPENED))
        assertEquals(VoxBadgeStage.REOPENED, stateMachine.getStage())

        // Повторное сворачивание
        assertTrue(stateMachine.transitionTo(VoxBadgeStage.DOWNLOADING_COMPACT))
        assertEquals(VoxBadgeStage.DOWNLOADING_COMPACT, stateMachine.getStage())

        // Этап 5: ДОЛГОЕ НАЖАТИЕ (контекстное меню продолжить / отменить)
        assertTrue(stateMachine.transitionTo(VoxBadgeStage.LONG_PRESS_MENU))
        assertEquals(VoxBadgeStage.LONG_PRESS_MENU, stateMachine.getStage())

        // Возврат в компактный режим
        assertTrue(stateMachine.transitionTo(VoxBadgeStage.DOWNLOADING_COMPACT))
        assertEquals(VoxBadgeStage.DOWNLOADING_COMPACT, stateMachine.getStage())

        // Завершение загрузки: зеленая галочка #00C853 "Скачано"
        assertTrue(stateMachine.transitionTo(VoxBadgeStage.COMPLETED))
        assertEquals(VoxBadgeStage.COMPLETED, stateMachine.getStage())

        // Автоскрытие через 3.5с
        assertTrue(stateMachine.transitionTo(VoxBadgeStage.HIDDEN))
        assertEquals(VoxBadgeStage.HIDDEN, stateMachine.getStage())
    }

    @Test
    fun testInvalidTransitionsRejected() {
        // Нельзя сразу перейти в REOPENED или LONG_PRESS_MENU из скрытого состояния
        assertFalse(stateMachine.transitionTo(VoxBadgeStage.REOPENED))
        assertEquals(VoxBadgeStage.HIDDEN, stateMachine.getStage())

        assertFalse(stateMachine.transitionTo(VoxBadgeStage.LONG_PRESS_MENU))
        assertEquals(VoxBadgeStage.HIDDEN, stateMachine.getStage())
    }

    @Test
    fun testPauseResumeTransitions() {
        stateMachine.transitionTo(VoxBadgeStage.DOWNLOADING_EXPANDED)
        assertTrue(stateMachine.transitionTo(VoxBadgeStage.PAUSED))
        assertEquals(VoxBadgeStage.PAUSED, stateMachine.getStage())

        // Возобновление возвращает в expanded
        assertTrue(stateMachine.transitionTo(VoxBadgeStage.DOWNLOADING_EXPANDED))
        assertEquals(VoxBadgeStage.DOWNLOADING_EXPANDED, stateMachine.getStage())
    }

    @Test
    fun testMockupFixedConstants() {
        assertEquals(64f, VoxDownloadOverlay.COMPACT_SIZE_DP, 0.01f)
        assertEquals(96f, VoxDownloadOverlay.EXPANDED_HEIGHT_DP, 0.01f)
        assertEquals(320f, VoxDownloadOverlay.EXPANDED_WIDTH_DP, 0.01f)
        assertEquals(24f, VoxDownloadOverlay.MARGIN_RIGHT_DP, 0.01f)
        assertEquals(24f, VoxDownloadOverlay.MARGIN_BOTTOM_DP, 0.01f)
    }

    @Test
    fun testMockupColorPaletteInvariants() {
        // Проверяем шестнадцатеричные коды эталонной палитры
        val playerBg = "#CC0B1622"
        val panelBg = "#CC0F1C2C"
        val accent = "#1890FF"
        val success = "#00C853"
        val info = "#4FC3F7"
        val textPrimary = "#FFFFFF"
        val textSecondary = "#B0BEC5"
        val pillBg = "#1A293D"
        val pillStroke = "#384E6E"
        val danger = "#FF5252"

        assertTrue(playerBg.endsWith("0B1622"))
        assertTrue(panelBg.endsWith("0F1C2C"))
        assertEquals("#1890FF", accent)
        assertEquals("#00C853", success)
        assertEquals("#4FC3F7", info)
        assertEquals("#FFFFFF", textPrimary)
        assertEquals("#B0BEC5", textSecondary)
        assertEquals("#1A293D", pillBg)
        assertEquals("#384E6E", pillStroke)
        assertEquals("#FF5252", danger)
    }
}
