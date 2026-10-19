import test from 'node:test';
import assert from 'node:assert/strict';

import {
  ReportStorage,
  CANONICAL_STATUSES,
  ALLOWED_STATUS_TRANSITIONS,
  isValidStatusTransition,
  normalizeStatus,
} from '../src/storage.mjs';
import {
  dispatchTelegramNotification,
  resetTelegramState,
  TelegramClient,
} from '../src/notifications/telegram.mjs';
import { classifyReport } from '../src/triage.mjs';
import { handleRequest } from '../src/diagnostics.mjs';

test.beforeEach(() => {
  resetTelegramState();
});

test('Incident Grouping: multiple reports with identical signature aggregate count, first_seen, last_seen, and affected_versions', async () => {
  const storage = new ReportStorage();
  const signature = 'PLAYER|BUFFER_STALL|ANDROID_TV_API34';

  await storage.saveReport({
    reportId: 'VOX-GRP-001',
    timestamp: 1000,
    appVersion: '32.56-vox.9.1',
    platform: 'Android TV',
    errorSignature: signature,
    errorCategory: 'PLAYER',
    severity: 'HIGH',
    status: 'NEW',
    safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'ERROR', timestamp: 1000 }],
  });

  await storage.saveReport({
    reportId: 'VOX-GRP-002',
    timestamp: 2500,
    appVersion: '32.56-vox.9.2',
    platform: 'Android TV',
    errorSignature: signature,
    errorCategory: 'PLAYER',
    severity: 'CRITICAL',
    status: 'NEW',
    safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'ERROR', timestamp: 2500 }],
  });

  await storage.saveReport({
    reportId: 'VOX-GRP-003',
    timestamp: 1800,
    appVersion: '32.56-vox.9.1',
    platform: 'Android TV',
    errorSignature: signature,
    errorCategory: 'PLAYER',
    severity: 'MEDIUM',
    status: 'NEW',
    safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'ERROR', timestamp: 1800 }],
  });

  // Separate report with different signature
  await storage.saveReport({
    reportId: 'VOX-GRP-DIFF',
    timestamp: 3000,
    appVersion: '32.56-vox.9.2',
    platform: 'Android TV',
    errorSignature: 'OTA|HASH_MISMATCH|ANDROID_TV_API34',
    errorCategory: 'OTA',
    severity: 'HIGH',
    status: 'NEW',
    safeRecentEvents: [{ code: 'OTA_SIGNATURE_MISMATCH', level: 'ERROR', timestamp: 3000 }],
  });

  const grouped = await storage.getGroupedIssues();
  assert.equal(grouped.length, 2, 'Must have 2 distinct groups');

  const mainGroup = grouped.find(g => g.error_signature === signature);
  assert.ok(mainGroup, 'Main group must exist');
  assert.equal(mainGroup.count, 3, 'Must have exactly 3 occurrences');
  assert.equal(mainGroup.first_seen, 1000);
  assert.equal(mainGroup.last_seen, 2500);
  assert.equal(mainGroup.severity, 'CRITICAL', 'Group severity must reflect highest rank');
  assert.deepEqual(mainGroup.affected_versions.sort(), ['32.56-vox.9.1', '32.56-vox.9.2'].sort());

  const issueDetail = await storage.getIssue(signature);
  assert.equal(issueDetail.occurrences, 3);
  assert.equal(issueDetail.first_seen, 1000);
  assert.equal(issueDetail.last_seen, 2500);
});

test('Status State Machine: valid transitions pass and invalid transitions throw RangeError', async () => {
  const storage = new ReportStorage();
  const reportId = 'VOX-SM-001';

  await storage.saveReport({
    reportId,
    appVersion: '32.56-vox.9.1',
    status: 'NEW',
    errorSignature: 'UI|HUD_FREEZE|ANDROID_TV',
    safeRecentEvents: [{ code: 'HUD_STATE_INCONSISTENT', level: 'ERROR', timestamp: Date.now() }],
  });

  // 1. NEW -> INVESTIGATING: VALID
  let rep = await storage.updateReport(reportId, { status: 'INVESTIGATING', reason: 'Triage started' });
  assert.equal(rep.status, 'INVESTIGATING');

  // 2. INVESTIGATING -> FIXED_PENDING_VERIFY: VALID
  rep = await storage.updateReport(reportId, { status: 'FIXED_PENDING_VERIFY', reason: 'Fix commit 12345' });
  assert.equal(rep.status, 'FIXED_PENDING_VERIFY');

  // 3. FIXED_PENDING_VERIFY -> RESOLVED: VALID
  rep = await storage.updateReport(reportId, { status: 'RESOLVED', reason: 'Verified in emulator' });
  assert.equal(rep.status, 'RESOLVED');

  // 4. RESOLVED -> FIXED_PENDING_VERIFY: ILLEGAL (cannot go back without investigating)
  await assert.rejects(
    async () => storage.updateReport(reportId, { status: 'FIXED_PENDING_VERIFY' }),
    RangeError,
    'RESOLVED to FIXED_PENDING_VERIFY must be rejected'
  );

  // 5. RESOLVED -> DUPLICATE: ILLEGAL
  await assert.rejects(
    async () => storage.updateReport(reportId, { status: 'DUPLICATE' }),
    RangeError,
    'RESOLVED to DUPLICATE must be rejected'
  );

  // 6. RESOLVED -> INVESTIGATING (reopen): VALID
  rep = await storage.updateReport(reportId, { status: 'INVESTIGATING', reason: 'Regression reported' });
  assert.equal(rep.status, 'INVESTIGATING');

  // 7. Arbitrary string: ILLEGAL
  await assert.rejects(
    async () => storage.updateReport(reportId, { status: 'RANDOM_BOGUS_STATUS' }),
    RangeError,
    'Arbitrary status string must throw RangeError'
  );
});

test('Duplicate Linking: duplicateOf links canonical signature and updates audit', async () => {
  const storage = new ReportStorage();
  const canonicalSig = 'DOWNLOAD|DISK_FULL|STORAGE_API29';
  const duplicateSig = 'DOWNLOAD|LOW_STORAGE|STORAGE_API29';

  await storage.updateIssue(canonicalSig, {
    status: 'INVESTIGATING',
    title: 'Недостаточно места на диске при скачивании',
  });

  await storage.updateIssue(duplicateSig, {
    status: 'DUPLICATE',
    duplicateOf: canonicalSig,
    title: 'Duplicate low storage issue',
  });

  const dupIssue = await storage.getIssue(duplicateSig);
  assert.equal(dupIssue.status, 'DUPLICATE');
  assert.equal(dupIssue.duplicate_of, canonicalSig);

  // Duplicate cannot transition directly to FIXED_PENDING_VERIFY
  await assert.rejects(
    async () => storage.updateIssue(duplicateSig, { status: 'FIXED_PENDING_VERIFY' }),
    RangeError
  );

  // Duplicate can transition to INVESTIGATING (unlinking)
  const unlinked = await storage.updateIssue(duplicateSig, { status: 'INVESTIGATING' });
  assert.equal(unlinked.status, 'INVESTIGATING');
});

test('Reopen from FIXED_PENDING_VERIFY and RESOLVED when new active event arrives', async () => {
  const storage = new ReportStorage();
  const sigPending = 'PLAYER|CODEC_STALL|API34';
  const sigResolved = 'OTA|RATE_LIMIT|API34';

  // 1. FIXED_PENDING_VERIFY issue reopens on fresh report
  await storage.updateIssue(sigPending, {
    status: 'FIXED_PENDING_VERIFY',
    fixedInVersion: '32.56-vox.9.1',
    title: 'Codec stall issue',
  });

  const reopenedPending = await storage.reopenRegressedIssue(sigPending, '32.56-vox.9.1', 'INVESTIGATING');
  assert.equal(reopenedPending, true, 'Must reopen FIXED_PENDING_VERIFY issue');

  const issuePending = await storage.getIssue(sigPending);
  assert.equal(issuePending.status, 'INVESTIGATING');
  assert.ok(issuePending.reopened_at > 0);

  // 2. RESOLVED issue reopens when fresh report on fixed version or newer arrives
  await storage.updateIssue(sigResolved, {
    status: 'RESOLVED',
    fixedInVersion: '32.56-vox.9.0',
    title: 'OTA Rate Limit',
  });

  const reopenedOlder = await storage.reopenRegressedIssue(sigResolved, '32.56-vox.8.9');
  assert.equal(reopenedOlder, false, 'Report from older version must NOT reopen resolved issue');

  const reopenedFresh = await storage.reopenRegressedIssue(sigResolved, '32.56-vox.9.1');
  assert.equal(reopenedFresh, true, 'Report from fixed/newer version MUST reopen resolved issue');

  const issueResolved = await storage.getIssue(sigResolved);
  assert.ok(issueResolved.reopened_at > 0);
  assert.equal(issueResolved.status, 'IN_PROGRESS');
});

test('Telegram Delivery: New -> SENT_NEW, Duplicate inside window -> SUPPRESSED_DUPLICATE, Reopened -> SENT_REGRESSION', async () => {
  const requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    requests.push({ url, opts });
    return new Response(JSON.stringify({ ok: true, result: { message_id: 888 } }), { status: 200 });
  };

  try {
    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'MOCK_TOKEN',
      TELEGRAM_CHAT_ID: '-100999888777',
      TELEGRAM_AGGREGATION_WINDOW_MINUTES: '15',
      DASHBOARD_BASE_URL: 'https://vox.test',
    };

    const signature = 'PLAYER|AUDIO_DESYNC|API34';
    const report1 = {
      reportId: 'VOX-E2E-001',
      appVersion: '32.56-vox.9.1',
      errorSignature: signature,
      errorCategory: 'PLAYER',
      safeRecentEvents: [{ code: 'TRANSLATION_DESYNC_WARNING', level: 'ERROR', timestamp: Date.now() }],
    };

    // 1. New incident -> SENT_NEW
    const res1 = await dispatchTelegramNotification({
      report: report1,
      classification: classifyReport(report1),
      issue: { status: 'NEW' },
      env,
    });
    assert.equal(res1.decision, 'SENT_NEW');
    assert.equal(res1.messageId, 888);
    assert.equal(requests.length, 1);

    // 2. Duplicate within 15 minutes -> SUPPRESSED_DUPLICATE
    const report2 = {
      reportId: 'VOX-E2E-002',
      appVersion: '32.56-vox.9.1',
      errorSignature: signature,
      errorCategory: 'PLAYER',
      safeRecentEvents: [{ code: 'TRANSLATION_DESYNC_WARNING', level: 'ERROR', timestamp: Date.now() }],
    };
    const res2 = await dispatchTelegramNotification({
      report: report2,
      classification: classifyReport(report2),
      issue: { status: 'NEW' },
      env,
    });
    assert.equal(res2.decision, 'SUPPRESSED_DUPLICATE');
    assert.equal(res2.repeatCount, 1);
    assert.equal(requests.length, 1, 'Duplicate must not send HTTP request');

    // 3. Issue marked FIXED_PENDING_VERIFY and new report arrives -> SENT_REGRESSION
    const res3 = await dispatchTelegramNotification({
      report: report2,
      classification: classifyReport(report2),
      issue: { status: 'FIXED_PENDING_VERIFY' },
      env,
    });
    assert.equal(res3.decision, 'SENT_REGRESSION');
    assert.equal(requests.length, 2, 'Regression must immediately dispatch alert without duplicate suppression');

    // 4. Issue marked RESOLVED and new report arrives -> SENT_REGRESSION
    const res4 = await dispatchTelegramNotification({
      report: report2,
      classification: classifyReport(report2),
      issue: { status: 'RESOLVED' },
      env,
    });
    assert.equal(res4.decision, 'SENT_REGRESSION');
    assert.equal(requests.length, 3, 'Resolved regression must immediately dispatch alert');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('Telegram Failure Isolation: network failure never fails report ingestion HTTP 201', async () => {
  const originalFetch = globalThis.fetch;
  // Always fail with network error
  globalThis.fetch = async () => {
    throw new Error('Telegram network connection refused');
  };

  try {
    const storage = new ReportStorage();
    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'MOCK_TOKEN',
      TELEGRAM_CHAT_ID: '-100999888777',
    };

    const req = new Request('https://diag.smarttube.app/v1/report', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        schema: 'vox-diagnostic-report-v2',
        reportId: 'VOX-ISOLATION-01',
        appVersion: '32.56-vox.9.1',
        platform: 'Android TV',
        manufacturer: 'TCL',
        model: 'Beyond TV',
        errorCategory: 'PLAYER',
        status: 'NEW',
        safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'ERROR', timestamp: Date.now() }],
      }),
    });

    const res = await handleRequest(req, env, storage);
    assert.equal(res.status, 201, 'Ingestion must succeed with 201 Created despite Telegram failure');

    const stored = await storage.getReportById('VOX-ISOLATION-01');
    assert.ok(stored, 'Report must be persisted');
    assert.equal(stored.payload.telegramDelivery?.delivery_state, 'FAILED');
    assert.equal(stored.payload.telegramDelivery?.decision, 'FAILED');
  } finally {
    globalThis.fetch = originalFetch;
  }
});
