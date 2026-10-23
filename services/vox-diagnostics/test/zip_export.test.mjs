import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildDiagnosticsZip, ACTIVE_EXPORT_STATUSES, FINAL_EXCLUDED_STATUSES } from '../src/export.mjs';
import { ZipWriter, sanitizeZipEntryPath } from '../src/zip.mjs';

function createMockStorage(reports = []) {
  const store = reports.slice();
  return {
    async listReports({ limit = 50, offset = 0, status, search } = {}) {
      let filtered = store.slice();
      if (status) {
        filtered = filtered.filter(r => (r.status || 'NEW') === status);
      }
      if (search) {
        filtered = filtered.filter(r => JSON.stringify(r).includes(search));
      }
      return filtered.slice(offset, offset + limit);
    },
    async getReportById(id) {
      return store.find(r => (r.report_id || r.reportId) === id) || null;
    }
  };
}

// Simple ZIP extractor for testing
function parseZipEntries(buffer) {
  const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  const entries = [];
  let offset = 0;

  while (offset + 30 <= buffer.length) {
    const sig = view.getUint32(offset, true);
    if (sig !== 0x04034b50) break; // End of local headers
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

test('ZIP Export Gate 1 & 2: NEW and INVESTIGATING are included in archive', async () => {
  const reports = [
    { report_id: 'VOX-NEW-01', status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-NEW-01', model: 'Mi Box S' }) },
    { report_id: 'VOX-INV-01', status: 'INVESTIGATING', payload_json: JSON.stringify({ reportId: 'VOX-INV-01', model: 'Google TV' }) },
  ];
  const storage = createMockStorage(reports);
  const res = await buildDiagnosticsZip(storage);

  assert.equal(res.totalReports, 2);
  const entries = parseZipEntries(res.buffer);
  const paths = entries.map(e => e.path);

  assert.ok(paths.includes('NEW/VOX-NEW-01.json'), 'NEW/VOX-NEW-01.json must be in zip');
  assert.ok(paths.includes('INVESTIGATING/VOX-INV-01.json'), 'INVESTIGATING/VOX-INV-01.json must be in zip');
  assert.ok(paths.includes('manifest.json'), 'manifest.json must be in zip');
  assert.ok(paths.includes('summary.json'), 'summary.json must be in zip');
});

test('ZIP Export Gate 3 & 4: RESOLVED and IGNORED statuses are strictly excluded', async () => {
  const reports = [
    { report_id: 'VOX-NEW-02', status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-NEW-02' }) },
    { report_id: 'VOX-RES-01', status: 'RESOLVED', payload_json: JSON.stringify({ reportId: 'VOX-RES-01' }) },
    { report_id: 'VOX-IGN-01', status: 'IGNORED', payload_json: JSON.stringify({ reportId: 'VOX-IGN-01' }) },
    { report_id: 'VOX-CLS-01', status: 'CLOSED', payload_json: JSON.stringify({ reportId: 'VOX-CLS-01' }) },
    { report_id: 'VOX-TEST-01', status: 'IGNORED_TEST', payload_json: JSON.stringify({ reportId: 'VOX-TEST-01' }) },
  ];
  const storage = createMockStorage(reports);
  const res = await buildDiagnosticsZip(storage);

  assert.equal(res.totalReports, 1);
  const entries = parseZipEntries(res.buffer);
  const paths = entries.map(e => e.path);

  assert.ok(paths.includes('NEW/VOX-NEW-02.json'));
  assert.ok(!paths.some(p => p.includes('VOX-RES-01')), 'RESOLVED must be excluded');
  assert.ok(!paths.some(p => p.includes('VOX-IGN-01')), 'IGNORED must be excluded');
  assert.ok(!paths.some(p => p.includes('VOX-CLS-01')), 'CLOSED must be excluded');
  assert.ok(!paths.some(p => p.includes('VOX-TEST-01')), 'IGNORED_TEST must be excluded');
});

test('ZIP Export Gate 5: All pages across DB are fetched via pagination', async () => {
  // Generate 250 reports (page size is 100, requires 3 pages)
  const manyReports = [];
  for (let i = 1; i <= 250; i++) {
    const id = `VOX-PAGE-${String(i).padStart(3, '0')}`;
    manyReports.push({
      report_id: id,
      status: i % 2 === 0 ? 'NEW' : 'INVESTIGATING',
      payload_json: JSON.stringify({ reportId: id, model: 'TestDevice' })
    });
  }
  const storage = createMockStorage(manyReports);
  const res = await buildDiagnosticsZip(storage);

  assert.equal(res.totalReports, 250, 'All 250 reports across all pages must be exported');
  const entries = parseZipEntries(res.buffer);
  // Total entries = 250 reports + manifest.json + summary.json = 252
  assert.equal(entries.length, 252);
});

test('ZIP Export Gate 6: Deduplication prevents duplicate reportId entries', async () => {
  const reportsWithDupes = [
    { report_id: 'VOX-DUP-01', status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-DUP-01' }) },
    { report_id: 'VOX-DUP-01', status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-DUP-01' }) },
    { report_id: 'VOX-UNIQUE-01', status: 'INVESTIGATING', payload_json: JSON.stringify({ reportId: 'VOX-UNIQUE-01' }) },
  ];
  const storage = createMockStorage(reportsWithDupes);
  const res = await buildDiagnosticsZip(storage);

  assert.equal(res.totalReports, 2, 'Duplicates must be deduped by reportId');
});

test('ZIP Export Gate 7 & 8: Archive opens correctly and JSON files are valid', async () => {
  const reportPayload = {
    reportId: 'VOX-VALID-01',
    installationId: 'inst-uuid-1234',
    model: 'Shield TV Pro',
    appVersion: '32.56-vox.9.2',
    errorCategory: 'PLAYER',
    safeRecentEvents: [{ code: 'BUFFER_STALL', isHistorical: false }]
  };
  const storage = createMockStorage([
    { report_id: 'VOX-VALID-01', status: 'NEW', payload_json: JSON.stringify(reportPayload) }
  ]);
  const res = await buildDiagnosticsZip(storage);
  const entries = parseZipEntries(res.buffer);

  // Check manifest.json
  const manifestEntry = entries.find(e => e.path === 'manifest.json');
  assert.ok(manifestEntry);
  const manifest = JSON.parse(manifestEntry.text);
  assert.equal(manifest.schemaVersion, '1.0');
  assert.equal(manifest.totalReports, 1);
  assert.equal(manifest.exportStatus, 'COMPLETE');

  // Check summary.json
  const summaryEntry = entries.find(e => e.path === 'summary.json');
  assert.ok(summaryEntry);
  const summary = JSON.parse(summaryEntry.text);
  assert.equal(summary.totalReports, 1);
  assert.equal(summary.distinctInstallationIds, 1);
  assert.equal(summary.unknownInstallationCount, 0);

  // Check report JSON
  const reportEntry = entries.find(e => e.path === 'NEW/VOX-VALID-01.json');
  assert.ok(reportEntry);
  const parsed = JSON.parse(reportEntry.text);
  assert.equal(parsed.reportId, 'VOX-VALID-01');
  assert.equal(parsed.installationId, 'inst-uuid-1234');
});

test('ZIP Export Gate 9: Security and ZIP Slip protection', () => {
  assert.throws(() => sanitizeZipEntryPath('../../etc/passwd'), /Security Exception/);
  assert.throws(() => sanitizeZipEntryPath('NEW/../../../secret.json'), /Security Exception/);
  assert.equal(sanitizeZipEntryPath('C:\\Windows\\file.json'), 'Windows/file.json');
  assert.equal(sanitizeZipEntryPath('/NEW/VOX-1234.json'), 'NEW/VOX-1234.json');
});

test('ZIP Export Gate 10: Missing installationId is supported and counted in unknownInstallationCount', async () => {
  const reports = [
    { report_id: 'VOX-OLD-01', status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-OLD-01' }) },
    { report_id: 'VOX-NEW-03', status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-NEW-03', installationId: 'uuid-abc' }) },
  ];
  const storage = createMockStorage(reports);
  const res = await buildDiagnosticsZip(storage);
  const entries = parseZipEntries(res.buffer);
  const summary = JSON.parse(entries.find(e => e.path === 'summary.json').text);

  assert.equal(summary.distinctInstallationIds, 1);
  assert.equal(summary.unknownInstallationCount, 1);
});

test('ZIP Export Gate 11 & 12: Safe handling of large volumes and no false success on empty errors', async () => {
  const emptyStorage = createMockStorage([]);
  const res = await buildDiagnosticsZip(emptyStorage);
  assert.equal(res.totalReports, 0);
  assert.ok(res.buffer.length > 0, 'Empty zip file still contains valid empty zip structure with manifest and summary');
  const entries = parseZipEntries(res.buffer);
  assert.equal(entries.length, 2); // manifest.json and summary.json
});

test('ZIP Export Gate 13: Bounded maxLimit sets isTruncated flag and warning in manifest', async () => {
  const reports = [
    { report_id: 'VOX-TRUNC-01', status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-TRUNC-01' }) },
    { report_id: 'VOX-TRUNC-02', status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-TRUNC-02' }) },
    { report_id: 'VOX-TRUNC-03', status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-TRUNC-03' }) },
  ];
  const storage = createMockStorage(reports);
  const res = await buildDiagnosticsZip(storage, { maxReports: 2 });
  assert.equal(res.totalReports, 2);
  assert.equal(res.isTruncated, true);

  const entries = parseZipEntries(res.buffer);
  const manifest = JSON.parse(entries.find(e => e.path === 'manifest.json').text);
  assert.equal(manifest.exportStatus, 'TRUNCATED_MAX_LIMIT');
  assert.equal(manifest.isTruncated, true);
  assert.ok(manifest.warning.includes('maximum limit of 2 reports'));
});

test('ZIP Export Gate 14: Exact limit boundary produces complete export without false truncation warning', async () => {
  const reports = [
    { report_id: 'VOX-EXACT-01', status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-EXACT-01' }) },
    { report_id: 'VOX-EXACT-02', status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-EXACT-02' }) },
  ];
  const storage = createMockStorage(reports);
  const res = await buildDiagnosticsZip(storage, { maxReports: 2 });
  assert.equal(res.totalReports, 2);
  assert.equal(res.isTruncated, false, 'Exact count must not be flagged as truncated');

  const entries = parseZipEntries(res.buffer);
  const manifest = JSON.parse(entries.find(e => e.path === 'manifest.json').text);
  assert.equal(manifest.exportStatus, 'COMPLETE');
  assert.equal(manifest.isTruncated, false);
  assert.equal(manifest.warning, undefined, 'No false warning on exact limit boundary');
});

test('ZIP Export Gate 15: Truncated export provides nextCursor for multipart continuous export', async () => {
  const reports = [
    { report_id: 'VOX-MULTI-01', created_at: 1000, status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-MULTI-01' }) },
    { report_id: 'VOX-MULTI-02', created_at: 2000, status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-MULTI-02' }) },
    { report_id: 'VOX-MULTI-03', created_at: 3000, status: 'NEW', payload_json: JSON.stringify({ reportId: 'VOX-MULTI-03' }) },
  ];
  const storage = createMockStorage(reports);
  const part1 = await buildDiagnosticsZip(storage, { maxReports: 2 });
  assert.equal(part1.totalReports, 2);
  assert.equal(part1.isTruncated, true);
  assert.ok(part1.nextCursor, 'Must supply nextCursor');

  // Verify part 1 contents
  const entries1 = parseZipEntries(part1.buffer);
  const manifest1 = JSON.parse(entries1.find(e => e.path === 'manifest.json').text);
  assert.equal(manifest1.nextCursor, part1.nextCursor);
  assert.ok(manifest1.warning.includes('To export the next batch, use ?cursor='));
});
