# Архитектура медиа-стека и совместимость SmartTube VOX (Media3 / ExoPlayer)

## 1. Обзор архитектуры

SmartTube VOX использует многоуровневую специализированную архитектуру воспроизведения, оптимизированную для широкого спектра телевизионных платформ (Android TV 9–14, Google TV, Fire TV, а также Samsung Tizen Web/AVPlay):

```
┌─────────────────────────────────────────────────────────────┐
│                 UI & Leanback Transport Glue                 │
│         (VideoPlayerGlue / PlaybackTransportRowPresenter)    │
├─────────────────────────────────────────────────────────────┤
│                    ExoPlayerController                      │
│      - Управление жизненным циклом и телеметрия задержек    │
│      - Интеграция с VoxSafeLogger (STARTUP / REBUFFER)      │
├─────────────────────────────────────────────────────────────┤
│                  Adaptive Buffer Policy                     │
│         (VoxPlaybackBufferPolicy / createLoadControl)       │
│      - HIGH_PERFORMANCE: 35s/60s буфер (>=3.5GB RAM / 4K)   │
│      - BALANCED: 25s/40s буфер (1.5GB–3.5GB RAM / 1080p)    │
│      - CONSERVATIVE: 6s/12s (Live) или 15s/25s (<1.5GB RAM) │
├─────────────────────────────────────────────────────────────┤
│            Hardware Codec & Track Selection Policy          │
│       (VoxCodecPolicy / VoxCompatibilityRiskEvaluator)      │
│      - Аппаратная приоритизация: AV1 -> VP9 -> AVC (H.264)  │
│      - Безопасное разделение декодирования и Passthrough    │
├─────────────────────────────────────────────────────────────┤
│                    Core ExoPlayer Engine                    │
│      - Базовый движок: exoplayer-amzn-2.10.6                │
│      - Расширения: Cronet, OkHttp, Leanback, MediaSession   │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Матрица совместимости Android TV (API 28–34)

| Версия Android TV | API Level | Статус поддержки | Особенности и оптимизации |
|---|---|---|---|
| Android TV 9 (Pie) | API 28 | Полная поддержка | Базовая поддержка VP9 Profile 0/2, безопасный fallback |
| Android TV 10 (Q) | API 29 | Полная поддержка | Оптимизированный буфер, поддержка аппаратного AV1 на совместимых SoC |
| Android TV 11 (R) | API 30 | Полная поддержка | Dynamic routing, tunneled playback |
| Android TV 12 (S) | API 31–32 | Полная поддержка | Display mode refresh switching, HDR10+ / HLG |
| Android TV 13 (T) | API 33 | Полная поддержка | Audio routing permissions, multi-audio passthrough |
| Android TV 14 (U) | API 34 | Полная поддержка | Target compileSdk 34, runtime guards, flat memory lifecycle |

---

## 3. Политика адаптивной буферизации (`VoxPlaybackBufferPolicy`)

1. **Прямой эфир (Live Streaming)**:
   - Минимальный буфер: 6 000 мс
   - Максимальный буфер: 12 000 мс
   - Старт воспроизведения: 1 500 мс
   - Back-буфер: 0 мс (предотвращает раздувание памяти при длительном просмотре)

2. **Локальные загрузки (Offline MKV)**:
   - Минимальный буфер: 8 000 мс
   - Максимальный буфер: 15 000 мс
   - Старт воспроизведения: 800 мс (мгновенный отклик с диска)
   - Back-буфер: 10 000 мс

3. **Сетевой VOD (YouTube Streams)**:
   - **POWERFUL / 4K**: 35s / 60s, целевой буфер до 128 МБ.
   - **STANDARD / 1080p**: 25s / 40s, целевой буфер до 64 МБ.
   - **BASIC / Low RAM**: 15s / 25s, целевой буфер 20–32 МБ.

---

## 4. Обоснование отложенной миграции на Media3 1.x

Полная замена кодовой базы плеера на Media3 1.x сознательно отложена по следующим техническим причинам:
1. Сохранение обратной совместимости с `minSdkVersion 17` для поддержки широкого парка legacy ТВ-приставок и смарт-ТВ.
2. Использование оптимизированной сборки `exoplayer-amzn-2.10.6`, содержащей вендорные исправления для Amazon Fire TV и Leanback glue.
3. Достижение всех ключевых преимуществ современных медиа-движков (адаптивная буферизация, runtime-guards API 28–34, аппаратная приоритизация кодеков, изолированная санитизированная телеметрия) через модульные сервисные слои без дестабилизации плеера.
