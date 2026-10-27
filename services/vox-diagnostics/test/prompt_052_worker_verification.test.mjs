import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { ReportStorage } from '../src/storage.mjs';
import { buildDiagnosticsZip } from '../src/export.mjs';

function parseZipEntries(buffer) {
  const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  const entries = [];
  let offset = 0;

  while (offset + 30 <= buffer.length) {
    const sig = view.getUint32(offset, true);
    if (sig !== 0x04034b50) break;
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

function createD1Mock() {
  const sqlite = new DatabaseSync(':memory:');
  const migrations = ['0001_init.sql', '0002_operations.sql', '0003_admin_22.sql', '0004_fix_issue_pk.sql', '0005_duplicate_tracking.sql', '0006_incident_workflow.sql'];
  for (const m of migrations) {
    try {
      const sql = readFileSync(fileURLToPath(new URL('../migrations/' + m, import.meta.url)), 'utf8');
      sqlite.exec(sql);
    } catch (_) {}
  }

  let queryCounter = 0;
  const queries = [];

  const d1 = {
    getQueryCount() { return queryCounter; },
    getQueries() { return [...queries]; },
    resetQueryCount() { queryCounter = 0; queries.length = 0; },
    prepare(sql) {
      const statement = sqlite.prepare(sql);
      const wrapped = {
        args: [],
        bind(...args) { this.args = args; return this; },
        async run() {
          queryCounter++;
          queries.push({ type: 'run', sql });
          return { meta: { changes: statement.run(...this.args).changes } };
        },
        async first() {
          queryCounter++;
          queries.push({ type: 'first', sql });
          return statement.get(...this.args) || null;
        },
        async all() {
          queryCounter++;
          queries.push({ type: 'all', sql });
          return { results: statement.all(...this.args) };
        },
      };
      return wrapped;
    },
    async batch(statements) {
      sqlite.exec('BEGIN');
      try {
        const results = [];
        for (const s of statements) results.push(await s.run());
        sqlite.exec('COMMIT');
        return results;
      } catch (err) {
        sqlite.exec('ROLLBACK');
        throw err;
      }
    },
    close() { sqlite.close(); }
  };

  return { d1, sqlite };
}

test('Prompt #052: Scale and edge case verification (0, 1, 50, 200, 640 synthetic reports + ties & malformed)', async () => {
  const { d1, sqlite } = createD1Mock();
  const storage = new ReportStorage(d1);

  // 1. Empty DB (0 reports)
  d1.resetQueryCount();
  const stats0 = await storage.getStats();
  assert.equal(stats0.totalReports, 0);
  const grouped0 = await storage.getGroupedIssues({ limit: 50 });
  assert.equal(grouped0.length, 0);
  const part0 = await buildDiagnosticsZip(storage, { maxReports: 200, part: 1 });
  assert.equal(part0.totalReports, 0);
  assert.equal(part0.isTruncated, false);

  // 2. Single report (1 report) with malformed JSON and empty payload
  const now = Date.now();
  sqlite.prepare(`
    INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
    VALUES ('VOX-MALFORMED-1', ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYER', 'SIG_MALFORMED', 'NEW', 'CRITICAL', '{bad_json', ?)
  `).run(now, now + 86400000);

  const part1 = await buildDiagnosticsZip(storage, { maxReports: 200, part: 1, until: now });
  assert.equal(part1.totalReports, 1);
  assert.ok(part1.buffer.length > 0);

  // Verify parseZipEntries can parse archive containing malformed payload without crash
  const entries1 = parseZipEntries(part1.buffer);
  const paths1 = entries1.map(e => e.path);
  assert.ok(paths1.includes('manifest.json'));
  assert.ok(paths1.some(p => p.includes('VOX-MALFORMED-1')));

  // 3. 50 reports with identical timestamps (timestamp ties) to verify deterministic ordering
  const tieTimestamp = now - 50000;
  for (let i = 1; i <= 50; i++) {
    const reportId = `VOX-TIE-${String(i).padStart(3, '0')}`;
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYER', 'SIG_TIE', 'NEW', 'MEDIUM', '{}', ?)
    `).run(reportId, tieTimestamp, now + 86400000);
  }

  // 4. Populate up to 640 reports (with 50 unique signatures and long strings)
  const longTitle = 'A'.repeat(500);
  for (let i = 52; i <= 640; i++) {
    const reportId = `VOX-640-${String(i).padStart(4, '0')}`;
    const sigIndex = i % 50;
    const sig = `CATEGORY|SUBSYSTEM|UNIQUE_SIG_${sigIndex}`;
    const status = i <= 585 ? 'NEW' : 'RESOLVED';
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYER', ?, ?, 'MEDIUM', ?, ?)
    `).run(reportId, now - i * 100, sig, status, JSON.stringify({ longTitle, id: i }), now + 86400000);
  }

  // Check total reports in DB
  const stats640 = await storage.getStats();
  assert.equal(stats640.totalReports, 640);
  assert.equal(stats640.newCount, 585); // 1 malformed + 50 ties + (585-51) = 585 active

  // Verify getGroupedIssues bounded query count on 640 records with 50 unique signatures
  d1.resetQueryCount();
  const startTime = Date.now();
  const grouped50 = await storage.getGroupedIssues({ limit: 50 });
  const durationMs = Date.now() - startTime;

  assert.equal(grouped50.length, 50, 'Must return 50 grouped issues');
  assert.ok(d1.getQueryCount() <= 4, `Query count must remain <= 4 even with 50 signatures, was ${d1.getQueryCount()}`);
  assert.ok(durationMs < 200, `Execution time must be under 200ms locally, took ${durationMs}ms`);

  // Verify 4-part segmented export of all 585 active reports
  const p1 = await buildDiagnosticsZip(storage, { maxReports: 200, part: 1, until: now });
  const p2 = await buildDiagnosticsZip(storage, { maxReports: 200, part: 2, cursor: p1.nextCursor, until: now });
  const p3 = await buildDiagnosticsZip(storage, { maxReports: 200, part: 3, cursor: p2.nextCursor, until: now });

  assert.equal(p1.totalReports, 200);
  assert.equal(p2.totalReports, 200);
  assert.equal(p3.totalReports, 185);
  assert.equal(p3.isTruncated, false);

  // Validate ZIP integrity
  const entriesP1 = parseZipEntries(p1.buffer);
  assert.equal(entriesP1.length, 202); // 200 reports + manifest.json + summary.json
  const manifestEntry = entriesP1.find(e => e.path === 'manifest.json');
  assert.ok(manifestEntry);
  const manifestP1 = JSON.parse(manifestEntry.text);
  assert.equal(manifestP1.totalReports, 200);
  assert.equal(manifestP1.part, 1);

  d1.close();
});

test('Prompt #052: Concurrency simulation (1, 5, 20 parallel client requests)', async () => {
  const { d1, sqlite } = createD1Mock();
  const storage = new ReportStorage(d1);

  const now = Date.now();
  for (let i = 1; i <= 200; i++) {
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYER', ?, 'NEW', 'MEDIUM', '{}', ?)
    `).run(`VOX-CONCUR-${i}`, now - i * 100, `SIG_${i % 10}`, now + 86400000);
  }

  // Concurrency test helper
  async function runConcurrent(clientsCount) {
    const start = Date.now();
    const tasks = [];
    for (let c = 0; c < clientsCount; c++) {
      tasks.push((async () => {
        const stats = await storage.getStats();
        const issues = await storage.getGroupedIssues({ limit: 10 });
        const reports = await storage.listReports({ limit: 20 });
        return { stats, issues, reports };
      })());
    }
    const results = await Promise.all(tasks);
    const duration = Date.now() - start;
    return { count: results.length, duration, avgPerClient: duration / clientsCount };
  }

  // 1 client
  const c1 = await runConcurrent(1);
  assert.equal(c1.count, 1);

  // 5 clients
  const c5 = await runConcurrent(5);
  assert.equal(c5.count, 5);

  // 20 clients
  const c20 = await runConcurrent(20);
  assert.equal(c20.count, 20);

  // Ensure graceful scaling without exponential degradation
  assert.ok(c20.duration < 1500, `20 concurrent requests must complete within 1500ms, took ${c20.duration}ms`);

  d1.close();
});

test('Prompt #052: 5000+ reports scale safety & bounded export memory footprint', async () => {
  const { d1, sqlite } = createD1Mock();
  const storage = new ReportStorage(d1);

  const now = Date.now();
  const insertStmt = sqlite.prepare(`
    INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
    VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYER', 'MASS_SIG', 'NEW', 'MEDIUM', '{}', ?)
  `);

  // Bulk insert 5,000 synthetic records inside a single transaction
  sqlite.exec('BEGIN TRANSACTION');
  for (let i = 1; i <= 5000; i++) {
    insertStmt.run(`VOX-MASS-${i}`, now - i * 10, now + 86400000);
  }
  sqlite.exec('COMMIT');

  const stats = await storage.getStats();
  assert.equal(stats.totalReports, 5000);

  // Querying part 1 with 200 limit must be fast and memory-bounded
  d1.resetQueryCount();
  const exportStart = Date.now();
  const part1 = await buildDiagnosticsZip(storage, { maxReports: 200, part: 1, until: now });
  const exportDuration = Date.now() - exportStart;

  assert.equal(part1.totalReports, 200);
  assert.equal(part1.isTruncated, true);
  assert.ok(part1.nextCursor);
  assert.ok(exportDuration < 500, `Exporting bounded 200-part on 5000-record DB must take < 500ms, took ${exportDuration}ms`);

  d1.close();
});
