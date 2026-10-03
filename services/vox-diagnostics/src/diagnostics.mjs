import crypto from 'node:crypto';

const MAX_PAYLOAD_SIZE = 64 * 1024; // 64 KB
const SUPPORTED_SCHEMA = 'vox-diagnostic-report-v1';

const BANNED_PATTERNS = [
  'password',
  'passwd',
  'pwd',
  'secret',
  'token',
  'device_code',
  'authorization',
  'cookie',
  'session',
  'account_id',
  'email',
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
    return jsonResponse({ status: 'healthy', service: 'vox-diagnostics', schema: SUPPORTED_SCHEMA });
  }

  if (request.method !== 'POST') {
    return jsonResponse({ error: 'method_not_allowed', message: `Method ${request.method} is not allowed` }, 405);
  }

  if (path !== '/v1/report' && path !== '/report' && path !== '/') {
    return jsonResponse({ error: 'not_found', message: 'Endpoint not found' }, 404);
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

  if (payload.schema !== SUPPORTED_SCHEMA) {
    return jsonResponse({
      error: 'unsupported_schema',
      message: `Schema '${payload.schema}' is unsupported. Expected '${SUPPORTED_SCHEMA}'`,
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

  const randomHex = crypto.randomBytes(3).toString('hex').toUpperCase();
  const reportId = payload.reportId || `VOX-${randomHex}`;

  return jsonResponse({
    status: 'ok',
    reportId,
    schema: SUPPORTED_SCHEMA,
    receivedAt: Date.now(),
  }, 201);
}
