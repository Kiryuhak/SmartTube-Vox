import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

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
import { ADMIN_APP_JS } from '../src/admin_client.mjs';

test('Prompt 014 GATE: Disjoint Status Buckets Invariant sum(buckets) == totalReports', async () => {
  const storage = new ReportStorage(null);

  // Seed storage with synthetic fixtures covering ALL 8 status groups
  const fixtures = [
    { report_id: 'VOX-NEW-01', status: 'NEW', created_at: Date.now() },
    { report_id: 'VOX-NEW-02', status: 'NEW', created_at: Date.now() },
    { report_id: 'VOX-TRIAGED-01', status: 'TRIAGED', created_at: Date.now() },
    { report_id: 'VOX-INV-01', status: 'INVESTIGATING', created_at: Date.now() },
    { report_id: 'VOX-INP-01', status: 'IN_PROGRESS', created_at: Date.now() },
    { report_id: 'VOX-ROOT-01', status: 'ROOT_CAUSE_FOUND', created_at: Date.now() },
    { report_id: 'VOX-FIX-01', status: 'FIX_IMPLEMENTED', created_at: Date.now() },
    { report_id: 'VOX-TEST-01', status: 'TESTING', created_at: Date.now() },
    { report_id: 'VOX-FIXED-AWAIT-01', status: 'FIXED_AWAITING_VERIFICATION', created_at: Date.now() },
    { report_id: 'VOX-FIXED-PEND-01', status: 'FIXED_PENDING_VERIFICATION', created_at: Date.now() },
    { report_id: 'VOX-RES-01', status: 'RESOLVED', created_at: Date.now() },
    { report_id: 'VOX-VER-01', status: 'VERIFIED', created_at: Date.now() },
    { report_id: 'VOX-CLOSED-01', status: 'CLOSED', created_at: Date.now() },
    { report_id: 'VOX-LIMIT-01', status: 'KNOWN_LIMITATION', created_at: Date.now() },
    { report_id: 'VOX-ISSUE-01', status: 'KNOWN_ISSUE', created_at: Date.now() },
    { report_id: 'VOX-IGN-TEST-01', status: 'IGNORED_TEST', created_at: Date.now() },
    { report_id: 'VOX-IGN-01', status: 'IGNORED', created_at: Date.now() },
    { report_id: 'VOX-WONT-01', status: 'WONT_FIX', created_at: Date.now() },
    { report_id: 'VOX-DUP-01', status: 'DUPLICATE', created_at: Date.now() },
  ];

  for (const f of fixtures) {
    storage.inMemoryReports.set(f.report_id, f);
  }

  const stats = await storage.getStats();

  assert.equal(stats.totalReports, fixtures.length, 'Total reports must match fixture count');

  const disjointSum =
    stats.newCount +
    stats.triagedCount +
    stats.inProgressCount +
    stats.fixedAwaitingCount +
    stats.resolvedCount +
    stats.closedCount +
    stats.knownIssueCount +
    stats.ignoredTestCount;

  assert.equal(
    disjointSum,
    stats.totalReports,
    `Sum of disjoint buckets (${disjointSum}) MUST exactly equal totalReports (${stats.totalReports})`
  );

  // Verify individual disjoint counts
  assert.equal(stats.newCount, 2, 'NEW count must be 2');
  assert.equal(stats.triagedCount, 1, 'TRIAGED count must be 1');
  assert.equal(stats.inProgressCount, 5, 'IN_PROGRESS / INVESTIGATING group must be 5');
  assert.equal(stats.fixedAwaitingCount, 2, 'FIXED awaiting group must be 2');
  assert.equal(stats.resolvedCount, 2, 'RESOLVED / VERIFIED group must be 2');
  assert.equal(stats.closedCount, 1, 'CLOSED count must be 1');
  assert.equal(stats.knownIssueCount, 2, 'KNOWN limitation/issue group must be 2');
  assert.equal(stats.ignoredTestCount, 4, 'IGNORED/TEST/WONT_FIX/DUP group must be 4');
});

test('Prompt 014 GATE: Empty Database Safety (Zero Reports Graceful Handling)', async () => {
  const storage = new ReportStorage(null);
  const stats = await storage.getStats();

  assert.equal(stats.totalReports, 0);
  assert.equal(stats.newCount, 0);
  assert.equal(stats.triagedCount, 0);
  assert.equal(stats.inProgressCount, 0);
  assert.equal(stats.fixedAwaitingCount, 0);
  assert.equal(stats.resolvedCount, 0);
  assert.equal(stats.closedCount, 0);
  assert.equal(stats.knownIssueCount, 0);
  assert.equal(stats.ignoredTestCount, 0);

  const html = renderAdminHtml([], stats, []);
  assert.ok(html.includes('id="kpiTotal">0</strong>'), 'Total must render 0');
  assert.ok(html.includes('id="kpiNew" style="color: #60a5fa;">0</div>'), 'New must render 0');
  assert.ok(html.includes('id="kpiClosed">0</strong>'), 'Closed must render 0');
  assert.ok(!html.includes('NaN'), 'HTML must never contain NaN');
});

test('Prompt 014 GATE: Status Transition Matrix Legal vs Illegal Transitions', () => {
  // Legal forward & backward transitions
  assert.ok(isValidStatusTransition('NEW', 'INVESTIGATING'));
  assert.ok(isValidStatusTransition('NEW', 'TRIAGED'));
  assert.ok(isValidStatusTransition('INVESTIGATING', 'TRIAGED'));
  assert.ok(isValidStatusTransition('INVESTIGATING', 'NEW'));
  assert.ok(isValidStatusTransition('INVESTIGATING', 'ROOT_CAUSE_FOUND'));
  assert.ok(isValidStatusTransition('ROOT_CAUSE_FOUND', 'FIX_IMPLEMENTED'));
  assert.ok(isValidStatusTransition('FIX_IMPLEMENTED', 'TESTING'));
  assert.ok(isValidStatusTransition('TESTING', 'FIXED_AWAITING_VERIFICATION'));
  assert.ok(isValidStatusTransition('FIXED_AWAITING_VERIFICATION', 'VERIFIED'));
  assert.ok(isValidStatusTransition('FIXED_AWAITING_VERIFICATION', 'INVESTIGATING')); // Reclassification
  assert.ok(isValidStatusTransition('VERIFIED', 'RESOLVED'));
  assert.ok(isValidStatusTransition('RESOLVED', 'CLOSED'));
  assert.ok(isValidStatusTransition('CLOSED', 'REGRESSION'));

  // Unknown / invalid transitions
  assert.equal(isValidStatusTransition('UNKNOWN_STATUS', 'NEW'), false);
  assert.equal(isValidStatusTransition('NEW', 'NON_EXISTENT_STATUS'), false);
  assert.equal(isValidStatusTransition(null, 'NEW'), false);
});

test('Prompt 014 GATE: XSS & HTML Injection Protection in Developer Notes and Labels', () => {
  const maliciousInput = '<script>alert("XSS")</script><img src=x onerror=alert(1)>';
  const maliciousReport = {
    report_id: 'VOX-XSS-TEST',
    created_at: Date.now(),
    app_version: '32.56-vox.9.2-rc.4',
    platform: 'Android TV',
    device_family: 'TestDevice',
    error_category: 'PLAYER',
    error_signature: 'PLAYER|XSS_TEST',
    status: 'NEW',
    report_purpose: 'TEST',
    developer_notes: maliciousInput,
  };

  const html = renderAdminHtml([maliciousReport], { totalReports: 1, newCount: 1 }, []);

  // Must NOT contain raw unescaped script tags
  assert.ok(!html.includes('<script>alert("XSS")</script>'), 'HTML must not contain raw script tag');
  assert.ok(!html.includes('<img src=x onerror=alert(1)>'), 'HTML must not contain raw img error handler');
  assert.ok(html.includes('&lt;script&gt;alert(&quot;XSS&quot;)&lt;/script&gt;'), 'HTML must contain safely escaped script');
});

test('Prompt 014 GATE: Admin UI KPI Strip Elements Integrity', () => {
  const html = renderAdminHtml([], {
    totalReports: 320,
    newCount: 226,
    triagedCount: 0,
    inProgressCount: 39,
    fixedAwaitingCount: 23,
    resolvedCount: 8,
    closedCount: 0,
    knownIssueCount: 6,
    ignoredTestCount: 10,
    reports24h: 3,
    reports7d: 320
  }, []);

  // Invariant elements presence
  assert.ok(html.includes('id="kpiTotal">320</strong>'), 'Must show total');
  assert.ok(html.includes('id="kpiNew" style="color: #60a5fa;">226</div>'), 'Must show new count');
  assert.ok(html.includes('id="kpiTriaged">0</strong>'), 'Must show triaged count');
  assert.ok(html.includes('id="kpiInProgress" style="color: var(--warning);">39</div>'), 'Must show in-progress count');
  assert.ok(html.includes('id="kpiFixedAwaiting">23</strong>'), 'Must show fixed awaiting count');
  assert.ok(html.includes('id="kpiResolved" style="color: var(--success);">8</div>'), 'Must show resolved count');
  assert.ok(html.includes('id="kpiClosed">0</strong>'), 'Must show closed count');
  assert.ok(html.includes('id="kpiKnownIssue">6</strong>'), 'Must show known issues count');
  assert.ok(html.includes('id="kpiIgnoredTest">10</strong>'), 'Must show ignored test count');
});
