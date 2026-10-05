/**
 * Безопасный адаптер уведомлений о новых диагностических отчётах.
 * Никогда не отправляет чувствительные данные, логирует только краткую сводку.
 * Включает защиту от спама (дедупликация и троттлинг по сигнатуре ошибки).
 */

const NOTIFICATION_THROTTLE_MS = 15 * 60 * 1000; // 15 минут между одинаковыми ошибками
const notificationCache = new Map();

export function resetNotifierCache() {
  notificationCache.clear();
}

export async function sendReportNotification(report, env = {}) {
  const token = env.TELEGRAM_BOT_TOKEN;
  const chatId = env.TELEGRAM_CHAT_ID;
  const webhookUrl = env.NOTIFICATION_WEBHOOK_URL;

  // Если уведомления не настроены — выходим
  if (!token && !webhookUrl) return;

  const reportId = report.reportId;
  const platform = report.platform || 'Unknown';
  const appVersion = report.appVersion || 'Unknown';
  const errorCategory = report.errorCategory || 'None';
  const errorSignature = report.errorSignature || `${errorCategory}|${platform}`;
  const device = `${report.manufacturer || ''} ${report.model || ''}`.trim() || 'Unknown';
  const purpose = report.reportPurpose || report.purpose || (reportId && reportId.includes('TEST') ? 'TEST' : 'USER');

  // Не отправлять Telegram-уведомления для явных тестовых отчётов, если не включен дебаг
  if (purpose === 'TEST' && !env.NOTIFY_TEST_REPORTS) {
    return;
  }

  // Throttling / deduplication
  const now = Date.now();
  const lastSent = notificationCache.get(errorSignature);
  if (lastSent && now - lastSent < NOTIFICATION_THROTTLE_MS) {
    return; // Пропускаем дубликат ошибки в пределах окна троттлинга
  }
  notificationCache.set(errorSignature, now);

  // Очистка старых записей кэша
  if (notificationCache.size > 500) {
    for (const [sig, ts] of notificationCache.entries()) {
      if (now - ts > NOTIFICATION_THROTTLE_MS) {
        notificationCache.delete(sig);
      }
    }
  }

  // 1. Telegram бот
  if (token && chatId) {
    try {
      const isErr = errorCategory && errorCategory !== 'None';
      const icon = isErr ? '🚨' : '📊';
      const text = `${icon} *SmartTube VOX Report*\n\n` +
        `• *ID:* \`${reportId}\`\n` +
        `• *Платформа:* ${escapeTgMarkdown(platform)}\n` +
        `• *Версия:* ${escapeTgMarkdown(appVersion)}\n` +
        `• *Устройство:* ${escapeTgMarkdown(device)}\n` +
        `• *Категория:* \`${errorCategory}\`\n` +
        (errorSignature ? `• *Сигнатура:* \`${escapeTgMarkdown(errorSignature)}\`\n` : '');

      const tgUrl = `https://api.telegram.org/bot${token}/sendMessage`;
      await fetch(tgUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          chat_id: chatId,
          text,
          parse_mode: 'Markdown',
        }),
      });
    } catch (e) {
      // Игнорируем ошибки сети уведомлений
    }
  }

  // 2. Generic Webhook
  if (webhookUrl) {
    try {
      await fetch(webhookUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          reportId,
          platform,
          appVersion,
          device,
          errorCategory,
          errorSignature,
          purpose,
          timestamp: now,
        }),
      });
    } catch (e) {
      // Игнорируем ошибки сети уведомлений
    }
  }
}

function escapeTgMarkdown(text) {
  if (!text) return '';
  return String(text).replace(/[_*[\]()~`>#+\-=|{}.!]/g, '\\$&');
}
