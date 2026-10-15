# Интеграция уведомлений Telegram в VOX Diagnostics

## 1. Обзор архитектуры

Система уведомлений Telegram в `services/vox-diagnostics` обеспечивает автоматическое информирование инженеров о новых сбоях и критических регрессиях в продакшене без утечки пользовательских данных.

### Конвейер обработки (Pipeline):
```text
REPORT RECEIVED
      ↓
   SANITIZE (vox-diagnostic-telegram-v1: удаление секретов, токенов, IP, URL)
      ↓
   CLASSIFY (канонический статус, категория ошибки, сигнатура)
      ↓
    DEDUP   (окно агрегации 15 минут, подавление дубликатов)
      ↓
UPDATE DASHBOARD (обновление D1 / InMemory хранилища)
      ↓
 DECIDE TELEGRAM (SENT_NEW / SENT_REGRESSION / SENT_AGGREGATE / SUPPRESSED)
      ↓
SEND TELEGRAM MESSAGE (текст с подробностями инцидента и ссылкой на Dashboard)
      ↓
ATTACH SANITIZED JSON (прикрепление безопасного .json файла отчёта)
```

---

## 2. Переменные окружения (Environment Configuration)

Все параметры настраиваются **исключительно** через переменные окружения (Cloudflare Workers secrets / `.env`):

| Переменная | Тип | По умолчанию | Описание |
| :--- | :--- | :--- | :--- |
| `TELEGRAM_NOTIFICATIONS_ENABLED` | boolean | `false` | Включение отправки уведомлений (`true` / `false`) |
| `TELEGRAM_BOT_TOKEN` | string | `""` | Токен бота от `@BotFather` (никогда не логируется) |
| `TELEGRAM_CHAT_ID` | string | `""` | ID целевого чата или канала для рабочих алертов |
| `TELEGRAM_TEST_CHAT_ID` | string | `""` | Опциональный ID чата для синтетических и тестовых отчётов |
| `TELEGRAM_MESSAGE_THREAD_ID` | number | `""` | ID топика в супергруппе (если используются темы) |
| `DASHBOARD_BASE_URL` | string | `""` | Базовый URL панели управления (для кнопки `[Открыть в Dashboard]`) |
| `TELEGRAM_AGGREGATION_WINDOW_MINUTES` | number | `15` | Окно дедупликации и агрегации повторов (в минутах) |

> [!CAUTION]
> Никогда не коммитьте реальные токены Telegram бота в Git. Токены добавляются через `wrangler secret put TELEGRAM_BOT_TOKEN`.

---

## 3. Политика безопасности и анонимизации (Sanitization)

Прикрепляемый к Telegram файл генерируется по схеме `vox-diagnostic-telegram-v1`:
- **Удаляемые данные:** заголовки `Authorization`, `Cookie`, `Set-Cookie`, любые поля `token`, `accessToken`, `refreshToken`, `password`, `secret`, внутренние IP-адреса, серийные номера ADB, email, идентификаторы аккаунтов.
- **Очистка URL:** ссылки на видео и аудиопотоки (`googlevideo.com`, `yandex`) полностью очищаются от секретных параметров сессии (`sig`, `signature`, `token`, `key`, `ip`, `expire`), сохраняя только базовый путь.
- **Ограничение истории:** `safeRecentEvents` ограничивается последними 30 событиями.
- **Формат имени файла:** `VOX-<ID>_<VERSION>_<CATEGORY>.json` (например, `VOX-D0DF1A08_32.56-vox.9_PLAYER.json`).

---

## 4. Дедупликация и 15-минутная агрегация (Anti-Spam)

1. **Первый отчёт инцидента:** Отправляется статус `SENT_NEW` с полным описанием и прикреплённым JSON.
2. **Повторы в течение 15 минут:** Сообщения подавляются (`SUPPRESSED_DUPLICATE`), счётчик повторов инкрементируется.
3. **Истечение окна агрегации:** При поступлении очередного повтора отправляется статус `SENT_AGGREGATE` со сводкой:
   > ⚠️ **VOX Diagnostics — повтор проблемы**  
   > `PLAYER_REBUFFER_BUFFER_POLICY`  
   > **Новых повторов:** +8  
   > **Всего:** 12  
   > **Последняя версия:** `32.56-vox.9.1`
4. **Критическая регрессия:** Если проблема ранее имела статус `RESOLVED`, а свежий отчёт поступил с версии `>= fixedInVersion`, статус `SENT_REGRESSION` отправляется **немедленно**, игнорируя окно дедупликации.

---

## 5. Изоляция сбоев и обработка ошибок (Fault Tolerance)

- Сбои сети Telegram, тайм-ауты или ошибки API **ни при каких обстоятельствах** не прерывают прием диагностических отчетов (эндпоинт `/v1/report` всегда отвечает `201 Created`).
- **HTTP 429 (Rate Limit):** Клиент считывает параметр `parameters.retry_after` и делает до 3 попыток с паузой.
- **HTTP 400 / 401 / 403:** Клиент не зацикливается и сразу фиксирует отказ отправки (`FAILED`).
- Вся информация о доставке фиксируется в объекте отчёта (`telegramDelivery.decision`).

---

## 6. Ручное тестирование интеграции

Для проверки связи с Telegram используйте синтетический скрипт:
```bash
TELEGRAM_BOT_TOKEN="<токен>" TELEGRAM_CHAT_ID="<chat_id>" node scripts/test_telegram_notification.mjs
```
Скрипт формирует синтетический отчёт `VOX-TELEGRAM-TEST.json` и отправляет его в чат без использования реальных данных пользователей.
