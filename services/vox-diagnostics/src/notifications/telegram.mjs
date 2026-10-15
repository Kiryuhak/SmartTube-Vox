/**
 * SmartTube VOX Diagnostics — Telegram Notification Service
 * 
 * Отвечает за:
 * 1. Взаимодействие с Telegram Bot API (sendMessage, sendDocument) с изоляцией сбоев.
 * 2. Обработку rate-limiting (429) с уважением retry_after и ограничением до 3 попыток.
 * 3. Отсутствие бесконечных retry при клиентских ошибках (400, 401, 403).
 * 4. Полную изоляцию токена бота от логов.
 * 5. Дедупликацию и 15-минутную агрегацию повторных инцидентов.
 * 6. Фильтрацию тестовых и чисто исторических отчётов.
 */

import {
  createSanitizedTelegramBundle,
  generateTelegramBundleFilename
} from './sanitizer.mjs';
import {
  formatNewIssueMessage,
  formatRegressionMessage,
  formatAggregateMessage,
  formatTestMessage,
  buildDashboardKeyboard
} from './telegram_formatter.mjs';
import { isSyntheticTestReport } from '../triage.mjs';

const DEFAULT_TIMEOUT_MS = 10000;
const MAX_RETRIES = 3;
const DEFAULT_AGGREGATION_WINDOW_MS = 15 * 60 * 1000; // 15 минут

// In-memory состояние для агрегации и дедупликации (сигнатура -> метаданные)
const notificationState = new Map();

export function resetTelegramState() {
  notificationState.clear();
}

/**
 * Безопасный HTTP-клиент к Telegram Bot API.
 */
export class TelegramClient {
  constructor(token) {
    this.token = token;
  }

  /**
   * Выполняет безопасный запрос с ретраями на 429 и 5xx, тайм-аутом и изоляцией ошибок.
   */
  async _request(method, body, isFormData = false) {
    if (!this.token) {
      return { ok: false, error: 'NO_TOKEN' };
    }

    const url = `https://api.telegram.org/bot${this.token}/${method}`;
    let attempt = 0;

    while (attempt < MAX_RETRIES) {
      attempt++;
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS);

      try {
        const headers = isFormData ? {} : { 'Content-Type': 'application/json' };
        const response = await fetch(url, {
          method: 'POST',
          headers,
          body: isFormData ? body : JSON.stringify(body),
          signal: controller.signal,
        });

        clearTimeout(timer);

        if (response.ok) {
          const resJson = await response.json().catch(() => ({ ok: true }));
          return { ok: true, result: resJson.result };
        }

        const status = response.status;
        const errBody = await response.json().catch(() => ({}));

        // 400, 401, 403, 404 — фатальные ошибки авторизации или параметров: не ретраим
        if (status >= 400 && status < 500 && status !== 429) {
          return { ok: false, status, error: errBody.description || `HTTP_${status}` };
        }

        // 429 Rate Limit — ждём указанное в параметрах время или 1.5 сек
        if (status === 429) {
          const retryAfterSec = Number(errBody.parameters?.retry_after) || 1.5;
          if (attempt >= MAX_RETRIES) {
            return { ok: false, status: 429, error: 'RATE_LIMIT_EXCEEDED' };
          }
          await new Promise(r => setTimeout(r, Math.min(retryAfterSec * 1000, 5000)));
          continue;
        }

        // 5xx Server Error — экспоненциальный бэкофф
        if (status >= 500) {
          if (attempt >= MAX_RETRIES) {
            return { ok: false, status, error: `SERVER_ERROR_${status}` };
          }
          await new Promise(r => setTimeout(r, attempt * 500));
          continue;
        }

        return { ok: false, status, error: `HTTP_${status}` };
      } catch (err) {
        clearTimeout(timer);
        if (attempt >= MAX_RETRIES) {
          return { ok: false, error: err.name === 'AbortError' ? 'TIMEOUT' : 'NETWORK_ERROR' };
        }
        await new Promise(r => setTimeout(r, attempt * 500));
      }
    }

    return { ok: false, error: 'MAX_RETRIES_REACHED' };
  }

  /**
   * Отправка текстового сообщения.
   */
  async sendMessage({ chatId, text, parseMode = 'HTML', replyMarkup, messageThreadId }) {
    const payload = {
      chat_id: chatId,
      text,
      parse_mode: parseMode,
      disable_web_page_preview: true,
    };
    if (replyMarkup) payload.reply_markup = replyMarkup;
    if (messageThreadId) payload.message_thread_id = messageThreadId;

    return this._request('sendMessage', payload, false);
  }

  /**
   * Отправка документа (JSON файла) с сообщением.
   */
  async sendDocument({ chatId, documentBuffer, filename, caption, parseMode = 'HTML', replyMarkup, messageThreadId }) {
    const formData = new FormData();
    formData.append('chat_id', String(chatId));

    const blob = new Blob([documentBuffer], { type: 'application/json' });
    formData.append('document', blob, filename);

    if (caption) {
      formData.append('caption', caption);
      formData.append('parse_mode', parseMode);
    }
    if (replyMarkup) {
      formData.append('reply_markup', typeof replyMarkup === 'string' ? replyMarkup : JSON.stringify(replyMarkup));
    }
    if (messageThreadId) {
      formData.append('message_thread_id', String(messageThreadId));
    }

    return this._request('sendDocument', formData, true);
  }
}

/**
 * Главный диспетчер Telegram-уведомлений.
 * Вызывается при получении нового отчёта в diagnostics ingestion.
 */
export async function dispatchTelegramNotification({
  report = {},
  classification = {},
  issue = {},
  env = {},
  storage = null,
}) {
  const isEnabled = env.TELEGRAM_NOTIFICATIONS_ENABLED === 'true' || env.TELEGRAM_NOTIFICATIONS_ENABLED === true;
  if (!isEnabled) {
    return { decision: 'DISABLED' };
  }

  const token = env.TELEGRAM_BOT_TOKEN;
  const prodChatId = env.TELEGRAM_CHAT_ID;
  const testChatId = env.TELEGRAM_TEST_CHAT_ID;
  const threadId = env.TELEGRAM_MESSAGE_THREAD_ID;
  const baseUrl = env.DASHBOARD_BASE_URL || '';
  const windowMinutes = Number(env.TELEGRAM_AGGREGATION_WINDOW_MINUTES) || 15;
  const windowMs = windowMinutes * 60 * 1000;

  if (!token || (!prodChatId && !testChatId)) {
    return { decision: 'DISABLED', reason: 'MISSING_ENV' };
  }

  const client = new TelegramClient(token);
  const isTest = isSyntheticTestReport(report) || report.reportPurpose === 'TEST';

  // 1. Тестовые отчёты не отправляются в прод-чат
  if (isTest) {
    if (!testChatId) {
      return { decision: 'SUPPRESSED_TEST' };
    }
    // Отправляем в тестовый чат
    try {
      const bundle = createSanitizedTelegramBundle(report, classification, issue);
      const jsonStr = JSON.stringify(bundle, null, 2);
      const filename = generateTelegramBundleFilename(report.reportId, report.appVersion, classification.errorCategory);
      const caption = formatTestMessage(`Тестовый отчёт: ${report.reportId || 'TEST'}`);
      const keyboard = buildDashboardKeyboard(baseUrl, 'report', report.reportId);

      const res = await client.sendDocument({
        chatId: testChatId,
        documentBuffer: Buffer.from(jsonStr, 'utf8'),
        filename,
        caption,
        parseMode: 'HTML',
        replyMarkup: keyboard,
        messageThreadId: threadId,
      });

      return {
        decision: res.ok ? 'SENT_NEW' : 'FAILED',
        messageId: res.result?.message_id,
        targetChat: 'TEST',
        error: res.error,
      };
    } catch (e) {
      return { decision: 'FAILED', error: e.message };
    }
  }

  // 2. Исторические отчёты (без свежих активных ошибок) не спамят в Telegram
  const hasActive = classification.reportActiveIncident !== false &&
    (classification.activeEventCount > 0 || !classification.events || classification.events.length === 0);

  if (!hasActive) {
    return { decision: 'SUPPRESSED_HISTORICAL' };
  }

  const signature = classification.signature || report.errorSignature || 'GENERAL|UNKNOWN';
  const now = Date.now();

  // Проверяем состояние агрегации
  const state = notificationState.get(signature) || {
    lastNotifiedAt: 0,
    notificationCount: 0,
    repeatCount: 0,
    lastReportId: null,
  };

  const isRegression = Boolean(issue.reopened || (issue.status === 'RESOLVED' && hasActive));
  const timeSinceLast = now - state.lastNotifiedAt;

  // 3. Дедупликация и троттлинг внутри окна агрегации
  if (state.lastNotifiedAt > 0 && timeSinceLast < windowMs && !isRegression) {
    state.repeatCount++;
    state.lastReportId = report.reportId;
    notificationState.set(signature, state);
    return {
      decision: 'SUPPRESSED_DUPLICATE',
      signature,
      repeatCount: state.repeatCount,
      windowRemainingMs: windowMs - timeSinceLast,
    };
  }

  // 4. Формируем сообщение и прикрепляемый бандл
  const bundle = createSanitizedTelegramBundle(report, classification, issue);
  const jsonStr = JSON.stringify(bundle, null, 2);
  const filename = generateTelegramBundleFilename(report.reportId, report.appVersion, classification.errorCategory);

  let messageText = '';
  let decision = 'SENT_NEW';

  if (isRegression) {
    messageText = formatRegressionMessage(report, issue, classification);
    decision = 'SENT_REGRESSION';
  } else if (state.repeatCount > 0 && timeSinceLast >= windowMs) {
    messageText = formatAggregateMessage(signature, {
      title: issue.title || signature,
      newCount: state.repeatCount,
      totalCount: state.notificationCount + state.repeatCount,
      lastVersion: report.appVersion,
    });
    decision = 'SENT_AGGREGATE';
  } else {
    messageText = formatNewIssueMessage(report, issue, classification);
    decision = 'SENT_NEW';
  }

  const keyboard = buildDashboardKeyboard(baseUrl, 'report', report.reportId);

  try {
    const res = await client.sendDocument({
      chatId: prodChatId,
      documentBuffer: Buffer.from(jsonStr, 'utf8'),
      filename,
      caption: messageText,
      parseMode: 'HTML',
      replyMarkup: keyboard,
      messageThreadId: threadId,
    });

    if (res.ok) {
      state.lastNotifiedAt = now;
      state.notificationCount++;
      state.repeatCount = 0;
      state.lastReportId = report.reportId;
      state.lastMessageId = res.result?.message_id;
      notificationState.set(signature, state);

      return {
        decision,
        messageId: res.result?.message_id,
        notifiedAt: now,
      };
    } else {
      return {
        decision: 'FAILED',
        error: res.error,
        status: res.status,
      };
    }
  } catch (err) {
    return {
      decision: 'FAILED',
      error: err.message,
    };
  }
}
