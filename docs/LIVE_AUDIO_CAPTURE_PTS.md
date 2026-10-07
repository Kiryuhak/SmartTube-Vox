# SmartTube VOX 8 — Live Audio Capture & PTS Synchronization

## 1. Архитектурная цель Patch #6
В рамках этапа Patch #6 в проекте **SmartTube VOX 8** реализован рабочий прототип перехвата живого звука из аудиодекодера ExoPlayer и синхронизации вторичного воспроизведения по аппаратным меткам времени (**PTS — Presentation Time Stamp**).

Ключевые инженерные задачи Patch #6:
- Исследовать низкоуровневый аудиоконвейер ExoPlayer и определить безопасную точку перехвата без задержек и искажений основного потока.
- Реализовать механизм безопасного перехвата (`VoxLiveAudioTap`) с нулевым оверхедом при выключенном флаге.
- Привязать каждый аудиофрейм к аппаратному PTS декодера (`bufferPresentationTimeUs`).
- Реализовать нормализацию меток времени и детекцию разрывов непрерывности (Discontinuity / Generation Bump).
- Реализовать сборщик фрагментов целевой длительности (~2-3 сек) с жестким ограничением памяти (Bounded Memory).
- Расширить шлюз живых аудиосегментов (`services/vox-live-gateway`) приемом PTS-метаданных и поддержкой режима Passthrough.
- Реализовать вторичный аудиоплеер (`VoxLiveSecondaryAudioPlayer`) на базе Android `AudioTrack` в потоковом режиме с поддержкой трех режимов маршрутизации.
- Построить контроллер PTS-синхронизации (`VoxLivePtsSyncController`), удерживающий рассинхрон в пределах $\le 150$ мс и обеспечивающий мгновенный бесшовный откат (Fallback) на оригинальный звук при опустошении буфера.

---

## 2. Исследование аудиоконвейера ExoPlayer
Аудиовывод в SmartTube базируется на кастомном рендерере `DelayMediaCodecAudioRenderer`, наследующемся от ExoPlayer `MediaCodecAudioRenderer`.

В ходе анализа исследованы альтернативные точки интеграции:
1. `AudioProcessor` цепочки `AudioSink`: не имеет прямого доступа к аппаратным `bufferPresentationTimeUs` декодера в ExoPlayer 2.10.
2. `MediaCodecAudioRenderer.processOutputBuffer`: вызывается для каждого декодированного аппаратного буфера `ByteBuffer` с точным `bufferPresentationTimeUs`, флагами и параметрами формата (`Format`).

Вывод: `DelayMediaCodecAudioRenderer.processOutputBuffer` является оптимальной и архитектурно наиболее точной точкой захвата.

---

## 3. Точка перехвата: VoxLiveAudioTap
Метод `processOutputBuffer` в `DelayMediaCodecAudioRenderer` расширен безопасным перехватом:
```java
ByteBuffer tapBuffer = null;
if (VoxLiveAudioTap.isEnabled() && buffer != null) {
    tapBuffer = buffer.duplicate();
}

boolean result = super.processOutputBuffer(
        positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex, bufferFlags,
        bufferPresentationTimeUs, isDecodeOnlyBuffer, isLastBuffer, format
);

if (result && tapBuffer != null && VoxLiveAudioTap.isEnabled()) {
    int sampleRate = format != null ? format.sampleRate : 48000;
    int channelCount = format != null ? format.channelCount : 2;
    int pcmEncoding = format != null ? format.pcmEncoding : 2;
    VoxLiveAudioTap.onAudioOutputBuffer(
            tapBuffer,
            bufferPresentationTimeUs,
            sampleRate,
            channelCount,
            pcmEncoding
    );
}
```

Преимущества данного решения:
- `buffer.duplicate()` создает легковесную обертку без копирования памяти на этапе проверки.
- Аудиофрейм передается в тап **только** после того, как `super.processOutputBuffer` вернул `true` (буфер успешно принят `AudioSink`), исключая дубликаты при повторах.
- Основной рендерер и синхронизация видеопотока ExoPlayer не модифицируются.

---

## 4. Zero-Overhead и Feature Flags
Все компоненты захвата звука и вторичного плеера по умолчанию строго отключены:
- `VoxLiveFeatureFlags.VOX_LIVE_AUDIO_CAPTURE_EXPERIMENTAL = false`
- `VoxLiveFeatureFlags.VOX_LIVE_SECONDARY_AUDIO_EXPERIMENTAL = false`

Когда захват отключен:
- `VoxLiveAudioTap.isEnabled()` возвращает `false`.
- Нет аллокаций памяти, создания дубликатов буферов или вызовов обработчиков.
- Оверхед равен ровно одной атомарной проверке булевой переменной в потоке декодера.

---

## 5. Формат захватываемого аудио
- Тип данных: линейный PCM (Linear PCM 16-bit Little-Endian).
- Частота дискретизации: 48 000 Гц или 44 100 Гц (определяется дорожкой YouTube).
- Каналы: 2 (Stereo).
- Длительность одиночного буфера: обычно 10–40 мс (зависит от настроек аппаратного декодера).
- Расчет длительности фрейма:
  $$\text{durationUs} = \frac{\text{remainingBytes} \times 1\,000\,000}{\text{channels} \times 2 \times \text{sampleRate}}$$

---

## 6. PTS-модель и привязка меток
Каждый фрейм несет оригинальный аппаратный PTS (`presentationTimeUs`) в микросекундах. Это позволяет связать акустический сигнал непосредственно с видеокадрами в буфере видеодекодера.

---

## 7. Нормализация меток времени (VoxLiveAudioPtsNormalizer)
Аппаратные таймстемпы YouTube прямых трансляций могут начинаться с произвольного значения и содержать периодические сдвиги.

`VoxLiveAudioPtsNormalizer` преобразует абсолютный PTS в сессионно-относительный:
$$\text{relativePtsUs} = \text{rawPtsUs} - \text{basePtsUs}$$

Где `basePtsUs` фиксируется по первому пришедшему фрейму сессии.

---

## 8. Детекция разрывов непрерывности (Discontinuity Detection)
При перемотке пользователем (Seek) или прыжке прямого эфира (Live Catchup):
- Прыжок вперед: $\Delta \text{PTS} > 10\,000\,000$ мкс (10 сек).
- Перемотка назад: $\Delta \text{PTS} < -500\,000$ мкс (-500 мс).

При наступлении любого из этих условий:
1. Инкрементируется поколение: `generation++`.
2. Базовый PTS перепривязывается: `basePtsUs = newRawPtsUs`.
3. Очищается текущий накапливаемый фрагмент сборщика.
4. Вторичный аудиоплеер сбрасывает устаревшие фреймы предыдущих поколений.

---

## 9. Сборщик аудиофрагментов (VoxLiveAudioFragmentAssembler)
Декодер выдает фреймы небольшими порциями (по 10–40 мс). Сетевой шлюз и системы перевода работают эффективнее с чанками длительностью 2–3 секунды.

`VoxLiveAudioFragmentAssembler` объединяет мелкие PCM-фреймы в единый `VoxLiveAudioFragment`:
- `targetDurationUs = 2_000_000L` (2 секунды по умолчанию, настраиваемо 0.5–5.0 сек).
- Сохраняет `startPtsUs`, `endPtsUs`, `sampleRate`, `channels`, `encoding`.
- Формирует монотонный `sequence` (0, 1, 2, ...).

---

## 10. Защита памяти и вытеснение буфера (Bounded Queue)
Для предотвращения утечек памяти и `OutOfMemoryError` на Android TV устройствах:
- Максимальное количество готовых фрагментов в очереди: **15**.
- Максимальный объем оперативной памяти под фрагменты: **8 МБ**.
- При переполнении самый старый невычитанный фрагмент удаляется (`drop oldest`), инкрементируя счетчик `droppedFragmentCount`.

---

## 11. Расширение локального шлюза (services/vox-live-gateway)
Шлюз дополнен поддержкой заголовков PTS:
- `X-Vox-Pts-Start-Us`: начальная метка фрагмента в микросекундах.
- `X-Vox-Pts-End-Us`: конечная метка фрагмента в микросекундах.
- `X-Vox-Sample-Rate`: частота дискретизации (например, 48000).
- `X-Vox-Channels`: число каналов (2).
- `X-Vox-Encoding`: кодировка (`pcm_16bit`).

Валидация шлюза проверяет:
- Если переданы обе метки времени, `ptsEndUs` должен быть $\ge$ `ptsStartUs`. Нарушение возвращает `400 Bad Request` (`INVALID_PTS_RANGE`).
- CORS-заголовки обновлены для разрешения передачи PTS-заголовков.

---

## 12. Режим Passthrough для детерминированного тестирования
В `MockLiveTranslationProvider` добавлен режим сквозной передачи (`passthroughAudio: true`):
- Переданные PCM-байты возвращаются без изменений.
- Все PTS-метки сохраняются в результатах опроса `GET /v1/live/session/:id/segment/:seq`.
- Позволяет детерминированно измерять задержку конвейера и точность PTS-синхронизации без внешних обращений к нейросетевым сервисам.

---

## 13. Прототип вторичного аудиоплеера (VoxLiveSecondaryAudioPlayer)
Реализован на базе стандартного класса Android `AudioTrack`:
- Режим: `AudioTrack.MODE_STREAMING`.
- Атрибуты: `USAGE_MEDIA`, `CONTENT_TYPE_MUSIC`.
- Потоковая запись `write(data, offset, size)`.
- Оценка текущей позиции воспроизведения `getEstimatedPlaybackPtsUs()` на основе `playbackHeadPosition` с учетом частоты дискретизации.

---

## 14. Режимы маршрутизации звука (VoxAudioRoutingMode)
1. `ORIGINAL_ONLY` (по умолчанию):
   - Вторичный `AudioTrack` замьючен (`volume = 0.0f`).
   - Основной звук YouTube играет на 100% громкости.
2. `SECONDARY_ONLY` (тестовый/прототипный режим):
   - Вторичный `AudioTrack` играет на 100% громкости.
   - Основной звук YouTube приглушен до 0%.
3. `MIX_DEBUG` (режим отладки синхрона):
   - Вторичный `AudioTrack` играет на 100%.
   - Основной звук приглушен до 20% для наглядного аудиального контроля рассинхронизации ("эхо").

---

## 15. Контроллер синхронизации (VoxLivePtsSyncController)
Контроллер вычисляет расхождение во времени между мастер-плеером и вторичным аудио:
$$\text{targetUs} = \text{masterPositionUs} - \text{targetDelayUs}$$
$$\text{driftMs} = \frac{\text{secondaryPtsUs} - \text{targetUs}}{1000}$$

---

## 16. Пороги рассинхронизации и действия
| Расхождение (Drift) | Действие (`VoxSyncAction`) | Поведение системы |
|---|---|---|
| $|\text{driftMs}| \le 150$ мс | `PLAY` | Синхрон в норме, продолжать воспроизведение |
| $\text{driftMs} > +150$ мс | `WAIT` | Вторичный звук опережает видео, приостановить вывод |
| $-500 \le \text{driftMs} < -150$ мс | `DROP` | Вторичный звук отстает от видео, сбросить отстающие фреймы |
| $|\text{driftMs}| > 500$ мс | `REANCHOR` | Критический рассинхрон, жесткий сброс привязки таймлайна |
| Буфер пуст (Underrun) | `FALLBACK` | Мгновенный откат на оригинальный звук YouTube |

---

## 17. Бесшовный Fallback при опустошении буфера
Критическое требование стабильности:
- Опустошение буфера вторичного звука **никогда не останавливает видеопоток YouTube**.
- Срабатывает `triggerUnderrunFallback()`.
- Громкость оригинального звука немедленно восстанавливается до 100% (`1.0f`).
- Пользователь продолжает смотреть трансляцию без заиканий и зависаний.

---

## 18. Поддержка скоростей воспроизведения
`VoxLivePtsSyncController` учитывает темп воспроизведения (1.0x, 1.25x, 1.5x, 1.75x, 2.0x):
- При ускорении воспроизведения расчетная целевая позиция масштабируется пропорционально.
- Проверена корректность работы при динамической смене скорости без сброса сессии.

---

## 19. Метрики и Zero-Telemetry диагностика
Диагностическая модель `VoxLiveAudioSyncDiagnostics` собирает:
- `captureActive`: флаг активности захвата.
- `routingMode`: режим маршрутизации звука.
- `generation`: текущий номер поколения непрерывности.
- `lastPtsUs`: последняя зафиксированная метка времени.
- `currentDriftMs` и `maxDriftMs`: текущее и пиковое расхождение.
- `underrunCount`: число опустошений буфера.
- `reanchorCount`: число жестких перепривязок таймлайна.
- `totalCapturedFragments` и `totalCapturedBytes`: объем захваченных данных.
- `fallbackActive`: флаг активности защитного отката.

Диагностика строго санитизирована: не содержит аудиоданных, заголовков авторизации или названий видео.

---

## 20. Итоги валидации и планы на Patch #7
1. Архитектура захвата и синхронизации по PTS полностью покрыта модульными тестами Robolectric и Node.js.
2. В шлюзе подтверждено корректное прохождение PTS-метаданных и детерминированный Passthrough.
3. Проверена стабильность основного видеопотока прямых эфиров (число ребуферизаций остается равным 0).
4. В рамках следующего Patch #7 запланирована интеграция сборщика с клиентом шлюза и сквозное тестирование сессионного конвейера.
