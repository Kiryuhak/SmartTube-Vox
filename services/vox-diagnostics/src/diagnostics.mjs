import crypto from 'crypto';
import { ReportStorage } from './storage.mjs';
import { checkAdminAuth, renderAdminHtml } from './admin.mjs';
import { ADMIN_APP_JS } from './admin_client.mjs';
import { sendReportNotification } from './notifier.mjs';

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
  if (report.errorSignature && typeof report.errorSignature === 'string') {
    return report.errorSignature;
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
<html lang="en">
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
    <div class="status-badge"><span class="dot"></span> Online</div>
    <h1>SmartTube VOX Diagnostics</h1>
    <p>SmartTube VOX Diagnostics is running</p>
    <div class="info">Telemetry &amp; error reporting endpoint. User reports are strictly confidential and protected.</div>
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
    <form method="GET" action="/admin">
      <input type="password" name="token" placeholder="Секретный ключ (Admin Secret)" required autofocus />
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
      storage.listReports({ limit: 100 }),
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

    const tokenParam = url.searchParams.get('token');
    if (tokenParam && env.ADMIN_SECRET && tokenParam === env.ADMIN_SECRET) {
      headers['Set-Cookie'] = `vox_admin_token=${encodeURIComponent(tokenParam)}; Path=/; HttpOnly; SameSite=Strict; Secure`;
    }

    return new Response(html, {
      status: 200,
      headers,
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

  // 4. Admin API: Grouped issues (GET /v1/admin/issues)
  if (request.method === 'GET' && path === '/v1/admin/issues') {
    if (!checkAdminAuth(request, env)) {
      return jsonResponse({ error: 'unauthorized', message: 'Admin authentication required' }, 401);
    }
    const limit = parseInt(url.searchParams.get('limit') || '50', 10);
    const offset = parseInt(url.searchParams.get('offset') || '0', 10);
    const status = url.searchParams.get('status') || undefined;

    const issues = await storage.getGroupedIssues({ limit, offset, status });
    return jsonResponse({ issues });
  }

  // 5. Admin API: List reports (GET /v1/admin/reports)
  if (request.method === 'GET' && path === '/v1/admin/reports') {
    if (!checkAdminAuth(request, env)) {
      return jsonResponse({ error: 'unauthorized', message: 'Admin authentication required' }, 401);
    }
    const platform = url.searchParams.get('platform') || undefined;
    const appVersion = url.searchParams.get('appVersion') || undefined;
    const errorCategory = url.searchParams.get('errorCategory') || undefined;
    const status = url.searchParams.get('status') || undefined;
    const reportPurpose = url.searchParams.get('reportPurpose') || undefined;
    const search = url.searchParams.get('search') || undefined;
    const limit = parseInt(url.searchParams.get('limit') || '50', 10);
    const offset = parseInt(url.searchParams.get('offset') || '0', 10);

    const reports = await storage.listReports({
      platform,
      appVersion,
      errorCategory,
      status,
      reportPurpose,
      search,
      limit,
      offset,
    });
    return jsonResponse({ reports });
  }

  // 6. Admin API: Single report operations (GET / PATCH / DELETE /v1/admin/reports/:id)
  if (path.startsWith('/v1/admin/reports/')) {
    if (!checkAdminAuth(request, env)) {
      return jsonResponse({ error: 'unauthorized', message: 'Admin authentication required' }, 401);
    }
    const reportId = decodeURIComponent(path.substring('/v1/admin/reports/'.length));

    if (request.method === 'GET') {
      const report = await storage.getReportById(reportId);
      if (!report) {
        return jsonResponse({ error: 'not_found', message: `Report ${reportId} not found` }, 404);
      }
      return jsonResponse(report);
    }

    if (request.method === 'PATCH') {
      let body;
      try {
        body = await request.json();
      } catch (e) {
        return jsonResponse({ error: 'invalid_json', message: 'Invalid JSON request body' }, 400);
      }
      const updated = await storage.updateReport(reportId, body);
      if (!updated) {
        return jsonResponse({ error: 'not_found', message: `Report ${reportId} not found` }, 404);
      }
      return jsonResponse({ status: 'ok', report: updated });
    }

    if (request.method === 'DELETE') {
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

    const reportId = payload.reportId || generateReportId(payload.platform);
    const receivedAt = Date.now();
    const errorSignature = generateErrorSignature(payload);
    const reportPurpose = payload.reportPurpose || payload.purpose || (reportId.includes('TEST') ? 'TEST' : 'USER');

    const sanitizedReport = {
      ...payload,
      reportId,
      receivedAt,
      errorSignature,
      reportPurpose,
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
