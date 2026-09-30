# VOX 5: Matroska / MKV Муксер (Архитектура и реализация)

## 1. Обзор и цели

В рамках Патча #5 реализован легковесный, потоковый муксер контейнера Matroska (MKV) на чистом Kotlin для Android/SmartTube.

### Основные требования и ограничения:
- **Remux-only (без перекодирования)**: видео и аудиодорожки упаковываются в контейнер байт-в-байт без декомпрессии и пережатия, что исключает нагрузку на CPU/GPU и сохраняет 100% исходного качества.
- **Zero FFmpeg / Zero GPL**: реализация полностью написана с нуля (clean-room) по стандарту RFC 8794 (EBML) и спецификации Matroska v4 под пермиссивной лицензией (Apache-2.0 / MIT совместимой). Никаких сторонних бинарных библиотек или GPL/AGPL зависимостей не привлекалось.
- **Многодорожечный вывод**: контейнер объединяет 3 потока:
  1. Видеодорожка (DASH AVC1 / H.264).
  2. Оригинальная аудиодорожка (DASH AAC-LC / `und`).
  3. Дорожка голосового перевода (Яндекс VOT MP3 / `ru` / флаг Default Audio Track).

---

## 2. Архитектура компонентов (`com.liskovsoft.smartyoutubetv2.common.vox.mux`)

1. **`VoxEbmlWriter.kt`**:
   - Потоковый кодировщик элементов EBML и типов данных:
     - `VINT` (Variable-size integer) кодирование для идентификаторов элементов и размеров данных.
     - Беззнаковые (`UInt`) и знаковые (`SInt`) целые числа переменной длины (1–8 байт).
     - Числа с плавающей точкой (IEEE 754 Float32 / Float64).
     - Строки UTF-8 / ASCII.
     - Бинарные блоки (`Binary`).
     - Элементы-контейнеры (`Master`) переменной и фиксированной длины.
   - Поддержка вложенности мастер-элементов с точным расчётом размеров в памяти (`ByteArrayOutputStream`).

2. **`VoxMediaExtractorSource.kt`**:
   - Потоковый экстрактор семплов на базе стандартного платформенного `android.media.MediaExtractor`.
   - Автоматическое извлечение кодек-приватных данных (`CodecPrivate`):
     - Для AVC (H.264): конструирование `AVCDecoderConfigurationRecord` (`avcC`) из SPS (`csd-0`) и PPS (`csd-1`).
     - Для AAC: извлечение `AudioSpecificConfig` (2 байта `csd-0`).
   - Автоматическая нормализация NAL-юнитов (преобразование 4-байтовых Annex B префиксов `0x00000001` в формат длины пакета AVCC при необходимости).

3. **`VoxMatroskaMuxer.kt`**:
   - Основной мультиплексор потоков:
     - Формирование заголовка EBML (DocType `matroska`, DocTypeVersion 4).
     - Формирование метаданных сегмента (`Segment`, `Info` с `TimecodeScale` = 1,000,000 нс / 1 мс).
     - Регистрация дорожек (`Tracks`, `TrackEntry` с `TrackNumber`, `TrackType`, `CodecID`, `CodecPrivate`, `Language`, `DefaultDuration`).
     - Чередование семплов (interleaving) из всех источников по шкале таймстемпов презентации (PTS).
     - Формирование кластеров (`Cluster`) длительностью ~2000 мс с выравниванием по ключевым кадрам (Keyframe/Sync samples).
     - Упаковка семплов в `SimpleBlock` с флагами ключевого кадра.
     - Построение индекса быстрой перемотки (`Cues`, `CuePoint`, `CueTime`, `CueTrackPositions`).
     - Финализация контейнера: расчёт итоговой длительности (`Duration`), формирование карты оглавления (`SeekHead`), патчинг заголовка сегмента через `RandomAccessFile`.

4. **Интеграция с ядром загрузки (`VoxDownloadCoordinator.kt`)**:
   - Состояние задачи переходит из `READY_FOR_MUX` -> `MUXING` -> `MUXED`.
   - Вывод записывается во временный файл `output.mkv.tmp`, атомарно переименовываемый в `output.mkv` после завершения.
   - При отмене или ошибке `output.mkv.tmp` удаляется, а исходные `.part` файлы сохраняются для возможности повторной сборки.
   - При перезапуске приложения прерванный процесс муксинга автоматически возвращается в статус `READY_FOR_MUX`.

---

## 3. Структура сгенерированного файла Matroska

```
[EBML Header] (0x1A45DFA3)
  ├── EBMLVersion: 1
  ├── EBMLReadVersion: 1
  ├── EBMLMaxIDLength: 4
  ├── EBMLMaxSizeLength: 8
  ├── DocType: "matroska"
  └── DocTypeVersion: 4
[Segment] (0x18538067)
  ├── [SeekHead] (0x114D9B74) -> Указатели на Info, Tracks, Cues
  ├── [Info] (0x1549A966)
  │     ├── TimecodeScale: 1000000 (1 ms)
  │     ├── MuxingApp: "SmartTube-Vox Matroska Muxer"
  │     ├── WritingApp: "SmartTube-Vox"
  │     └── Duration: <запатченная длительность в мс>
  ├── [Tracks] (0x1654AE6B)
  │     ├── Track 1 (Video):
  │     │     ├── TrackNumber: 1
  │     │     ├── TrackUID: 1
  │     │     ├── TrackType: 1 (Video)
  │     │     ├── CodecID: "V_MPEG4/ISO/AVC"
  │     │     ├── CodecPrivate: <avcC record: SPS/PPS>
  │     │     └── VideoSettings: PixelWidth=640, PixelHeight=360
  │     ├── Track 2 (Original Audio):
  │     │     ├── TrackNumber: 2
  │     │     ├── TrackUID: 2
  │     │     ├── TrackType: 2 (Audio)
  │     │     ├── CodecID: "A_AAC"
  │     │     ├── CodecPrivate: <AudioSpecificConfig: 2 bytes>
  │     │     ├── Language: "und"
  │     │     ├── FlagDefault: 0
  │     │     └── AudioSettings: SamplingFrequency=44100, Channels=2
  │     └── Track 3 (Translated Audio - VOT):
  │           ├── TrackNumber: 3
  │           ├── TrackUID: 3
  │           ├── TrackType: 2 (Audio)
  │           ├── CodecID: "A_MPEG/L3"
  │           ├── Language: "ru"
  │           ├── FlagDefault: 1 (Default audio)
  │           └── AudioSettings: SamplingFrequency=44100, Channels=2
  ├── [Cluster 1] (0x1F43B675) (Timecode = 0)
  │     ├── SimpleBlock (Track 1, Keyframe)
  │     ├── SimpleBlock (Track 2)
  │     ├── SimpleBlock (Track 3)
  │     └── ...
  ├── [Cluster 2..N] (~2 сек интервалы, выровненные по I-frame видео)
  └── [Cues] (0x1C53BB6B) -> Точки входа (Seek points) по ключевым кадрам видео
```

---

## 4. Результаты аппаратной и программной верификации

### Входные данные (реальные потоки с эмулятора `Vox-TV-14` / Android 14):
- Видео: `video.part` (MP4 AVC 640x360) — 18,220,768 байт
- Оригинальное аудио: `original_audio.part` (MP4 AAC 44.1kHz 2ch) — 1,718,053 байт
- Перевод VOT: `translated_audio.part` (MP3 44.1kHz 2ch) — 4,507,315 байт
- Суммарный размер сырых потоков: **24,446,136 байт**

### Выходной файл `output.mkv`:
- Итоговый размер: **24,498,013 байт**
- Накладные расходы контейнера: **51,877 байт (~0.21%)**
- Сигнатура заголовка: `1A 45 DF A3` (EBML Header)

### Проверка через `android.media.MediaExtractor`:
- Дорожка 0: `video/avc`, 640x360, `firstPtsUs=0`, `lastPtsUs=17892000` (429 семплов в выборке).
- Дорожка 1: `audio/mp4a-latm`, 44100Hz 2ch, `firstPtsUs=0`, `lastPtsUs=17878000` (386 семплов в выборке).
- Дорожка 2: `audio/mpeg`, 44100Hz 2ch, `firstPtsUs=0`, `lastPtsUs=17867736` (685 семплов в выборке).
- Проверка перемотки по Cues (`SEEK_TO_CLOSEST_SYNC`):
  - Перемотка на 30.0с -> синхронизировано на ключевой кадр `23.94с` (`seek30sPtsUs=23940000`).
  - Перемотка на 60.0с -> синхронизировано на ключевой кадр `59.48с` (`seek60sPtsUs=59476000`).
  - Перемотка назад на 10.0с -> синхронизировано на ключевой кадр `6.63с` (`seekBackPtsUs=6631000`).

### Проверка через `ExoPlayer` (`SimpleExoPlayer` / `ProgressiveMediaSource`):
- `EXOPLAYER_PROBE_TRACKS: trackGroupsCount=3`
- Группа 0: `video/avc`
- Группа 1: `audio/mp4a-latm` (`lang=und`)
- Группа 2: `audio/mpeg` (`lang=ru`)
- Перемотка в ExoPlayer:
  - `player.seekTo(30000)` -> `pos=30000` (PASS)
  - `player.seekTo(60000)` -> `pos=60000` (PASS)
  - `player.seekTo(10000)` -> `pos=10000` (PASS)
- Ошибки воспроизведения: 0 (`EXOPLAYER_PROBE_ERROR` не зафиксировано).

---

## 5. Границы и перенос задач в Патч #6

- **Включено в Патч #5**: Чистый Matroska/EBML писатель, потоковый сэмплер дорожек, SeekHead/Cues индексация, интеграция с состояниями воркера загрузки, unit-тесты, реальная верификация в среде Android 14.
- **Отложено до Патча #6**: Публикация итогового файла в Android `MediaStore` (каталог `Movies/SmartTube`), интеграция пользовательского интерфейса (кнопка скачивания в карточке видео/плеере, шторка прогресса скачивания и муксинга).
