import assert from 'node:assert/strict';
import test, { beforeEach } from 'node:test';
import { handleRequest, resetRateLimiter, isExpiredReport, DEFAULT_RETENTION_MS } from '../src/diagnostics.mjs';

const validReportV1 = {
  schema: 'vox-diagnostic-report-v1',
  timestamp: Date.now(),
  appVersion: '32.56-vox.7-dev',
  appVersionCode: 2446007,
  platform: 'Android TV',
  manufacturer: 'TCL',
  model: 'BeyondTV',
  osName: 'Android',
  osVersion: '12',
  sdkInt: 31,
  deviceTier: 'Премиум TV',
  videoCodecs: {
    AVC: 'Поддерживается [HW]',
    VP9: 'Поддерживается [HW]',
    AV1: 'Поддерживается [HW]',
  },
  audioCodecs: {
    AAC: 'Декод: Поддерживается, Passthrough: Не поддерживается',
    OPUS: 'Декод: Поддерживается, Passthrough: Не поддерживается',
    AC3: 'Декод: Поддерживается, Passthrough: Поддерживается',
    EAC3: 'Декод: Поддерживается, Passthrough: Поддерживается',
  },
  display: {
    resolution: '3840x2160',
    refreshRateHz: 60,
    hdr10: 'SUPPORTED',
  },
  currentPolicy: {
    mode: 'AUTO',
    maxQualityHeight: 2160,
    preferredVideoCodec: 'AUTO',
    preferredAudioCodec: 'AUTO',
    passthroughEnabled: false,
  },
  recommendedSettings: {
    tier: 'PREMIUM_TV',
    mode: 'AUTO',
    maxQualityHeight: 2160,
  },
};

const validReportV2 = {
  ...validReportV1,
  schema: 'vox-diagnostic-report-v2',
  safeRecentEvents: Array.from({ length: 50 }, (_, i) => ({
    timestamp: Date.now() - (50 - i) * 1000,
    level: 'INFO',
    category: 'DOWNLOAD',
    code: 'DOWNLOAD_STARTED',
    message: `Safe download event #${i + 1}`,
    context: { stage: 'INIT', mode: 'DIRECT' },
  })),
};

function createRequest(body, options = {}) {
  const url = options.url ?? 'https://diagnostics.example.com/v1/report';
  const method = options.method ?? 'POST';
  return new Request(url, {
    method,
    headers: {
      'Content-Type': options.contentType ?? 'application/json',
    },
    body: method === 'GET' ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
  });
}

test('GET /healthz returns 200 healthy status', async () => {
  const req = createRequest(null, { url: 'https://diagnostics.example.com/healthz', method: 'GET' });
  const res = await handleRequest(req);
  assert.equal(res.status, 200);
  const json = await res.json();
  assert.equal(json.status, 'healthy');
  assert.deepEqual(json.supportedSchemas, ['vox-diagnostic-report-v1', 'vox-diagnostic-report-v2']);
});

test('POST /v1/report accepts valid v1 report and returns 201 with VOX- prefix reportId', async () => {
  const req = createRequest(validReportV1);
  const res = await handleRequest(req);
  assert.equal(res.status, 201);
  const json = await res.json();
  assert.equal(json.status, 'ok');
  assert.match(json.reportId, /^VOX-[A-Z0-9]+$/);
});

test('POST /v1/report accepts valid v2 report with 50 safeRecentEvents', async () => {
  const req = createRequest(validReportV2);
  const res = await handleRequest(req);
  assert.equal(res.status, 201);
  const json = await res.json();
  assert.equal(json.status, 'ok');
  assert.equal(json.schema, 'vox-diagnostic-report-v2');
  assert.match(json.reportId, /^VOX-[A-Z0-9]+$/);
});

test('POST /v1/report rejects v2 report exceeding 50 events with 400', async () => {
  const tooManyEventsReport = {
    ...validReportV2,
    safeRecentEvents: Array.from({ length: 51 }, (_, i) => ({
      timestamp: Date.now(),
      level: 'INFO',
      category: 'DOWNLOAD',
      code: 'DOWNLOAD_STARTED',
      message: `Event #${i}`,
    })),
  };
  const req = createRequest(tooManyEventsReport);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'too_many_events');
});

test('POST /v1/report rejects unsupported schema with 400', async () => {
  const invalid = { ...validReportV1, schema: 'vox-report-v0-legacy' };
  const req = createRequest(invalid);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'unsupported_schema');
});

test('POST /v1/report rejects payload containing top-level tokens with 400', async () => {
  const dirty = { ...validReportV1, access_token: 'ya29.secret' };
  const req = createRequest(dirty);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'forbidden_data');
});

test('POST /v1/report rejects payload containing nested passwords or cookies with 400', async () => {
  const dirty = {
    ...validReportV1,
    display: {
      ...validReportV1.display,
      session_cookie: 'secret-id',
    },
  };
  const req = createRequest(dirty);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'forbidden_data');
});

test('POST /v1/report rejects forbidden keys inside safeRecentEvents context', async () => {
  const dirty = {
    ...validReportV2,
    safeRecentEvents: [
      {
        timestamp: Date.now(),
        level: 'ERROR',
        category: 'YANDEX_AUTH',
        code: 'AUTH_FAILED',
        message: 'Auth error',
        context: { refresh_token: 'secret123' },
      },
    ],
  };
  const req = createRequest(dirty);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'forbidden_data');
});

test('POST /v1/report rejects missing required device fields with 400', async () => {
  const missing = { ...validReportV1 };
  delete missing.model;
  const req = createRequest(missing);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'missing_fields');
});

test('POST /v1/report rejects oversized payload with 413', async () => {
  const bigPayload = {
    ...validReportV1,
    largeData: 'A'.repeat(260 * 1024),
  };
  const req = createRequest(bigPayload);
  const res = await handleRequest(req);
  assert.equal(res.status, 413);
  const json = await res.json();
  assert.equal(json.error, 'payload_too_large');
});

test('PUT /v1/report returns 405 Method Not Allowed', async () => {
  const req = createRequest(validReportV1, { method: 'PUT' });
  const res = await handleRequest(req);
  assert.equal(res.status, 405);
});

test('POST /v1/report applies rate limiting and returns 429 when threshold is exceeded', async () => {
  resetRateLimiter();
  for (let i = 0; i < 20; i++) {
    const req = createRequest(validReportV1);
    const res = await handleRequest(req);
    assert.equal(res.status, 201);
  }
  // 21st request should be rate limited
  const reqBlocked = createRequest(validReportV1);
  const resBlocked = await handleRequest(reqBlocked);
  assert.equal(resBlocked.status, 429);
  const json = await resBlocked.json();
  assert.equal(json.error, 'rate_limited');
});

test('isExpiredReport correctly identifies reports older than 30 days', () => {
  const now = Date.now();
  const fresh = now - 1000;
  const old29Days = now - 29 * 24 * 60 * 60 * 1000;
  const old31Days = now - 31 * 24 * 60 * 60 * 1000;

  assert.equal(isExpiredReport(fresh), false);
  assert.equal(isExpiredReport(old29Days), false);
  assert.equal(isExpiredReport(old31Days), true);
  assert.equal(isExpiredReport(null), false);
});
