import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handleRequest } from '../src/diagnostics.mjs';

const ADMIN_SECRET = 'test-admin-secret-gate-key';
const env = {
  ADMIN_SECRET,
  VOX_DIAGNOSTICS_SERVICE_TOKEN: 'test-token',
};

function makeAuthHeaders(secret = ADMIN_SECRET) {
  return {
    authorization: `Bearer ${secret}`,
    'content-type': 'application/json',
  };
}

test('API Gate 1: Bulk ZIP export requires admin authorization', async () => {
  const reqUnauth = new Request('http://localhost/v1/admin/export/zip');
  const resUnauth = await handleRequest(reqUnauth, env);
  assert.equal(resUnauth.status, 401, 'Unauthorized request must return 401');

  const reqAuth = new Request('http://localhost/v1/admin/export/zip', {
    headers: makeAuthHeaders(),
  });
  const resAuth = await handleRequest(reqAuth, env);
  assert.equal(resAuth.status, 200, 'Authorized request must return 200');
  assert.equal(resAuth.headers.get('content-type'), 'application/zip');
  assert.ok(resAuth.headers.get('content-disposition').includes('SmartTube-VOX-Diagnostics-'));
  const body = await resAuth.arrayBuffer();
  assert.ok(body.byteLength > 100, 'Zip file must contain valid PK bytes');
});

test('API Gate 2: Heartbeat endpoint validates payload and records activity', async () => {
  // Invalid payload without installationId
  const reqBad = new Request('http://localhost/v1/analytics/heartbeat', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ appVersion: '32.56-vox.9.2' }),
  });
  const resBad = await handleRequest(reqBad, env);
  assert.equal(resBad.status, 400);

  // Valid heartbeat
  const reqGood = new Request('http://localhost/v1/analytics/heartbeat', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      installationId: 'test-install-uuid-9999',
      appVersion: '32.56-vox.9.2',
      platform: 'Android TV',
      manufacturer: 'TCL',
      model: 'Beyond TV4',
      sdkInt: 31,
    }),
  });
  const resGood = await handleRequest(reqGood, env);
  assert.equal(resGood.status, 200);
  const data = await resGood.json();
  assert.equal(data.ok, true);
  assert.equal(data.status, 'received');
});

test('API Gate 3: Devices endpoint requires auth and returns device groups', async () => {
  const reqUnauth = new Request('http://localhost/v1/admin/devices');
  const resUnauth = await handleRequest(reqUnauth, env);
  assert.equal(resUnauth.status, 401);

  const reqAuth = new Request('http://localhost/v1/admin/devices', {
    headers: makeAuthHeaders(),
  });
  const resAuth = await handleRequest(reqAuth, env);
  assert.equal(resAuth.status, 200);
  const data = await resAuth.json();
  assert.ok(Array.isArray(data.models));
  assert.ok(typeof data.totalInstallations === 'number');
});

test('API Gate 4: Analytics endpoint requires auth and returns metrics and time-series', async () => {
  const reqUnauth = new Request('http://localhost/v1/admin/analytics?period=7d');
  const resUnauth = await handleRequest(reqUnauth, env);
  assert.equal(resUnauth.status, 401);

  const reqAuth = new Request('http://localhost/v1/admin/analytics?period=7d', {
    headers: makeAuthHeaders(),
  });
  const resAuth = await handleRequest(reqAuth, env);
  assert.equal(resAuth.status, 200);
  const data = await resAuth.json();
  assert.equal(data.period, '7d');
  assert.ok(typeof data.activeNow === 'number');
  assert.ok(typeof data.activeInPeriod === 'number');
  assert.ok(Array.isArray(data.timeline));
  assert.ok(data.versionDistribution);
});
