import crypto from 'node:crypto';

const MAX_PAYLOAD_SIZE = 256 * 1024; // 256 KB
const SUPPORTED_SCHEMAS = ['vox-diagnostic-report-v1', 'vox-diagnostic-report-v2'];
const MAX_EVENTS_COUNT = 50;
const MAX_EVENT_MESSAGE_LEN = 500;
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

export async function handleRequest(request) {
  const url = new URL(request.url);
  const path = url.pathname;

  // Health check endpoint
  if (request.method === 'GET' && (path === '/healthz' || path === '/health' || path === '/')) {
    return jsonResponse({
      status: 'healthy',
      service: 'vox-diagnostics',
      supportedSchemas: SUPPORTED_SCHEMAS,
    });
  }

  if (request.method !== 'POST') {
    return jsonResponse({ error: 'method_not_allowed', message: `Method ${request.method} is not allowed` }, 405);
  }

  if (path !== '/v1/report' && path !== '/report' && path !== '/') {
    return jsonResponse({ error: 'not_found', message: 'Endpoint not found' }, 404);
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

  const randomHex = crypto.randomBytes(3).toString('hex').toUpperCase();
  const reportId = payload.reportId || `VOX-${randomHex}`;

  return jsonResponse({
    status: 'ok',
    reportId,
    schema: payload.schema,
    receivedAt: Date.now(),
  }, 201);
}
