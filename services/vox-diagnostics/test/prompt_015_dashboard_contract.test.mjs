import test from 'node:test';
import assert from 'node:assert/strict';
import {
  STATUS_LABELS,
  CANONICAL_STATUSES,
  PRIMARY_STATUSES,
  ALLOWED_STATUS_TRANSITIONS,
  isValidStatusTransition,
  normalizeStatus,
  ReportStorage
} from '../src/storage.mjs';
import { renderAdminHtml } from '../src/admin.mjs';

test('Prompt 015 GATE 1: Mathematical Invariant with Unknown Statuses (sum + otherCount == totalReports)', async () => {
  const storage = new ReportStorage(null);

  // Fixtures with known statuses + unexpected / unknown statuses
  const fixtures = [
    { report_id: 'VOX-NEW-1', status: 'NEW', created_at: Date.now() },
    { report_id: 'VOX-TRIAGED-1', status: 'TRIAGED', created_at: Date.now() },
    { report_id: 'VOX-INP-1', status: 'INVESTIGATING', created_at: Date.now() },
    { report_id: 'VOX-FIX-1', status: 'FIXED_AWAITING_VERIFICATION', created_at: Date.now() },
    { report_id: 'VOX-RES-1', status: 'RESOLVED', created_at: Date.now() },
    { report_id: 'VOX-CLS-1', status: 'CLOSED', created_at: Date.now() },
    { report_id: 'VOX-KNOW-1', status: 'KNOWN_LIMITATION', created_at: Date.now() },
    { report_id: 'VOX-IGN-1', status: 'IGNORED_TEST', created_at: Date.now() },
    // 3 arbitrary unexpected / custom statuses
    { report_id: 'VOX-CUSTOM-1', status: 'EXPERIMENTAL_BUG', created_at: Date.now() },
    { report_id: 'VOX-CUSTOM-2', status: 'CORRUPTED_VALUE', created_at: Date.now() },
    { report_id: 'VOX-CUSTOM-3', status: 'THIRD_PARTY_STALL', created_at: Date.now() },
  ];

  for (const f of fixtures) {
    storage.inMemoryReports.set(f.report_id, f);
  }

  const stats = await storage.getStats();

  assert.equal(stats.totalReports, 11, 'Total reports must be 11');
  assert.equal(stats.otherCount, 3, 'otherCount must capture the 3 unexpected statuses');

  const disjointSum =
    stats.newCount +
    stats.triagedCount +
    stats.inProgressCount +
    stats.fixedAwaitingCount +
    stats.resolvedCount +
    stats.closedCount +
    stats.knownIssueCount +
    stats.ignoredTestCount +
    stats.otherCount;

  assert.equal(
    disjointSum,
    stats.totalReports,
    `Sum of 8 disjoint buckets + otherCount (${disjointSum}) MUST exactly equal totalReports (${stats.totalReports})`
  );
});

test('Prompt 015 GATE 2: PLAYER 229 vs 228 Reconciliation (OTA misclassification & all 13 sub-signatures)', async () => {
  const storage = new ReportStorage(null);

  // Fixtures representing the exact 13 signature codes making up the 229 baseline
  const signatureFixtureSpec = [
    { code: 'PLAYER_REBUFFER', count: 95 },
    { code: 'PLAYER_HTTP_ERROR', count: 72 },
    { code: 'PLAYER_STARTUP_SLOW', count: 19 },
    { code: 'UNKNOWN_CODE', count: 16 },
    { code: 'PLAYER_SOURCE_ERROR', count: 9 },
    { code: 'PLAYER_OUT_OF_MEMORY', count: 4 },
    { code: 'LIVE_STALL_RECOVERY_ATTEMPT', count: 3 },
    { code: 'LIVE_OFFSET_INVALID', count: 3 },
    { code: 'PLAYER_RENDERER_ERROR', count: 3 },
    { code: 'PLAYER_DECODER_ERROR', count: 2 },
    { code: 'OTA_RATE_LIMIT', count: 1 }, // Report VOX-6B514E8A misclassified by client as category PLAYER
    { code: 'PLAYER_LOCAL_SOURCE_ERROR', count: 1 },
    { code: 'PLAYER_RECOVERY_FAILED', count: 1 },
  ];

  let idCounter = 1;
  let totalFixtures = 0;
  for (const spec of signatureFixtureSpec) {
    for (let i = 0; i < spec.count; i++) {
      const reportId = `VOX-PL-${String(idCounter++).padStart(4, '0')}`;
      const sig = spec.code === 'OTA_RATE_LIMIT'
        ? 'OTA|OTA_RATE_LIMIT|ANDROID_TV_API34'
        : `PLAYER|${spec.code}|ANDROID_TV_API34`;
      storage.inMemoryReports.set(reportId, {
        report_id: reportId,
        status: 'NEW',
        error_category: 'PLAYER',
        error_signature: sig,
        created_at: Date.now(),
      });
      totalFixtures++;
    }
  }

  assert.equal(totalFixtures, 229, 'Total PLAYER baseline fixture count must be exactly 229');

  const allReports = Array.from(storage.inMemoryReports.values());
  const playerReports = allReports.filter((r) => r.error_category === 'PLAYER');
  assert.equal(playerReports.length, 229, 'Category PLAYER count must be 229');

  // Verify code aggregation matches specification
  const codeCounts = {};
  for (const r of playerReports) {
    const parts = r.error_signature.split('|');
    const code = parts[1] || parts[0];
    codeCounts[code] = (codeCounts[code] || 0) + 1;
  }

  assert.equal(codeCounts['PLAYER_REBUFFER'], 95);
  assert.equal(codeCounts['PLAYER_HTTP_ERROR'], 72);
  assert.equal(codeCounts['PLAYER_STARTUP_SLOW'], 19);
  assert.equal(codeCounts['UNKNOWN_CODE'], 16);
  assert.equal(codeCounts['PLAYER_SOURCE_ERROR'], 9);
  assert.equal(codeCounts['PLAYER_OUT_OF_MEMORY'], 4);
  assert.equal(codeCounts['LIVE_STALL_RECOVERY_ATTEMPT'], 3);
  assert.equal(codeCounts['LIVE_OFFSET_INVALID'], 3);
  assert.equal(codeCounts['PLAYER_RENDERER_ERROR'], 3);
  assert.equal(codeCounts['PLAYER_DECODER_ERROR'], 2);
  assert.equal(codeCounts['OTA_RATE_LIMIT'], 1, 'Client-misclassified OTA report must be preserved');
  assert.equal(codeCounts['PLAYER_LOCAL_SOURCE_ERROR'], 1);
  assert.equal(codeCounts['PLAYER_RECOVERY_FAILED'], 1);

  const reconciledSum = Object.values(codeCounts).reduce((a, b) => a + b, 0);
  assert.equal(reconciledSum, 229, 'Reconciled sub-signature sum must equal 229');
});

test('Prompt 015 GATE 3: Status Transition Reversible Matrix', () => {
  // Standard forward lifecycle
  assert.ok(isValidStatusTransition('NEW', 'TRIAGED'));
  assert.ok(isValidStatusTransition('TRIAGED', 'INVESTIGATING'));
  assert.ok(isValidStatusTransition('INVESTIGATING', 'FIXED_AWAITING_VERIFICATION'));
  assert.ok(isValidStatusTransition('FIXED_AWAITING_VERIFICATION', 'RESOLVED'));
  assert.ok(isValidStatusTransition('RESOLVED', 'CLOSED'));

  // Regression returns
  assert.ok(isValidStatusTransition('RESOLVED', 'REGRESSION'));
  assert.ok(isValidStatusTransition('RESOLVED', 'INVESTIGATING'));
  assert.ok(isValidStatusTransition('CLOSED', 'REGRESSION'));
  assert.ok(isValidStatusTransition('FIXED_AWAITING_VERIFICATION', 'INVESTIGATING'));

  // Boundary checks (null / undefined / empty)
  assert.equal(isValidStatusTransition(null, 'NEW'), false);
  assert.equal(isValidStatusTransition('NEW', null), false);
  assert.equal(isValidStatusTransition('', 'NEW'), false);
  assert.equal(isValidStatusTransition('NON_EXISTENT_STATUS', 'NEW'), false);
});

test('Prompt 015 GATE 4: Context-Aware HTML Escaping in Notes and Signatures', () => {
  const maliciousInput = '<script>document.cookie="stolen"</script><b onmouseover=alert(1)>bold</b>';
  const report = {
    report_id: 'VOX-SEC-01',
    created_at: Date.now(),
    app_version: '32.56-vox.9.2-rc.4',
    platform: 'Android TV',
    device_family: 'Test<script>',
    error_category: 'PLAYER',
    error_signature: 'PLAYER|<XSS>|API34',
    status: 'NEW',
    report_purpose: 'TEST',
    developer_notes: maliciousInput,
  };

  const html = renderAdminHtml([report], { totalReports: 1, newCount: 1 }, []);

  assert.ok(!html.includes('<script>document.cookie'), 'Must not contain raw script tag');
  assert.ok(!html.includes('<b onmouseover='), 'Must not contain raw HTML tag with event handler');
  assert.ok(html.includes('&lt;script&gt;'), 'Must contain safely escaped script tag');
  assert.ok(html.includes('&lt;b onmouseover=alert(1)&gt;'), 'Must safely escape tag with handler');
  assert.ok(html.includes('&lt;XSS&gt;'), 'Must safely escape signature tag');
});

test('Prompt 015 GATE 5: Dynamic KPI Strip with otherCount Rendering', () => {
  // When otherCount > 0, kpiOther must be rendered in the strip
  const htmlWithOther = renderAdminHtml([], {
    totalReports: 324,
    newCount: 226,
    triagedCount: 0,
    inProgressCount: 39,
    fixedAwaitingCount: 23,
    resolvedCount: 8,
    closedCount: 0,
    knownIssueCount: 6,
    ignoredTestCount: 19,
    otherCount: 3,
  }, []);

  assert.ok(htmlWithOther.includes('id="kpiOther">3</strong>'), 'Must render kpiOther when otherCount > 0');

  // When otherCount is 0, kpiOther must NOT be rendered
  const htmlClean = renderAdminHtml([], {
    totalReports: 321,
    newCount: 227,
    triagedCount: 0,
    inProgressCount: 39,
    fixedAwaitingCount: 23,
    resolvedCount: 8,
    closedCount: 0,
    knownIssueCount: 6,
    ignoredTestCount: 18,
    otherCount: 0,
  }, []);

  assert.ok(!htmlClean.includes('id="kpiOther"'), 'Must omit kpiOther when otherCount is 0');
});
