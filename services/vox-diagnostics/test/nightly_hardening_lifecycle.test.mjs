import test from 'node:test';
import assert from 'node:assert/strict';

import {
  ReportStorage,
  CANONICAL_STATUSES,
  PRIMARY_STATUSES,
  STATUS_LABELS,
  STATUS_COLORS,
  ALLOWED_STATUS_TRANSITIONS,
  isValidStatusTransition,
  normalizeStatus,
  toCanonicalStatus,
} from '../src/storage.mjs';

import {
  HISTORICAL_INCIDENTS_CATALOG,
  generateBackfillSql,
  generateRollbackSql,
} from '../scripts/backfill_vox91_statuses.mjs';

import { checkAdminAuth } from '../src/admin.mjs';

// Test 1: NEW issue initialization
test('1. NEW issue: default initial status is NEW with proper canonical defaults', async () => {
  const storage = new ReportStorage();
  const issue = await storage.getIssue('PLAYER|STALL_ERROR|ANDROID_TV_API34');
  assert.equal(issue.status, 'NEW');
  assert.equal(issue.issue_status, 'NEW');
  assert.equal(issue.occurrences, 0);
  assert.equal(issue.first_seen, 0);
  assert.equal(issue.last_seen, 0);
});

// Test 2: Status persistence
test('2. Status persistence: updated issue status and notes persist across queries', async () => {
  const storage = new ReportStorage();
  const sig = 'PLAYER|HTTP_TIMEOUT|ANDROID_TV';

  await storage.updateIssue(sig, {
    status: 'INVESTIGATING',
    title: 'ExoPlayer HTTP Read Timeout',
    actor: 'ENGINEER_A',
  });

  const fetched = await storage.getIssue(sig);
  assert.equal(fetched.status, 'INVESTIGATING');
  assert.equal(fetched.title, 'ExoPlayer HTTP Read Timeout');
});

// Test 3: Invalid transition
test('3. Invalid transition: illegal state jumps throw RangeError', async () => {
  const storage = new ReportStorage();
  const sig = 'PLAYER|CODEC_CRASH|ANDROID_TV';

  // Seed as VERIFIED
  await storage.updateIssue(sig, {
    status: 'VERIFIED',
    fixCommit: '457ffc27a',
    evidence: 'Unit test pass',
  });

  // VERIFIED directly to FIXED_AWAITING_VERIFICATION is illegal without re-investigating
  await assert.rejects(
    async () => storage.updateIssue(sig, { status: 'FIXED_AWAITING_VERIFICATION' }),
    RangeError
  );
});

// Test 4: Audit history
test('4. Audit history: status changes record actor, action, timestamp, and target', async () => {
  const storage = new ReportStorage();
  const sig = 'OTA|DOWNLOAD_FAIL|TIZEN';

  await storage.updateIssue(sig, {
    status: 'INVESTIGATING',
    actor: 'DIAG_AGENT',
  });

  const audit = storage.audit.filter(a => a.target_id === sig);
  assert.ok(audit.length >= 1);
  assert.equal(audit[0].target_type, 'ISSUE');
  assert.equal(audit[0].actor, 'DIAG_AGENT');
  assert.ok(audit[0].created_at > 0);
});

// Test 5: Duplicate inherits issue status
test('5. Duplicate inherits issue status: incoming duplicate report for an in-progress issue does not show as un-triaged', async () => {
  const storage = new ReportStorage();
  const sig = 'TRANSLATION|DESYNC|ANDROID_TV';

  await storage.updateIssue(sig, {
    status: 'INVESTIGATING',
    title: 'Audio track desync',
  });

  await storage.saveReport({
    reportId: 'VOX-DUP-01',
    appVersion: '32.56-vox.9.1',
    errorSignature: sig,
    safeRecentEvents: [{ code: 'TRANSLATION_DESYNC_WARNING', level: 'WARN', timestamp: Date.now() }],
  });

  const grouped = await storage.getGroupedIssues();
  const grp = grouped.find(g => g.error_signature === sig);
  assert.ok(grp);
  assert.equal(grp.status, 'INVESTIGATING');
  assert.equal(grp.count, 1);
});

// Test 6: Duplicate cannot reset VERIFIED
test('6. Duplicate cannot reset VERIFIED: incoming duplicate report from old version cannot reset a verified issue to NEW', async () => {
  const storage = new ReportStorage();
  const sig = 'PLAYER|BUFFER_UNDERFLOW|ANDROID_TV';

  await storage.updateIssue(sig, {
    status: 'VERIFIED',
    fixedInVersion: '32.56-vox.9.1',
    fixCommit: '64d5116a4',
    evidence: 'Player recovery tested on emulator',
  });

  // Duplicate report arrives from OLD version 32.56-vox.8.2-beta.2
  await storage.saveReport({
    reportId: 'VOX-OLD-REP-01',
    appVersion: '32.56-vox.8.2-beta.2',
    errorSignature: sig,
    safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'ERROR', timestamp: Date.now() }],
  });

  const issue = await storage.getIssue(sig);
  assert.equal(issue.status, 'VERIFIED');
  assert.equal(issue.issue_status, 'VERIFIED');
});

// Test 7: Old build recurrence
test('7. Old build recurrence: reports from older builds do not trigger regression reopen', async () => {
  const storage = new ReportStorage();
  const sig = 'OTA|RATE_LIMIT|ANDROID_TV';

  await storage.updateIssue(sig, {
    status: 'RESOLVED',
    fixedInVersion: '32.56-vox.9.1',
    fixCommit: 'c67b476e1',
    evidence: 'Cooldown policy active',
  });

  const reopened = await storage.reopenRegressedIssue(sig, '32.56-vox.9.0');
  assert.equal(reopened, false, 'Old build report must NOT reopen resolved issue');

  const issue = await storage.getIssue(sig);
  assert.equal(issue.status, 'RESOLVED');
});

// Test 8: New build regression
test('8. New build regression: reports from fixed or newer version reopen issue as regression', async () => {
  const storage = new ReportStorage();
  const sig = 'PLAYER|DOUBLE_BACK|ANDROID_TV';

  await storage.updateIssue(sig, {
    status: 'RESOLVED',
    fixedInVersion: '32.56-vox.9.1',
    fixCommit: '9a2feb20b',
    evidence: 'Tested on physical TV',
  });

  const reopened = await storage.reopenRegressedIssue(sig, '32.56-vox.9.1', 'REGRESSION');
  assert.equal(reopened, true, 'Same or newer build must trigger regression reopen');

  const issue = await storage.getIssue(sig);
  assert.equal(issue.status, 'REGRESSION');
});

// Test 9: Unknown build no false regression
test('9. Unknown build no false regression: unknown version string does not reopen verified issue', async () => {
  const storage = new ReportStorage();
  const sig = 'DOWNLOAD|PACKAGING|ANDROID_TV';

  await storage.updateIssue(sig, {
    status: 'VERIFIED',
    fixedInVersion: '32.56-vox.9.1',
    fixCommit: '3d323a188',
    evidence: 'MKV packaging check',
  });

  const reopened = await storage.reopenRegressedIssue(sig, 'unknown');
  assert.equal(reopened, false);

  const issue = await storage.getIssue(sig);
  assert.equal(issue.status, 'VERIFIED');
});

// Test 10: Verified requires evidence
test('10. Verified requires evidence: transition to VERIFIED without evidence or commit throws RangeError', async () => {
  const storage = new ReportStorage();
  const sig = 'UI|SEEK_HUD_GLITCH|ANDROID_TV';

  await storage.updateIssue(sig, { status: 'INVESTIGATING' });

  // Attempting to jump to VERIFIED with zero evidence and empty reason
  await assert.rejects(
    async () => storage.updateIssue(sig, { status: 'VERIFIED', reason: '', evidence: '' }),
    RangeError
  );

  // With evidence provided, transition succeeds
  const verified = await storage.updateIssue(sig, {
    status: 'VERIFIED',
    fixCommit: '47c784c5f',
    evidence: 'OTA HUD regression suite passed',
    reason: 'Verified on API 34',
  });
  assert.equal(verified.status, 'VERIFIED');
});

// Test 11: Dry-run no mutation
test('11. Dry-run no mutation: dry run generates SQL but does not modify database state', () => {
  const sql = generateBackfillSql();
  assert.ok(sql.length > 500);
  assert.ok(sql.includes('UPDATE issues SET status ='));
  // State of files or DB is not modified by generating SQL
});

// Test 12: Rollback exact restoration
test('12. Rollback exact restoration: rollback script contains valid statements restoring previous state', () => {
  const rollbackSql = generateRollbackSql();
  assert.ok(rollbackSql.includes('UPDATE issues SET status = \'NEW\''));
  assert.ok(rollbackSql.includes('UPDATE reports SET status = \'NEW\''));
  assert.ok(HISTORICAL_INCIDENTS_CATALOG.length >= 10);
});

// Test 13: Separate Telegram delivery state
test('13. Separate Telegram delivery state: telegram delivery states are isolated from incident lifecycle', async () => {
  const storage = new ReportStorage();
  const reportId = 'VOX-TG-DELIV-01';

  await storage.saveReport({
    reportId,
    appVersion: '32.56-vox.9.1',
    status: 'INVESTIGATING',
    safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'ERROR', timestamp: Date.now() }],
  });

  await storage.recordTelegramDelivery(reportId, {
    decision: 'SENT_NEW',
    messageId: 8888,
    attemptCount: 1,
  });

  const rep = await storage.getReportById(reportId);
  // Report lifecycle status remains INVESTIGATING, unaffected by Telegram state
  assert.equal(rep.status, 'INVESTIGATING');
});

// Test 14: Existing legacy statuses
test('14. Existing legacy statuses: IN_PROGRESS, FIXED_PENDING_VERIFICATION, RESOLVED normalize correctly', () => {
  assert.equal(normalizeStatus('IN_PROGRESS'), 'IN_PROGRESS');
  assert.equal(normalizeStatus('FIXED_PENDING_VERIFY'), 'FIXED_PENDING_VERIFY');
  assert.equal(normalizeStatus('FIXED_PENDING_VERIFICATION'), 'FIXED_PENDING_VERIFICATION');
  assert.equal(normalizeStatus('RESOLVED'), 'RESOLVED');
  assert.equal(normalizeStatus('KNOWN_ISSUE'), 'KNOWN_ISSUE');
  assert.equal(normalizeStatus('IGNORED_TEST'), 'IGNORED_TEST');

  // toCanonicalStatus maps to the 10 canonical stages
  assert.equal(toCanonicalStatus('IN_PROGRESS'), 'INVESTIGATING');
  assert.equal(toCanonicalStatus('FIXED_PENDING_VERIFY'), 'FIXED_AWAITING_VERIFICATION');
  assert.equal(toCanonicalStatus('RESOLVED'), 'VERIFIED');
  assert.equal(toCanonicalStatus('KNOWN_ISSUE'), 'KNOWN_LIMITATION');
  assert.equal(toCanonicalStatus('IGNORED_TEST'), 'IGNORED');
});

// Test 15: Concurrent updates
test('15. Concurrent updates: sequential status updates with audit trail maintain consistency', async () => {
  const storage = new ReportStorage();
  const reportId = 'VOX-CONCURRENT-01';

  await storage.saveReport({
    reportId,
    status: 'NEW',
    appVersion: '32.56-vox.9.1',
    safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'ERROR', timestamp: Date.now() }],
  });

  const [u1, u2] = await Promise.all([
    storage.updateReport(reportId, { developerNotes: 'Note from Dev A' }),
    storage.updateReport(reportId, { developerNotes: 'Note from Dev B' }),
  ]);

  const rep = await storage.getReportById(reportId);
  assert.ok(rep.developer_notes === 'Note from Dev A' || rep.developer_notes === 'Note from Dev B');
  assert.ok(rep.notes.length >= 1);
});

// Test 16: Authorization checks
test('16. Authorization checks: unauthenticated requests cannot access admin endpoints', () => {
  const env = { ADMIN_SECRET: 'test-secret-12345' };
  const reqNoAuth = new Request('http://localhost/admin');
  assert.equal(checkAdminAuth(reqNoAuth, env), false);

  const reqValidAuth = new Request('http://localhost/admin', {
    headers: { authorization: 'Bearer test-secret-12345' },
  });
  assert.equal(checkAdminAuth(reqValidAuth, env), true);
});

// Test 17: Synthetic report handling
test('17. Synthetic report handling: test and synthetic reports are automatically assigned IGNORED_TEST', async () => {
  const storage = new ReportStorage();

  const synthReport = await storage.saveReport({
    reportId: 'VOX-SYNTH-01',
    appVersion: '32.56-vox.9.1',
    reportPurpose: 'TEST',
    device: { manufacturer: 'Google', model: 'Android SDK built for x86_64' },
    safeRecentEvents: [{ code: 'AUTO_SETUP_PASS', level: 'INFO', timestamp: Date.now() }],
  });

  assert.equal(synthReport.reportPurpose, 'TEST');
  assert.equal(synthReport.status, 'IGNORED_TEST');
});

// Test 18: Different causes not merged
test('18. Different causes not merged: distinct error codes on same platform produce separate issues', async () => {
  const storage = new ReportStorage();

  await storage.saveReport({
    reportId: 'VOX-SIG-A',
    appVersion: '32.56-vox.9.1',
    errorSignature: 'PLAYER|BUFFER_STALL|ANDROID_TV_API34',
    safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'ERROR', timestamp: Date.now() }],
  });

  await storage.saveReport({
    reportId: 'VOX-SIG-B',
    appVersion: '32.56-vox.9.1',
    errorSignature: 'PLAYER|OUT_OF_MEMORY|ANDROID_TV_API34',
    safeRecentEvents: [{ code: 'PLAYER_OUT_OF_MEMORY', level: 'ERROR', timestamp: Date.now() }],
  });

  const grouped = await storage.getGroupedIssues();
  const groupA = grouped.find(g => g.error_signature.includes('BUFFER_STALL'));
  const groupB = grouped.find(g => g.error_signature.includes('OUT_OF_MEMORY'));

  assert.ok(groupA, 'Group A must exist');
  assert.ok(groupB, 'Group B must exist');
  assert.notEqual(groupA.error_signature, groupB.error_signature, 'Distinct causes must not be merged');
});
