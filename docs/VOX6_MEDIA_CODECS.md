# VOX 6 Patch 3 — Media Codecs Audit

## Цель (Purpose)
Аудит и реализация поддержки кодеков VP9 и Opus для оффлайн загрузки и мультиплексирования в контейнер Matroska (MKV).
Из-за строгих требований к стабильности, VP9/Opus остаются в режиме opt-in (выбор по умолчанию — AVC/AAC), но полностью подтверждены в реальном рантайме.

## Статус (Status)
- **VP9 (V_VP9)**: **CONFIRMED**. Реальная загрузка с YouTube → мукс → ExoPlayer пройдена. Все 3 трека (VP9, Opus, MP3 перевод) корректно смультиплексированы и воспроизведены. EOS достигнут, seek работает, переключение аудиодорожки работает.
- **Opus (A_OPUS)**: **CONFIRMED**. CodecPrivate (OpusHead, 19 байт) корректно синтезирован. csd-1 (задержка кодека в наносекундах) правильно конвертируется в preSkip-сэмплы (312 сэмплов при 48 кГц). Playback через ExoPlayer без ошибок.
- **AVC + AAC (Baseline)**: **CONFIRMED**. Реальная загрузка → мукс → ExoPlayer. Seek 30s/60s пройден. EOS достигнут.
- **AV1 (V_AV1)**: ARCHITECTURE_READY. Поддержан мультиплексором. Не включён в текущем релизе из соображений производительности.

## Доказательства (Runtime Evidence)
Тест: видео `jNQXAC9IVRw`, эмулятор `Vox-TV-14` (Android 14 / API 34).

### VP9 + Opus (VoxCodecTestOverrides.forceVp9Video=true, forceOpusAudio=true)
```
DOWNLOAD_PROBE: state=MUXED totalBytes=854497
DOWNLOAD_PROBE: state=COMPLETED totalBytes=854497
VoxDownloadCoordinator: published to content://media/external/video/media/1000000317
EXOPLAYER_TRACK: group=0 mime=video/x-vnd.on2.vp9   ← VP9 ✓
EXOPLAYER_TRACK: group=1 mime=audio/opus lang=und    ← Opus ✓
EXOPLAYER_TRACK: group=2 mime=audio/mpeg lang=ru     ← перевод ✓
EXOPLAYER_EOS_PASS: pos=30000
EXOPLAYER_SELECT_ORIGINAL_PASS / EXOPLAYER_SWITCH_TRANSLATION_PASS
EXOPLAYER_TRACK_SWITCH_CYCLE_PASS
```

### AVC + AAC (overrides отключены)
```
DOWNLOAD_PROBE: state=MUXED totalBytes=1048359
DOWNLOAD_PROBE: state=COMPLETED totalBytes=1048359
EXOPLAYER_TRACK: group=0 mime=video/avc              ← AVC ✓
EXOPLAYER_TRACK: group=1 mime=audio/mp4a-latm        ← AAC ✓
EXOPLAYER_TRACK: group=2 mime=audio/mpeg lang=ru     ← перевод ✓
EXOPLAYER_EOS_PASS: pos=30000
EXOPLAYER_SEEK_30S_PASS / EXOPLAYER_SEEK_60S_PASS
```

## Архитектурные изменения (Architectural Changes)
1. **OpusHead CodecPrivate**:
   Функция синтеза OpusHead (RFC 7845 / Matroska A_OPUS) в `VoxMediaExtractorSource.kt`. При наличии валидного OpusHead в `csd-0`, используется он, иначе генерируется на основе `sampleRate`, `channels` и `preSkip`. Bounds-checking: mono/stereo only (mapping family 0), preSkip зажат в [0, 65535].
2. **csd-1 nano→samples конвертация**:
   Android MediaExtractor отдаёт csd-1 в **наносекундах**. Конвертация: `preSkipSamples = (delayNs * sampleRate) / 1_000_000_000L`. Некорректная прямая конвертация через `toInt()` исправлена.
3. **Переопределения (Overrides)**:
   `VoxCodecTestOverrides` — opt-in флаги для принудительного выбора VP9/Opus при тестировании.
4. **MIME Normalization**:
   `vp9`, `vp09` → `VoxMuxCodec.VP9`; `opus` → `VoxMuxCodec.OPUS`.

## Политика (Policy)
VP9/Opus **CONFIRMED** в реальном рантайме. По умолчанию остаётся AVC/AAC (совместимость со старыми TV). VP9/Opus доступны через `VoxCodecTestOverrides` и будут включены как опция для пользователя в следующих патчах.
