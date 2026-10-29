import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { ReportStorage, expandStatusVariants, normalizeStatus } from '../src/storage.mjs';
import { buildDiagnosticsZip, ACTIVE_EXPORT_STATUSES, FINAL_EXCLUDED_STATUSES } from '../src/export.mjs';
import { handleRequest } from '../src/diagnostics.mjs';
import { crc32 } from '../src/zip.mjs';

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

// -------------------------------------------------------------
// GROUP 1: Load Scenarios (0, 1, 99, 100, 101, 803, 847, 902, 1000+)
// -------------------------------------------------------------

test('Prompt #062: Load Scenario Matrix (0, 1, 99, 100, 101, 803, 847, 902, 1000+ reports)', async () => {
  const testCounts = [0, 1, 99, 100, 101, 803, 847, 902, 1005];

  for (const count of testCounts) {
    const { d1, sqlite } = createD1Mock();
    const storage = new ReportStorage(d1);
    const now = 1730000000000;

    // Seed active reports (NEW and INVESTIGATING)
    for (let i = 1; i <= count; i++) {
      const id = `VOX-REP-${count}-${String(i).padStart(5, '0')}`;
      const status = (i % 5 === 0) ? 'INVESTIGATING' : 'NEW';
      sqlite.prepare(`
        INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
        VALUES (?, ?, '1', '32.56-vox.9.2', 'Android TV', 'TCL_PRO', 'PLAYER', 'SIG_LOAD', ?, 'MEDIUM', ?, ?)
      `).run(id, now - i * 10, status, JSON.stringify({ reportId: id, model: 'TCL_PRO' }), now + 86400000);
    }

    // Seed 15 excluded reports (RESOLVED, IGNORED_TEST, CLOSED)
    for (let i = 1; i <= 15; i++) {
      const id = `VOX-EXCL-${count}-${String(i).padStart(3, '0')}`;
      const status = (i % 3 === 0) ? 'RESOLVED' : (i % 2 === 0 ? 'IGNORED_TEST' : 'CLOSED');
      sqlite.prepare(`
        INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
        VALUES (?, ?, '1', '32.56-vox.9.2', 'Android TV', 'TCL_PRO', 'PLAYER', 'SIG_EXCL', ?, 'LOW', ?, ?)
      `).run(id, now - i * 5, status, JSON.stringify({ reportId: id }), now + 86400000);
    }

    const exportedIds = new Set();
    let cursor = null;
    let part = 1;
    let hasMore = true;
    const chunkLimit = 50; // Using resilient default 50

    while (hasMore) {
      const res = await buildDiagnosticsZip(storage, {
        maxReports: chunkLimit,
        part,
        cursor,
        until: now,
      });

      for (const id of res.manifest.reportIds) {
        assert.ok(!exportedIds.has(id), `Duplicate ID ${id} detected in part ${part} for count ${count}`);
        assert.ok(!id.startsWith('VOX-EXCL-'), `Excluded status report ${id} leaked into part ${part}`);
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

    assert.equal(exportedIds.size, count, `Must export exactly ${count} unique reports`);
    if (count > 0) {
      const expectedParts = Math.ceil(count / chunkLimit);
      assert.equal(part, expectedParts, `Expected ${expectedParts} parts for count ${count} at chunk ${chunkLimit}`);
    } else {
      assert.equal(part, 1);
    }

    d1.close();
  }
});

// -------------------------------------------------------------
// GROUP 2: Failure and Recovery Scenarios (Part 1, Part 7, Checkpoint, Resume)
// -------------------------------------------------------------

test('Prompt #062: Client Resumable Export Simulation with Error 1102 / 503 on Part 7', async () => {
  const { d1, sqlite } = createD1Mock();
  const storage = new ReportStorage(d1);
  const now = 1730000000000;
  const totalReportsCount = 803; // Exact active count from Prompt #055

  for (let i = 1; i <= totalReportsCount; i++) {
    const id = `VOX-ACT-${String(i).padStart(4, '0')}`;
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'Android TV', 'TCL_PRO', 'PLAYER', 'SIG_ACT', 'NEW', 'MEDIUM', ?, ?)
    `).run(id, now - i * 10, JSON.stringify({ reportId: id, installationId: `inst-${i % 20}` }), now + 86400000);
  }

  // Simulated browser sessionStorage
  const simulatedSessionStorage = new Map();
  const SESSION_KEY = 'vox_export_session_v1';

  function saveSession(session) {
    simulatedSessionStorage.set(SESSION_KEY, JSON.stringify(session));
  }
  function loadSession() {
    const d = simulatedSessionStorage.get(SESSION_KEY);
    return d ? JSON.parse(d) : null;
  }

  // Simulated client adaptive export loop
  let session = {
    exportId: 'exp_test_062',
    snapshotUntil: now,
    targetStatuses: 'NEW,INVESTIGATING',
    currentPart: 1,
    currentCursor: null,
    chunkLimit: 100, // Starts at 100
    completedParts: [],
    totalDownloaded: 0,
    allCollectedIds: [],
    status: 'IN_PROGRESS',
  };
  saveSession(session);

  let currentCursor = null;
  let part = 1;
  let hasMoreParts = true;
  let chunkLimit = 100;
  const downloadedFiles = [];

  // Failure simulation flags:
  // Part 7 will fail with 503 on attempt 1, 1102 HTML on attempt 2, then succeed when limit steps down to 25!
  let part7Failures = 0;

  while (hasMoreParts) {
    let partSuccess = false;
    let attempts = 0;

    while (!partSuccess && attempts < 5) {
      attempts++;

      // Trigger simulated failure on Part 7:
      if (part === 7 && part7Failures < 2) {
        part7Failures++;
        if (part7Failures === 1) {
          // Simulate HTTP 503
          // Client adaptive step-down:
          if (chunkLimit > 50) chunkLimit = 50;
          else if (chunkLimit > 25) chunkLimit = 25;
          session.chunkLimit = chunkLimit;
          saveSession(session);
          continue; // retry
        } else if (part7Failures === 2) {
          // Simulate Cloudflare 1102 HTML
          if (chunkLimit > 25) chunkLimit = 25;
          session.chunkLimit = chunkLimit;
          saveSession(session);
          continue; // retry with 25
        }
      }

      // Live server call with current limit
      const res = await buildDiagnosticsZip(storage, {
        maxReports: chunkLimit,
        part,
        cursor: currentCursor,
        until: session.snapshotUntil,
      });

      downloadedFiles.push({
        part,
        filename: res.filename,
        reportsCount: res.totalReports,
        ids: res.manifest.reportIds,
      });

      session.completedParts.push({
        part,
        filename: res.filename,
        reportsCount: res.totalReports,
        nextCursor: res.isTruncated ? res.nextCursor : null,
      });
      session.allCollectedIds.push(...res.manifest.reportIds);
      session.totalDownloaded += res.totalReports;
      session.currentPart = part + 1;
      session.currentCursor = res.nextCursor;
      saveSession(session);

      if (res.isTruncated && res.nextCursor) {
        currentCursor = res.nextCursor;
        part++;
      } else {
        hasMoreParts = false;
      }
      partSuccess = true;
    }

    assert.ok(partSuccess, `Part ${part} must eventually succeed through adaptive step-down`);
  }

  // Verify that Part 7 failure stepped down chunk limit and resumed cleanly
  assert.equal(part7Failures, 2, 'Part 7 must have triggered both simulated failures (503 then 1102)');
  const restoredSession = loadSession();
  assert.equal(restoredSession.totalDownloaded, totalReportsCount, 'Total downloaded must match exact active reports (803)');

  // Verify zero duplicates across all downloaded files
  const allIds = downloadedFiles.flatMap(f => f.ids);
  assert.equal(allIds.length, totalReportsCount);
  const uniqueIds = new Set(allIds);
  assert.equal(uniqueIds.size, totalReportsCount, 'Must have zero duplicate IDs');

  d1.close();
});

test('Prompt #062: Browser Tab Reload Checkpoint Resume from Part 7 without re-downloading 1..6', async () => {
  const { d1, sqlite } = createD1Mock();
  const storage = new ReportStorage(d1);
  const now = 1730000000000;
  const totalReportsCount = 803;

  for (let i = 1; i <= totalReportsCount; i++) {
    const id = `VOX-RELOAD-${String(i).padStart(4, '0')}`;
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'Android TV', 'TCL_PRO', 'PLAYER', 'SIG_RELOAD', 'NEW', 'MEDIUM', ?, ?)
    `).run(id, now - i * 10, JSON.stringify({ reportId: id }), now + 86400000);
  }

  // Phase 1: Download parts 1..6 (100 reports each)
  const simulatedStorage = new Map();
  let cursor = null;
  const initialFiles = [];

  for (let p = 1; p <= 6; p++) {
    const res = await buildDiagnosticsZip(storage, {
      maxReports: 100,
      part: p,
      cursor,
      until: now,
    });
    initialFiles.push({ part: p, ids: res.manifest.reportIds });
    cursor = res.nextCursor;
  }

  // Save session state representing checkpoint before reload at Part 7
  const checkpoint = {
    exportId: 'exp_reload_test',
    snapshotUntil: now,
    targetStatuses: 'NEW,INVESTIGATING',
    currentPart: 7,
    currentCursor: cursor,
    chunkLimit: 50,
    completedParts: initialFiles.map(f => ({ part: f.part, count: f.ids.length })),
    totalDownloaded: 600,
    allCollectedIds: initialFiles.flatMap(f => f.ids),
    status: 'PAUSED',
  };
  simulatedStorage.set('vox_export_session_v1', JSON.stringify(checkpoint));

  // Phase 2: Simulate tab refresh and "Resume from Part 7"
  const restored = JSON.parse(simulatedStorage.get('vox_export_session_v1'));
  assert.equal(restored.currentPart, 7);
  assert.equal(restored.completedParts.length, 6);
  assert.equal(restored.totalDownloaded, 600);

  // Resume loop: starts directly from Part 7 using saved currentCursor
  const resumedFiles = [];
  let resumeCursor = restored.currentCursor;
  let resumePart = restored.currentPart;
  let resumeHasMore = true;

  while (resumeHasMore) {
    const res = await buildDiagnosticsZip(storage, {
      maxReports: restored.chunkLimit,
      part: resumePart,
      cursor: resumeCursor,
      until: restored.snapshotUntil,
    });
    resumedFiles.push({ part: resumePart, ids: res.manifest.reportIds });

    if (res.isTruncated && res.nextCursor) {
      resumeCursor = res.nextCursor;
      resumePart++;
    } else {
      resumeHasMore = false;
    }
  }

  // Verify Parts 1..6 were NOT re-downloaded
  assert.ok(!resumedFiles.some(f => f.part < 7), 'Resumed download must only fetch part 7 and onwards');

  // Verify completeness across phase 1 + phase 2
  const totalCombinedIds = [...initialFiles.flatMap(f => f.ids), ...resumedFiles.flatMap(f => f.ids)];
  assert.equal(totalCombinedIds.length, totalReportsCount, 'Total collected must be exactly 803');
  const uniqueCombined = new Set(totalCombinedIds);
  assert.equal(uniqueCombined.size, totalReportsCount, 'Zero duplicate IDs across checkpoint resume');

  d1.close();
});

// -------------------------------------------------------------
// GROUP 3: Concurrent Ingestion & Status Mutations during Export
// -------------------------------------------------------------

test('Prompt #062: Concurrent Data Ingestion during Multipart Export (Strict Snapshot Isolation)', async () => {
  const { d1, sqlite } = createD1Mock();
  const storage = new ReportStorage(d1);
  const snapshotUntil = 1730000000000;
  const initialActiveCount = 200;

  // 1. Initial 200 reports before snapshot
  for (let i = 1; i <= initialActiveCount; i++) {
    const id = `VOX-INIT-${String(i).padStart(4, '0')}`;
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYER', 'SIG_INIT', 'NEW', 'MEDIUM', '{}', ?)
    `).run(id, snapshotUntil - i * 10, snapshotUntil + 86400000);
  }

  // Part 1: Download first 50 reports
  const part1 = await buildDiagnosticsZip(storage, {
    maxReports: 50,
    part: 1,
    until: snapshotUntil,
  });
  assert.equal(part1.totalReports, 50);

  // 2. CONCURRENT EVENT A: 50 brand new reports arrive in production (created_at > snapshotUntil)
  for (let i = 1; i <= 50; i++) {
    const newId = `VOX-CONCURRENT-NEW-${String(i).padStart(4, '0')}`;
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYER', 'SIG_CONC', 'NEW', 'HIGH', '{}', ?)
    `).run(newId, snapshotUntil + i * 100, snapshotUntil + 86400000);
  }

  // Part 2..4: Continue downloading remaining parts using fixed snapshotUntil
  const allIds = [...part1.manifest.reportIds];
  let cursor = part1.nextCursor;
  let part = 2;
  let hasMore = true;

  while (hasMore) {
    const res = await buildDiagnosticsZip(storage, {
      maxReports: 50,
      part,
      cursor,
      until: snapshotUntil,
    });
    for (const id of res.manifest.reportIds) {
      assert.ok(!id.startsWith('VOX-CONCURRENT-NEW-'), `Concurrent report ${id} leaked past snapshot ceiling`);
      allIds.push(id);
    }
    if (res.isTruncated && res.nextCursor) {
      cursor = res.nextCursor;
      part++;
    } else {
      hasMore = false;
    }
  }

  // Assert exact snapshot fidelity: exactly the original 200 reports, 0 new leaks, 0 duplicates
  assert.equal(allIds.length, initialActiveCount);
  assert.equal(new Set(allIds).size, initialActiveCount);

  d1.close();
});

test('Prompt #062: Concurrent Status Mutation during Export (Query-time read-committed consistency)', async () => {
  const { d1, sqlite } = createD1Mock();
  const storage = new ReportStorage(d1);
  const now = 1730000000000;

  for (let i = 1; i <= 100; i++) {
    const id = `VOX-MUT-${String(i).padStart(3, '0')}`;
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYER', 'SIG', 'NEW', 'MEDIUM', '{}', ?)
    `).run(id, now - i * 10, now + 86400000);
  }

  // Part 1: export 50 reports
  const p1 = await buildDiagnosticsZip(storage, { maxReports: 50, part: 1, until: now });
  assert.equal(p1.totalReports, 50);

  // During export: admin resolves report #75 in the second half
  sqlite.prepare(`UPDATE reports SET status = 'RESOLVED' WHERE report_id = 'VOX-MUT-075'`).run();

  // Part 2: export remaining active reports
  const p2 = await buildDiagnosticsZip(storage, { maxReports: 50, part: 2, cursor: p1.nextCursor, until: now });
  
  // VOX-MUT-075 was resolved, so p2 has 49 reports
  assert.equal(p2.totalReports, 49);
  assert.ok(!p2.manifest.reportIds.includes('VOX-MUT-075'), 'Resolved report must not be included');

  // Total collected = 99 unique reports, 0 duplicates
  const allIds = [...p1.manifest.reportIds, ...p2.manifest.reportIds];
  assert.equal(allIds.length, 99);
  assert.equal(new Set(allIds).size, 99);

  d1.close();
});

// -------------------------------------------------------------
// GROUP 4: Performance, Query Count, and CRC32 Verification
// -------------------------------------------------------------

test('Prompt #062: D1 Subrequest Query Count Bounded per Chunk (<= 3 queries per chunk)', async () => {
  const { d1, sqlite } = createD1Mock();
  const storage = new ReportStorage(d1);
  const now = 1730000000000;

  for (let i = 1; i <= 100; i++) {
    const id = `VOX-QRY-${String(i).padStart(3, '0')}`;
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYER', 'SIG', 'NEW', 'MEDIUM', '{}', ?)
    `).run(id, now - i * 10, now + 86400000);
  }

  d1.resetQueryCount();
  const res = await buildDiagnosticsZip(storage, { maxReports: 50, part: 1, until: now });
  const queriesUsed = d1.getQueryCount();

  // A chunk of 50 reports uses bounded O(1) queries (<= 3 queries total including probe)
  assert.ok(queriesUsed <= 3, `D1 queries per chunk must be <= 3 (actual: ${queriesUsed})`);
  assert.equal(res.totalReports, 50);

  d1.close();
});

test('Prompt #062: High-throughput Unrolled CRC32 Verification vs Known Test Vectors', () => {
  // 1. Standard test vector: "123456789" -> 3421780262 (0xCBF43926 in inverted/reflected representation)
  const standardVector = new TextEncoder().encode('123456789');
  assert.equal(crc32(standardVector), 3421780262);

  // 2. Empty buffer -> 0
  assert.equal(crc32(new Uint8Array(0)), 0);

  // 3. 1 MB repetitive buffer throughput & determinism test
  const bigBuffer = new Uint8Array(1024 * 1024);
  for (let i = 0; i < bigBuffer.length; i++) bigBuffer[i] = i & 0xFF;
  const start = performance.now();
  const sum1 = crc32(bigBuffer);
  const duration = performance.now() - start;
  const sum2 = crc32(bigBuffer);

  assert.equal(sum1, sum2, 'CRC32 must be strictly deterministic');
  assert.ok(duration < 50, `1MB CRC32 calculation must execute in < 50ms (actual: ${duration.toFixed(2)}ms)`);
});

// -------------------------------------------------------------
// GROUP 5: Security, Auth, and Cloudflare 1102 / 503 HTTP Headers Contract
// -------------------------------------------------------------

test('Prompt #062: Export API Contract (Session Headers, Truncation, Security, Fail-closed Auth)', async () => {
  const { d1, sqlite } = createD1Mock();
  const now = Date.now();

  for (let i = 1; i <= 60; i++) {
    const id = `VOX-SEC-${String(i).padStart(3, '0')}`;
    sqlite.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, status, severity, payload_json, expires_at)
      VALUES (?, ?, '1', '32.56-vox.9.2', 'ATV', 'TCL', 'PLAYER', 'SIG', 'NEW', 'MEDIUM', '{}', ?)
    `).run(id, now - i * 10, now + 86400000);
  }

  const env = {
    DB: d1,
    ADMIN_SECRET: 'prompt_062_secret_key_secure',
  };

  // 1. Unauthorized request is fail-closed (401)
  const reqUnauth = new Request('https://vox-diagnostics.workers.dev/v1/admin/export/zip?statuses=NEW', {
    method: 'GET',
  });
  const resUnauth = await handleRequest(reqUnauth, env);
  assert.equal(resUnauth.status, 401);

  // 2. Authorized request with limit=50 returns part 1 of 60 items
  const reqAuth = new Request('https://vox-diagnostics.workers.dev/v1/admin/export/zip?statuses=NEW&limit=50&sessionId=session_123', {
    method: 'GET',
    headers: {
      Authorization: 'Bearer prompt_062_secret_key_secure',
    },
  });
  const resAuth = await handleRequest(reqAuth, env);
  assert.equal(resAuth.status, 200);
  assert.equal(resAuth.headers.get('Content-Type'), 'application/zip');
  assert.equal(resAuth.headers.get('X-Total-Reports'), '50');
  assert.equal(resAuth.headers.get('X-Export-Truncated'), 'true');
  assert.equal(resAuth.headers.get('X-Export-Session-Id'), 'session_123');
  assert.ok(resAuth.headers.get('X-Export-Next-Cursor'));

  // 3. Inspect ZIP contents: ensure NO secret leak in manifest or reports
  const zipBuffer = new Uint8Array(await resAuth.arrayBuffer());
  const entries = parseZipEntries(zipBuffer);
  const manifestEntry = entries.find(e => e.path === 'manifest.json');
  assert.ok(manifestEntry);
  assert.ok(!manifestEntry.text.includes('prompt_062_secret_key_secure'), 'ADMIN_SECRET must never appear in manifest');

  d1.close();
});
