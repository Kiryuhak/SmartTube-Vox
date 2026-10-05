import assert from 'node:assert/strict';
import test, { beforeEach } from 'node:test';
import { handleRequest, resetRateLimiter, isExpiredReport, DEFAULT_RETENTION_MS, CANONICAL_SCHEMA } from '../src/diagnostics.mjs';
import { ReportStorage } from '../src/storage.mjs';

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
      ...(options.headers || {}),
    },
    body: method === 'GET' ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
  });
}

beforeEach(() => {
  resetRateLimiter();
});

test('GET /healthz returns 200 healthy status with canonical schema', async () => {
  const req = createRequest(null, { url: 'https://diagnostics.example.com/healthz', method: 'GET' });
  const res = await handleRequest(req);
  assert.equal(res.status, 200);
  const json = await res.json();
  assert.equal(json.status, 'healthy');
  assert.equal(json.canonicalSchema, CANONICAL_SCHEMA);
  assert.deepEqual(json.supportedSchemas, ['vox-diagnostic-report-v1', 'vox-diagnostic-report-v2']);
});

test('POST /v1/report accepts valid v1 report and returns 201 with VOX- prefix reportId', async () => {
  const req = createRequest(validReportV1);
  const res = await handleRequest(req);
  assert.equal(res.status, 201);
  const json = await res.json();
  assert.equal(json.status, 'ok');
  assert.match(json.reportId, /^VOX-A-[A-Z0-9]+$/);
});

test('POST /v1/report accepts valid v2 report with 50 safeRecentEvents', async () => {
  const req = createRequest(validReportV2);
  const res = await handleRequest(req);
  assert.equal(res.status, 201);
  const json = await res.json();
  assert.equal(json.status, 'ok');
  assert.equal(json.schema, 'vox-diagnostic-report-v2');
  assert.match(json.reportId, /^VOX-A-[A-Z0-9]+$/);
});

test('POST /v1/report rejects v2 report exceeding 50 events with 400', async () => {
  const excessiveEvents = {
    ...validReportV2,
    safeRecentEvents: Array.from({ length: 51 }, (_, i) => ({
      timestamp: Date.now(),
      level: 'INFO',
      code: 'EVENT',
      message: `Event #${i}`,
    })),
  };
  const req = createRequest(excessiveEvents);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'too_many_events');
});

test('POST /v1/report rejects unsupported schema with 400', async () => {
  const badSchema = { ...validReportV1, schema: 'vox-report-v999' };
  const req = createRequest(badSchema);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'unsupported_schema');
});

test('POST /v1/report rejects payload containing top-level tokens with 400', async () => {
  const leakedPayload = {
    ...validReportV1,
    access_token: 'secret_oauth_token_12345',
  };
  const req = createRequest(leakedPayload);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'forbidden_data');
});

test('POST /v1/report rejects payload containing nested passwords or cookies with 400', async () => {
  const leakedNested = {
    ...validReportV1,
    networkLogs: {
      headers: {
        cookie: 'session_id=abcdef',
      },
    },
  };
  const req = createRequest(leakedNested);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'forbidden_data');
});

test('POST /v1/report rejects forbidden keys inside safeRecentEvents context', async () => {
  const leakedEventContext = {
    ...validReportV2,
    safeRecentEvents: [
      {
        timestamp: Date.now(),
        level: 'INFO',
        code: 'AUTH',
        message: 'Authenticated successfully',
        context: { user_token: 'xyz789' },
      },
    ],
  };
  const req = createRequest(leakedEventContext);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'forbidden_data');
});

test('POST /v1/report rejects missing required device fields with 400', async () => {
  const missingModel = {
    schema: 'vox-diagnostic-report-v1',
    appVersion: '32.56-vox.7-dev',
    platform: 'Android TV',
  };
  const req = createRequest(missingModel);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'missing_fields');
});

test('POST /v1/report rejects oversized payload with 413', async () => {
  const bigMessage = 'A'.repeat(300 * 1024);
  const req = createRequest(bigMessage, { contentType: 'application/json' });
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
  for (let i = 0; i < 20; i++) {
    const req = createRequest(validReportV1);
    const res = await handleRequest(req);
    assert.equal(res.status, 201);
  }
  const rateLimitedReq = createRequest(validReportV1);
  const res = await handleRequest(rateLimitedReq);
  assert.equal(res.status, 429);
  const json = await res.json();
  assert.equal(json.error, 'rate_limited');
});

test('isExpiredReport correctly identifies reports older than 30 days', () => {
  const now = Date.now();
  const fresh = now - 1000 * 60 * 60 * 24 * 10; // 10 days ago
  const expired = now - DEFAULT_RETENTION_MS - 1000; // 30+ days ago
  assert.equal(isExpiredReport(fresh), false);
  assert.equal(isExpiredReport(expired), true);
});

test('Admin UI and Admin API list and single report retrieval', async () => {
  const customStorage = new ReportStorage();
  const env = { ADMIN_SECRET: 'test_admin_secret_123' };

  // Submit a report into storage
  const submitReq = createRequest(validReportV2);
  const submitRes = await handleRequest(submitReq, env, customStorage);
  assert.equal(submitRes.status, 201);
  const { reportId } = await submitRes.json();
  assert.ok(reportId);

  // 1. GET /admin without auth -> 401
  const unauthAdminReq = createRequest(null, { url: 'https://diagnostics.example.com/admin', method: 'GET' });
  const unauthAdminRes = await handleRequest(unauthAdminReq, env, customStorage);
  assert.equal(unauthAdminRes.status, 401);

  // 2. GET /admin with auth -> 200 HTML
  const authAdminReq = createRequest(null, {
    url: 'https://diagnostics.example.com/admin?token=test_admin_secret_123',
    method: 'GET',
  });
  const authAdminRes = await handleRequest(authAdminReq, env, customStorage);
  assert.equal(authAdminRes.status, 200);
  const html = await authAdminRes.text();
  assert.match(html, /SmartTube VOX/);
  assert.match(html, new RegExp(reportId));

  // 3. GET /v1/admin/reports API with Bearer token
  const listReq = createRequest(null, {
    url: 'https://diagnostics.example.com/v1/admin/reports',
    method: 'GET',
    headers: { Authorization: 'Bearer test_admin_secret_123' },
  });
  const listRes = await handleRequest(listReq, env, customStorage);
  assert.equal(listRes.status, 200);
  const listJson = await listRes.json();
  assert.equal(listJson.reports.length, 1);
  assert.equal(listJson.reports[0].report_id, reportId);

  // 4. GET /v1/admin/reports/:id API
  const getReq = createRequest(null, {
    url: `https://diagnostics.example.com/v1/admin/reports/${encodeURIComponent(reportId)}`,
    method: 'GET',
    headers: { Authorization: 'Bearer test_admin_secret_123' },
  });
  const getRes = await handleRequest(getReq, env, customStorage);
  assert.equal(getRes.status, 200);
  const getJson = await getRes.json();
  assert.equal(getJson.report_id, reportId);
  assert.equal(getJson.payload.schema, 'vox-diagnostic-report-v2');
  assert.equal(getJson.payload.safeRecentEvents.length, 50);
});
