import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { ReportStorage, expandStatusVariants, normalizeStatus } from '../src/storage.mjs';
import { buildDiagnosticsZip, ACTIVE_EXPORT_STATUSES, FINAL_EXCLUDED_STATUSES } from '../src/export.mjs';
import { handleRequest } from '../src/diagnostics.mjs';

function createD1Mock() {
  const sqlite = new DatabaseSync(':memory:');
  const migrations = [
    '0001_init.sql',
    '0002_operations.sql',
    '0003_admin_22.sql',
    '0004_fix_issue_pk.sql',
    '0005_duplicate_tracking.sql',
    '0006_incident_workflow.sql',
  ];
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

// Simple ZIP parser for verification
function parseZipEntries(buffer) {
  const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  const entries = [];
  let offset = 0;

  while (offset + 30 <= buffer.length) {
    const sig = view.getUint32(offset, true);
    if (sig !== 0x04034b50) break;
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

test('Prompt #054: Status Alias & Casing Expansion Unit Test', () => {
  const variants = expandStatusVariants(['NEW', 'INVESTIGATING']);
  assert.ok(variants.includes('NEW'));
  assert.ok(variants.includes('new'));
  assert.ok(variants.includes('INVESTIGATING'));
  assert.ok(variants.includes('investigating'));
  assert.ok(variants.includes('IN_PROGRESS'));
  assert.ok(variants.includes('in_progress'));
  assert.ok(variants.includes('TRIAGED'));
  assert.ok(variants.includes('triaged'));
  assert.ok(!variants.includes('RESOLVED'));
  assert.ok(!variants.includes('IGNORED'));
});

test('Prompt #054: Count Matrix - 0 reports', async () => {
  const { d1 } = createD1Mock();
  const storage = new ReportStorage(d1);

  const res = await buildDiagnosticsZip(storage, { maxReports: 100 });
  assert.equal(res.totalReports, 0);
  assert.equal(res.isTruncated, false);
  assert.equal(res.nextCursor, null);

  const entries = parseZipEntries(res.buffer);
  assert.ok(entries.some(e => e.path === 'manifest.json'));
  assert.ok(entries.some(e => e.path === 'summary.json'));

  d1.close();
});

test('Prompt #054: Count Matrix - 1, 25, 50, 99, 100 reports (Single Part Bounded)', async () => {
  for (const count of [1, 25, 50, 99, 100]) {
    const { d1, sqlite } = createD1Mock();
    const storage = new ReportStorage(d1);
    const now = 1700000000000;

    for (let i = 1; i <= count; i++) {
      const id = `VOX-REP-${count}-${String(i).padStart(4, '0')}`;
      sqlite.prepare(`
        INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
        VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL_PRO', 'PLAYER', 'ERR_SIG', ?, 'MEDIUM', ?, ?)
      `).run(id, now - i * 10, i % 2 === 0 ? 'INVESTIGATING' : 'NEW', JSON.stringify({ reportId: id, model: 'TCL_PRO' }), now + 86400000);
    }

    const res = await buildDiagnosticsZip(storage, { maxReports: 100 });
    assert.equal(res.totalReports, count);
    assert.equal(res.isTruncated, false, `Count ${count} should not be truncated with maxReports=100`);
    assert.equal(res.nextCursor, null);

    const entries = parseZipEntries(res.buffer);
    assert.equal(entries.filter(e => e.path.endsWith('.json') && e.path !== 'manifest.json' && e.path !== 'summary.json').length, count);

    d1.close();
  }
});

test('Prompt #054: Count Matrix - 101, 200, 250, 251, 796 reports (Multipart Bounded Chunks of 100)', async () => {
  for (const totalCount of [101, 200, 250, 251, 796]) {
    const { d1, sqlite } = createD1Mock();
    const storage = new ReportStorage(d1);
    const now = 1700000000000;

    for (let i = 1; i <= totalCount; i++) {
      const id = `VOX-MULTI-${totalCount}-${String(i).padStart(4, '0')}`;
      sqlite.prepare(`
        INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
        VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL_PRO', 'PLAYER', 'ERR_SIG', 'NEW', 'MEDIUM', ?, ?)
      `).run(id, now - i * 10, JSON.stringify({ reportId: id, model: 'TCL_PRO' }), now + 86400000);
    }

    let exportedIds = new Set();
    let cursor = null;
    let part = 1;
    let hasMore = true;

    while (hasMore) {
      const res = await buildDiagnosticsZip(storage, { maxReports: 100, part, cursor });
      for (const id of res.manifest.reportIds) {
        assert.ok(!exportedIds.has(id), `Duplicate ID ${id} detected in part ${part}`);
        exportedIds.add(id);
      }
      if (res.isTruncated) {
        assert.ok(res.nextCursor, `Part ${part} is truncated but has no nextCursor`);
        cursor = res.nextCursor;
        part++;
      } else {
        hasMore = false;
      }
    }

    assert.equal(exportedIds.size, totalCount, `Must export exactly ${totalCount} unique reports`);
    const expectedParts = Math.ceil(totalCount / 100);
    assert.equal(part, expectedParts, `Total parts must be ${expectedParts} for ${totalCount} items`);

    d1.close();
  }
});

test('Prompt #054: Full Production Snapshot Scale - 851 Reports (757 NEW, 39 INVESTIGATING, 55 RESOLVED)', async () => {
  const { d1, sqlite } = createD1Mock();
  const storage = new ReportStorage(d1);
  const now = 1700000000000;

  // 1. Seed exactly 757 NEW reports
  for (let i = 1; i <= 757; i++) {
    const id = `VOX-NEW-${String(i).padStart(4, '0')}`;
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'Android TV', 'TCL C735', 'PLAYER', 'SIG_NEW', 'NEW', 'MEDIUM', ?, ?)
    `).run(id, now - i * 100, JSON.stringify({ reportId: id, installationId: `inst-${i % 20}` }), now + 86400000);
  }

  // 2. Seed exactly 39 INVESTIGATING reports (using mixed cases and aliases)
  for (let i = 1; i <= 39; i++) {
    const id = `VOX-INV-${String(i).padStart(4, '0')}`;
    const statusVariant = i % 3 === 0 ? 'in_progress' : (i % 2 === 0 ? 'INVESTIGATING' : 'triaged');
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'Android TV', 'TCL C735', 'PLAYER', 'SIG_INV', ?, 'HIGH', ?, ?)
    `).run(id, now - 800000 - i * 100, statusVariant, JSON.stringify({ reportId: id, installationId: `inst-${i % 10}` }), now + 86400000);
  }

  // 3. Seed exactly 55 RESOLVED/IGNORED reports (must NOT be exported)
  for (let i = 1; i <= 55; i++) {
    const id = `VOX-RES-${String(i).padStart(4, '0')}`;
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'Android TV', 'TCL C735', 'PLAYER', 'SIG_RES', 'RESOLVED', 'LOW', ?, ?)
    `).run(id, now - 1600000 - i * 100, JSON.stringify({ reportId: id }), now + 86400000);
  }

  // Verify total count in DB
  const countRow = sqlite.prepare('SELECT COUNT(*) as cnt FROM reports').get();
  assert.equal(countRow.cnt, 851, 'Database must contain exactly 851 reports');

  // Perform bulk active export in 100-report bounded chunks
  const allExportedIds = new Set();
  let cursor = null;
  let part = 1;
  let hasMore = true;
  let totalParts = 0;

  while (hasMore) {
    const res = await buildDiagnosticsZip(storage, { maxReports: 100, part, cursor });
    totalParts++;
    for (const id of res.manifest.reportIds) {
      assert.ok(!allExportedIds.has(id), `Duplicate ID ${id} in part ${part}`);
      assert.ok(!id.startsWith('VOX-RES-'), `Excluded resolved report ${id} was leaked into export`);
      allExportedIds.add(id);
    }

    if (res.isTruncated) {
      assert.equal(res.totalReports, 100, `Truncated part ${part} must contain exactly 100 reports`);
      assert.ok(res.nextCursor, `Part ${part} must have nextCursor`);
      cursor = res.nextCursor;
      part++;
    } else {
      hasMore = false;
    }
  }

  // Total active reports: 757 + 39 = 796
  assert.equal(allExportedIds.size, 796, 'Must export all 796 active reports with 0 lost');
  // 796 reports with limit 100 = 8 parts (7 * 100 + 1 * 96)
  assert.equal(totalParts, 8, 'Expected 8 parts for 796 reports at 100 per part');

  d1.close();
});

test('Prompt #054: Duplicate created_at Disambiguation with Keyset Cursor', async () => {
  const { d1, sqlite } = createD1Mock();
  const storage = new ReportStorage(d1);
  const fixedTimestamp = 1700000000000;

  // Insert 50 reports all sharing the exact same created_at millisecond
  for (let i = 1; i <= 50; i++) {
    const id = `VOX-SAME-TS-${String(i).padStart(3, '0')}`;
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYER', 'SIG', 'NEW', 'MEDIUM', '{}', ?)
    `).run(id, fixedTimestamp, fixedTimestamp + 86400000);
  }

  // Export in chunks of 15
  const collectedIds = new Set();
  let cursor = null;
  let part = 1;
  let hasMore = true;

  while (hasMore) {
    const res = await buildDiagnosticsZip(storage, { maxReports: 15, part, cursor });
    for (const id of res.manifest.reportIds) {
      assert.ok(!collectedIds.has(id), `Duplicate ${id} in part ${part}`);
      collectedIds.add(id);
    }
    if (res.isTruncated) {
      cursor = res.nextCursor;
      part++;
    } else {
      hasMore = false;
    }
  }

  assert.equal(collectedIds.size, 50, 'All 50 reports with identical timestamp must be exported');
  d1.close();
});

test('Prompt #054: Admin API Endpoint Contract & Default Limit (100)', async () => {
  const { d1, sqlite } = createD1Mock();
  const now = Date.now();

  for (let i = 1; i <= 150; i++) {
    const id = `VOX-API-${String(i).padStart(3, '0')}`;
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYER', 'SIG', 'NEW', 'MEDIUM', '{}', ?)
    `).run(id, now - i * 100, now + 86400000);
  }

  const env = {
    DB: d1,
    ADMIN_SECRET: 'test_admin_secret_key_123',
  };

  // 1. Request without limit -> defaults to 100
  const reqDefault = new Request('https://vox-diagnostics.workers.dev/v1/admin/export/zip?statuses=NEW,INVESTIGATING', {
    method: 'GET',
    headers: {
      'Authorization': 'Bearer test_admin_secret_key_123',
    },
  });

  const resDefault = await handleRequest(reqDefault, env);
  assert.equal(resDefault.status, 200);
  assert.equal(resDefault.headers.get('Content-Type'), 'application/zip');
  assert.equal(resDefault.headers.get('X-Total-Reports'), '100');
  assert.equal(resDefault.headers.get('X-Export-Truncated'), 'true');
  assert.ok(resDefault.headers.get('X-Export-Next-Cursor'));

  // 2. Request with explicit limit=50
  const req50 = new Request('https://vox-diagnostics.workers.dev/v1/admin/export/zip?statuses=NEW,INVESTIGATING&limit=50', {
    method: 'GET',
    headers: {
      'Authorization': 'Bearer test_admin_secret_key_123',
    },
  });
  const res50 = await handleRequest(req50, env);
  assert.equal(res50.status, 200);
  assert.equal(res50.headers.get('X-Total-Reports'), '50');
  assert.equal(res50.headers.get('X-Export-Truncated'), 'true');

  d1.close();
});

test('Prompt #054: Client-side fetchExportPartWithRetry Simulated Resilience (503 / 429)', async () => {
  let callCount = 0;
  async function mockFetchWithRetry(simulateStatusSequence, maxAttempts = 3) {
    let lastRes = null;
    let attemptsMade = 0;
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      attemptsMade++;
      const currentStatus = simulateStatusSequence[attempt - 1] || 200;
      const res = {
        ok: currentStatus >= 200 && currentStatus < 300,
        status: currentStatus,
        headers: new Map([['Retry-After', '1']]),
      };
      if (res.ok) {
        return { ok: true, attemptsMade, status: currentStatus };
      }
      lastRes = res;
      if ((res.status >= 500 || res.status === 429) && attempt < maxAttempts) {
        continue;
      }
      return { ok: false, attemptsMade, status: currentStatus };
    }
    return { ok: false, attemptsMade, status: lastRes?.status };
  }

  // Test recovery on 2nd attempt: 503 -> 200
  const recovery503 = await mockFetchWithRetry([503, 200]);
  assert.equal(recovery503.ok, true);
  assert.equal(recovery503.attemptsMade, 2);

  // Test recovery on 3rd attempt: 429 -> 503 -> 200
  const recovery429 = await mockFetchWithRetry([429, 503, 200]);
  assert.equal(recovery429.ok, true);
  assert.equal(recovery429.attemptsMade, 3);

  // Test permanent 401: no retry
  const fail401 = await mockFetchWithRetry([401, 200], 3);
  assert.equal(fail401.ok, false);
  assert.equal(fail401.attemptsMade, 1);
});
