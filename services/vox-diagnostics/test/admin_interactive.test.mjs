import assert from 'node:assert/strict';
import test from 'node:test';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { handleRequest } from '../src/diagnostics.mjs';
import { ADMIN_APP_JS } from '../src/admin_client.mjs';
import { ReportStorage } from '../src/storage.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const adminAppPath = path.resolve(__dirname, '../src/admin_app.js');

function createRequest(url, options = {}) {
  return new Request(url, {
    method: options.method ?? 'GET',
    headers: {
      'Content-Type': options.contentType ?? 'application/json',
      ...(options.headers || {}),
    },
    body: options.body ? (typeof options.body === 'string' ? options.body : JSON.stringify(options.body)) : undefined,
  });
}

const mockEnv = {
  ADMIN_SECRET: 'test_super_secret_key_98765',
};

test('1. Syntax validation of src/admin_app.js using node --check and node:vm', () => {
  // Check via node --check
  execSync(`node --check "${adminAppPath}"`, { stdio: 'pipe' });

  // Check via node:vm Script parsing
  assert.doesNotThrow(() => {
    new vm.Script(ADMIN_APP_JS);
  }, 'ADMIN_APP_JS must be valid ECMAScript without syntax errors');

  // Verify file content matches exported constant
  const fileContent = fs.readFileSync(adminAppPath, 'utf-8');
  assert.equal(fileContent, ADMIN_APP_JS, 'src/admin_app.js must match ADMIN_APP_JS in admin_client.mjs');
});

test('2. GET /admin unauthorized returns 401 with login prompt', async () => {
  const req = createRequest('https://diagnostics.example.com/admin');
  const res = await handleRequest(req, mockEnv);
  assert.equal(res.status, 401);
  assert.equal(res.headers.get('www-authenticate'), 'Bearer realm="VOX-Admin"');
  const html = await res.text();
  assert.match(html, /SmartTube VOX Admin/);
  assert.match(html, /<form method="GET" action="\/admin">/);
  assert.doesNotMatch(html, new RegExp(mockEnv.ADMIN_SECRET));
});

test('3. GET /admin authorized returns 200 with strict CSP and external script link', async () => {
  const storage = new ReportStorage();
  await storage.saveReport({
    reportId: 'VOX-A-SONY01',
    schema: 'vox-diagnostic-report-v2',
    timestamp: Date.now(),
    appVersion: '32.56-vox.7-dev',
    appVersionCode: 2446007,
    platform: 'Android TV',
    manufacturer: 'Sony',
    model: 'BRAVIA-4K',
    errorCategory: 'PLAYBACK',
    safeRecentEvents: [],
  });

  const req = createRequest('https://diagnostics.example.com/admin', {
    headers: { Authorization: `Bearer ${mockEnv.ADMIN_SECRET}` },
  });
  const res = await handleRequest(req, mockEnv, storage);
  assert.equal(res.status, 200);

  const cspHeader = res.headers.get('content-security-policy') || '';
  const scriptMatch = cspHeader.match(/script-src\s+([^;]+)/);
  assert.ok(scriptMatch, 'script-src directive must be present');
  assert.ok(!scriptMatch[1].includes('unsafe-inline'), 'script-src must NOT contain unsafe-inline');
  assert.ok(!scriptMatch[1].includes('unsafe-eval'), 'script-src must NOT contain unsafe-eval');
  assert.ok(scriptMatch[1].includes("'self'"), "script-src must contain 'self'");

  const html = await res.text();
  // Must link external script
  assert.match(html, /<script src="\/admin\/app\.js" defer><\/script>/);
  // Must not have inline <script> blocks
  assert.doesNotMatch(html, /<script>(?!<\/script>)/);

  // Must have required semantic interactive markup
  assert.match(html, /id="refreshButton"/);
  assert.match(html, /data-tab="reports"/);
  assert.match(html, /data-tab="issues"/);
  assert.match(html, /data-tab="metrics"/);
  assert.match(html, /id="searchInput"/);
  assert.match(html, /id="filterPlatform"/);
  assert.match(html, /id="filterStatus"/);
  assert.match(html, /id="filterPurpose"/);
  assert.match(html, /id="filterCategory"/);
  assert.match(html, /id="modalOverlay"/);
  assert.match(html, /data-modal-tab="overview"/);
  assert.match(html, /data-modal-tab="codecs"/);
  assert.match(html, /data-modal-tab="timeline"/);
  assert.match(html, /data-modal-tab="ops"/);
  assert.match(html, /data-modal-tab="raw"/);

  // Zero inline event handlers in rendered table
  assert.doesNotMatch(html, /onclick=/);
  assert.doesNotMatch(html, /onkeyup=/);
  assert.doesNotMatch(html, /onchange=/);

  // No secret exposure
  assert.doesNotMatch(html, new RegExp(mockEnv.ADMIN_SECRET));
});

test('4. GET /admin/app.js returns 200 application/javascript without secrets', async () => {
  const req = createRequest('https://diagnostics.example.com/admin/app.js');
  const res = await handleRequest(req, mockEnv);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'application/javascript; charset=utf-8');
  assert.equal(res.headers.get('x-content-type-options'), 'nosniff');

  const js = await res.text();
  assert.ok(js.length > 5000);
  assert.match(js, /initAdminApp/);
  assert.match(js, /switchMainTab/);
  assert.match(js, /switchModalTab/);
  assert.match(js, /filterReportsTable/);
  assert.match(js, /openReport/);
  assert.match(js, /refreshDashboard/);
  assert.match(js, /saveReportOperations/);
  assert.match(js, /deleteCurrentReport/);
  assert.match(js, /copyGitHubMarkdown/);
  assert.match(js, /downloadJsonFile/);
  assert.match(js, /copyRawJson/);

  // Absolutely NO secret exposed in script
  assert.doesNotMatch(js, new RegExp(mockEnv.ADMIN_SECRET));
  assert.doesNotMatch(js, /ADMIN_SECRET/);
});

test('5. Admin API operations and detail inspection', async () => {
  const storage = new ReportStorage();
  const reportId = 'VOX-A-TCL999';
  const saved = await storage.saveReport({
    reportId,
    schema: 'vox-diagnostic-report-v2',
    timestamp: Date.now(),
    appVersion: '32.56-vox.7-dev',
    appVersionCode: 2446007,
    platform: 'Android TV',
    manufacturer: 'TCL',
    model: 'BeyondTV',
    errorCategory: 'DOWNLOAD',
    safeRecentEvents: [
      {
        timestamp: Date.now(),
        level: 'ERROR',
        category: 'DOWNLOAD',
        code: 'AUDIO_STREAM_TIMEOUT',
        message: 'Timeout downloading audio stream',
        context: { chunk: 4 },
      },
    ],
  });

  // GET /v1/admin/reports/:id unauthorized -> 401
  const unauthDetail = createRequest(`https://diagnostics.example.com/v1/admin/reports/${encodeURIComponent(reportId)}`);
  const unauthDetailRes = await handleRequest(unauthDetail, mockEnv, storage);
  assert.equal(unauthDetailRes.status, 401);

  // GET /v1/admin/reports/:id authorized -> 200
  const authDetail = createRequest(`https://diagnostics.example.com/v1/admin/reports/${encodeURIComponent(reportId)}`, {
    headers: { Authorization: `Bearer ${mockEnv.ADMIN_SECRET}` },
  });
  const authDetailRes = await handleRequest(authDetail, mockEnv, storage);
  assert.equal(authDetailRes.status, 200);
  const detailJson = await authDetailRes.json();
  assert.equal(detailJson.report_id, reportId);
  assert.equal(detailJson.platform, 'Android TV');
  assert.equal(detailJson.error_category, 'DOWNLOAD');
  assert.equal(detailJson.payload.manufacturer, 'TCL');
  assert.equal(detailJson.payload.safeRecentEvents.length, 1);

  // PATCH /v1/admin/reports/:id unauthorized -> 401
  const unauthPatch = createRequest(`https://diagnostics.example.com/v1/admin/reports/${encodeURIComponent(reportId)}`, {
    method: 'PATCH',
    body: { status: 'KNOWN_ISSUE', developerNotes: 'Fix queued for vot.8' },
  });
  const unauthPatchRes = await handleRequest(unauthPatch, mockEnv, storage);
  assert.equal(unauthPatchRes.status, 401);

  // PATCH /v1/admin/reports/:id authorized -> 200
  const authPatch = createRequest(`https://diagnostics.example.com/v1/admin/reports/${encodeURIComponent(reportId)}`, {
    method: 'PATCH',
    headers: { Authorization: `Bearer ${mockEnv.ADMIN_SECRET}` },
    body: { status: 'KNOWN_ISSUE', developerNotes: 'Fix queued for vot.8' },
  });
  const authPatchRes = await handleRequest(authPatch, mockEnv, storage);
  assert.equal(authPatchRes.status, 200);
  const patchJson = await authPatchRes.json();
  assert.equal(patchJson.status, 'ok');
  assert.equal(patchJson.report.status, 'KNOWN_ISSUE');
  assert.equal(patchJson.report.developer_notes, 'Fix queued for vot.8');

  // DELETE /v1/admin/reports/:id unauthorized -> 401
  const unauthDelete = createRequest(`https://diagnostics.example.com/v1/admin/reports/${encodeURIComponent(reportId)}`, {
    method: 'DELETE',
  });
  const unauthDeleteRes = await handleRequest(unauthDelete, mockEnv, storage);
  assert.equal(unauthDeleteRes.status, 401);

  // DELETE /v1/admin/reports/:id authorized -> 200
  const authDelete = createRequest(`https://diagnostics.example.com/v1/admin/reports/${encodeURIComponent(reportId)}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${mockEnv.ADMIN_SECRET}` },
  });
  const authDeleteRes = await handleRequest(authDelete, mockEnv, storage);
  assert.equal(authDeleteRes.status, 200);
  const deleteJson = await authDeleteRes.json();
  assert.equal(deleteJson.status, 'deleted');
  assert.equal(deleteJson.reportId, reportId);
});
