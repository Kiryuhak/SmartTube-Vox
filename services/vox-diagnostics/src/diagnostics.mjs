import crypto from 'crypto';
import { ReportStorage, normalizeStatus } from './storage.mjs';
import { checkAdminAuth, resolveAuth, createAdminSession, renderAdminHtml } from './admin.mjs';
import { ADMIN_APP_JS } from './admin_client.mjs';
import { sendReportNotification } from './notifier.mjs';
import { classifyReport, structuredEvents, technicalSummary, STATUSES, SEVERITIES } from './triage.mjs';

/**
 * Формирует безопасный и полный JSON объект для скачивания отчёта администратором.
 * Гарантирует отсутствие любых секретов, токенов, cookies, внутренних ключей и IP.
 */
export function prepareReportDownloadJson(report) {
  if (!report) return null;
  const p = report.payload || {};
  const sanitizedPayload = sanitizePayload(p);

  const status = normalizeStatus(report.status);
  const developerNotes = report.developer_notes || report.developerNotes || '';

  return {
    reportId: report.report_id || report.reportId,
    createdAt: report.created_at || report.createdAt,
    platform: report.platform || 'unknown',
    appVersion: report.app_version || report.appVersion || 'unknown',
    device: {
      family: report.device_family || '',
      manufacturer: p.manufacturer || '',
      model: p.model || '',
      tier: p.deviceTier || '',
      sdkInt: p.sdkInt || null,
      osVersion: p.osVersion || '',
    },
    errorCategory: report.error_category || report.errorCategory || null,
    errorSignature: report.error_signature || report.errorSignature || '',
    status,
    developerNotes,
    safeRecentEvents: p.safeRecentEvents || [],
    payload: sanitizedPayload,
  };
}

function sanitizePayload(value, depth = 0) {
  if (depth > 12) return '[REDACTED]';
  if (Array.isArray(value)) return value.slice(0, 100).map(item => sanitizePayload(item, depth + 1));
  if (value && typeof value === 'object') {
    const result = {};
    for (const [key, item] of Object.entries(value)) {
      if (BANNED_PATTERNS.some(pattern => key.toLowerCase().includes(pattern)) || /(?:^|_)(?:ip|url|uri|path|trace)(?:$|_)/i.test(key) || /^(?:downloadId|channelId|groupName|groupId|videoId)$/i.test(key)) continue;
      result[key] = sanitizePayload(item, depth + 1);
    }
    return result;
  }
  if (typeof value !== 'string') return value;
  return value.slice(0, 1000)
    .replace(/https?:\/\/\S+/gi, '[REDACTED_URL]')
    .replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, '[REDACTED_EMAIL]')
    .replace(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g, '[REDACTED_IP]')
    .replace(/(?:bearer|token|secret|key)\s*[:=]\s*\S+/gi, '[REDACTED]');
}

export const MAX_PAYLOAD_SIZE = 256 * 1024; // 256 KB
export const CANONICAL_SCHEMA = 'vox-diagnostic-report-v2';
export const SUPPORTED_SCHEMAS = ['vox-diagnostic-report-v1', 'vox-diagnostic-report-v2'];
export const MAX_EVENTS_COUNT = 50;
export const MAX_EVENT_MESSAGE_LEN = 500;
export const DEFAULT_RETENTION_DAYS = 30;
export const DEFAULT_RETENTION_MS = DEFAULT_RETENTION_DAYS * 24 * 60 * 60 * 1000;
const RATE_LIMIT_WINDOW_MS = 60 * 1000; // 1 minute
const RATE_LIMIT_MAX_REQUESTS = 20;

// Ephemeral in-memory sliding window rate limiter (hashes source, stores no raw IP)
const rateLimitMap = new Map();

export function resetRateLimiter() {
  rateLimitMap.clear();
}

export function isExpiredReport(reportTimestamp, retentionMs = DEFAULT_RETENTION_MS) {
  if (!reportTimestamp || typeof reportTimestamp !== 'number') return false;
  return Date.now() - reportTimestamp > retentionMs;
}

function checkRateLimit(request) {
  const forwarded = request.headers.get('x-forwarded-for') || '';
  const realIp = request.headers.get('x-real-ip') || '';
  const userAgent = request.headers.get('user-agent') || 'anonymous';
  const rawKey = `${forwarded}:${realIp}:${userAgent}`;
  // One-way hash so raw IP is never kept in memory
  const bucketKey = crypto.createHash('sha256').update(rawKey).digest('hex').substring(0, 16);

  const now = Date.now();
  const timestamps = rateLimitMap.get(bucketKey) || [];
  const recent = timestamps.filter((t) => now - t < RATE_LIMIT_WINDOW_MS);

  if (recent.length >= RATE_LIMIT_MAX_REQUESTS) {
    return false;
  }

  recent.push(now);
  rateLimitMap.set(bucketKey, recent);

  // Periodic cleanup of stale rate-limit buckets
  if (rateLimitMap.size > 1000) {
    for (const [key, list] of rateLimitMap.entries()) {
      const active = list.filter((t) => now - t < RATE_LIMIT_WINDOW_MS);
      if (active.length === 0) {
        rateLimitMap.delete(key);
      } else {
        rateLimitMap.set(key, active);
      }
    }
  }

  return true;
}

const BANNED_PATTERNS = [
  'password',
  'passwd',
  'pwd',
  'secret',
  'token',
  'access_token',
  'refresh_token',
  'id_token',
  'device_code',
  'user_code',
  'authorization',
  'proxy_authorization',
  'cookie',
  'session',
  'account_id',
  'email',
  'android_id',
  'ssaid',
  'serial',
  'mac',
  'private_key',
  'api_key',
  'apikey',
];

function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    },
  });
}

function hasBannedKeys(obj) {
  if (!obj || typeof obj !== 'object') return false;
  if (Array.isArray(obj)) {
    return obj.some((item) => hasBannedKeys(item));
  }
  for (const [key, value] of Object.entries(obj)) {
    const lower = key.toLowerCase();
    if (BANNED_PATTERNS.some((pattern) => lower.includes(pattern))) {
      return true;
    }
    if (value && typeof value === 'object') {
      if (hasBannedKeys(value)) return true;
    }
  }
  return false;
}

function generateReportId(platform = '') {
  const randomHex = crypto.randomBytes(3).toString('hex').toUpperCase();
  const lowerPlat = platform.toLowerCase();
  if (lowerPlat.includes('android')) {
    return `VOX-A-${randomHex}`;
  } else if (lowerPlat.includes('tizen')) {
    return `VOX-TZ-${randomHex}`;
  }
  return `VOX-${randomHex}`;
}

export function generateErrorSignature(report = {}) {
  if (report.errorSignature && typeof report.errorSignature === 'string' && /^[A-Za-z0-9_|:.-]{1,160}$/.test(report.errorSignature)) {
    return report.errorSignature;
  }

  if (Array.isArray(report.safeRecentEvents) && report.safeRecentEvents.some(ev => ev.stage || ev.subsystem || ev.event)) {
    const classified = classifyReport(report);
    const event = [...classified.events].reverse().find(ev => ev.level === 'ERROR' || ['HIGH', 'CRITICAL'].includes(ev.severity)) || classified.events.at(-1);
    if (event || classified.errorCategory) return [event?.category || 'GENERAL', classified.subsystem, classified.stage || 'GENERAL', classified.errorCategory || 'UNKNOWN', event?.safeContext?.exceptionClassSafe || 'NONE', event?.safeContext?.sourceType || report.sourceType || 'UNKNOWN'].join('|');
  }

  const category = report.errorCategory || '';
  const platform = (report.platform || 'Unknown').toUpperCase().replace(/\s+/g, '_');
  const sdk = report.sdkInt ? `API${report.sdkInt}` : (report.osVersion || '');

  // Look for error codes in safeRecentEvents
  let lastErrorCode = '';
  if (Array.isArray(report.safeRecentEvents)) {
    for (let i = report.safeRecentEvents.length - 1; i >= 0; i--) {
      const ev = report.safeRecentEvents[i];
      if (ev && (ev.level === 'ERROR' || ev.level === 'WARNING') && ev.code) {
        lastErrorCode = ev.code;
        break;
      }
    }
  }

  if (!category && !lastErrorCode) {
    return '';
  }

  const catPart = category || 'GENERAL';
  const codePart = lastErrorCode || 'UNKNOWN_CODE';
  return `${catPart}|${codePart}|${platform}${sdk ? `_${sdk}` : ''}`;
}

const defaultStorage = new ReportStorage();

export async function handleRequest(request, env = {}, customStorage = null) {
  const url = new URL(request.url);
  const path = url.pathname;
  const storage = customStorage || (env.DB ? new ReportStorage(env.DB) : defaultStorage);

  // 1. Root Service Status page
  if (request.method === 'GET' && (path === '/' || path === '')) {
    const html = `<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>SmartTube VOX Diagnostics</title>
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      background: #0f1115;
      color: #e6edf3;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      margin: 0;
      padding: 20px;
      box-sizing: border-box;
    }
    .card {
      background: #181c24;
      border: 1px solid #2a313d;
      border-radius: 12px;
      padding: 32px 40px;
      max-width: 480px;
      text-align: center;
      box-shadow: 0 8px 24px rgba(0,0,0,0.4);
    }
    .status-badge {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      background: rgba(46, 160, 67, 0.15);
      color: #3fb950;
      padding: 6px 14px;
      border-radius: 20px;
      font-size: 14px;
      font-weight: 600;
      margin-bottom: 20px;
    }
    .dot {
      width: 8px;
      height: 8px;
      background: #3fb950;
      border-radius: 50%;
    }
    h1 {
      margin: 0 0 12px;
      font-size: 22px;
      color: #ffffff;
    }
    p {
      margin: 0 0 20px;
      color: #8b949e;
      font-size: 15px;
      line-height: 1.5;
    }
    .info {
      font-size: 13px;
      color: #6e7681;
      border-top: 1px solid #2a313d;
      padding-top: 16px;
    }
  </style>
</head>
<body>
  <div class="card">
    <div class="status-badge"><span class="dot"></span> Работает</div>
    <h1>SmartTube VOX Diagnostics</h1>
    <p>Сервис диагностики SmartTube VOX работает.</p>
    <div class="info">Отчёты отправляются только с согласия пользователя и защищены.</div>
  </div>
</body>
</html>`;
    return new Response(html, {
      status: 200,
      headers: {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
      },
    });
  }

  // 2. Health check endpoints
  if (request.method === 'GET' && path === '/health') {
    return jsonResponse({
      status: 'ok',
      service: 'SmartTube VOX Diagnostics',
    });
  }

  if (request.method === 'GET' && path === '/healthz') {
    return jsonResponse({
      status: 'healthy',
      service: 'vox-diagnostics',
      canonicalSchema: CANONICAL_SCHEMA,
      supportedSchemas: SUPPORTED_SCHEMAS,
      retentionDays: DEFAULT_RETENTION_DAYS,
    });
  }

  // 3. Developer Admin UI (GET /admin)
  if (request.method === 'GET' && (path === '/admin' || path === '/admin/')) {
    if (!checkAdminAuth(request, env)) {
      const loginHtml = `<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Вход в панель управления — SmartTube VOX</title>
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      background: #0b0f19;
      color: #e5e7eb;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      margin: 0;
      padding: 20px;
    }
    .login-box {
      background: #111827;
      border: 1px solid #374151;
      border-radius: 12px;
      padding: 32px;
      width: 100%;
      max-width: 400px;
      box-shadow: 0 10px 25px rgba(0,0,0,0.5);
    }
    h2 { margin-top: 0; font-size: 20px; color: #fff; margin-bottom: 8px; }
    p { font-size: 14px; color: #9ca3af; margin-bottom: 24px; line-height: 1.4; }
    input {
      width: 100%;
      box-sizing: border-box;
      padding: 12px 14px;
      background: #1f2937;
      border: 1px solid #374151;
      border-radius: 8px;
      color: #fff;
      font-size: 14px;
      margin-bottom: 16px;
      outline: none;
    }
    input:focus { border-color: #3b82f6; }
    button {
      width: 100%;
      padding: 12px;
      background: #3b82f6;
      border: none;
      border-radius: 8px;
      color: #fff;
      font-weight: 600;
      font-size: 15px;
      cursor: pointer;
    }
    button:hover { background: #2563eb; }
    .footer { margin-top: 16px; font-size: 12px; color: #6b7280; text-align: center; }
  </style>
</head>
<body>
  <div class="login-box">
    <h2>SmartTube VOX Admin</h2>
    <p>Для доступа к диагностическим отчётам требуется ключ администратора.</p>
    <form method="POST" action="/admin/session">
      <input type="password" name="secret" placeholder="Ключ администратора" autocomplete="current-password" required autofocus />
      <button type="submit">Войти</button>
    </form>
    <div class="footer">Закрытый служебный раздел разработчика</div>
  </div>
</body>
</html>`;
      return new Response(loginHtml, {
        status: 401,
        headers: {
          'Content-Type': 'text/html; charset=utf-8',
          'WWW-Authenticate': 'Bearer realm="VOX-Admin"',
          'X-Content-Type-Options': 'nosniff',
        },
      });
    }
    const [reports, stats, groupedIssues] = await Promise.all([
      storage.listReports({ limit: 50 }),
      storage.getStats(),
      storage.getGroupedIssues({ limit: 50 }),
    ]);

    const html = renderAdminHtml(reports, stats, groupedIssues);
    const headers = {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      'Content-Security-Policy': "default-src 'self' data:; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none';",
    };

    return new Response(html, {
      status: 200,
      headers,
    });
  }

  if (request.method === 'POST' && path === '/admin/session') {
    const form = await request.formData().catch(() => null);
    const submitted = form?.get('secret');
    const valid = typeof submitted === 'string' && submitted.length <= 256 && env.ADMIN_SECRET &&
      checkAdminAuth(new Request(request.url, { headers: { authorization: `Bearer ${submitted}` } }), env);
    if (!valid) return jsonResponse({ error: 'unauthorized' }, 401);
    return new Response(null, {
      status: 303,
      headers: {
        Location: '/admin',
        'Set-Cookie': `vox_admin_session=${createAdminSession(env.ADMIN_SECRET)}; Path=/; Max-Age=28800; HttpOnly; SameSite=Strict; Secure`,
        'Cache-Control': 'no-store',
      },
    });
  }

  // 3.1 Developer Admin Client Application (GET /admin/app.js)
  if (request.method === 'GET' && path === '/admin/app.js') {
    return new Response(ADMIN_APP_JS, {
      status: 200,
      headers: {
        'Content-Type': 'application/javascript; charset=utf-8',
        'Cache-Control': 'no-cache',
        'X-Content-Type-Options': 'nosniff',
      },
    });
  }

  // 3. Admin API: Stats (GET /v1/admin/stats)
  if (request.method === 'GET' && path === '/v1/admin/stats') {
    if (!checkAdminAuth(request, env)) {
      return jsonResponse({ error: 'unauthorized', message: 'Admin authentication required' }, 401);
    }
    const stats = await storage.getStats();
    return jsonResponse(stats);
  }

  if (request.method === 'GET' && (path === '/v1/admin/metrics' || path === '/api/admin/metrics')) {
    if (!checkAdminAuth(request, env)) return jsonResponse({ error: 'unauthorized' }, 401);
    return jsonResponse(await storage.getStats());
  }

  if (request.method === 'GET' && (path === '/v1/admin/reports/changes' || path === '/api/admin/reports/changes')) {
    if (!checkAdminAuth(request, env)) return jsonResponse({ error: 'unauthorized' }, 401);
    try { return jsonResponse(await storage.listChanges(url.searchParams.get('cursor') || 0, url.searchParams.get('limit') || 50)); }
    catch { return jsonResponse({ error: 'invalid_cursor' }, 400); }
  }

  // 4. Admin API: Grouped issues (GET /v1/admin/issues or /api/admin/issues)
  if (path.startsWith('/v1/admin/issues/') || path.startsWith('/api/admin/issues/')) {
    const auth = resolveAuth(request, env);
    if (!auth.authorized) return jsonResponse({ error: 'unauthorized' }, 401);
    const sub = path.substring(path.startsWith('/api/') ? '/api/admin/issues/'.length : '/v1/admin/issues/'.length);

    if (sub.endsWith('/status')) {
      if (request.method !== 'PATCH') return jsonResponse({ error: 'method_not_allowed' }, 405);
      const signature = decodeURIComponent(sub.slice(0, -('/status'.length)));
      try {
        const body = await request.json();
        const issue = await storage.updateIssue(signature, body, auth.actor);
        return jsonResponse({ issue });
      } catch (error) {
        return jsonResponse({ error: error instanceof RangeError ? 'invalid_issue' : 'invalid_json' }, 400);
      }
    }

    if (sub.endsWith('/notes')) {
      if (request.method !== 'POST') return jsonResponse({ error: 'method_not_allowed' }, 405);
      const signature = decodeURIComponent(sub.slice(0, -('/notes'.length)));
      try {
        const body = await request.json();
        const issue = await storage.updateIssue(signature, { title: body.note || body.title }, auth.actor);
        return jsonResponse({ issue });
      } catch (error) {
        return jsonResponse({ error: error instanceof RangeError ? 'invalid_issue' : 'invalid_json' }, 400);
      }
    }

    const signature = decodeURIComponent(sub);
    if (request.method === 'GET') {
      const issue = await storage.getIssue(signature);
      return jsonResponse({ issue });
    }
    if (request.method === 'PATCH') {
      try {
        const body = await request.json();
        const issue = await storage.updateIssue(signature, body, auth.actor);
        return jsonResponse({ issue });
      } catch (error) {
        return jsonResponse({ error: error instanceof RangeError ? 'invalid_issue' : 'invalid_json' }, 400);
      }
    }
    return jsonResponse({ error: 'method_not_allowed' }, 405);
  }

  if (request.method === 'GET' && (path === '/v1/admin/issues' || path === '/api/admin/issues')) {
    if (!checkAdminAuth(request, env, 'issues:read')) {
      return jsonResponse({ error: 'unauthorized', message: 'Admin authentication required' }, 401);
    }
    const limit = parseInt(url.searchParams.get('limit') || '50', 10);
    const offset = parseInt(url.searchParams.get('offset') || '0', 10);
    const status = url.searchParams.get('status') || undefined;

    const issues = await storage.getGroupedIssues({ limit, offset, status });
    return jsonResponse({ issues });
  }

  // 5. Admin API: List reports (GET /v1/admin/reports)
  if (request.method === 'GET' && (path === '/v1/admin/reports' || path === '/api/admin/reports')) {
    if (!checkAdminAuth(request, env, 'reports:read')) {
      return jsonResponse({ error: 'unauthorized', message: 'Admin authentication required' }, 401);
    }
    const platform = url.searchParams.get('platform') || undefined;
    const appVersion = url.searchParams.get('appVersion') || undefined;
    const errorCategory = url.searchParams.get('errorCategory') || undefined;
    const status = url.searchParams.get('status') || undefined;
    const reportPurpose = url.searchParams.get('reportPurpose') || undefined;
    const search = url.searchParams.get('search') || undefined;
    const severity = url.searchParams.get('severity') || undefined;
    const device = url.searchParams.get('device') || undefined;
    const signature = url.searchParams.get('signature') || undefined;
    const hasNotes = url.searchParams.get('hasNotes') === 'true';
    const hasDuplicates = url.searchParams.get('hasDuplicates') === 'true';
    const attention = url.searchParams.get('attention') === 'true';
    const sort = url.searchParams.get('sort') || 'newest';
    let cursor = url.searchParams.get('cursor') || undefined;
    const since = url.searchParams.get('since') || undefined;
    const limit = parseInt(url.searchParams.get('limit') || '50', 10);
    let offset = parseInt(url.searchParams.get('offset') || '0', 10);
    if (cursor && sort !== 'newest') {
      if (!/^offset:\d{1,7}$/.test(cursor)) return jsonResponse({ error: 'invalid_cursor' }, 400);
      offset = Number(cursor.slice(7));
      cursor = undefined;
    }

    let reports;
    const pageSize = Math.min(Math.max(1, Number.isFinite(limit) ? limit : 50), 50);
    try { reports = await storage.listReports({
      platform,
      appVersion,
      errorCategory,
      status,
      reportPurpose,
      search,
      severity, device, signature, hasNotes, hasDuplicates, attention, sort, cursor, since,
      limit: pageSize + 1,
      offset,
    }); } catch (error) { return jsonResponse({ error: 'invalid_filter' }, 400); }
    const hasNext = reports.length > pageSize;
    reports = reports.slice(0, pageSize);
    const nextCursor = !hasNext ? null : sort === 'newest'
      ? `${reports.at(-1).created_at}:${reports.at(-1).report_id}` : `offset:${offset + pageSize}`;
    return jsonResponse({ reports, nextCursor });
  }

  // 5.1 Admin API: Purge all reports / Safe Purge (DELETE /v1/admin/reports)
  if (request.method === 'DELETE' && path === '/v1/admin/reports') {
    const auth = resolveAuth(request, env);
    if (!auth.authorized) {
      return jsonResponse({ error: 'unauthorized', message: 'Admin authentication required' }, 401);
    }
    if (!auth.scopes.includes('admin:all')) {
      return jsonResponse({ error: 'forbidden', message: 'Service tokens cannot purge database' }, 403);
    }
    const deleted = await storage.deleteAllReports();
    return jsonResponse({ ok: true, deleted });
  }

  // 6. Admin API: Single report operations (GET / PATCH / DELETE /v1/admin/reports/:id and /download)
  if (path.startsWith('/v1/admin/reports/') || path.startsWith('/api/admin/reports/')) {
    const auth = resolveAuth(request, env);
    if (!auth.authorized) {
      return jsonResponse({ error: 'unauthorized', message: 'Admin authentication required' }, 401);
    }
    const subPath = path.substring(path.startsWith('/api/') ? '/api/admin/reports/'.length : '/v1/admin/reports/'.length);

    if (subPath.endsWith('/status')) {
      if (request.method !== 'PATCH') return jsonResponse({ error: 'method_not_allowed' }, 405);
      const rawId = subPath.slice(0, -('/status'.length));
      const reportId = decodeURIComponent(rawId);
      try {
        const body = await request.json();
        const report = await storage.updateReport(reportId, { ...body, actor: auth.actor });
        return report ? jsonResponse({ status: 'ok', report }) : jsonResponse({ error: 'not_found' }, 404);
      } catch (error) {
        return jsonResponse({ error: error instanceof RangeError ? 'invalid_report_patch' : 'invalid_json' }, 400);
      }
    }

    if (subPath.endsWith('/notes')) {
      if (request.method !== 'POST') return jsonResponse({ error: 'method_not_allowed' }, 405);
      try {
        const body = await request.json();
        const report = await storage.addNote(decodeURIComponent(subPath.slice(0, -6)), body.note, auth.actor);
        return report ? jsonResponse({ report }) : jsonResponse({ error: 'not_found' }, 404);
      } catch { return jsonResponse({ error: 'invalid_note' }, 400); }
    }

    // Download single sanitized report JSON: GET /v1/admin/reports/:id/download
    if (subPath.endsWith('/download')) {
      const rawId = subPath.slice(0, -('/download'.length));
      const reportId = decodeURIComponent(rawId);

      if (request.method !== 'GET') {
        return jsonResponse({ error: 'method_not_allowed', message: `Method ${request.method} is not allowed` }, 405);
      }

      const report = await storage.getReportById(reportId);
      if (!report) {
        return jsonResponse({ error: 'not_found', message: `Report ${reportId} not found` }, 404);
      }

      const downloadData = prepareReportDownloadJson(report);
      const filename = `vox-diagnostic-${encodeURIComponent(reportId)}.json`;
      return new Response(JSON.stringify(downloadData, null, 2), {
        status: 200,
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
          'Content-Disposition': `attachment; filename="${filename}"`,
          'Cache-Control': 'no-store',
          'X-Content-Type-Options': 'nosniff',
        },
      });
    }

    const reportId = decodeURIComponent(subPath);

    if (request.method === 'GET') {
      const report = await storage.getReportById(reportId);
      if (!report) {
        return jsonResponse({ error: 'not_found', message: `Report ${reportId} not found` }, 404);
      }
      return jsonResponse({ ...report, payload: sanitizePayload(report.payload) });
    }

    if (request.method === 'PATCH') {
      let body;
      try {
        body = await request.json();
      } catch (e) {
        return jsonResponse({ error: 'invalid_json', message: 'Invalid JSON request body' }, 400);
      }
      let updated;
      try { updated = await storage.updateReport(reportId, { ...body, actor: auth.actor }); }
      catch (error) { return jsonResponse({ error: error instanceof RangeError ? 'invalid_report_patch' : 'update_failed' }, error instanceof RangeError ? 400 : 500); }
      if (!updated) {
        return jsonResponse({ error: 'not_found', message: `Report ${reportId} not found` }, 404);
      }
      return jsonResponse({ status: 'ok', report: { ...updated, payload: sanitizePayload(updated.payload) } });
    }

    if (request.method === 'DELETE') {
      if (!auth.scopes.includes('admin:all')) {
        return jsonResponse({ error: 'forbidden', message: 'Service tokens cannot delete reports' }, 403);
      }
      const deleted = await storage.deleteReportById(reportId);
      if (!deleted) {
        return jsonResponse({ error: 'not_found', message: `Report ${reportId} not found` }, 404);
      }
      return jsonResponse({ status: 'deleted', reportId });
    }

    return jsonResponse({ error: 'method_not_allowed', message: `Method ${request.method} is not allowed` }, 405);
  }

  // 7. Submit Diagnostic Report (POST /v1/report or POST /report)
  if (path === '/v1/report' || path === '/report') {
    if (request.method !== 'POST') {
      return jsonResponse({ error: 'method_not_allowed', message: `Method ${request.method} is not allowed` }, 405);
    }

    if (!checkRateLimit(request)) {
      return jsonResponse({
        error: 'rate_limited',
        message: 'Too many diagnostic reports received from this source, please try again later',
      }, 429);
    }

    let text;
    try {
      text = await request.text();
    } catch (err) {
      return jsonResponse({ error: 'invalid_body', message: 'Failed to read request body' }, 400);
    }

    if (!text || text.length === 0) {
      return jsonResponse({ error: 'empty_body', message: 'Request body cannot be empty' }, 400);
    }

    if (text.length > MAX_PAYLOAD_SIZE) {
      return jsonResponse({ error: 'payload_too_large', message: `Payload exceeds limit of ${MAX_PAYLOAD_SIZE} bytes` }, 413);
    }

    let payload;
    try {
      payload = JSON.parse(text);
    } catch (err) {
      return jsonResponse({ error: 'invalid_json', message: 'Malformed JSON payload' }, 400);
    }

    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
      return jsonResponse({ error: 'invalid_payload', message: 'Payload must be a JSON object' }, 400);
    }

    if (!payload.schema || !SUPPORTED_SCHEMAS.includes(payload.schema)) {
      return jsonResponse({
        error: 'unsupported_schema',
        message: `Schema '${payload.schema}' is unsupported. Expected one of: ${SUPPORTED_SCHEMAS.join(', ')}`,
      }, 400);
    }

    if (hasBannedKeys(payload)) {
      return jsonResponse({
        error: 'forbidden_data',
        message: 'Payload contains sensitive or forbidden fields (tokens, passwords, cookies, or account identifiers)',
      }, 400);
    }

    if (!payload.platform || !payload.appVersion || !payload.manufacturer || !payload.model) {
      return jsonResponse({
        error: 'missing_fields',
        message: 'Missing required diagnostics fields (platform, appVersion, manufacturer, model)',
      }, 400);
    }

    // Validate safeRecentEvents if present
    if (payload.safeRecentEvents !== undefined) {
      if (!Array.isArray(payload.safeRecentEvents)) {
        return jsonResponse({
          error: 'invalid_events',
          message: 'safeRecentEvents must be an array',
        }, 400);
      }
      if (payload.safeRecentEvents.length > MAX_EVENTS_COUNT) {
        return jsonResponse({
          error: 'too_many_events',
          message: `safeRecentEvents exceeds maximum of ${MAX_EVENTS_COUNT} events`,
        }, 400);
      }
      for (const ev of payload.safeRecentEvents) {
        if (!ev || typeof ev !== 'object' || Array.isArray(ev)) {
          return jsonResponse({
            error: 'invalid_event_item',
            message: 'Each event in safeRecentEvents must be an object',
          }, 400);
        }
        if (typeof ev.message === 'string' && ev.message.length > MAX_EVENT_MESSAGE_LEN) {
          return jsonResponse({
            error: 'event_message_too_long',
            message: `Event message exceeds maximum limit of ${MAX_EVENT_MESSAGE_LEN} characters`,
          }, 400);
        }
      }
    }

    if (payload.severity !== undefined && !SEVERITIES.includes(payload.severity)) return jsonResponse({ error: 'invalid_severity' }, 400);

    if (payload.reportId && (typeof payload.reportId !== 'string' || !/^VOX-[A-Z0-9-]{1,40}$/.test(payload.reportId))) return jsonResponse({ error: 'invalid_report_id' }, 400);
    const reportId = payload.reportId || generateReportId(payload.platform);
    const receivedAt = Date.now();
    const errorSignature = generateErrorSignature(payload);
    const reportPurpose = payload.reportPurpose || payload.purpose || (reportId.includes('TEST') ? 'TEST' : 'USER');

    const sanitizedReport = {
      ...sanitizePayload(payload),
      reportId,
      receivedAt,
      errorSignature,
      reportPurpose,
      structuredEvents: structuredEvents(payload),
      technicalSummary: technicalSummary(payload),
    };

    // Save report in persistent storage (D1 / in-memory)
    await storage.saveReport(sanitizedReport);

    // Optional safe developer notification
    await sendReportNotification(sanitizedReport, env);

    return jsonResponse({
      status: 'ok',
      reportId,
      schema: payload.schema,
      receivedAt,
    }, 201);
  }

  // Fallback 404 for unknown endpoints
  if (request.method !== 'GET' && request.method !== 'POST') {
    return jsonResponse({ error: 'method_not_allowed', message: `Method ${request.method} is not allowed` }, 405);
  }

  return jsonResponse({ error: 'not_found', message: 'Endpoint not found' }, 404);
}
