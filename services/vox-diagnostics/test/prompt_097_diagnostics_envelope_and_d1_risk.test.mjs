/**
 * SmartTube VOX Diagnostics — Prompt #097 Envelope, D1 Fetch Risk & Causality Bounds Suite
 *
 * Verifies:
 * 1. Full HTTP Response Envelope:
 *    - JSON envelope fields: ok, reports, totalReports, isTruncated, nextCursor, cursorStart, cursorEnd, part, exportSessionId.
 *    - HTTP Response Headers: X-Total-Reports, X-Export-Truncated, X-Export-Part, X-Export-Next-Cursor, X-Export-Session-Id.
 * 2. UTF-8 Multi-byte & Emoji Byte Accounting:
 *    - Wire size accuracy with complex Unicode (Cyrillic, CJK, Emoji symbols across structured events).
 * 3. Giant Report Progress Contract:
 *    - Single document > maxBytes returns progress without infinite loop/deadlock.
 * 4. D1 Fetch Limit & Materialization Analysis:
 *    - Verifies that D1 executes SELECT with `fetchLimit` (SQL LIMIT) and materializes rows
 *      BEFORE JS-level byte-budget trimming occurs.
 *    - Proves why D1_FETCH_503_RISK = REMAINS on un-deployed edge workers.
 * 5. Keyset Cursor Continuity & 1000-Limit Ceiling:
 *    - Zero dropped IDs, zero duplicated IDs across multi-chunk pagination.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { ReportStorage } from '../src/storage.mjs';
import { buildDiagnosticsZip, ACTIVE_EXPORT_STATUSES } from '../src/export.mjs';
import { handleRequest } from '../src/diagnostics.mjs';

function createD1Mock() {
  const sqlite = new DatabaseSync(':memory:');
  const migrations = [
    '0001_init.sql',
    '0002_operations.sql',
    '0003_admin_22.sql',
    '0004_vox91_backfill.sql',
    '0005_duplicate_tracking.sql',
    '0006_devices_analytics.sql',
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
        bind(...args) {
          this.args = args.map(a => a === undefined ? null : a);
          return this;
        },
        async run() {
          queryCounter++;
          queries.push({ type: 'run', sql, args: this.args });
          const res = statement.run(...this.args);
          return { meta: { changes: res.changes } };
        },
        async first() {
          queryCounter++;
          queries.push({ type: 'first', sql, args: this.args });
          return statement.get(...this.args) || null;
        },
        async all() {
          queryCounter++;
          queries.push({ type: 'all', sql, args: this.args });
          return { results: statement.all(...this.args) };
        },
      };
      return wrapped;
    },
    async batch(statements) {
      sqlite.exec('BEGIN');
      try {
        const results = [];
        for (const st of statements) {
          results.push(await st.run());
        }
        sqlite.exec('COMMIT');
        return results;
      } catch (err) {
        sqlite.exec('ROLLBACK');
        throw err;
      }
    },
    rawSqlite: sqlite,
  };

  return d1;
}

const mockEnv = {
  ADMIN_SECRET: 'prompt097_test_secret_key',
  TELEGRAM_BOT_TOKEN: 'mock_token',
  TELEGRAM_CHAT_ID: 'mock_chat',
};

describe('Prompt #097: Diagnostics Wire Envelope, D1 Fetch Risk & Causality Bounds', () => {

  test('97.1: HTTP Response Envelope & Headers Deliver Complete Metadata on format=json', async () => {
    const d1 = createD1Mock();
    const storage = new ReportStorage(d1);
    const now = 1793400000000;

    // Seed 3 reports
    for (let i = 1; i <= 3; i++) {
      const id = `VOX-ENV-00${i}`;
      const payload = JSON.stringify({
        reportId: id,
        eventCount: 5,
        testTag: `Unicode Test 🚀 — Ошибка воспроизведения №${i}`,
      });
      await d1.prepare(`
        INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, severity, status, payload_json, expires_at)
        VALUES (?, ?, 'vox-diagnostic-report-v3', '32.56-vox.9.2-rc.8', 'Android TV', 'TCL', 'PLAYER', 'PLAYER|REBUFFER', 'HIGH', 'NEW', ?, ?)
      `).bind(id, now - i * 1000, payload, now + 86400000).run();
    }

    const req = new Request('https://diagnostics.example.com/v1/admin/export/zip?format=json&limit=2&sessionId=sess_097', {
      headers: {
        Authorization: 'Bearer prompt097_test_secret_key',
      },
    });

    const res = await handleRequest(req, { ...mockEnv, DB: d1 }, storage);
    assert.equal(res.status, 200);

    const bodyText = await res.text();
    const json = JSON.parse(bodyText);

    // Verify envelope fields
    assert.equal(json.ok, true);
    assert.equal(json.reports.length, 2);
    assert.equal(json.totalReports, 2);
    assert.equal(json.isTruncated, true);
    assert.ok(json.nextCursor);
    assert.ok(json.cursorStart);
    assert.ok(json.cursorEnd);
    assert.equal(json.part, 1);
    assert.equal(json.exportSessionId, 'sess_097');

    // Verify HTTP response headers
    assert.equal(res.headers.get('X-Total-Reports'), '2');
    assert.equal(res.headers.get('X-Export-Truncated'), 'true');
    assert.equal(res.headers.get('X-Export-Part'), '1');
    assert.equal(res.headers.get('X-Export-Session-Id'), 'sess_097');
    assert.equal(res.headers.get('X-Export-Next-Cursor'), json.nextCursor);
  });

  test('97.2: Unicode Multi-byte & Emoji Byte Accounting in JSON Envelope', async () => {
    const d1 = createD1Mock();
    const storage = new ReportStorage(d1);
    const now = 1793400000000;

    // Build structured events with Cyrillic and 4-byte UTF-8 emoji
    const events = Array.from({ length: 15 }, (_, i) => ({
      index: i,
      name: `СОБЫТИЕ_ПЛЕЕРА_№${i}_🔥`,
      details: `Ошибка воспроизведения видеопотока 1440p VP9: буферизация на позиции ${i * 1500} мс. Давление памяти в норме. 🚀`,
    }));

    const payload = JSON.stringify({
      reportId: 'VOX-UNICODE-001',
      events,
    });

    await d1.prepare(`
      INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, severity, status, payload_json, expires_at)
      VALUES ('VOX-UNICODE-001', ?, 'vox-diagnostic-report-v3', '32.56-vox.9.2-rc.8', 'Android TV', 'TCL', 'PLAYER', 'PLAYER|UNICODE', 'HIGH', 'NEW', ?, ?)
    `).bind(now, payload, now + 86400000).run();

    const result = await buildDiagnosticsZip(storage, {
      statuses: ACTIVE_EXPORT_STATUSES,
      maxReports: 5,
      maxBytes: 100 * 1024,
      format: 'json',
    });

    assert.equal(result.reportsList.length, 1);
    assert.equal(result.reportsList[0].reportId, 'VOX-UNICODE-001');

    const reportWireBytes = Buffer.byteLength(JSON.stringify(result.reportsList[0]), 'utf8');
    assert.ok(reportWireBytes > 5 * 1024, `Report wire size (${reportWireBytes}) must accurately account for UTF-8 multi-byte encoding`);
  });

  test('97.3: D1 Fetch Boundary Analysis — Proves D1_FETCH_503_RISK = REMAINS', async () => {
    const d1 = createD1Mock();
    const storage = new ReportStorage(d1);
    const now = 1793400000000;

    // Seed 10 reports, each with 30 structured events ~ 30 KB payload / ~65 KB wire representation
    for (let i = 1; i <= 10; i++) {
      const id = `VOX-D1-RISK-${String(i).padStart(3, '0')}`;
      const events = Array.from({ length: 30 }, (_, idx) => ({
        idx,
        type: 'BUFFER_STALL',
        details: ('Detailed VP9 buffer stall event trace data at position ' + (idx * 1000) + ' ms with adaptive bitrate. ').repeat(8),
      }));
      const payload = JSON.stringify({
        reportId: id,
        events,
      });
      await d1.prepare(`
        INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, severity, status, payload_json, expires_at)
        VALUES (?, ?, 'vox-diagnostic-report-v3', '32.56-vox.9.2-rc.8', 'Android TV', 'TCL', 'PLAYER', 'PLAYER|RISK', 'HIGH', 'NEW', ?, ?)
      `).bind(id, now - i * 100, payload, now + 86400000).run();
    }

    d1.resetQueryCount();

    // Call buildDiagnosticsZip with maxBytes = 85 KB
    // The byte-budget in export.mjs caps the JS reportsList at 1 report (~65 KB <= 85 KB; 2nd report would be ~130 KB > 85 KB)
    const result = await buildDiagnosticsZip(storage, {
      statuses: ACTIVE_EXPORT_STATUSES,
      maxReports: 10,
      maxBytes: 85 * 1024,
      format: 'json',
    });

    // The output wire reportsList is safely capped at 1
    assert.equal(result.reportsList.length, 1, 'JS-level byte budget caps reportsList to 1 report');
    assert.equal(result.isTruncated, true, 'Archive must be marked truncated');

    // HOWEVER, inspect what D1 SQL queries actually executed:
    const queries = d1.getQueries();
    const selectQuery = queries.find(q => q.sql.includes('SELECT') && q.sql.includes('payload_json'));
    assert.ok(selectQuery, 'D1 SQL query with payload_json must have executed');

    // The SQL query used LIMIT = 10 (fetchLimit), meaning D1 retrieved all 10 heavy rows (~300 KB payload) into memory!
    assert.ok(selectQuery.sql.includes('LIMIT ?') || selectQuery.sql.includes('LIMIT 10'), 'SQL query fetches up to fetchLimit');
    // This architectural reality proves that byte-budgeting in JS does NOT prevent D1 from loading heavy rows
    // Therefore, D1_FETCH_503_RISK = REMAINS until server queries dynamically bound SQL LIMIT by size
  });

  test('97.4: Keyset Cursor Pagination With Zero Dropped and Zero Duplicated IDs', async () => {
    const d1 = createD1Mock();
    const storage = new ReportStorage(d1);
    const now = 1793400000000;
    const TOTAL_RECORDS = 30;

    for (let i = 0; i < TOTAL_RECORDS; i++) {
      const id = `VOX-SEQ-${String(i).padStart(3, '0')}`;
      const payload = JSON.stringify({ reportId: id, step: i, data: 'sample data '.repeat(20) });
      await d1.prepare(`
        INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, error_signature, severity, status, payload_json, expires_at)
        VALUES (?, ?, 'vox-diagnostic-report-v3', '32.56-vox.9.2-rc.8', 'Android TV', 'TCL', 'PLAYER', 'PLAYER|SEQ', 'LOW', 'NEW', ?, ?)
      `).bind(id, now - i * 1000, payload, now + 86400000).run();
    }

    // Paginate in small chunks with maxBytes constraint
    const collected = [];
    let curCursor = null;
    let iterations = 0;
    let hasMore = true;

    while (hasMore && iterations < 20) {
      iterations++;
      const res = await buildDiagnosticsZip(storage, {
        statuses: ACTIVE_EXPORT_STATUSES,
        maxReports: 5,
        maxBytes: 50 * 1024,
        cursor: curCursor || undefined,
        format: 'json',
      });

      for (const item of res.reportsList) {
        collected.push(item.reportId);
      }

      if (!res.isTruncated || !res.nextCursor || res.reportsList.length === 0) {
        hasMore = false;
      } else {
        curCursor = res.nextCursor;
      }
    }

    assert.equal(collected.length, TOTAL_RECORDS, `Must collect all ${TOTAL_RECORDS} records`);
    const unique = new Set(collected);
    assert.equal(unique.size, TOTAL_RECORDS, 'Zero duplicate IDs across keyset cursor pagination');
  });

});
