# SmartTube VOX 8 — Patch #20: Translation UX & Auto-Activation

## 1. Базовая информация
- **Base Commit**: `358c6ef9c8fb81e5c0286d831cc4cc986922dc6f` (Patch #19 merge)
- **Branch**: `vox8/patch20-translation-auto-activation-ux`
- **App Version**: `32.56-vox.8-rc1` (versionCode `2446010`)
- **Эмулятор**: `Vox-TV-14` (Google TV / Android TV, Android 14, API 34 x86)
- **Физический TV**: НЕ использовался (`PHYSICAL_TV_TESTED: NO`)

---

## 2. Проблема и контекст
По обратной связи реальных пользователей:
> «Перевод готов, можно включить в настройках. Говорит на английском. Куда нажимать и почему нет автоматического разговора на русском языке?»

### Причины проблемы:
1. **Отсутствие автоактивации готового перевода**: При запросе перевода бэкенд возвращал состояние `READY`, но аудиотрек перевода автоматически не запускался и основной звук не приглушался без дополнительного действия.
2. **Вводящее в заблуждение сообщение**: Оверлей `VotProgressOverlay.showReady()` отображал подзаголовок `«Можно включить в настройках»` (`vot_progress_subtitle_ready`), создавая впечатление, что перевод не активен или требует ручного включения в глубине меню настроек.
3. **Отсутствие чёткого разделения намерений**: При ручном отключении перевода пользователем случайные запоздалые коллбэки готовности перевода могли снова вмешиваться в воспроизведение.

---

## 3. Реализованные решения

### 1. Автоматическая активация готового перевода (`Auto-Activation`)
- В `VoiceTranslateController.java` при переходе перевода в состояние `READY` (по явной кнопке или автопереводу) происходит немедленный запуск воспроизведения русской аудиодорожки перевода через `YandexVotPlaybackAdapter` / `TranslationAudioPlayer`, а громкость основного видео плавно приглушается (`ducking`).
- При этом оверлей кратковременно показывает уведомление: `«Перевод включён»` (`vot_translation_activated`) без каких-либо вводящих в заблуждение инструкций.

### 2. Защита намерения пользователя (`User Intent Safeguard`)
- Добавлен флаг `mUserExplicitlySelectedOriginal`. Если пользователь вручную отключил перевод во время загрузки или нажал «Оригинал», запоздалые ответы от сети или кэша переводчика не прерывают воспроизведение оригинала и не приглушают звук.
- Добавлен статус `STATE_READY = 3`. Если в настройках выключена опция автоактивации, плашка показывает `«Перевод готов»` и подзаголовок `«Нажмите кнопку перевода для включения»`, а нажатие кнопки перевода сразу активирует готовый трек.
- При смене видео (`onNewVideo`) все сессионные данные, ссылки на аудио и флаги намерений гарантированно сбрасываются.

### 3. Настройка в меню и диалоге микширования
- В `VotData.java` добавлена настройка `AUTO_ACTIVATE_READY_TRANSLATION` (`isAutoActivateReadyTranslation()`, по умолчанию `true`).
- В меню настроек плеера (`PlayerSettingsPresenter.java`) и диалоге микширования звука (`AppDialogUtil.java`) добавлен переключатель:
  - `«Автоматически включать готовый перевод»` / `«Сразу запускать озвучку после подготовки без дополнительного нажатия»`.
- Реализован метод `VotData.getTargetLanguage()` с автоматическим определением русского языка (`"ru"`) для русской локали устройства.

---

## 4. Результаты тестирования

### Модульные тесты:
- **`VoiceTranslateAutoActivationTest.java`**: 6/6 тестов PASS
  - `testAutoActivationWhenReadyAndSettingEnabled()` — мгновенный запуск и приглушение звука при `READY`.
  - `testManualActivationWhenAutoActivationDisabled()` — переход в `STATE_READY` с возможностью запуска по клику.
  - `testExplicitOriginalSelectionPreventsAutoActivation()` — защита выбора оригинала от фоновых ответов бэкенда.
  - `testNewVideoResetsUserIntentAndPendingAudio()` — изоляция сессий при смене роликов.
  - `testCachedTranslationInstantAutoActivation()` — немедленная активация при наличии кэшированного перевода.
  - `testTargetLanguageDefaultsToRussianOnRussianLocale()` — корректное определение целевого языка.
- **Android Unit Tests**: PASS 100% (`:common:testStvotDebugUnitTest`, `:smarttubetv:testStvotDebugUnitTest`)
- **Backend & Service Tests**:
  - `services/vox-diagnostics`: 41/41 PASS
  - `services/vox-live-gateway`: 30/30 PASS
  - `services/vox-live-backend`: 13/13 PASS
  - `services/yandex-oauth-broker`: 14/14 PASS
  - `tizen`: 9/9 PASS

### Эмулятор Android TV (API 34):
- Сборка и установка debug APK (`io.github.kiryuhak.smarttubetv.stable`).
- Проверка интерфейса и запуск плеера.
