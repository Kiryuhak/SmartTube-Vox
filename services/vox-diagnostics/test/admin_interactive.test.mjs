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

test('6. Individual download endpoint & privacy verification (GET /v1/admin/reports/:id/download)', async () => {
  const storage = new ReportStorage();
  const reportId = 'VOX-A-PRIV01';
  await storage.saveReport({
    reportId,
    schema: 'vox-diagnostic-report-v2',
    timestamp: Date.now(),
    appVersion: '32.56-vox.7-dev',
    platform: 'Android TV',
    manufacturer: 'Philips',
    model: 'Ambilight-55',
    errorCategory: 'NETWORK',
    errorSignature: 'NETWORK_SOCKET_TIMEOUT',
    safeRecentEvents: [
      {
        timestamp: Date.now(),
        level: 'WARNING',
        category: 'NETWORK',
        code: 'SOCKET_TIMEOUT',
        message: 'Socket timed out while requesting manifest',
      },
    ],
  });

  // 1. Unauthenticated download -> 401
  const unauthReq = createRequest(`https://diagnostics.example.com/v1/admin/reports/${encodeURIComponent(reportId)}/download`);
  const unauthRes = await handleRequest(unauthReq, mockEnv, storage);
  assert.equal(unauthRes.status, 401);

  // 2. Authenticated download -> 200
  const authReq = createRequest(`https://diagnostics.example.com/v1/admin/reports/${encodeURIComponent(reportId)}/download`, {
    headers: { Authorization: `Bearer ${mockEnv.ADMIN_SECRET}` },
  });
  const authRes = await handleRequest(authReq, mockEnv, storage);
  assert.equal(authRes.status, 200);
  assert.equal(authRes.headers.get('content-type'), 'application/json; charset=utf-8');
  assert.equal(authRes.headers.get('content-disposition'), `attachment; filename="vox-diagnostic-${reportId}.json"`);

  const rawJsonText = await authRes.text();
  const downloadData = JSON.parse(rawJsonText);

  // Validate expected properties
  assert.equal(downloadData.reportId, reportId);
  assert.equal(downloadData.platform, 'Android TV');
  assert.equal(downloadData.errorCategory, 'NETWORK');
  assert.equal(downloadData.errorSignature, 'NETWORK_SOCKET_TIMEOUT');
  assert.equal(downloadData.status, 'NEW');
  assert.ok(downloadData.device);
  assert.equal(downloadData.device.manufacturer, 'Philips');
  assert.equal(downloadData.device.model, 'Ambilight-55');
  assert.ok(Array.isArray(downloadData.safeRecentEvents));
  assert.equal(downloadData.safeRecentEvents.length, 1);

  // Mandatory Privacy Check: serialize and scan for forbidden keywords
  const privacyBanned = [
    'authorization',
    'cookie',
    'access_token',
    'refresh_token',
    'password',
    'admin_secret',
    'private_key',
    'client_secret',
    'signed_url',
    mockEnv.ADMIN_SECRET,
  ];

  for (const banned of privacyBanned) {
    assert.ok(
      !rawJsonText.toLowerCase().includes(banned.toLowerCase()),
      `Downloaded JSON export MUST NOT contain sensitive term: "${banned}"`
    );
  }
});

test('7. Incident workflow statuses and developer notes lifecycle', async () => {
  const storage = new ReportStorage();
  const reportId = 'VOX-A-FLOW77';
  await storage.saveReport({
    reportId,
    schema: 'vox-diagnostic-report-v2',
    timestamp: Date.now(),
    appVersion: '32.56-vox.7-dev',
    platform: 'Android TV',
    errorCategory: 'TRANSLATION',
    errorSignature: 'TRANSLATION_DESYNC_WARNING',
  });

  const authHeaders = { Authorization: `Bearer ${mockEnv.ADMIN_SECRET}` };

  // Status step 1: NEW -> IN_PROGRESS with note
  const patch1 = createRequest(`https://diagnostics.example.com/v1/admin/reports/${encodeURIComponent(reportId)}`, {
    method: 'PATCH',
    headers: authHeaders,
    body: { status: 'IN_PROGRESS', developerNotes: 'Исправляется в Patch #35' },
  });
  const res1 = await handleRequest(patch1, mockEnv, storage);
  assert.equal(res1.status, 200);
  const data1 = (await res1.json()).report;
  assert.equal(data1.status, 'IN_PROGRESS');
  assert.equal(data1.developer_notes, 'Исправляется в Patch #35');

  // Status step 2: IN_PROGRESS -> RESOLVED with updated note
  const patch2 = createRequest(`https://diagnostics.example.com/v1/admin/reports/${encodeURIComponent(reportId)}`, {
    method: 'PATCH',
    headers: authHeaders,
    body: { status: 'RESOLVED', developerNotes: 'Исправлено и проверено в Patch #35' },
  });
  const res2 = await handleRequest(patch2, mockEnv, storage);
  assert.equal(res2.status, 200);
  const data2 = (await res2.json()).report;
  assert.equal(data2.status, 'RESOLVED');
  assert.equal(data2.developer_notes, 'Исправлено и проверено в Patch #35');

  // Status step 3: Backward-compatibility check for legacy 'reviewed' / 'triaged'
  const patch3 = createRequest(`https://diagnostics.example.com/v1/admin/reports/${encodeURIComponent(reportId)}`, {
    method: 'PATCH',
    headers: authHeaders,
    body: { status: 'reviewed' },
  });
  const res3 = await handleRequest(patch3, mockEnv, storage);
  assert.equal(res3.status, 200);
  const data3 = (await res3.json()).report;
  assert.equal(data3.status, 'IN_PROGRESS'); // Normalized to IN_PROGRESS
});

test('8. Filter by status, common issues grouping, and metrics breakdown', async () => {
  const storage = new ReportStorage();
  // Seed reports with different statuses
  await storage.saveReport({
    reportId: 'VOX-A-01',
    errorSignature: 'SIG_A',
    status: 'NEW',
    platform: 'Android TV',
  });
  await storage.saveReport({
    reportId: 'VOX-A-02',
    errorSignature: 'SIG_A',
    status: 'IN_PROGRESS',
    platform: 'Android TV',
  });
  await storage.saveReport({
    reportId: 'VOX-A-03',
    errorSignature: 'SIG_A',
    status: 'RESOLVED',
    platform: 'Samsung Tizen',
  });
  await storage.saveReport({
    reportId: 'VOX-A-04',
    errorSignature: 'SIG_B',
    status: 'KNOWN_ISSUE',
    platform: 'Android TV',
  });
  await storage.saveReport({
    reportId: 'VOX-A-05',
    errorSignature: 'SIG_B',
    status: 'IGNORED_TEST',
    platform: 'Android TV',
  });

  const authHeaders = { Authorization: `Bearer ${mockEnv.ADMIN_SECRET}` };

  // 1. Filter by status = RESOLVED
  const filterReq = createRequest('https://diagnostics.example.com/v1/admin/reports?status=RESOLVED', {
    headers: authHeaders,
  });
  const filterRes = await handleRequest(filterReq, mockEnv, storage);
  assert.equal(filterRes.status, 200);
  const filterList = (await filterRes.json()).reports;
  assert.equal(filterList.length, 1);
  assert.equal(filterList[0].report_id, 'VOX-A-03');

  // 2. Common issues grouping breakdown
  const issuesReq = createRequest('https://diagnostics.example.com/v1/admin/issues', {
    headers: authHeaders,
  });
  const issuesRes = await handleRequest(issuesReq, mockEnv, storage);
  assert.equal(issuesRes.status, 200);
  const issuesList = (await issuesRes.json()).issues;
  assert.equal(issuesList.length, 2);
  const sigA = issuesList.find(i => i.error_signature === 'SIG_A');
  assert.ok(sigA);
  assert.equal(sigA.count, 3);
  assert.equal(sigA.new_count, 1);
  assert.equal(sigA.in_progress_count, 1);
  assert.equal(sigA.resolved_count, 1);

  // 3. Stats & Metrics breakdown
  const statsReq = createRequest('https://diagnostics.example.com/v1/admin/stats', {
    headers: authHeaders,
  });
  const statsRes = await handleRequest(statsReq, mockEnv, storage);
  assert.equal(statsRes.status, 200);
  const stats = await statsRes.json();
  assert.equal(stats.totalReports, 5);
  assert.equal(stats.newCount, 1);
  assert.equal(stats.inProgressCount, 1);
  assert.equal(stats.resolvedCount, 1);
  assert.equal(stats.knownIssueCount, 1);
  assert.equal(stats.ignoredTestCount, 1);
  assert.ok(Array.isArray(stats.statusBreakdown));
  assert.equal(stats.statusBreakdown.length, 5);
});

test('9. Safe Purge (DELETE /v1/admin/reports)', async () => {
  const storage = new ReportStorage();
  await storage.saveReport({ reportId: 'VOX-DEL-1', schema: 'vox-diagnostic-report-v2' });
  await storage.saveReport({ reportId: 'VOX-DEL-2', schema: 'vox-diagnostic-report-v2' });

  // 1. Unauthenticated purge -> 401
  const unauthPurge = createRequest('https://diagnostics.example.com/v1/admin/reports', {
    method: 'DELETE',
  });
  const unauthPurgeRes = await handleRequest(unauthPurge, mockEnv, storage);
  assert.equal(unauthPurgeRes.status, 401);

  // 2. Authenticated purge -> 200
  const authPurge = createRequest('https://diagnostics.example.com/v1/admin/reports', {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${mockEnv.ADMIN_SECRET}` },
  });
  const authPurgeRes = await handleRequest(authPurge, mockEnv, storage);
  assert.equal(authPurgeRes.status, 200);
  const purgeJson = await authPurgeRes.json();
  assert.equal(purgeJson.ok, true);
  assert.equal(purgeJson.deleted, 2);

  // 3. Verify storage is now empty
  const afterList = await storage.listReports();
  assert.equal(afterList.length, 0);

  // 4. Schema survives: can save report after purge
  const newSave = await storage.saveReport({ reportId: 'VOX-DEL-SURVIVE', schema: 'vox-diagnostic-report-v2' });
  assert.equal(newSave.reportId, 'VOX-DEL-SURVIVE');
  const checkReport = await storage.getReportById('VOX-DEL-SURVIVE');
  assert.ok(checkReport);
});
