# VOX 6 Patch 3 — Media Codecs Audit

## Цель (Purpose)
Аудит и частичная реализация поддержки кодеков VP9 и Opus для оффлайн загрузки и мультиплексирования в контейнер Matroska (MKV).
Из-за строгих требований к стабильности, на данный момент кодеки остаются в режиме fallback (на уровне архитектуры поддержаны, но выбор по умолчанию оставлен за AVC/AAC).

## Статус (Status)
- **VP9 (V_VP9)**: ARCHITECTURE_READY. MediaExtractor корректно извлекает видеопоток. Мультиплексор (Matroska) правильно упаковывает VP9 (без CodecPrivate). Поддерживается аппаратное/программное декодирование на эмуляторе.
- **Opus (A_OPUS)**: ARCHITECTURE_READY. MediaExtractor извлекает аудио. CodecPrivate (OpusHead) корректно синтезируется, если он отсутствует в csd-0 (согласно RFC 7845).
- **AV1 (V_AV1)**: ARCHITECTURE_READY. Формат поддерживается мультиплексором и на уровне MediaExtractor. По соображениям производительности (CPU) и стабильности включен не будет в текущем релизе.

## Архитектурные изменения (Architectural Changes)
1. **OpusHead CodecPrivate**:
   Добавлена функция синтеза OpusHead (согласно спецификации Matroska для A_OPUS) в `VoxMediaExtractorSource.kt`. При наличии валидного OpusHead в `csd-0`, используется он, иначе генерируется стандартный заголовок на основе `sampleRate`, `channels` и `preSkip`.
2. **Переопределения (Overrides)**:
   Добавлен `VoxCodecTestOverrides` для принудительного включения скачивания VP9 и Opus в целях тестирования и отладки, минуя стандартную политику выбора форматов в `VoxStreamResolver`.
3. **MIME Normalization**:
   `vp9`, `vp09` мапятся на `VoxMuxCodec.VP9`, `opus` мапится на `VoxMuxCodec.OPUS`.

## Решение по политике (Policy Decision)
**Вариант A**: VP9/Opus CONFIRMED but remain fallback only.

VP9 и Opus структурно корректно загружаются, мультиплексируются и проигрываются, однако из-за риска несовместимости с устаревшими TV-устройствами мы сохраняем приоритет AVC/AAC как самый безопасный вариант по умолчанию.
