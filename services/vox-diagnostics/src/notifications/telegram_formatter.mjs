/**
 * SmartTube VOX Diagnostics — Telegram Message Formatter
 * 
 * Форматирует сообщения для Telegram:
 * 1. Новая проблема (🚨 VOX Diagnostics — новая проблема)
 * 2. Регрессия (🔴 VOX Diagnostics — REGRESSION)
 * 3. Агрегация повторов (⚠️ VOX Diagnostics — повтор проблемы)
 * 4. Синтетический тест (🧪 VOX Diagnostics — TEST)
 */

export function escapeHtml(str = '') {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

/**
 * Создаёт inline-кнопку для перехода в Dashboard.
 */
export function buildDashboardKeyboard(baseUrl, targetType, targetId) {
  if (!baseUrl) return undefined;
  const cleanBase = baseUrl.replace(/\/+$/, '');
  let link = `${cleanBase}/admin`;
  if (targetType === 'report' && targetId) {
    link += `#report=${encodeURIComponent(targetId)}`;
  } else if (targetType === 'issue' && targetId) {
    link += `#issue=${encodeURIComponent(targetId)}`;
  }

  return {
    inline_keyboard: [
      [
        {
          text: '📊 Открыть в Dashboard',
          url: link
        }
      ]
    ]
  };
}

/**
 * Форматирует строковое описание устройства.
 */
export function formatDeviceString(report = {}) {
  const dev = report.device || {};
  const mfr = dev.manufacturer || report.manufacturer || '';
  const model = dev.model || report.model || '';
  const name = [mfr, model].filter(Boolean).join(' ').trim() || 'Android Device';
  const os = dev.osVersion || report.osVersion || (dev.sdkInt ? `Android API ${dev.sdkInt}` : '');
  return os ? `${name} / ${os}` : name;
}

/**
 * Форматирует уведомление о новой проблеме.
 */
export function formatNewIssueMessage(report = {}, issue = {}, classification = {}) {
  const reportId = report.reportId || report.report_id || 'UNKNOWN';
  const appVersion = report.appVersion || report.app_version || 'unknown';
  const category = classification.errorCategory || issue.category || report.errorCategory || 'PLAYER';
  const errorName = issue.key || issue.title || classification.signature || report.errorSignature || 'UNKNOWN_ERROR';
  const device = formatDeviceString(report);
  const status = issue.status || report.status || 'NEW';
  const repeats = issue.repeatCount || report.repeatCount || 1;

  return `🚨 <b>VOX Diagnostics — новая проблема</b>\n\n` +
    `<b>Версия:</b> <code>${escapeHtml(appVersion)}</code>\n` +
    `<b>Категория:</b> <code>${escapeHtml(category)}</code>\n` +
    `<b>Ошибка:</b> <code>${escapeHtml(errorName)}</code>\n` +
    `<b>Устройство:</b> ${escapeHtml(device)}\n` +
    `<b>Статус:</b> <b>${escapeHtml(status)}</b>\n` +
    `<b>Повторов:</b> ${repeats}\n` +
    `<b>Report:</b> <code>${escapeHtml(reportId)}</code>\n\n` +
    `📎 <i>JSON приложен.</i>`;
}

/**
 * Форматирует уведомление о регрессии (проблема появилась снова после исправленной версии).
 */
export function formatRegressionMessage(report = {}, issue = {}, classification = {}) {
  const reportId = report.reportId || report.report_id || 'UNKNOWN';
  const appVersion = report.appVersion || report.app_version || 'unknown';
  const category = classification.errorCategory || issue.category || report.errorCategory || 'PLAYER';
  const errorName = issue.key || issue.title || classification.signature || report.errorSignature || 'UNKNOWN_ERROR';
  const device = formatDeviceString(report);
  const fixedIn = issue.fixedInVersion || issue.fixed_in_version || 'предыдущей версии';

  return `🔴 <b>VOX Diagnostics — REGRESSION</b>\n\n` +
    `⚠️ <b>Проблема появилась снова после исправления!</b>\n` +
    `<b>Версия:</b> <code>${escapeHtml(appVersion)}</code>\n` +
    `<b>Исправлено в:</b> <code>${escapeHtml(fixedIn)}</code>\n` +
    `<b>Категория:</b> <code>${escapeHtml(category)}</code>\n` +
    `<b>Ошибка:</b> <code>${escapeHtml(errorName)}</code>\n` +
    `<b>Устройство:</b> ${escapeHtml(device)}\n` +
    `<b>Статус:</b> <b>REOPENED</b>\n` +
    `<b>Report:</b> <code>${escapeHtml(reportId)}</code>\n\n` +
    `📎 <i>JSON приложен.</i>`;
}

/**
 * Форматирует агрегированное сообщение о повторяющихся инцидентах.
 */
export function formatAggregateMessage(issueKeyOrSignature, countData = {}) {
  const title = countData.title || issueKeyOrSignature;
  const newCount = countData.newCount || 1;
  const totalCount = countData.totalCount || countData.newCount || 1;
  const lastVersion = countData.lastVersion || 'unknown';

  return `⚠️ <b>VOX Diagnostics — повтор проблемы</b>\n\n` +
    `<code>${escapeHtml(title)}</code>\n\n` +
    `<b>Новых повторов:</b> +${newCount}\n` +
    `<b>Всего:</b> ${totalCount}\n` +
    `<b>Последняя версия:</b> <code>${escapeHtml(lastVersion)}</code>`;
}

/**
 * Форматирует синтетическое тестовое сообщение.
 */
export function formatTestMessage(extraInfo = '') {
  return `🧪 <b>VOX Diagnostics — TEST</b>\n\n` +
    `Интеграция с Telegram успешно настроена и работает.\n` +
    (extraInfo ? `<i>${escapeHtml(extraInfo)}</i>\n` : '') +
    `📎 <i>Синтетический JSON приложен.</i>`;
}
