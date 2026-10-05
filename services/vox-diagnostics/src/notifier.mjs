/**
 * Безопасный адаптер уведомлений о новых диагностических отчётах.
 * Никогда не отправляет чувствительные данные, логирует только краткую сводку.
 * Если переменные окружения не заданы — тихо отключается без сбоев.
 */
export async function sendReportNotification(report, env = {}) {
  const token = env.TELEGRAM_BOT_TOKEN;
  const chatId = env.TELEGRAM_CHAT_ID;
  const webhookUrl = env.NOTIFICATION_WEBHOOK_URL;

  const reportId = report.reportId;
  const platform = report.platform || 'Unknown';
  const appVersion = report.appVersion || 'Unknown';
  const errorCategory = report.errorCategory || 'None';
  const device = `${report.manufacturer || ''} ${report.model || ''}`.trim();

  // 1. Опциональный Telegram бот
  if (token && chatId) {
    try {
      const text = `📊 *SmartTube VOX Report*\n\n` +
        `• ID: \`${reportId}\`\n` +
        `• Platform: ${platform}\n` +
        `• Version: ${appVersion}\n` +
        `• Device: ${device}\n` +
        `• Error: ${errorCategory}\n`;

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

  // 2. Опциональный Generic Webhook
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
          timestamp: Date.now(),
        }),
      });
    } catch (e) {
      // Игнорируем ошибки сети уведомлений
    }
  }
}
