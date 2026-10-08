import assert from 'node:assert/strict';
import test from 'node:test';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { generateErrorSignature, handleRequest, prepareReportDownloadJson } from '../src/diagnostics.mjs';
import { ReportStorage } from '../src/storage.mjs';
import { classifyReport, compareVoxVersions, safeContext, technicalSummary } from '../src/triage.mjs';

const secret = 'fixture-only-secret';
const env = { ADMIN_SECRET: secret };
const base = 'https://diagnostics.example.com';

async function api(storage, path, method = 'GET', body, auth = true) {
  const request = new Request(base + path, {
    method,
    headers: { ...(auth ? { authorization: `Bearer ${secret}` } : {}), ...(body ? { 'content-type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  return handleRequest(request, env, storage);
}

const report = (id, version = '32.56-vox.7', timestamp = Date.now()) => ({
  reportId: id, schema: 'vox-diagnostic-report-v2', timestamp, appVersion: version,
  platform: 'Android TV', manufacturer: 'TCL', model: 'TV', errorCategory: 'DOWNLOAD',
  errorSignature: 'DOWNLOAD|PACKAGING|MUX_FAILED',
  safeRecentEvents: [{ timestamp, category: 'DOWNLOAD', subsystem: 'DOWNLOAD', stage: 'PACKAGING', event: 'MUX_FAILED', severity: 'ERROR', safeContext: { retryCount: 2, token: 'must-not-appear' } }],
});

test('POST login uses a signed session, rejects secret in URL and forged headers', async () => {
  assert.equal((await api(new ReportStorage(), '/admin?token=' + secret, 'GET', null, false)).status, 401);
  const forged = new Request(base + '/v1/admin/stats', { headers: { 'cf-access-authenticated-user-email': 'admin@example.com' } });
  assert.equal((await handleRequest(forged, env, new ReportStorage())).status, 401);
  const login = await handleRequest(new Request(base + '/admin/session', { method: 'POST', body: new URLSearchParams({ secret }) }), env, new ReportStorage());
  assert.equal(login.status, 303);
  const cookie = login.headers.get('set-cookie');
  assert.match(cookie, /vox_admin_session=/);
  assert.doesNotMatch(cookie, /fixture-only-secret/);
  assert.equal((await handleRequest(new Request(base + '/v1/admin/stats', { headers: { cookie } }), env, new ReportStorage())).status, 200);
  const wrong = await handleRequest(new Request(base + '/admin/session', { method: 'POST', body: new URLSearchParams({ secret: 'wrong' }) }), env, new ReportStorage());
  assert.equal(wrong.status, 401);
});

test('status, notes, issue regression, changes cursor and safe structured events', async () => {
  const storage = new ReportStorage();
  await storage.saveReport(report('VOX-A-FIRST'));
  await storage.saveReport(report('VOX-A-SECOND'));
  assert.equal((await api(storage, '/v1/admin/reports/VOX-A-FIRST', 'PATCH', { status: 'BOGUS' })).status, 400);
  assert.equal((await api(storage, '/v1/admin/reports/VOX-A-FIRST', 'PATCH', { status: 'IN_PROGRESS', reason: 'triage' })).status, 200);
  assert.equal((await api(storage, '/v1/admin/reports/VOX-A-FIRST/notes', 'POST', { note: '<script>alert(1)</script>' })).status, 200);
  const detail = await (await api(storage, '/v1/admin/reports/VOX-A-FIRST')).json();
  assert.equal(detail.statusHistory[0].fromStatus, 'NEW');
  assert.equal(detail.statusHistory[0].toStatus, 'IN_PROGRESS');
  assert.equal(detail.notes[0].note, '<script>alert(1)</script>');
  assert.equal(detail.relatedReportsCount, 1);
  assert.deepEqual(detail.payload.structuredEvents[0].safeContext, { retryCount: 2 });
  const issues = await (await api(storage, '/api/admin/issues')).json();
  assert.equal(issues.issues[0].count, 2);
  const issuePath = '/api/admin/issues/' + encodeURIComponent('DOWNLOAD|PACKAGING|MUX_FAILED');
  assert.equal((await api(storage, issuePath, 'PATCH', { status: 'RESOLVED', fixedInVersion: '32.56-vox.8', fixPatch: 'Patch #12', fixCommit: '60075019' })).status, 200);
  await storage.saveReport(report('VOX-A-OLDER', '32.56-vox.7'));
  assert.equal((await storage.getIssue('DOWNLOAD|PACKAGING|MUX_FAILED')).issue_status, 'RESOLVED');
  await storage.saveReport(report('VOX-A-REGRESSED', '32.56-vox.8'));
  const reopened = await storage.getIssue('DOWNLOAD|PACKAGING|MUX_FAILED');
  assert.equal(reopened.issue_status, 'IN_PROGRESS');
  assert.ok(reopened.reopened_at > 0);
  const changes = await (await api(storage, '/api/admin/reports/changes?cursor=0&limit=2')).json();
  assert.equal(changes.changes.length, 2);
  const later = await (await api(storage, '/api/admin/reports/changes?cursor=' + changes.nextCursor)).json();
  assert.ok(later.changes.every(change => change.id > changes.nextCursor));
  const page1 = await (await api(storage, '/api/admin/reports?limit=2&sort=newest')).json();
  assert.equal(page1.reports.length, 2);
  assert.ok(page1.nextCursor);
  const page2 = await (await api(storage, '/api/admin/reports?limit=2&cursor=' + encodeURIComponent(page1.nextCursor))).json();
  assert.equal(page2.reports.length, 2);
  assert.notEqual(page1.reports[0].report_id, page2.reports[0].report_id);
  assert.equal((await api(storage, '/api/admin/reports?cursor=bad')).status, 400);
  assert.equal((await api(storage, '/api/admin/reports?hasDuplicates=true')).status, 200);
  const oldest = await (await api(storage, '/api/admin/reports?limit=2&sort=oldest')).json();
  assert.equal(oldest.reports.length, 2);
  assert.match(oldest.nextCursor, /^offset:/);
  const oldestNext = await (await api(storage, '/api/admin/reports?limit=2&sort=oldest&cursor=' + encodeURIComponent(oldest.nextCursor))).json();
  assert.equal(oldestNext.reports.length, 2);
  assert.notEqual(oldest.reports[0].report_id, oldestNext.reports[0].report_id);
});

test('version comparison, context allowlist and recursive export redaction', () => {
  assert.equal(compareVoxVersions('32.56-vox.7', '32.56-vox.8'), -1);
  assert.equal(compareVoxVersions('32.56-vox.8', '32.56-vox.8'), 0);
  assert.equal(compareVoxVersions('32.56-vox.8', '32.56-vox.8-rc1'), 1);
  assert.deepEqual(safeContext({ retryCount: 3, token: 'secret', groupName: 'private' }), { retryCount: 3 });
  assert.equal(classifyReport(report('VOX-A-CLASSIFY')).severity, 'HIGH');
  const signatureInput = report('VOX-A-SIGNATURE');
  delete signatureInput.errorSignature;
  assert.equal(generateErrorSignature(signatureInput), 'DOWNLOAD|DOWNLOAD|PACKAGING|MUX_FAILED|NONE|UNKNOWN');
  const technical = technicalSummary({
    downloadDiagnostics: { queueLength: 2, retryCount: 1, lastStage: 'PACKAGING', packagingStarted: true, downloadPerformance: { totalBytes: 1200, averageBytesPerSecond: 300, stallCount: 1 } },
    livePlayback: { selectedCodec: 'AV1', rebufferCount: 2 },
    channelGroupState: { groupCount: 3, groupName: 'private' },
  });
  assert.equal(technical.download.stage, 'PACKAGING');
  assert.equal(technical.download.bytes, 1200);
  assert.equal(technical.player.selectedCodec, 'AV1');
  assert.equal(technical.channelGroups.groupCount, 3);
  assert.doesNotMatch(JSON.stringify(technical), /private/);
  const safe = prepareReportDownloadJson({ report_id: 'VOX-A-X', payload: { nested: { token: 'secret', message: 'https://example.com/?token=secret' } } });
  assert.doesNotMatch(JSON.stringify(safe), /secret/);
});

test('additive D1 migration preserves four resolved reports and notes', async () => {
  const db = new DatabaseSync(':memory:');
  for (const name of ['0001_init.sql', '0002_operations.sql']) db.exec(readFileSync(fileURLToPath(new URL('../migrations/' + name, import.meta.url)), 'utf8'));
  const ids = ['VOX-A-AD9BF2', 'VOX-A-CECBBE', 'VOX-DA7404', 'VOX-A-DF6567'];
  for (const id of ids) db.prepare('INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, payload_json, expires_at, status, developer_notes, report_purpose, error_signature) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
    .run(id, Date.now(), 'vox-diagnostic-report-v2', '32.56-vox.7', 'Android TV', 'TCL TV', JSON.stringify(report(id)), Date.now() + 86400000, 'RESOLVED', 'Существующая заметка', 'USER', 'DOWNLOAD|PACKAGING|MUX_FAILED');
  db.exec(readFileSync(fileURLToPath(new URL('../migrations/0003_admin_22.sql', import.meta.url)), 'utf8'));
  assert.equal(db.prepare('SELECT COUNT(*) AS count FROM reports WHERE status = ? AND developer_notes = ?').get('RESOLVED', 'Существующая заметка').count, 4);
  for (const id of ids) assert.equal(db.prepare('SELECT status FROM reports WHERE report_id = ?').get(id).status, 'RESOLVED');
  db.close();
});

test('D1 query path uses migrated schema for listing, history, issues and changes', async () => {
  const sqlite = new DatabaseSync(':memory:');
  for (const name of ['0001_init.sql', '0002_operations.sql', '0003_admin_22.sql'])
    sqlite.exec(readFileSync(fileURLToPath(new URL('../migrations/' + name, import.meta.url)), 'utf8'));
  const d1 = {
    prepare(sql) {
      const statement = sqlite.prepare(sql);
      const wrapped = {
        args: [],
        bind(...args) { this.args = args; return this; },
        async run() { return { meta: { changes: statement.run(...this.args).changes } }; },
        async first() { return statement.get(...this.args) || null; },
        async all() { return { results: statement.all(...this.args) }; },
      };
      return wrapped;
    },
    async batch(statements) {
      sqlite.exec('BEGIN');
      try { const results = []; for (const statement of statements) results.push(await statement.run()); sqlite.exec('COMMIT'); return results; }
      catch (error) { sqlite.exec('ROLLBACK'); throw error; }
    },
  };
  const storage = new ReportStorage(d1);
  await storage.saveReport(report('VOX-A-D1TEST'));
  await storage.saveReport(report('VOX-A-D1COPY'));
  assert.equal((await storage.listReports({ search: 'D1TEST' })).length, 1);
  await storage.updateReport('VOX-A-D1TEST', { status: 'IN_PROGRESS', developerNotes: 'Проверено' });
  const detail = await storage.getReportById('VOX-A-D1TEST');
  assert.equal(detail.statusHistory[0].toStatus, 'IN_PROGRESS');
  assert.equal(detail.notes[0].note, 'Проверено');
  assert.equal(detail.relatedReportsCount, 1);
  await storage.updateIssue('DOWNLOAD|PACKAGING|MUX_FAILED', { status: 'RESOLVED', fixedInVersion: '32.56-vox.8' });
  assert.equal((await storage.getGroupedIssues({}))[0].issue_status, 'RESOLVED');
  assert.ok((await storage.listChanges(0, 10)).changes.length >= 3);
  sqlite.close();
});
