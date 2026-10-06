# Развёртывание и эксплуатация сервиса диагностики SmartTube VOX

В данном документе описаны требования, конфигурация окружения, политика безопасности и регламент развёртывания микросервиса приёма анонимных отчётов совместимости SmartTube VOX (`services/vox-diagnostics`).

---

## 1. Архитектура и принципы работы

Микросервис `vox-diagnostics` предназначен для безопасного централизованного приёма отчётов диагностики от устройств Android TV / Google TV и Samsung Tizen.

### Ключевые гарантии безопасности (Privacy-First):
- **Zero-PII / Строгий фильтр**: Сервис проверяет каждое входящее поле на отсутствие токенов (`access_token`, `refresh_token`, `id_token`, `device_code`), паролей, cookies, заголовков авторизации, email-адресов, серийных номеров, Android ID и SSAID. При обнаружении любого из этих полей запрос немедленно отклоняется с кодом `400 Bad Request` (`forbidden_data`).
- **Строгие лимиты**:
  - Максимальный размер тела запроса: **256 КБ** (код `413 Payload Too Large`);
  - Максимальное количество событий в отчёте: **50 записей** (`safeRecentEvents`);
  - Максимальная длина сообщения события: **500 символов**.
- **Rate Limiting**: Ограничение частоты запросов (по умолчанию **20 запросов в минуту** на источник).
- **Анонимизация IP-адресов**: Исходный IP-адрес используется исключительно в виде одностороннего криптографического SHA-256 хеша для счётчика rate limiter. Необратимый хеш не сохраняется вместе с отчётом, а сырой IP никогда не логируется в теле отчёта.
- **Поддерживаемые схемы**: `vox-diagnostic-report-v1`, `vox-diagnostic-report-v2`.

---

## 2. Системные требования

- **Среда исполнения**: Node.js 18.x LTS, 20.x LTS или выше;
- **Оперативная память**: от 256 МБ RAM;
- **Дисковое пространство**: от 1 ГБ;
- **Сетевой стек**: Обязательное наличие Reverse Proxy (Nginx, Caddy, Traefik или Cloudflare) с поддержкой HTTPS/TLS 1.2+ и сертификатов Let's Encrypt / ZeroSSL.

---

## 3. Переменные окружения (Environment Variables)

| Переменная | Описание | Значение по умолчанию |
|---|---|---|
| `PORT` | Порт прослушивания HTTP-сервера | `3000` |
| `BIND_HOST` | Сетевой интерфейс привязки | `127.0.0.1` |
| `NODE_ENV` | Режим работы (`production` / `development`) | `production` |
| `RETENTION_DAYS` | Срок хранения отчётов (дней) | `30` |
| `RATE_LIMIT_MAX_PER_MIN`| Лимит запросов в минуту на один источник | `20` |
| `STORAGE_TYPE` | Тип хранилища (`memory`, `sqlite`, `filesystem`) | `filesystem` |
| `STORAGE_PATH` | Путь к каталогу хранения отчётов | `./data/reports` |

> 🔒 **Важно**: Никогда не помещайте секретные ключи или токены в переменные окружения данного сервиса.

---

## 4. Конфигурация Reverse Proxy (Nginx)

Прямое подключение клиентов к Node.js-процессу по незащищённому HTTP **запрещено**. TLS-терминация должна выполняться на уровне обратного прокси.

Пример конфигурации для Nginx:

```nginx
server {
    listen 443 ssl http2;
    server_name diagnostics.smarttube.app;

    ssl_certificate /etc/letsencrypt/live/diagnostics.smarttube.app/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/diagnostics.smarttube.app/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    # Лимит размера тела запроса (256 КБ + запас на заголовки)
    client_max_body_size 512k;

    # Защитные заголовки
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    # Отключение логирования тела запросов в access.log
    access_log /var/log/nginx/diagnostics_access.log combined;
    error_log /var/log/nginx/diagnostics_error.log warn;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Запрет буферизации для быстрых ответов
        proxy_buffering off;
        proxy_connect_timeout 5s;
        proxy_read_timeout 10s;
    }
}
```

---

## 5. Политика хранения и ротации (Retention Policy)

1. **Срок хранения**: Все принятые диагностические отчёты хранятся **не более 30 дней** (`DEFAULT_RETENTION_DAYS = 30`).
2. **Автоматическая очистка**:
   - Функция `isExpiredReport(timestamp, retentionMs)` проверяет возраст отчёта.
   - Ежесуточный фоновый процесс (cron/timer) удаляет отчёты с возрастом $> 30$ дней.
3. **Безопасное удаление**: Файлы устаревших отчётов удаляются без возможности восстановления.

---

## 6. Логирование и приватность сервера (Server Privacy)

- **Запрет дампа тела отчётов**: Серверный логгинг регистрирует только метаданные запроса (`[TIMESTAMP] POST /v1/report -> 201 Created (reportId=VOX-XXXXXX)`).
- **Тело запроса (JSON payload)** никогда не выводится в `console.log` или стандартный поток ошибок.
- **Заголовки `Authorization` и `Cookie`**: Если клиент ошибочно передаёт заголовки авторизации, они игнорируются и не регистрируются в логах.

---

## 7. Мониторинг и Healthcheck

- **Сервисная страница статуса**: `GET /`
  - Возвращает `200 OK` с чистой HTML-карточкой:
  - Текст: `SmartTube VOX Diagnostics is running`
  - Не раскрывает пользовательские отчёты и данные.
- **Стандартный эндпоинт проверки здоровья**: `GET /health`
  - Возвращает `200 OK`:
```json
{
  "status": "ok",
  "service": "SmartTube VOX Diagnostics"
}
```
- **Служебный healthcheck с метаданными схем**: `GET /healthz`
  - Возвращает `200 OK`:
```json
{
  "status": "healthy",
  "service": "vox-diagnostics",
  "supportedSchemas": [
    "vox-diagnostic-report-v1",
    "vox-diagnostic-report-v2"
  ]
}
```
- **Закрытая консоль администратора**: `GET /admin`
  - Защищена ключом `ADMIN_SECRET` / Cloudflare Access.
  - Поддерживает вход через форму, заголовок `Authorization: Bearer <ADMIN_SECRET>` или параметр `?token=<ADMIN_SECRET>`.
  - Позволяет просматривать отчёты, менять статусы инцидентов, копировать Markdown и JSON.

---

## 8. Статус развёртывания и верификации

- **Статус бэкенда**: `DEPLOYED / OPERATIONAL` (`https://vox-diagnostics.amn2402.workers.dev/`)
- **База данных**: Cloudflare D1 `vox_diagnostics_db` (ID: `3ba3cc6f-92a8-475d-8114-ffe495dd56ac`).
- **Сквозная физическая верификация**: Пройдена успешно на физическом устройстве TCL Smart TV Pro (Android 14 / API 34). Отчёт принят в D1, получен `reportId` и подтверждён в `/admin`.
