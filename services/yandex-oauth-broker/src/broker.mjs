const TOKEN_URL = 'https://oauth.yandex.ru/token';
const PATH = '/oauth/yandex/device/token';
const MAX_BODY_BYTES = 1024;
const MAX_UPSTREAM_BYTES = 4096;

function json(status, body, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      ...extraHeaders,
    },
  });
}

function validBody(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const keys = Object.keys(value).sort();
  return keys.length === 2 && keys[0] === 'device_code' && keys[1] === 'request_id'
    && typeof value.device_code === 'string'
    && /^[A-Za-z0-9._~-]{16,128}$/.test(value.device_code)
    && typeof value.request_id === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.request_id);
}

function mapYandex(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    return { state: 'temporary_server_error' };
  }
  const states = new Set([
    'authorization_pending', 'slow_down', 'access_denied', 'invalid_client',
    'unauthorized_client', 'expired_token', 'invalid_grant',
  ]);
  if (states.has(body.error)) {
    return { state: body.error };
  }
  if (typeof body.access_token === 'string' && body.access_token.length > 0) {
    const result = { state: 'success', access_token: body.access_token };
    if (Number.isSafeInteger(body.expires_in) && body.expires_in > 0) {
      result.expires_in = body.expires_in;
    }
    // A refresh token is never stored or returned by this endpoint.
    return result;
  }
  return { state: 'temporary_server_error' };
}

async function sha256(value) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('');
}

async function readBounded(response, maxBytes) {
  const reader = response.body?.getReader();
  if (!reader) return '';
  const chunks = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > maxBytes) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
}

export async function handleRequest(request, env, fetchUpstream = fetch) {
  const url = new URL(request.url);
  if (url.pathname !== PATH || url.search) return json(404, { state: 'not_found' });
  if (request.method !== 'POST') return json(405, { state: 'method_not_allowed' }, { Allow: 'POST' });
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get('Content-Type') ?? '')) {
    return json(415, { state: 'unsupported_media_type' });
  }
  const length = Number(request.headers.get('Content-Length'));
  if (Number.isFinite(length) && length > MAX_BODY_BYTES) {
    return json(413, { state: 'request_too_large' });
  }
  let payload;
  try {
    const raw = await readBounded(request, MAX_BODY_BYTES);
    if (raw === null) {
      return json(413, { state: 'request_too_large' });
    }
    payload = JSON.parse(raw);
  } catch {
    return json(400, { state: 'invalid_request' });
  }
  if (!validBody(payload)) return json(400, { state: 'invalid_request' });
  if (!env.YANDEX_CLIENT_ID || !env.YANDEX_CLIENT_SECRET || !env.RATE_GATE) {
    return json(503, { state: 'service_unavailable' });
  }

  // Cloudflare supplies CF-Connecting-IP; never trust an app-provided static key.
  const clientIp = request.headers.get('CF-Connecting-IP');
  if (!clientIp) return json(503, { state: 'service_unavailable' });
  const codeHash = await sha256(payload.device_code);
  let gate;
  try {
    const stub = env.RATE_GATE.getByName('global');
    gate = await stub.fetch('https://rate-gate.internal/check', {
      method: 'POST',
      body: JSON.stringify({ ip: clientIp, code_hash: codeHash }),
    });
  } catch {
    return json(503, { state: 'service_unavailable' });
  }
  if (!gate.ok) {
    return gate.status === 429
      ? json(429, { state: 'rate_limited' }, { 'Retry-After': '5' })
      : json(503, { state: 'service_unavailable' });
  }

  const form = new URLSearchParams({
    grant_type: 'device_code',
    code: payload.device_code,
    client_id: env.YANDEX_CLIENT_ID,
    client_secret: env.YANDEX_CLIENT_SECRET,
  });
  let upstream;
  try {
    upstream = await fetchUpstream(TOKEN_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
      body: form,
      redirect: 'error',
      signal: AbortSignal.timeout(10_000),
    });
    if (upstream.status >= 500) return json(502, { state: 'temporary_server_error' });
  } catch {
    return json(502, { state: 'network_error' });
  }
  try {
    const raw = await readBounded(upstream, MAX_UPSTREAM_BYTES);
    if (raw === null) return json(502, { state: 'temporary_server_error' });
    const result = mapYandex(JSON.parse(raw));
    if (!upstream.ok && result.state === 'success') {
      return json(502, { state: 'temporary_server_error' });
    }
    return json(result.state === 'temporary_server_error' ? 502 : 200, result);
  } catch {
    return json(502, { state: 'temporary_server_error' });
  }
}

export { mapYandex, validBody };
