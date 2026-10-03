import assert from 'node:assert/strict';
import test from 'node:test';
import { handleRequest } from '../src/diagnostics.mjs';

const validReport = {
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
  assert.equal(json.schema, 'vox-diagnostic-report-v1');
});

test('POST /v1/report accepts valid report and returns 201 with VOX- prefix reportId', async () => {
  const req = createRequest(validReport);
  const res = await handleRequest(req);
  assert.equal(res.status, 201);
  const json = await res.json();
  assert.equal(json.status, 'ok');
  assert.match(json.reportId, /^VOX-[A-Z0-9]+$/);
});

test('POST /v1/report rejects unsupported schema with 400', async () => {
  const invalid = { ...validReport, schema: 'vox-report-v0-legacy' };
  const req = createRequest(invalid);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'unsupported_schema');
});

test('POST /v1/report rejects payload containing top-level tokens with 400', async () => {
  const dirty = { ...validReport, access_token: 'ya29.secret' };
  const req = createRequest(dirty);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'forbidden_data');
});

test('POST /v1/report rejects payload containing nested passwords or cookies with 400', async () => {
  const dirty = {
    ...validReport,
    display: {
      ...validReport.display,
      session_cookie: 'secret-id',
    },
  };
  const req = createRequest(dirty);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'forbidden_data');
});

test('POST /v1/report rejects missing required device fields with 400', async () => {
  const missing = { ...validReport };
  delete missing.model;
  const req = createRequest(missing);
  const res = await handleRequest(req);
  assert.equal(res.status, 400);
  const json = await res.json();
  assert.equal(json.error, 'missing_fields');
});

test('POST /v1/report rejects oversized payload with 413', async () => {
  const bigPayload = {
    ...validReport,
    largeData: 'A'.repeat(70 * 1024),
  };
  const req = createRequest(bigPayload);
  const res = await handleRequest(req);
  assert.equal(res.status, 413);
  const json = await res.json();
  assert.equal(json.error, 'payload_too_large');
});

test('PUT /v1/report returns 405 Method Not Allowed', async () => {
  const req = createRequest(validReport, { method: 'PUT' });
  const res = await handleRequest(req);
  assert.equal(res.status, 405);
});
