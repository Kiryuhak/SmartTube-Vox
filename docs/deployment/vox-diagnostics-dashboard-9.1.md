# SmartTube VOX 9.1 — План безопасного развёртывания Diagnostics Dashboard

> **СТАТУС ПЛАНА: ГОТОВ К РАЗВЁРТЫВАНИЮ (READY FOR PRODUCTION DEPLOYMENT)**  
> **ФАКТИЧЕСКОЕ РАЗВЁРТЫВАНИЕ: НЕ ВЫПОЛНЯЛОСЬ (DEPLOYMENT EXECUTED = NO)**  
> **ИЗМЕНЕНИЕ ДАННЫХ В PRODUCTION: НЕТ (PRODUCTION DATA MODIFIED = NO)**

Данный документ описывает строгий регламент промышленного развёртывания микросервиса диагностики SmartTube VOX (`services/vox-diagnostics`) и применения миграций базы данных Cloudflare D1 для версии **32.56-vox.9.1**.

---

## 1. Резервное копирование перед развёртыванием (Pre-deploy Backup)

Перед применением любых изменений на продакшене обязательно создаётся снимок состояния базы Cloudflare D1 и конфигурации:

```bash
# 1. Создание резервной копии базы D1 продакшена
npx wrangler d1 backup create vox-diagnostics-db

# 2. Выгрузка актуального дампа таблиц в защищённый архив
npx wrangler d1 export vox-diagnostics-db --output="./backups/d1-pre-vox91-$(date +%Y%m%d_%H%M%S).sql"

# 3. Фиксация контрольной суммы дампа (SHA256)
sha256sum ./backups/d1-pre-vox91-*.sql > ./backups/d1-pre-vox91-checksum.txt
```

---

## 2. Обязательные переменные окружения (Environment Variables & Secrets)

Все секреты передаются в Cloudflare Worker исключительно через механизм `wrangler secret`:

| Имя переменной | Тип | Обязательность | Назначение |
| :--- | :--- | :--- | :--- |
| `NODE_ENV` | string | Обязательно | `production` |
| `CANONICAL_SCHEMA` | string | Обязательно | `vox-diagnostic-report-v3` |
| `ADMIN_SESSION_SECRET` | secret | Обязательно | 64-байтный криптографический ключ подписи cookie сессий админки |
| `ADMIN_PASSWORD_HASH` | secret | Обязательно | PBKDF2/Argon2 хеш пароля администратора панели |
| `TELEGRAM_NOTIFICATIONS_ENABLED` | var | Обязательно | `true` для активации отправки алертов в Telegram |
| `TELEGRAM_BOT_TOKEN` | secret | Обязательно | Токен бота от `@BotFather` (строго маскируется в логах) |
| `TELEGRAM_CHAT_ID` | var/secret | Обязательно | ID канала или рабочей супергруппы инженеров |
| `TELEGRAM_TEST_CHAT_ID` | var | Опционально | Отдельный чат для синтетических/тестовых отчётов |
| `TELEGRAM_AGGREGATION_WINDOW_MINUTES` | var | Опционально | Окно дедупликации повторов (по умолчанию `15` минут) |
| `DASHBOARD_BASE_URL` | var | Обязательно | `https://diagnostics.smarttube.app` (для ссылок в кнопках Telegram) |

Команды загрузки секретов (выполняются администратором вручную):
```bash
npx wrangler secret put ADMIN_SESSION_SECRET
npx wrangler secret put ADMIN_PASSWORD_HASH
npx wrangler secret put TELEGRAM_BOT_TOKEN
```

---

## 3. Проверка совместимости схемы (Schema Compatibility Check)

Схема базы данных расширяется аддитивно. Существующие записи отчётов и поля `payload_json` остаются на 100% неизменными (immutability).

Проверка совместимости таблиц:
```sql
SELECT name FROM sqlite_master WHERE type='table' AND name IN ('reports', 'issues', 'report_status_history', 'report_notes', 'admin_audit');
```

Новые таблицы и индексы в VOX 9.1:
- `report_status_history` — аудит-лог переходов жизненного цикла из 10 канонических статусов.
- `report_notes` — заметки разработчиков с указанием автора и времени.
- Индексы по `error_signature`, `status`, `created_at`.

---

## 4. Пробный прогон миграции (Migration Dry-Run)

Перед применением миграции на боевой базе данных обязателен запуск сухого прогона:

```bash
# Локальная валидация SQL и проверка целостности каталога
node services/vox-diagnostics/scripts/backfill_vox91_statuses.mjs --dry-run
```

Ожидаемый результат:
- Успешный анализ всех исторических отчётов;
- Проверка неизменности исторических данных (0 активных событий у архивных отчётов);
- Генерация SQL без деструктивных операторов (`DROP TABLE`, `DELETE FROM reports`).

Пробный прогон на тестовой локальной копии D1:
```bash
npx wrangler d1 execute vox-diagnostics-db --local --file=services/vox-diagnostics/migrations/0004_vox91_backfill.sql
```

---

## 5. Стратегия технического обслуживания и отката (Maintenance & Rollback Strategy)

1. Сервис спроектирован по принципу нулевого простоя (Zero Downtime).
2. Приём отчётов клиентами (`POST /v1/report`) изолирован от админки и Telegram-уведомлений. Даже во время выполнения миграции эндпоинт `/v1/report` сохраняет входящие отчёты.
3. В случае критической ошибки развёртывания активируется план отката:
   - Немедленный откат Worker на предыдущую стабильную версию:
     ```bash
     npx wrangler rollback --message "Rollback to pre-vox9.1 deployment"
     ```
   - Откат статусов в D1 через сгенерированный скрипт отката:
     ```bash
     npx wrangler d1 execute vox-diagnostics-db --file=./migrations/0004_vox91_rollback.sql
     ```
   - При фатальном повреждении — восстановление из резервной копии:
     ```bash
     npx wrangler d1 backup restore vox-diagnostics-db <BACKUP_ID>
     ```

---

## 6. Порядок развёртывания (Deployment Sequence)

> ⚠️ Выполняется только при наличии явного разрешения владельца проекта.

1. **Шаг 1: Сборка клиентских ассетов админ-панели**
   ```bash
   cd services/vox-diagnostics
   npm run build:client
   npm test
   ```
2. **Шаг 2: Применение миграций схемы D1**
   ```bash
   npx wrangler d1 migrations apply vox-diagnostics-db --remote
   ```
3. **Шаг 3: Применение матрицы канонических статусов 9.1**
   ```bash
   npx wrangler d1 execute vox-diagnostics-db --remote --file=migrations/0004_vox91_backfill.sql
   ```
4. **Шаг 4: Деплой Cloudflare Worker**
   ```bash
   npx wrangler deploy
   ```

---

## 7. Дымовое тестирование после деплоя (Smoke Verification)

1. **Проверка эндпоинта здоровья:**
   ```bash
   curl -s -i https://diagnostics.smarttube.app/health
   # Ожидаемый ответ: HTTP 200 OK {"status":"ok","service":"vox-diagnostics"}
   ```
2. **Проверка авторизации в панели администрирования:**
   ```bash
   curl -s -i https://diagnostics.smarttube.app/admin
   # Ожидаемый ответ: HTTP 401 Unauthorized (без cookie сессии)
   ```
3. **Проверка тестового приёма отчёта (Synthetic Ingestion):**
   ```bash
   curl -s -X POST https://diagnostics.smarttube.app/v1/report \
     -H "Content-Type: application/json" \
     -d '{"schema":"vox-diagnostic-report-v1","reportId":"VOX-SMOKE-DEPLOY-01","appVersion":"32.56-vox.9.1","platform":"Android TV","manufacturer":"TCL","model":"Smart TV Pro","reportPurpose":"TEST","safeRecentEvents":[]}'
   # Ожидаемый ответ: HTTP 201 Created
   ```
4. **Контрольная отправка тестового алерта в Telegram:**
   ```bash
   node services/vox-diagnostics/scripts/test_telegram_notification.mjs
   ```

---

## 8. Мониторинг и наблюдаемость (Monitoring)

- Мониторинг метрик Cloudflare: CPU time, Duration, Request rate, Error rate (5xx).
- Проверка журнала событий Worker в реальном времени:
  ```bash
  npx wrangler tail
  ```
- Логи доставки Telegram: события `[TELEGRAM_NOTIFY_START]`, `[TELEGRAM_NOTIFY_SUCCESS]`, `[TELEGRAM_NOTIFY_SKIPPED_DEDUP]`. Отсутствие событий с необработанными ошибками (`TELEGRAM_NOTIFY_FAILED`).
- Аудит логов: строгое отсутствие токенов (`maskBotToken`) и Chat ID (`maskChatId`).

---

## 9. Процедура отката (Rollback Procedure)

В случае обнаружения регрессий после развёртывания:

1. **Мгновенный откат Worker на предыдущую ревизию:**
   ```bash
   npx wrangler rollback
   ```
2. **Применение обратной SQL-миграции:**
   ```bash
   npx wrangler d1 execute vox-diagnostics-db --remote --file=migrations/0004_vox91_rollback.sql
   ```
3. **Проверка целостности данных:**
   Убедиться, что количество отчётов в таблице `reports` не изменилось:
   ```bash
   npx wrangler d1 execute vox-diagnostics-db --remote --command="SELECT count(*) as total_reports FROM reports;"
   ```
4. **Оповещение команды в Telegram о статусе отката.**

---

**ИТОГОВЫЙ СТАТУС:**  
План полностью подготовлен, протестирован локально в dry-run режиме, шаги верифицированы.  
Фактическое применение на продакшене не выполнялось в соответствии с установленными ограничениями (`DEPLOYMENT EXECUTED = NO`).
