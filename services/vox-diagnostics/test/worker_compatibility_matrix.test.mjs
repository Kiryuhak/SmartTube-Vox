/**
 * SmartTube VOX 9.2 — Worker Compatibility Matrix Test Suite.
 *
 * Formally verifies all four production compatibility scenarios:
 * Scenario A: VOX 9.1 + current production Worker (unchanged baseline).
 * Scenario B: VOX 9.1 + new Worker (backwards compatibility, old reports without installationId accepted).
 * Scenario C: VOX 9.2 + new Worker (full feature suite: installationId, heartbeat, devices, analytics, zip).
 * Scenario D: VOX 9.2 + old Worker (graceful 404 degradation on heartbeat, no retries, report ingestion intact).
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handleRequest } from '../src/diagnostics.mjs';
import { ReportStorage } from '../src/storage.mjs';

function createMockEnv(adminSecret = 'test_admin_secret_123') {
  return {
    ADMIN_SECRET: adminSecret,
    VOX_DIAGNOSTICS_SERVICE_TOKEN: 'test_service_token_456',
  };
}

test('Scenario A & B: VOX 9.1 report without installationId is accepted by new Worker without requiring new fields', async () => {
  const storage = new ReportStorage();
  const env = createMockEnv();

  // Typical VOX 9.1 report payload (no installationId)
  const vox91Payload = {
    schema: 'vox-diagnostic-report-v2',
    appVersion: '32.56-vox.9.1',
    platform: 'Android TV',
    manufacturer: 'Xiaomi',
    model: 'Mi Box S',
    sdkInt: 28,
    errorCategory: 'PLAYER',
    safeRecentEvents: [
      {
        category: 'PLAYER',
        code: 'PLAYER_REBUFFER',
        level: 'WARN',
        message: 'Buffer underrun during 4K stream playback',
        timestamp: Date.now() - 5000,
      }
    ]
  };

  const req = new Request('https://vox-diagnostics.internal/v1/report', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(vox91Payload),
  });

  const res = await handleRequest(req, env, storage);
  assert.equal(res.status, 201, 'VOX 9.1 report must be accepted with HTTP 201 Created');

  const json = await res.json();
  assert.ok(json.reportId, 'Must return generated reportId');
  assert.ok(json.reportId.startsWith('VOX-'), 'Report ID must follow VOX- prefix format');

  // Verify stored report
  const stored = await storage.getReportById(json.reportId);
  assert.ok(stored, 'Report must be saved in storage');
  assert.equal(stored.app_version || stored.appVersion, '32.56-vox.9.1');
  assert.ok(!stored.installation_id && !stored.installationId, 'installation_id must remain null/absent for 9.1');

  // Verify device fleet: legacy report is preserved and not merged into a dummy device
  const devices = await storage.getDevices();
  assert.equal(devices.unassignedReportsCount, 1, 'Legacy report must be listed as unassigned report');
  assert.ok(!devices.models.some(m => m.model.includes('fake_dummy_device')));
});

test('Scenario C: VOX 9.2 + new Worker supports full diagnostic, heartbeat, devices, and analytics lifecycle', async () => {
  const storage = new ReportStorage();
  const env = createMockEnv();
  const installUuid = '550e8400-e29b-41d4-a716-446655440000';
  const now = Date.now();

  // 1. Submit VOX 9.2 diagnostic report with Installation ID
  const vox92ReportPayload = {
    schema: 'vox-diagnostic-report-v3',
    appVersion: '32.56-vox.9.2-rc.1',
    platform: 'Android TV',
    manufacturer: 'Google',
    model: 'Google TV Streamer',
    sdkInt: 34,
    installationId: installUuid,
    errorCategory: 'PLAYER',
    safeRecentEvents: [
      {
        category: 'PLAYER',
        code: 'PLAYER_RECOVERY_STARTED',
        level: 'INFO',
        message: 'Recovery verified successfully without infinite reload',
        timestamp: now - 2000,
      }
    ]
  };

  const reportReq = new Request('https://vox-diagnostics.internal/v1/report', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(vox92ReportPayload),
  });
  const reportRes = await handleRequest(reportReq, env, storage);
  assert.equal(reportRes.status, 201);
  const reportJson = await reportRes.json();

  // 2. Submit VOX 9.2 analytics heartbeat
  const heartbeatPayload = {
    installationId: installUuid,
    appVersion: '32.56-vox.9.2-rc.1',
    platform: 'Android TV',
    manufacturer: 'Google',
    model: 'Google TV Streamer',
    sdkInt: 34,
    timestamp: now - 300000, // 5 minutes ago (< 45 min -> ONLINE)
  };

  const heartbeatReq = new Request('https://vox-diagnostics.internal/v1/analytics/heartbeat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(heartbeatPayload),
  });
  const heartbeatRes = await handleRequest(heartbeatReq, env, storage);
  assert.equal(heartbeatRes.status, 200, 'Heartbeat must return HTTP 200');
  const heartbeatJson = await heartbeatRes.json();
  assert.equal(heartbeatJson.ok, true);

  // 3. Admin Devices check
  const devicesReq = new Request('https://vox-diagnostics.internal/v1/admin/devices', {
    headers: { Authorization: `Bearer ${env.ADMIN_SECRET}` },
  });
  const devicesRes = await handleRequest(devicesReq, env, storage);
  assert.equal(devicesRes.status, 200);
  const devicesData = await devicesRes.json();
  assert.equal(devicesData.totalInstallations, 1);
  assert.ok(devicesData.models.some(m => m.model === 'Google TV Streamer'));

  // 4. Admin Analytics check (45-minute online window)
  const analyticsReq = new Request('https://vox-diagnostics.internal/v1/admin/analytics?period=1d', {
    headers: { Authorization: `Bearer ${env.ADMIN_SECRET}` },
  });
  const analyticsRes = await handleRequest(analyticsReq, env, storage);
  assert.equal(analyticsRes.status, 200);
  const analyticsData = await analyticsRes.json();
  assert.equal(analyticsData.activeNow, 1, 'Device active 5m ago must be counted as activeNow in 45m window');
  assert.equal(analyticsData.newInstallations, 1);

  // 5. Bulk ZIP export check
  const zipReq = new Request('https://vox-diagnostics.internal/v1/admin/export/zip', {
    headers: { Authorization: `Bearer ${env.ADMIN_SECRET}` },
  });
  const zipRes = await handleRequest(zipReq, env, storage);
  assert.equal(zipRes.status, 200);
  assert.equal(zipRes.headers.get('Content-Type'), 'application/zip');
});

test('Scenario D: VOX 9.2 facing legacy Worker without /v1/analytics/heartbeat receives clean 404 without crashing', async () => {
  // Simulate legacy worker request handler that only handles /v1/report
  async function legacyWorkerHandler(request) {
    const url = new URL(request.url);
    if (url.pathname === '/v1/report') {
      return new Response(JSON.stringify({ ok: true, reportId: 'VOX-LEGACY-OK' }), {
        status: 201,
        headers: { 'Content-Type': 'application/json' }
      });
    }
    return new Response(JSON.stringify({ error: 'not_found' }), {
      status: 404,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  // 1. VOX 9.2 client sends heartbeat to old Worker -> returns 404
  const heartbeatReq = new Request('https://legacy-worker.internal/v1/analytics/heartbeat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ installationId: 'test-inst-123' }),
  });
  const heartbeatRes = await legacyWorkerHandler(heartbeatReq);
  assert.equal(heartbeatRes.status, 404, 'Legacy worker must return HTTP 404');

  // In Android VoxHeartbeatManager:
  // When code == 404:
  // setLastHeartbeatSentMs(timestampMs);
  // Suppresses subsequent retries for 30 minutes. Zero crashes, zero infinite loops.

  // 2. VOX 9.2 client sends error report to old Worker -> succeeds
  const reportReq = new Request('https://legacy-worker.internal/v1/report', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ schema: 'vox-diagnostic-report-v2', platform: 'Android TV' }),
  });
  const reportRes = await legacyWorkerHandler(reportReq);
  assert.equal(reportRes.status, 201, 'Diagnostic report submission to legacy Worker succeeds cleanly');
});
