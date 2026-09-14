# Исследование стабильности live-потоков

Дата проверки: 15 сентября 2026 года.

## Подтверждённая причина

Проблема воспроизведена на подключённом `Smart TV Pro` с установленным SmartTube VOX `32.47-vot.8`. Live-видео открывалось как внутренний DASH-поток. Сетевой транспорт на момент проверки выбирался общей настройкой приложения.

Первичный дефект находился в `BufferingDetector`: он складывал длительности разных эпизодов `Player.STATE_BUFFERING` в окне 60 секунд. Переход в `STATE_READY` помечал поток playable, но не обнулял накопленную длительность. Поэтому два обычных эпизода нехватки буфера, между которыми видео снова воспроизводилось, ошибочно считались одним непрерывным stall продолжительностью 20 секунд.

Подтверждающий фрагмент baseline timeline:

| Время (epoch) | Событие | Интервал |
| --- | --- | --- |
| 1789414143.813 | `STATE_BUFFERING` | начало первого эпизода |
| 1789414153.219 | `STATE_READY` | восстановление через 9,406 с |
| 1789414162.470 | `STATE_BUFFERING` | новый независимый эпизод |
| 1789414173.069 | `Restarting the engine` | через 10,599 с; суммарно 20,005 с |

Между эпизодами было около 9 секунд успешного воспроизведения. Следовательно, watchdog сработал не по отсутствию сетевых байтов и не по зависанию декодера, а по ошибочно накопленному состоянию буферизации. Перезапуск освобождал текущий ExoPlayer и создавал новый, после чего цикл мог повторяться.

## Карта воспроизведения

`MediaItemFormatInfo` → выбор DASH/SABR/HLS в `VideoLoaderController` → `ExoMediaSourceFactory` → `DefaultHttpDataSource` / `CronetDataSource` / `OkHttpDataSource` → ExoPlayer loader и sample buffer → decoder/renderers → `STATE_BUFFERING`/`STATE_READY` → `BufferingDetector` → `ErrorFixerController` → restart/reload/fallback.

Транспортов три:

- системный `HttpURLConnection` (`PLAYER_DATA_SOURCE_DEFAULT`);
- Cronet;
- OkHttp.

Фабрика транспорта создаётся для экземпляра `ExoMediaSourceFactory` на основании сохранённой настройки `PlayerTweaksData`. Для live доступны HLS и DASH; SABR для live явно отключён. DASH manifest URL обновляется самим `DashMediaSource`, а встроенный динамический DASH строится с большим окном сегментов. В baseline не найдено признаков истечения signed URL через 2–3 минуты, ошибок manifest refresh, 403/404/410/5xx или decoder error.

## Старое восстановление

- После суммарных 20 секунд buffering watchdog безусловно вызывал `restartEngine()` для уже запускавшегося потока.
- Ошибка открытия data source с текстом `Unable to connect to` немедленно меняла транспорт по кругу Cronet → system → OkHttp → Cronet.
- Cooldown, grace period, rolling budget и защита от быстрого возврата отсутствовали.
- Несколько отложенных restart/reload одного и того же player generation могли одновременно оставаться в очереди.
- `onPlayerError` писал в logcat объект исключения целиком, включая полный signed media URL.

## Исправление

- Watchdog теперь измеряет один непрерывный эпизод buffering. Любой `STATE_READY` отменяет таймер и новый эпизод начинает независимое окно.
- Повторный `STATE_BUFFERING` внутри того же эпизода не создаёт второй watchdog callback.
- При сетевой ошибке сначала выполняется reconnect того же транспорта. Смена транспорта разрешается только при следующем отказе без промежуточного playback progress.
- После switch действует grace period 60 секунд, возврат к предыдущему транспорту запрещён 120 секунд, а rolling budget ограничен двумя switch за пять минут. При запрете выполняется reconnect текущего транспорта.
- Дублирующиеся отложенные restart и дублирующиеся reload независимо объединяются; при release все callbacks обоих типов снимаются.
- Cronet использует один process-scoped callback executor. Раньше каждый player generation создавал новый `newSingleThreadExecutor()`, который фабрика ExoPlayer не завершала, поэтому повторные restart могли накапливать живые потоки.
- Диагностика recovery содержит тип причины, live-флаг, номер транспорта и playback position, но не URL. Полный текст data-source exception больше не выводится в logcat.

## Ограничения

- Recovery policy применяется к автоматическому fallback при ошибке `Unable to connect to`; специализированные ветки ошибок renderer, клиента YouTube и стартовой custom DNS-защиты сохраняют прежнее поведение.
- Reconnect означает пересоздание ExoPlayer с тем же выбранным транспортом, а не повторное использование аварийного data-source объекта.
- Grace period, cooldown и switch budget хранятся в рамках одной playback-сессии и сбрасываются при выборе нового видео.
- Process-scoped Cronet executor намеренно живёт до завершения процесса приложения; число его потоков больше не растёт при restart.
- Реальный runtime подтверждает исправление исследованного DASH live-сценария. HLS, искусственные 4xx/5xx и принудительный разрыв сети отдельным device runtime не воспроизводились; unit-тесты покрывают watchdog и абстрактную policy переключения, но не полную интеграцию каждого транспорта.

## Проверки

Добавлены unit regression tests для:

- двух отдельных buffering по 12 секунд с успешным progress между ними;
- повторного сигнала buffering одного эпизода;
- reconnect-first;
- grace period;
- cooldown предыдущего транспорта;
- rolling switch budget.

Проверочная debug-сборка установлена поверх существующего приложения с сохранением данных. После первого `STATE_READY` live-поток наблюдался 928,738 секунды (15 минут 28,738 секунды). За этот интервал зафиксированы 37 переходов в `STATE_BUFFERING` и 36 возвратов в `STATE_READY`; многие отдельные эпизоды в пределах одной минуты суммарно превышали старый порог 20 секунд. При этом не было ни одного `Stall recovery`, `Restarting engine`, `Network recovery: switch` или `onPlayerError`. Один ExoPlayer был освобождён и пересоздан после короткой буферизации без участия watchdog и без смены транспорта; новый экземпляр вышел в `STATE_READY`, а дальнейших пересозданий за оставшееся время не было. На момент завершения приложение оставалось foreground в `PlaybackActivity`.

Quality gate:

- `:common:testStvotDebugUnitTest` — 21 тест, ошибок и пропусков нет;
- `:smarttubetv:assembleStvotDebug` — успешно;
- `git diff --check` и проверка staged diff — успешно;
- upstream `:exoplayer-extension-cronet:testStvotDebugUnitTest` не исполняет тестовую логику на используемой JDK 17: все 40 тестов останавливаются при инициализации старого Robolectric/ASM с `Unsupported class file major version 61`;
- targeted upstream `ExoPlayerTest` также не доходит до assertions: 76 тестов останавливаются в том же старом Robolectric/ASM test environment (сначала конфликт looper mode, после выбора `LEGACY` — та же неподдерживаемая major version 61);
- `:smarttubetv:lintStvotDebug` блокируется до анализа приложения старым test dependency Espresso 3.2.0: в его merged androidTest manifest отсутствует обязательный `android:exported`;
- отдельный `:common:lintStvotDebug` доходит до lint и обнаруживает семь ранее существовавших `NewApi` для `StandardCharsets.UTF_8` при `minSdk 17`, не связанных с данным исправлением.

Итог: подтверждённый цикл ложных watchdog-restart устранён без увеличения таймаута и без отключения защитного восстановления действительно непрерывного stall. Полный lint нельзя считать пройденным до отдельного обновления test dependency и устранения накопленного `NewApi` debt.
