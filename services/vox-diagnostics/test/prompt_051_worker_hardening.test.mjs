import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { ReportStorage } from '../src/storage.mjs';
import { buildDiagnosticsZip } from '../src/export.mjs';

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

test('Prompt #051: N+1 queries eliminated in getGroupedIssues (bounded D1 subrequests)', async () => {
  const { d1, sqlite } = createD1Mock();
  const storage = new ReportStorage(d1);

  // Seed 50 distinct signatures in D1
  const now = Date.now();
  for (let i = 1; i <= 50; i++) {
    const reportId = `VOX-TEST-${String(i).padStart(3, '0')}`;
    const sig = `CATEGORY|SUBSYSTEM|SIG_${i}`;
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL_PRO', 'PLAYER', ?, 'NEW', 'MEDIUM', '{}', ?)
    `).run(reportId, now - i * 1000, sig, now + 86400000);
  }

  // Pre-seed an issue in the issues table
  sqlite.prepare(`
    INSERT INTO issues (signature, status, title, fixed_in_version, updated_at)
    VALUES ('CATEGORY|SUBSYSTEM|SIG_1', 'RESOLVED', 'Sample issue', '32.56-vox.9.2', ?)
  `).run(now);

  d1.resetQueryCount();

  const grouped = await storage.getGroupedIssues({ limit: 50 });
  assert.equal(grouped.length, 50, 'Must return all 50 grouped issues');
  assert.equal(grouped.find(g => g.error_signature === 'CATEGORY|SUBSYSTEM|SIG_1')?.issue_status, 'RESOLVED');

  const queryCount = d1.getQueryCount();
  // Old code made 100+ queries (2 subrequests per issue in Promise.all map)
  // New batch code makes at most 3 queries: 1 group query + 1 batch issues + 1 batch versions
  assert.ok(queryCount <= 4, `Query count must be <= 4 (batch), but was ${queryCount}`);

  d1.close();
});

test('Prompt #051: getStats runs in a single consolidated aggregate query', async () => {
  const { d1, sqlite } = createD1Mock();
  const storage = new ReportStorage(d1);

  const now = Date.now();
  sqlite.prepare(`
    INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
    VALUES ('VOX-1', ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYBACK', 'SIG_1', 'NEW', 'CRITICAL', '{}', ?)
  `).run(now - 1000, now + 86400000);
  sqlite.prepare(`
    INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
    VALUES ('VOX-2', ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'TRANSLATION', 'SIG_2', 'IN_PROGRESS', 'HIGH', '{}', ?)
  `).run(now - 5000, now + 86400000);
  sqlite.prepare(`
    INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
    VALUES ('VOX-3', ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'DOWNLOAD', 'SIG_3', 'RESOLVED', 'LOW', '{}', ?)
  `).run(now - 2 * 86400 * 1000, now + 86400000); // 2 days ago

  d1.resetQueryCount();
  const stats = await storage.getStats(now);

  assert.equal(stats.totalReports, 3);
  assert.equal(stats.reports24h, 2);
  assert.equal(stats.reports7d, 3);
  assert.equal(stats.newCount, 1);
  assert.equal(stats.inProgressCount, 1);
  assert.equal(stats.resolvedCount, 1);

  // Consolidated aggregate query + group by platforms + versions + categories + topIssues = 5 queries total in Promise.all
  assert.equal(d1.getQueryCount(), 5, 'Consolidated getStats must execute exactly 5 parallel queries instead of 8');

  d1.close();
});

test('Prompt #051: Resumable bounded export parts (200 reports/part) for 640 synthetic reports', async () => {
  const { d1, sqlite } = createD1Mock();
  const storage = new ReportStorage(d1);

  const now = Date.now();
  // Generate 640 reports (matching production screenshot DB snapshot: 640 total in database)
  for (let i = 1; i <= 640; i++) {
    const reportId = `VOX-640-${String(i).padStart(4, '0')}`;
    const status = i <= 585 ? 'NEW' : 'RESOLVED'; // 585 active, 55 resolved
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYER', 'ERR_SIG', ?, 'MEDIUM', ?, ?)
    `).run(reportId, now - i * 100, status, JSON.stringify({ reportId, device: 'TCL' }), now + 86400000);
  }

  // Part 1: Bounded to 200 reports
  const part1 = await buildDiagnosticsZip(storage, { maxReports: 200, part: 1, until: now });
  assert.equal(part1.totalReports, 200);
  assert.equal(part1.isTruncated, true);
  assert.ok(part1.nextCursor, 'Part 1 must provide nextCursor');

  // Part 2: Next 200 reports
  const part2 = await buildDiagnosticsZip(storage, { maxReports: 200, part: 2, cursor: part1.nextCursor, until: now });
  assert.equal(part2.totalReports, 200);
  assert.equal(part2.isTruncated, true);
  assert.ok(part2.nextCursor, 'Part 2 must provide nextCursor');

  // Part 3: Remaining 185 active reports (585 total active - 400 = 185)
  const part3 = await buildDiagnosticsZip(storage, { maxReports: 200, part: 3, cursor: part2.nextCursor, until: now });
  assert.equal(part3.totalReports, 185);
  assert.equal(part3.isTruncated, false, 'Part 3 should complete the active reports export');

  // Verify zero overlap across parts
  const ids1 = new Set(part1.manifest.reportIds);
  const ids2 = new Set(part2.manifest.reportIds);
  const ids3 = new Set(part3.manifest.reportIds);

  for (const id of ids2) assert.ok(!ids1.has(id), `Duplicate ID between Part 1 and Part 2: ${id}`);
  for (const id of ids3) assert.ok(!ids1.has(id) && !ids2.has(id), `Duplicate ID in Part 3: ${id}`);

  assert.equal(ids1.size + ids2.size + ids3.size, 585, 'Total exported reports across 3 parts must equal 585 active reports');

  d1.close();
});
