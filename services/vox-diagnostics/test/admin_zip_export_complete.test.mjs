import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { buildDiagnosticsZip, ACTIVE_EXPORT_STATUSES, FINAL_EXCLUDED_STATUSES } from '../src/export.mjs';
import { handleRequest } from '../src/diagnostics.mjs';
import { renderAdminHtml } from '../src/admin.mjs';
import { ADMIN_APP_JS } from '../src/admin_client.mjs';
import { ReportStorage } from '../src/storage.mjs';

// Fast in-memory ZIP extractor for verification
function parseZipEntries(buffer) {
  const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  const entries = [];
  let offset = 0;

  while (offset + 30 <= buffer.length) {
    const sig = view.getUint32(offset, true);
    if (sig !== 0x04034b50) break; // Local file header signature
    const compMethod = view.getUint16(offset + 8, true);
    const crc = view.getUint32(offset + 14, true);
    const compSize = view.getUint32(offset + 18, true);
    const uncompSize = view.getUint32(offset + 22, true);
    const pathLen = view.getUint16(offset + 26, true);
    const extraLen = view.getUint16(offset + 28, true);
    offset += 30;

    const pathBytes = buffer.slice(offset, offset + pathLen);
    const path = new TextDecoder().decode(pathBytes);
    offset += pathLen + extraLen;

    const data = buffer.slice(offset, offset + uncompSize);
    offset += uncompSize;

    entries.push({ path, data, text: new TextDecoder().decode(data) });
  }
  return entries;
}

// Generate reports array with controlled statuses and timestamps
function generateTestReports(count, { defaultStatus = 'NEW', startTimestamp = 1000000 } = {}) {
  const reports = [];
  for (let i = 0; i < count; i++) {
    const reportId = `VOX-REP-${String(i).padStart(6, '0')}`;
    const createdAt = startTimestamp + i * 10;
    reports.push({
      report_id: reportId,
      created_at: createdAt,
      status: typeof defaultStatus === 'function' ? defaultStatus(i) : defaultStatus,
      payload_json: JSON.stringify({
        reportId,
        createdAt,
        appVersion: '32.56-vox.9.2',
        platform: 'Android TV',
        model: 'UGOOS SK1',
        manufacturer: 'UGOOS',
        deviceTier: 'HIGH',
        errorCategory: 'PLAYER',
        errorSignature: 'PLAYER|SOURCE_ERROR',
        structuredEvents: [
          { code: 'BUFFER_STALL', timestamp: createdAt, safeContext: { bufferedMs: 0 } },
          { code: 'MEDIA_ERROR', timestamp: createdAt + 5, safeContext: { errorCode: 404 } }
        ],
        safeRecentEvents: [
          { code: 'BUFFER_STALL', message: 'Stall detected' }
        ],
        playbackContext: { resolution: '3840x2160', codec: 'AV1' },
      })
    });
  }
  return reports;
}

function createMockStorage(reports = []) {
  // Sort descending by created_at, report_id as standard
  const store = reports.slice().sort((a, b) => b.created_at - a.created_at || b.report_id.localeCompare(a.report_id));
  return {
    async listReports({ limit = 100, offset = 0, cursor, until, search, errorCategory, sort = 'newest' } = {}) {
      let filtered = store.slice();
      if (until) {
        filtered = filtered.filter(r => r.created_at <= Number(until));
      }
      if (cursor) {
        const [created, id] = cursor.split(':');
        filtered = filtered.filter(r => r.created_at < Number(created) || (r.created_at === Number(created) && r.report_id < id));
      }
      if (search) {
        filtered = filtered.filter(r => JSON.stringify(r).includes(search));
      }
      if (errorCategory) {
        filtered = filtered.filter(r => (r.error_category || '').includes(errorCategory));
      }
      // Keyset cursor ignores offset
      const start = cursor ? 0 : offset;
      return filtered.slice(start, start + limit);
    },
    async getReportById(id) {
      return store.find(r => (r.report_id || r.reportId) === id) || null;
    }
  };
}

// -------------------------------------------------------------
// Requirement 7: Check count sets: [0, 1, 50, 51, 100, 182, 4999, 5000, 5001, 10000]
// -------------------------------------------------------------

test('Suite Count: 0 reports', async () => {
  const storage = createMockStorage([]);
  const res = await buildDiagnosticsZip(storage);
  assert.equal(res.totalReports, 0);
  assert.equal(res.isTruncated, false);
  const entries = parseZipEntries(res.buffer);
  assert.equal(entries.length, 2); // manifest.json, summary.json
  const manifest = JSON.parse(entries.find(e => e.path === 'manifest.json').text);
  assert.equal(manifest.totalReports, 0);
  assert.equal(manifest.exportStatus, 'COMPLETE');
});

test('Suite Count: 1 report with full JSON fidelity and no event truncation', async () => {
  const reports = generateTestReports(1, { defaultStatus: 'NEW' });
  const storage = createMockStorage(reports);
  const res = await buildDiagnosticsZip(storage);

  assert.equal(res.totalReports, 1);
  assert.equal(res.isTruncated, false);
  const entries = parseZipEntries(res.buffer);
  assert.equal(entries.length, 3); // NEW/..., manifest, summary

  const reportEntry = entries.find(e => e.path.startsWith('NEW/'));
  assert.ok(reportEntry, 'Must be in NEW folder');
  const data = JSON.parse(reportEntry.text);

  assert.equal(data.reportId, 'VOX-REP-000000');
  assert.equal(data.status, 'NEW');
  assert.equal(data.appVersion, '32.56-vox.9.2');
  assert.equal(data.device.model, 'UGOOS SK1');
  assert.equal(data.device.manufacturer, 'UGOOS');
  // Check that structuredEvents and safeRecentEvents are NOT truncated
  assert.equal(data.structuredEvents.length, 2);
  assert.equal(data.structuredEvents[0].code, 'BUFFER_STALL');
  assert.equal(data.safeRecentEvents.length, 1);
  assert.ok(data.payload, 'Full sanitized payload must be present');
});

test('Suite Count: 50 reports', async () => {
  const reports = generateTestReports(50, {
    defaultStatus: (i) => i % 2 === 0 ? 'NEW' : 'INVESTIGATING'
  });
  const storage = createMockStorage(reports);
  const res = await buildDiagnosticsZip(storage);
  assert.equal(res.totalReports, 50);
  assert.equal(res.isTruncated, false);
  assert.equal(res.manifest.newReports, 25);
  assert.equal(res.manifest.investigatingReports, 25);
});

test('Suite Count: 51 reports across multiple pages', async () => {
  const reports = generateTestReports(51, { defaultStatus: 'NEW' });
  const storage = createMockStorage(reports);
  const res = await buildDiagnosticsZip(storage);
  assert.equal(res.totalReports, 51);
  assert.equal(res.isTruncated, false);
});

test('Suite Count: 100 reports (exact single page boundary)', async () => {
  const reports = generateTestReports(100, {
    defaultStatus: (i) => i < 80 ? 'NEW' : 'INVESTIGATING'
  });
  const storage = createMockStorage(reports);
  const res = await buildDiagnosticsZip(storage);
  assert.equal(res.totalReports, 100);
  assert.equal(res.isTruncated, false);
  assert.equal(res.manifest.newReports, 80);
  assert.equal(res.manifest.investigatingReports, 20);
});

test('Suite Count: 182 reports (Production snapshot: 179 NEW, 3 INVESTIGATING)', async () => {
  const reports = generateTestReports(182, {
    defaultStatus: (i) => i < 179 ? 'NEW' : 'INVESTIGATING'
  });
  // Add 55 excluded reports to simulate realistic production state of 237 total records
  for (let i = 0; i < 25; i++) {
    reports.push({ report_id: `VOX-FAV-${i}`, created_at: 500000 + i, status: 'FIXED_AWAITING_VERIFICATION' });
  }
  for (let i = 0; i < 15; i++) {
    reports.push({ report_id: `VOX-KL-${i}`, created_at: 600000 + i, status: 'KNOWN_LIMITATION' });
  }
  for (let i = 0; i < 10; i++) {
    reports.push({ report_id: `VOX-IGN-${i}`, created_at: 700000 + i, status: 'IGNORED' });
  }
  for (let i = 0; i < 3; i++) {
    reports.push({ report_id: `VOX-RES-${i}`, created_at: 800000 + i, status: 'RESOLVED' });
  }
  for (let i = 0; i < 2; i++) {
    reports.push({ report_id: `VOX-TEST-${i}`, created_at: 900000 + i, status: 'IGNORED_TEST' });
  }

  assert.equal(reports.length, 237, 'Total reports simulated is 237');

  const storage = createMockStorage(reports);
  const res = await buildDiagnosticsZip(storage);

  assert.equal(res.totalReports, 182, 'Strictly 182 reports must be exported');
  assert.equal(res.manifest.newReports, 179);
  assert.equal(res.manifest.investigatingReports, 3);
  assert.equal(res.manifest.skippedCount, 55, 'All 55 non-active reports must be skipped');
  assert.equal(res.isTruncated, false);
});

test('Suite Count: 4999 reports (single part under limit)', async () => {
  const reports = generateTestReports(4999, { defaultStatus: 'NEW' });
  const storage = createMockStorage(reports);
  const res = await buildDiagnosticsZip(storage, { maxReports: 5000 });
  assert.equal(res.totalReports, 4999);
  assert.equal(res.isTruncated, false);
  assert.equal(res.nextCursor, null);
});

test('Suite Count: 5000 reports (exact limit boundary)', async () => {
  const reports = generateTestReports(5000, { defaultStatus: 'NEW' });
  const storage = createMockStorage(reports);
  const res = await buildDiagnosticsZip(storage, { maxReports: 5000 });
  assert.equal(res.totalReports, 5000);
  assert.equal(res.isTruncated, false, 'Exact 5000 reports must be complete, not truncated');
  assert.equal(res.nextCursor, null);
});

test('Suite Count: 5001 reports (multipart boundary: part 1 = 5000, part 2 = 1)', async () => {
  const reports = generateTestReports(5001, { defaultStatus: 'NEW' });
  const storage = createMockStorage(reports);

  // Part 1
  const part1 = await buildDiagnosticsZip(storage, { maxReports: 5000, part: 1 });
  assert.equal(part1.totalReports, 5000);
  assert.equal(part1.isTruncated, true);
  assert.ok(part1.nextCursor, 'Must supply nextCursor');
  assert.ok(part1.filename.includes('_part1.zip'), `Filename must include _part1: ${part1.filename}`);

  // Part 2 using nextCursor
  const part2 = await buildDiagnosticsZip(storage, { maxReports: 5000, part: 2, cursor: part1.nextCursor });
  assert.equal(part2.totalReports, 1);
  assert.equal(part2.isTruncated, false);
  assert.ok(part2.filename.includes('_part2.zip'), `Filename must include _part2: ${part2.filename}`);

  // Check no duplicates and complete coverage across parts
  const allIds = new Set([...part1.manifest.reportIds, ...part2.manifest.reportIds]);
  assert.equal(allIds.size, 5001, 'All 5001 reports covered with 0 duplicates');
});

test('Suite Count: 10000 reports (multipart stress: 2 parts x 5000, 0 duplicates)', async () => {
  const reports = generateTestReports(10000, {
    defaultStatus: (i) => i % 5 === 0 ? 'INVESTIGATING' : 'NEW'
  });
  const storage = createMockStorage(reports);

  // Part 1
  const part1 = await buildDiagnosticsZip(storage, { maxReports: 5000, part: 1 });
  assert.equal(part1.totalReports, 5000);
  assert.equal(part1.isTruncated, true);
  assert.ok(part1.nextCursor);

  // Part 2
  const part2 = await buildDiagnosticsZip(storage, { maxReports: 5000, part: 2, cursor: part1.nextCursor });
  assert.equal(part2.totalReports, 5000);
  assert.equal(part2.isTruncated, false);

  const combinedIds = new Set([...part1.manifest.reportIds, ...part2.manifest.reportIds]);
  assert.equal(combinedIds.size, 10000, 'Must cover exactly 10,000 unique records without duplicates');
});

// -------------------------------------------------------------
// Requirement: Mixed statuses and strict exclusion of all other statuses
// -------------------------------------------------------------

test('Strict Status Isolation: only NEW and INVESTIGATING are included, everything else excluded', async () => {
  const mixedReports = [
    { report_id: 'VOX-1', created_at: 100, status: 'NEW' },
    { report_id: 'VOX-2', created_at: 200, status: 'INVESTIGATING' },
    { report_id: 'VOX-3', created_at: 300, status: 'RESOLVED' },
    { report_id: 'VOX-4', created_at: 400, status: 'CLOSED' },
    { report_id: 'VOX-5', created_at: 500, status: 'IGNORED' },
    { report_id: 'VOX-6', created_at: 600, status: 'IGNORED_TEST' },
    { report_id: 'VOX-7', created_at: 700, status: 'KNOWN_LIMITATION' },
    { report_id: 'VOX-8', created_at: 800, status: 'WONT_FIX' },
    { report_id: 'VOX-9', created_at: 900, status: 'FIXED_AWAITING_VERIFICATION' },
    { report_id: 'VOX-10', created_at: 1000, status: 'FIXED_PENDING_VERIFICATION' },
    { report_id: 'VOX-11', created_at: 1100, status: 'ROOT_CAUSE_FOUND' },
    { report_id: 'VOX-12', created_at: 1200, status: 'UNKNOWN_RANDOM_STATUS' },
    { report_id: 'VOX-13', created_at: 1300, status: '' },
    { report_id: 'VOX-14', created_at: 1400, status: null },
  ];

  const storage = createMockStorage(mixedReports);
  const res = await buildDiagnosticsZip(storage);

  assert.equal(res.totalReports, 2);
  assert.deepEqual(res.manifest.reportIds.sort(), ['VOX-1', 'VOX-2']);
  assert.equal(res.manifest.skippedCount, 12);
});

// -------------------------------------------------------------
// Requirement: Concurrency snapshot consistency (until parameter)
// -------------------------------------------------------------

test('Concurrency snapshot: reports inserted after export start are excluded', async () => {
  const t0 = 2000000;
  // 3 initial reports up to T0
  const reports = [
    { report_id: 'VOX-PRE-1', created_at: t0 - 200, status: 'NEW' },
    { report_id: 'VOX-PRE-2', created_at: t0 - 100, status: 'NEW' },
    { report_id: 'VOX-PRE-3', created_at: t0, status: 'NEW' },
    // 2 newly incoming reports after T0
    { report_id: 'VOX-POST-1', created_at: t0 + 100, status: 'NEW' },
    { report_id: 'VOX-POST-2', created_at: t0 + 200, status: 'NEW' },
  ];

  const storage = createMockStorage(reports);
  const res = await buildDiagnosticsZip(storage, { until: t0 });

  assert.equal(res.totalReports, 3);
  assert.ok(!res.manifest.reportIds.includes('VOX-POST-1'));
  assert.ok(!res.manifest.reportIds.includes('VOX-POST-2'));
});

// -------------------------------------------------------------
// Security: Unauthorized access rejected (401 / 403)
// -------------------------------------------------------------

test('Security Gate: Unauthorized request returns 401', async () => {
  const req = new Request('http://localhost/v1/admin/export/zip');
  const res = await handleRequest(req, { ADMIN_SECRET: 'test-secret' });
  assert.equal(res.status, 401);
});

// -------------------------------------------------------------
// UI: Buttons in Admin HTML and Client JS functions
// -------------------------------------------------------------

test('Admin UI: Button «📦 Скачать все новые и в работе (ZIP)» is present and properly styled', () => {
  const html = renderAdminHtml([], { totalReports: 0 });
  // Check toolbar button
  assert.match(html, /id="downloadAllActiveZipBtn"/);
  assert.match(html, /📦 Скачать все новые и в работе \(ZIP\)/);
  // Check header button
  assert.match(html, /id="headerDownloadAllZipBtn"/);
  // Check dropdown action menu item
  assert.match(html, /id="downloadAllPendingZipBtn"/);

  // Verify client-side implementation in ADMIN_APP_JS
  assert.match(ADMIN_APP_JS, /function downloadAllActiveZip\(/);
  assert.match(ADMIN_APP_JS, /statuses: 'NEW,INVESTIGATING'/);
  assert.match(ADMIN_APP_JS, /downloadAllActiveZipBtn/);
  assert.match(ADMIN_APP_JS, /headerDownloadAllZipBtn/);
  assert.match(ADMIN_APP_JS, /downloadAllPendingZipBtn/);
});

// -------------------------------------------------------------
// Realistic Fixtures: Full Player, Translation, Download, OTA, No StructuredEvents
// -------------------------------------------------------------

test('Payload Integrity: Realistic fixtures preserve full payload, structuredEvents, device without empty values', async () => {
  const fixtureFiles = [
    { file: 'vox-diagnostic-VOX-82E64C77.json', id: 'VOX-82E64C77', expectedCategory: 'PLAYER', hasStructured: true },
    { file: 'vox-diagnostic-VOX-BAE1F61E.json', id: 'VOX-BAE1F61E', expectedCategory: 'TRANSLATION', hasStructured: true },
    { file: 'vox-diagnostic-VOX-BDDBE424.json', id: 'VOX-BDDBE424', expectedCategory: 'DOWNLOAD', hasStructured: true },
    { file: 'vox-diagnostic-VOX-ACE7107E.json', id: 'VOX-ACE7107E', expectedCategory: 'OTA', hasStructured: false },
    { file: 'vox-diagnostic-VOX-D0DF1A08.json', id: 'VOX-D0DF1A08', expectedCategory: 'PLAYER', hasStructured: false },
  ];

  const storage = new ReportStorage(null);

  for (const f of fixtureFiles) {
    const rawText = readFileSync(new URL(`./fixtures/${f.file}`, import.meta.url), 'utf8');
    const parsed = JSON.parse(rawText);
    await storage.saveReport({
      ...parsed,
      reportId: f.id,
      schema: 'vox-diagnostic-report-v3',
    });
    // Set status to NEW for export inclusion
    await storage.updateReport(f.id, { status: 'NEW' });
  }

  const res = await buildDiagnosticsZip(storage);
  assert.equal(res.totalReports, fixtureFiles.length);

  const entries = parseZipEntries(res.buffer);

  for (const f of fixtureFiles) {
    const entry = entries.find(e => e.path === `NEW/${f.id}.json`);
    assert.ok(entry, `Entry for ${f.id} must be in NEW folder`);
    const data = JSON.parse(entry.text);

    assert.equal(data.reportId, f.id);
    assert.equal(data.status, 'NEW');
    assert.ok(data.payload && Object.keys(data.payload).length > 0, `Payload for ${f.id} must NOT be empty`);

    // Check device information is populated
    assert.ok(data.device, `Device object for ${f.id} must exist`);
    assert.ok(data.device.model || data.device.family, `Model or family for ${f.id} must not be empty`);

    // Check structuredEvents
    if (f.hasStructured) {
      assert.ok(Array.isArray(data.structuredEvents) && data.structuredEvents.length > 0,
        `StructuredEvents for ${f.id} must be preserved and non-empty`);
    } else {
      assert.ok(Array.isArray(data.structuredEvents), `StructuredEvents for ${f.id} must be an array`);
    }
  }
});

// -------------------------------------------------------------
// Multipart Consistency: Status mutation between parts reflects committed state at query time
// -------------------------------------------------------------

test('Multipart Consistency: status changed between parts reflects query-time committed state without duplicate or crash', async () => {
  const reports = generateTestReports(4, { defaultStatus: 'NEW', startTimestamp: 1000 });
  const storage = createMockStorage(reports);

  // Part 1: maxReports = 2
  const part1 = await buildDiagnosticsZip(storage, { maxReports: 2, part: 1 });
  assert.equal(part1.totalReports, 2);
  assert.equal(part1.isTruncated, true);
  assert.ok(part1.nextCursor);

  // Mutate one of the remaining reports to RESOLVED before Part 2 runs
  const reportToMutate = reports.find(r => r.report_id === 'VOX-REP-000000');
  if (reportToMutate) {
    reportToMutate.status = 'RESOLVED';
  }

  // Part 2: requests next batch with cursor
  const part2 = await buildDiagnosticsZip(storage, { maxReports: 2, part: 2, cursor: part1.nextCursor });
  assert.equal(part2.totalReports, 1, 'The mutated RESOLVED report is excluded from Part 2 as expected');
  assert.equal(part2.manifest.consistency.statusEvaluation, 'READ_COMMITTED_AT_QUERY_TIME');

  // Verify no overlap between part1 and part2
  for (const id of part1.manifest.reportIds) {
    assert.ok(!part2.manifest.reportIds.includes(id), `Report ${id} must not appear in both parts`);
  }
});
