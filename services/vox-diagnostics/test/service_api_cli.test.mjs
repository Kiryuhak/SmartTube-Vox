import assert from 'node:assert/strict';
import test from 'node:test';
import { handleRequest } from '../src/diagnostics.mjs';
import { ReportStorage } from '../src/storage.mjs';
import { resolveAuth, checkAdminAuth } from '../src/admin.mjs';
import { classifyReport, structuredEvents, technicalSummary } from '../src/triage.mjs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const cliPath = path.resolve(__dirname, '../../../tools/vox-diagnostics-cli.mjs');

const ADMIN_SECRET = 'test-admin-secret-fixture';
const SERVICE_TOKEN = 'test-antigravity-service-token-fixture';
const env = {
  ADMIN_SECRET,
  VOX_DIAGNOSTICS_SERVICE_TOKEN: SERVICE_TOKEN,
};
const base = 'https://diagnostics.example.com';

const fixtureD44EF7 = {
  reportId: 'VOX-A-D44EF7',
  schema: 'vox-diagnostic-report-v2',
  timestamp: 1728400000000,
  appVersion: '32.56-vox.8',
  appVersionCode: 2446011,
  platform: 'Android TV',
  manufacturer: 'TCL',
  model: 'Smart TV Pro',
  sdkInt: 34,
  osVersion: '14',
  deviceTier: 'Премиум TV',
  errorCategory: 'PLAYER',
  errorSignature: 'PLAYER|RENDERER|VP9|2160P',
  status: 'NEW',
  reportPurpose: 'USER',
  display: { resolution: '3840x2160', refreshRateHz: 60 },
  livePlayback: {
    selectedCodec: 'VP9',
    selectedHeight: 2160,
    rebufferCount: 2,
    totalRebufferMs: 12000,
  },
  downloadDiagnostics: {
    activeJobs: 1,
    packagingCompleted: false,
    finalizeCompleted: false,
    lastStage: 'COMPLETED', // Inconsistent: COMPLETED without packaging/finalize
  },
  safeRecentEvents: [
    { timestamp: 1728400000100, level: 'ERROR', category: 'PLAYER', code: 'PLAYER_RENDERER_ERROR', message: 'Codec exception' },
    { timestamp: 1728400000200, level: 'WARN', category: 'PLAYER', code: 'PLAYER_REBUFFER', durationMs: 12000 },
    { timestamp: 1728400000300, level: 'ERROR', category: 'OTA', code: 'NETWORK_ERROR', message: 'Connection reset' },
  ],
};

const fixture870711 = {
  reportId: 'VOX-A-870711',
  schema: 'vox-diagnostic-report-v2',
  timestamp: 1728500000000,
  appVersion: '32.56-vox.8.1',
  appVersionCode: 2446012,
  platform: 'Android TV',
  manufacturer: 'VS',
  model: 'KM1',
  sdkInt: 29,
  osVersion: '10',
  deviceTier: 'Стандартный TV',
  errorCategory: 'PLAYER',
  errorSignature: 'PLAYER|REBUFFER|VOD|ANDROID_TV_API29',
  status: 'NEW',
  reportPurpose: 'USER',
  playbackStats: {
    selectedCodec: 'AVC',
    selectedHeight: 1080,
    rebufferCount: 3,
    totalRebufferMs: 15400,
  },
  safeRecentEvents: [
    { timestamp: 1728500000100, level: 'WARN', category: 'PLAYER', code: 'PLAYER_REBUFFER', durationMs: 5000 },
    { timestamp: 1728500000200, level: 'WARN', category: 'PLAYER', code: 'PLAYER_REBUFFER', durationMs: 10400 },
  ],
};

const fixture7C4082 = {
  reportId: 'VOX-A-7C4082',
  schema: 'vox-diagnostic-report-v2',
  timestamp: 1728600000000,
  appVersion: '32.56-vox.8.2-beta.1',
  appVersionCode: 2446013,
  platform: 'Android TV',
  manufacturer: 'TCL',
  model: 'Smart TV Pro',
  sdkInt: 34,
  osVersion: '14',
  deviceTier: 'Премиум TV',
  errorCategory: 'PLAYER',
  errorSignature: 'PLAYER|LIVE_STALL|UNBOUNDED_WINDOW',
  status: 'NEW',
  reportPurpose: 'USER',
  livePlayback: {
    selectedCodec: 'VP9',
    selectedHeight: 1080,
    liveOffsetMs: 137000000,
    rebufferCount: 1,
    totalRebufferMs: 18000,
  },
  safeRecentEvents: [
    { timestamp: 1728600000100, level: 'WARN', category: 'PLAYER', code: 'LIVE_OFFSET_INVALID', message: 'Invalid live offset detected' },
    { timestamp: 1728600000200, level: 'WARN', category: 'PLAYER', code: 'PLAYER_REBUFFER', durationMs: 18000 },
  ],
};

test('Service Token authentication, scopes and query parameter rejection', () => {
  // Reject tokens in URL query parameter unconditionally
  const queryReq = new Request(`${base}/admin?token=${ADMIN_SECRET}`);
  assert.equal(checkAdminAuth(queryReq, env), false);
  const resolveQuery = resolveAuth(queryReq, env);
  assert.equal(resolveQuery.authorized, false);

  // Authenticate via Bearer service token
  const serviceReq = new Request(`${base}/api/admin/reports`, {
    headers: { authorization: `Bearer ${SERVICE_TOKEN}` },
  });
  const serviceAuth = resolveAuth(serviceReq, env);
  assert.equal(serviceAuth.authorized, true);
  assert.equal(serviceAuth.actor, 'ANTIGRAVITY');
  assert.ok(serviceAuth.scopes.includes('reports:read'));
  assert.ok(serviceAuth.scopes.includes('reports:update_status'));
  assert.ok(!serviceAuth.scopes.includes('admin:all'));

  // Authenticate via Bearer admin secret
  const adminReq = new Request(`${base}/api/admin/reports`, {
    headers: { authorization: `Bearer ${ADMIN_SECRET}` },
  });
  const adminAuth = resolveAuth(adminReq, env);
  assert.equal(adminAuth.authorized, true);
  assert.equal(adminAuth.actor, 'ADMIN');
  assert.ok(adminAuth.scopes.includes('admin:all'));
});

test('Service token cannot purge reports but admin can', async () => {
  const storage = new ReportStorage();
  await storage.saveReport(fixtureD44EF7);

  // Service token DELETE /v1/admin/reports -> 403 Forbidden
  const servicePurgeReq = new Request(`${base}/v1/admin/reports`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${SERVICE_TOKEN}` },
  });
  const serviceRes = await handleRequest(servicePurgeReq, env, storage);
  assert.equal(serviceRes.status, 403);

  // Admin secret DELETE /v1/admin/reports -> 200 OK
  const adminPurgeReq = new Request(`${base}/v1/admin/reports`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${ADMIN_SECRET}` },
  });
  const adminRes = await handleRequest(adminPurgeReq, env, storage);
  assert.equal(adminRes.status, 200);
});

test('Status workflow and Antigravity actor attribution on reports', async () => {
  const storage = new ReportStorage();
  await storage.saveReport(fixtureD44EF7);

  // 1. Antigravity sets IN_PROGRESS via PATCH /api/admin/reports/:id/status
  const statusReq = new Request(`${base}/api/admin/reports/VOX-A-D44EF7/status`, {
    method: 'PATCH',
    headers: {
      authorization: `Bearer ${SERVICE_TOKEN}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({ status: 'IN_PROGRESS', reason: 'Triage started by Antigravity' }),
  });
  const statusRes = await handleRequest(statusReq, env, storage);
  assert.equal(statusRes.status, 200);

  // 2. Antigravity adds developer note via POST /api/admin/reports/:id/notes
  const noteReq = new Request(`${base}/api/admin/reports/VOX-A-D44EF7/notes`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${SERVICE_TOKEN}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({ note: 'Identified VP9 renderer stall and inconsistent download completed state.' }),
  });
  const noteRes = await handleRequest(noteReq, env, storage);
  assert.equal(noteRes.status, 200);

  // 3. Move through FIXED_PENDING_VERIFICATION to CLOSED
  const fixReq = new Request(`${base}/api/admin/reports/VOX-A-D44EF7/status`, {
    method: 'PATCH',
    headers: {
      authorization: `Bearer ${SERVICE_TOKEN}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({ status: 'FIXED_PENDING_VERIFICATION', reason: 'Patch #28 applied' }),
  });
  assert.equal((await handleRequest(fixReq, env, storage)).status, 200);

  const report = await storage.getReportById('VOX-A-D44EF7');
  assert.equal(report.status, 'FIXED_PENDING_VERIFICATION');
  assert.equal(report.statusHistory[0].actor, 'ANTIGRAVITY');
  assert.equal(report.statusHistory[0].toStatus, 'FIXED_PENDING_VERIFICATION');
  assert.equal(report.notes[0].actor, 'ANTIGRAVITY');
  assert.equal(report.notes[0].note, 'Identified VP9 renderer stall and inconsistent download completed state.');
});

test('Issues management and resolution with patch & commit metadata', async () => {
  const storage = new ReportStorage();
  await storage.saveReport(fixture870711);

  const sig = encodeURIComponent('PLAYER|REBUFFER|VOD|ANDROID_TV_API29');

  // Antigravity sets issue to IN_PROGRESS
  const patchReq = new Request(`${base}/api/admin/issues/${sig}`, {
    method: 'PATCH',
    headers: {
      authorization: `Bearer ${SERVICE_TOKEN}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      status: 'IN_PROGRESS',
      title: 'Частые буферизации VOD на Android TV API 29',
    }),
  });
  const patchRes = await handleRequest(patchReq, env, storage);
  assert.equal(patchRes.status, 200);

  // Antigravity resolves issue with Patch #28 commit metadata
  const resolveReq = new Request(`${base}/api/admin/issues/${sig}`, {
    method: 'PATCH',
    headers: {
      authorization: `Bearer ${SERVICE_TOKEN}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      status: 'RESOLVED',
      fixedInVersion: '32.56-vox.8.2-beta.2',
      fixPatch: 'Patch #28',
      fixCommit: 'abcdef12',
    }),
  });
  assert.equal((await handleRequest(resolveReq, env, storage)).status, 200);

  // Fetch issue
  const getReq = new Request(`${base}/api/admin/issues/${sig}`, {
    headers: { authorization: `Bearer ${SERVICE_TOKEN}` },
  });
  const getRes = await handleRequest(getReq, env, storage);
  assert.equal(getRes.status, 200);
  const data = await getRes.json();
  assert.equal(data.issue.issue_status, 'RESOLVED');
  assert.equal(data.issue.fixed_in_version, '32.56-vox.8.2-beta.2');
  assert.equal(data.issue.fix_patch, 'Patch #28');
  assert.equal(data.issue.fix_commit, 'abcdef12');
});

test('Verification of user reports VOX-A-D44EF7 and VOX-A-870711 technical summary', () => {
  // VOX-A-D44EF7
  const summaryD = technicalSummary(fixtureD44EF7);
  assert.equal(summaryD.player.isLive, true);
  assert.equal(summaryD.player.selectedCodec, 'VP9');
  assert.equal(summaryD.player.selectedResolution, 2160);
  assert.equal(summaryD.player.rebufferCount, 2);

  // VOX-A-870711 (VOD rebuffer consistency check)
  const summary8 = technicalSummary(fixture870711);
  assert.equal(summary8.player.isLive, false);
  assert.equal(summary8.player.sourceType, 'VOD');
  assert.equal(summary8.player.rebufferCount, 3);
  assert.equal(summary8.player.selectedCodec, 'AVC');
  assert.equal(summary8.player.selectedResolution, 1080);

  // VOX-A-7C4082 (38h anomalous offset on 24/7 live stream)
  const summary7 = technicalSummary(fixture7C4082);
  assert.equal(summary7.player.isLive, true);
  assert.equal(summary7.player.selectedCodec, 'VP9');
  assert.equal(summary7.player.selectedResolution, 1080);
  assert.equal(summary7.player.liveOffsetMs, 137000000);
  assert.ok(fixture7C4082.safeRecentEvents.some((e) => e.code === 'LIVE_OFFSET_INVALID'));
});

test('Report metadata fidelity and non-contamination across devices', async () => {
  const fixture0B2418 = {
    reportId: 'VOX-A-0B2418',
    schema: 'vox-diagnostic-report-v2',
    timestamp: 1728630000000,
    appVersion: '32.56-vox.8.1',
    appVersionCode: 2446012,
    platform: 'Android TV',
    manufacturer: 'TUVIO',
    model: 'TD50UFBSV1',
    sdkInt: 30,
    osVersion: '11',
    deviceTier: 'Стандартный TV',
    errorCategory: 'BACKGROUND',
    errorSignature: 'BACKGROUND|PLAYBACK_EXIT',
    status: 'NEW',
    reportPurpose: 'USER',
    safeRecentEvents: [
      { timestamp: 1728630000100, level: 'INFO', category: 'BACKGROUND', code: 'BACKGROUND_PLAYBACK_REQUESTED', message: 'Requested' },
      { timestamp: 1728630000200, level: 'INFO', category: 'BACKGROUND', code: 'BACKGROUND_AUDIO_ONLY_ENTER', message: 'Audio only' },
      { timestamp: 1728630000300, level: 'INFO', category: 'BACKGROUND', code: 'BACKGROUND_AUDIO_FOCUS_GAIN', message: 'Focus gain' },
      { timestamp: 1728630000400, level: 'INFO', category: 'BACKGROUND', code: 'BACKGROUND_SERVICE_STARTED', message: 'Service started' },
      { timestamp: 1728630000500, level: 'WARN', category: 'BACKGROUND', code: 'BACKGROUND_AUDIO_ONLY_EXIT', message: 'Premature exit' },
    ],
  };

  const storage = new ReportStorage();
  await storage.saveReport(fixtureD44EF7);
  await storage.saveReport(fixture870711);
  await storage.saveReport(fixture0B2418);

  const getD = await storage.getReportById('VOX-A-D44EF7');
  const get8 = await storage.getReportById('VOX-A-870711');
  const get0 = await storage.getReportById('VOX-A-0B2418');

  // Verify strict separation of device identity
  assert.equal(getD.payload.manufacturer, 'TCL');
  assert.equal(getD.payload.model, 'Smart TV Pro');
  assert.equal(getD.payload.sdkInt, 34);

  assert.equal(get8.payload.manufacturer, 'VS');
  assert.equal(get8.payload.model, 'KM1');
  assert.equal(get8.payload.sdkInt, 29);

  assert.equal(get0.payload.manufacturer, 'TUVIO');
  assert.equal(get0.payload.model, 'TD50UFBSV1');
  assert.equal(get0.payload.sdkInt, 30);
  assert.equal(get0.payload.appVersion, '32.56-vox.8.1');

  // Verify status update with FIXED_PENDING_VALIDATION alias
  const updated = await storage.updateReport('VOX-A-0B2418', {
    status: 'FIXED_PENDING_VALIDATION',
    developerNotes: 'Patch #31: исправление подготовлено, требуется подтверждение на TUVIO/API30',
  });
  assert.equal(updated.status, 'FIXED_PENDING_VERIFICATION');
});

test('StructuredEvents never inherit report-level errorCategory into unrelated events', () => {
  const otaReport = {
    reportId: 'VOX-TEST-OTA-POLLUTION',
    errorCategory: 'OTA',
    triggerEvent: 'OTA_RATE_LIMIT',
    safeRecentEvents: [
      { timestamp: 1000, level: 'INFO', category: 'UI', code: 'HUD_SHOWN', message: 'HUD visible' },
      { timestamp: 2000, level: 'INFO', category: 'BACKGROUND', code: 'BACKGROUND_MEDIASESSION_ACTIVE', message: 'MediaSession on' },
      { timestamp: 3000, level: 'WARN', category: 'TRANSLATION', code: 'TRANSLATION_CACHED_URL_EXPIRED', message: 'Url expired' },
      { timestamp: 4000, level: 'ERROR', category: 'PLAYER', code: 'PLAYER_HTTP_ERROR', message: '403 error' },
      { timestamp: 5000, level: 'ERROR', category: 'OTA', code: 'OTA_RATE_LIMIT', message: 'Rate limit 403' },
    ]
  };

  const events = structuredEvents(otaReport);
  assert.equal(events.length, 5);

  // UI INFO event: category must be UI, errorCategory must NOT be OTA
  assert.equal(events[0].category, 'UI');
  assert.equal(events[0].errorCategory, null);

  // BACKGROUND INFO event: category must be BACKGROUND, errorCategory must NOT be OTA
  assert.equal(events[1].category, 'BACKGROUND');
  assert.equal(events[1].errorCategory, null);

  // TRANSLATION WARN event: category must be TRANSLATION, errorCategory must NOT be OTA
  assert.equal(events[2].category, 'TRANSLATION');
  assert.notEqual(events[2].errorCategory, 'OTA');

  // PLAYER ERROR event: category must be PLAYER, errorCategory must be PLAYER_HTTP_ERROR
  assert.equal(events[3].category, 'PLAYER');
  assert.equal(events[3].errorCategory, 'PLAYER_HTTP_ERROR');

  // OTA ERROR event: category must be OTA, errorCategory must be OTA_RATE_LIMIT
  assert.equal(events[4].category, 'OTA');
  assert.equal(events[4].errorCategory, 'OTA_RATE_LIMIT');
});

