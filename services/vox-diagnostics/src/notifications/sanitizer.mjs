/**
 * SmartTube VOX Diagnostics — Telegram Payload Sanitizer
 * 
 * Создаёт безопасный производный бандл по схеме `vox-diagnostic-telegram-v1`.
 * Удаляет любые токены, cookies, заголовки авторизации, пароли, секреты,
 * подписанные URL, прямые медиа-ссылки, IP-адреса, серийные номера и аккаунты.
 */

const FORBIDDEN_KEY_PATTERN = /^(authorization|cookie|set-cookie|token|accesstoken|refreshtoken|password|secret|signedurl|audiourl|videourl|ip|adb|email|accountid|deviceserial|body|rawbody|cookie_safe|token_safe)$/i;

/**
 * Очищает URL от токенов, подписей и секретных query-параметров, сохраняя только безопасный хост и базовый путь.
 */
export function sanitizeUrl(rawUrl = '') {
  if (!rawUrl || typeof rawUrl !== 'string') return '';
  try {
    const u = new URL(rawUrl);
    // Для googlevideo и других CDN сохраняем протокол и хост, очищая подпись (expire, signature, ip, id и т.д.)
    if (u.hostname.includes('googlevideo.com') || u.hostname.includes('yandex') || u.searchParams.has('sig') || u.searchParams.has('signature') || u.searchParams.has('token')) {
      return `${u.protocol}//${u.hostname}${u.pathname}`;
    }
    // Для остальных URL удаляем потенциально чувствительные searchParams
    const cleanParams = new URLSearchParams();
    for (const [k, v] of u.searchParams.entries()) {
      if (!/(token|sig|signature|key|auth|pass|user|secret|session|expire)/i.test(k)) {
        cleanParams.append(k, v);
      }
    }
    const query = cleanParams.toString();
    return `${u.protocol}//${u.hostname}${u.pathname}${query ? `?${query}` : ''}`;
  } catch {
    // Если не является валидным URL, фильтруем возможные токены по регулярке
    return rawUrl.replace(/(token|sig|key|secret|auth)=[^&]+/gi, '$1=[REDACTED]');
  }
}

/**
 * Рекурсивно очищает объект или массив от секретов и конфиденциальных полей.
 */
export function sanitizeContext(data, depth = 0) {
  if (depth > 6) return '[MAX_DEPTH]';
  if (data === null || data === undefined) return data;
  if (typeof data === 'string') {
    if (/https?:\/\//i.test(data)) {
      return sanitizeUrl(data);
    }
    // Удаляем возможные IPv4 адреса
    return data.replace(/\b(?:192\.168|10\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b/g, '[LOCAL_IP]')
               .replace(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g, (match) => {
                 // Оставляем известные публичные DNS или заменяем
                 if (match === '8.8.8.8' || match === '1.1.1.1') return match;
                 return '[IP_REDACTED]';
               });
  }
  if (typeof data !== 'object') return data;

  if (Array.isArray(data)) {
    return data.slice(0, 30).map(item => sanitizeContext(item, depth + 1));
  }

  const clean = {};
  for (const [key, val] of Object.entries(data)) {
    if (FORBIDDEN_KEY_PATTERN.test(key) || /token|secret|password|cookie|auth|signed_url/i.test(key)) {
      continue;
    }
    if (key === 'url' || key === 'sourceUrl' || key === 'audioUrl' || key === 'videoUrl') {
      clean[key] = sanitizeUrl(typeof val === 'string' ? val : '');
      continue;
    }
    clean[key] = sanitizeContext(val, depth + 1);
  }
  return clean;
}

/**
 * Очищает отдельное событие safeRecentEvents.
 */
export function sanitizeEvent(event = {}) {
  const code = event.code || event.event || 'UNKNOWN';
  const category = event.category || 'GENERAL';
  const level = event.level || event.severity || 'INFO';
  const message = typeof event.message === 'string' ? event.message.slice(0, 500) : '';

  const cleanEv = {
    timestamp: event.timestamp || event.ts || Date.now(),
    category,
    code,
    level,
    message: sanitizeContext(message),
    lifecycle: event.lifecycle || (event.isHistorical ? 'HISTORICAL' : 'RECENT'),
    isHistorical: Boolean(event.isHistorical),
  };

  if (event.recovered) {
    cleanEv.recovered = true;
    cleanEv.recoveryEvent = event.recoveryEvent;
  }

  const rawCtx = event.context || event.safeContext || {};
  cleanEv.context = sanitizeContext(rawCtx);

  return cleanEv;
}

/**
 * Создаёт производный JSON бандл для Telegram по схеме vox-diagnostic-telegram-v1.
 */
export function createSanitizedTelegramBundle(report = {}, classification = {}, incident = {}) {
  const reportId = report.reportId || report.report_id || 'VOX-UNKNOWN';
  const appVersion = report.appVersion || report.app_version || 'unknown';
  const appVersionCode = Number(report.appVersionCode || report.app_version_code || 0);
  const platform = report.platform || 'Android TV';
  const receivedAt = report.receivedAt || report.createdAt || report.timestamp || Date.now();

  const dev = report.device || {};
  const cleanDevice = {
    family: dev.family || report.deviceFamily || report.device_family || '',
    manufacturer: dev.manufacturer || report.manufacturer || '',
    model: dev.model || report.model || '',
    tier: dev.tier || report.deviceTier || '',
    sdkInt: dev.sdkInt || report.sdkInt || null,
    osVersion: dev.osVersion || report.osVersion || '',
  };

  const cleanIncident = {
    status: incident.status || report.status || 'NEW',
    severity: incident.severity || classification.severity || 'MEDIUM',
    category: incident.category || classification.errorCategory || report.errorCategory || 'PLAYER',
    errorSignature: incident.errorSignature || classification.signature || report.errorSignature || '',
    issueKey: incident.issueKey || '',
    reopened: Boolean(incident.reopened),
  };

  const cleanClassification = {
    errorCategory: classification.errorCategory || cleanIncident.category,
    subsystem: classification.subsystem || 'SYSTEM',
    stage: classification.stage || 'GENERAL',
    severity: classification.severity || cleanIncident.severity,
    activeEventCount: classification.activeEventCount ?? (classification.events ? classification.events.filter(e => !e.isHistorical && !e.recovered).length : 1),
    historicalEventCount: classification.historicalEventCount ?? (classification.events ? classification.events.filter(e => e.isHistorical).length : 0),
  };

  const rawEvents = Array.isArray(report.safeRecentEvents) ? report.safeRecentEvents :
    (Array.isArray(classification.events) ? classification.events : []);

  // Ограничиваем не более 30 последних событий
  const safeRecentEvents = rawEvents.slice(-30).map(sanitizeEvent);

  const bundle = {
    $schema: 'https://smarttube.app/schemas/vox-diagnostic-telegram-v1.json',
    schemaVersion: '1.0.0',
    reportId,
    receivedAt,
    appVersion,
    appVersionCode,
    platform,
    incident: cleanIncident,
    classification: cleanClassification,
    device: cleanDevice,
    technicalSummary: report.technicalSummary || classification.technicalSummary || '',
    safeRecentEvents,
  };

  return bundle;
}

/**
 * Генерирует чистое имя файла для прикрепляемого JSON в Telegram.
 * Пример: VOX-D0DF1A08_32.56-vox.9_PLAYER.json
 */
export function generateTelegramBundleFilename(reportId, appVersion, errorCategory) {
  const safeId = String(reportId || 'VOX-REPORT').replace(/[^A-Za-z0-9_-]/g, '_');
  const safeVer = String(appVersion || 'ver').replace(/[^A-Za-z0-9._-]/g, '_');
  const safeCat = String(errorCategory || 'INCIDENT').replace(/[^A-Za-z0-9_-]/g, '_');
  return `${safeId}_${safeVer}_${safeCat}.json`;
}
