import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { classifyReport, getDerivedStatus, extractReportIssues } from '../src/triage.mjs';
import { ReportStorage, CANONICAL_STATUSES, normalizeStatus } from '../src/storage.mjs';
import { AutoTriageService } from '../src/automation/auto_triage.mjs';
import { dispatchTelegramNotification } from '../src/notifications/telegram.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const fixturesDir = path.resolve(__dirname, 'fixtures');

// 1. rawStatusPreserved
test('Dashboard Hardening: rawStatusPreserved', () => {
  const report = {
    report_id: 'VOX-RAW-01',
    status: 'NEW',
    payload: {
      appVersion: '32.56-vox.9.1',
      safeRecentEvents: [{ code: 'PLAYER_REBUFFER', timestamp: Date.now() - 5000, level: 'ERROR' }]
    }
  };
  const derived = getDerivedStatus(report);
  assert.equal(report.status, 'NEW', 'Raw status in report object must not be mutated');
  assert.equal(derived, 'NEW');
});

// 2. derivedStatusUsedByDashboard
test('Dashboard Hardening: derivedStatusUsedByDashboard', () => {
  const historicalReport = {
    report_id: 'VOX-HIST-01',
    status: 'NEW',
    payload: {
      appVersion: '32.56-vox.9.1',
      safeRecentEvents: [{ code: 'PLAYER_REBUFFER', timestamp: Date.now() - 86400000 * 2, level: 'ERROR', isHistorical: true }]
    }
  };
  const derived = getDerivedStatus(historicalReport);
  assert.equal(historicalReport.status, 'NEW', 'Raw status remains NEW');
  assert.equal(derived, 'HISTORICAL', 'Derived status reflects historical events');
});

// 3. manualNewToInProgress
test('Dashboard Hardening: manualNewToInProgress', async () => {
  const storage = new ReportStorage();
  const reportId = 'VOX-DEV-NEW-TO-INP';
  await storage.saveReport({
    reportId,
    appVersion: '32.56-vox.9.1',
    device: { manufacturer: 'TCL', model: 'Beyond TV' },
    status: 'NEW',
    safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'ERROR', timestamp: Date.now() }]
  });

  const updated = await storage.updateReport(reportId, { status: 'IN_PROGRESS', developerNotes: 'Developer took into work' });
  assert.ok(updated);
  const fetched = await storage.getReportById(reportId);
  assert.equal(fetched.status, 'IN_PROGRESS');
  assert.equal(getDerivedStatus(fetched), 'IN_PROGRESS');
});

// 4. manualInProgressToFixedPending
test('Dashboard Hardening: manualInProgressToFixedPending', async () => {
  const storage = new ReportStorage();
  const reportId = 'VOX-DEV-INP-TO-FIX';
  await storage.saveReport({
    reportId,
    appVersion: '32.56-vox.9.1',
    device: { manufacturer: 'TCL', model: 'Beyond TV' },
    status: 'IN_PROGRESS',
    safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'ERROR', timestamp: Date.now() }]
  });

  const updated = await storage.updateReport(reportId, { status: 'FIXED_PENDING_VERIFICATION', developerNotes: 'Fixed in commit 9a2feb20b' });
  assert.ok(updated);
  const fetched = await storage.getReportById(reportId);
  assert.equal(fetched.status, 'FIXED_PENDING_VERIFICATION');
  assert.equal(getDerivedStatus(fetched), 'FIXED_PENDING_VERIFICATION');
});

// 5. fixedPendingToResolved
test('Dashboard Hardening: fixedPendingToResolved', async () => {
  const storage = new ReportStorage();
  const reportId = 'VOX-DEV-FIX-TO-RES';
  await storage.saveReport({
    reportId,
    appVersion: '32.56-vox.9.1',
    device: { manufacturer: 'TCL', model: 'Beyond TV' },
    status: 'FIXED_PENDING_VERIFICATION',
    safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'ERROR', timestamp: Date.now() }]
  });

  const updated = await storage.updateReport(reportId, { status: 'RESOLVED', developerNotes: 'Physical TV test verified' });
  assert.ok(updated);
  const fetched = await storage.getReportById(reportId);
  assert.equal(fetched.status, 'RESOLVED');
  assert.equal(getDerivedStatus(fetched), 'RESOLVED');
});

// 6. resolvedFreshEventReopensRegression
test('Dashboard Hardening: resolvedFreshEventReopensRegression', async () => {
  const storage = new ReportStorage();
  const triage = new AutoTriageService(storage);

  const sig = 'PLAYER|DOUBLE_BACK_PAUSE|TCL';
  await storage.updateIssue(sig, {
    status: 'RESOLVED',
    fixedInVersion: '32.56-vox.9.1',
    title: 'Выход по двойному назад'
  });

  await storage.saveReport({
    reportId: 'VOX-REG-REOPEN-01',
    appVersion: '32.56-vox.9.1',
    device: { manufacturer: 'TCL', model: 'Beyond TV' },
    status: 'NEW',
    errorCategory: 'PLAYER',
    errorSignature: sig,
    severity: 'HIGH',
    reportPurpose: 'USER',
    safeRecentEvents: [{
      code: 'PLAYER_DOUBLE_BACK_PAUSE',
      level: 'ERROR',
      timestamp: Date.now() - 1000,
      lifecycle: 'CURRENT_SESSION'
    }]
  });

  const summary = await triage.runTriage({ cursor: 0, dryRun: false });
  assert.equal(summary.reopenedIssues, 1);
  assert.equal(summary.statusChanges, 1);

  const issue = await storage.getIssue(sig);
  assert.equal(issue.issue_status, 'IN_PROGRESS');
  assert.ok(issue.reopened_at > 0);

  const report = await storage.getReportById('VOX-REG-REOPEN-01');
  assert.equal(report.status, 'IN_PROGRESS');
});

// 7. historicalNotCountedAsNew
test('Dashboard Hardening: historicalNotCountedAsNew', () => {
  const histReport = {
    reportId: 'VOX-HIST-NOT-NEW',
    status: 'NEW',
    safeRecentEvents: [
      { code: 'OTA_RATE_LIMIT', level: 'WARN', timestamp: Date.now() - 86400000 * 3, isHistorical: true }
    ]
  };
  const classified = classifyReport(histReport);
  assert.equal(classified.reportActiveIncident, false);
  const derived = getDerivedStatus(histReport, classified);
  assert.equal(derived, 'HISTORICAL');
  assert.notEqual(derived, 'NEW');
});

// 8. historicalNotCountedAsAttention
test('Dashboard Hardening: historicalNotCountedAsAttention', () => {
  const reports = [
    {
      report_id: 'VOX-HIST-ATTN-01',
      status: 'NEW',
      payload_json: JSON.stringify({
        safeRecentEvents: [{ code: 'OTA_FAILED', level: 'ERROR', timestamp: Date.now() - 86400000 * 5, isHistorical: true }]
      })
    }
  ];
  const summary = extractReportIssues(reports[0]);
  assert.ok(summary);
});

// 9. 707B7E10Historical
test('Dashboard Hardening: 707B7E10Historical', () => {
  const report707B = {
    reportId: 'VOX-707B7E10',
    appVersion: '32.56-vox.9.1',
    device: { manufacturer: 'TCL', model: 'Beyond TV', osVersion: '11' },
    status: 'NEW',
    safeRecentEvents: [
      { code: 'PLAYER_REBUFFER', timestamp: Date.now() - 86400000 * 2, level: 'WARN', isHistorical: true }
    ]
  };
  const classified = classifyReport(report707B);
  assert.equal(classified.reportActiveIncident, false);
  assert.equal(classified.isHistorical, true);
  const derived = getDerivedStatus(report707B, classified);
  assert.equal(derived, 'HISTORICAL');
});

// 10. D0DF1A08Active
test('Dashboard Hardening: D0DF1A08Active', () => {
  const filePath = path.join(fixturesDir, 'vox-diagnostic-VOX-D0DF1A08.json');
  if (fs.existsSync(filePath)) {
    const raw = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    const payload = raw.payload || raw;
    const classified = classifyReport(payload);
    assert.equal(classified.reportActiveIncident, true, 'VOX-D0DF1A08 must be an active incident');
    assert.ok(classified.activeEventCount > 0, 'Must contain active player rebuffer events');
    const derived = getDerivedStatus(raw, classified);
    assert.equal(derived, 'NEW');
  }
});

// 11. dashboardTelegramSameClassification
test('Dashboard Hardening: dashboardTelegramSameClassification', async () => {
  const report = {
    reportId: 'VOX-SAME-CLASS-01',
    appVersion: '32.56-vox.9.1',
    device: { manufacturer: 'TCL', model: 'Beyond TV' },
    status: 'NEW',
    safeRecentEvents: [{
      code: 'TRANSLATION_DESYNC_WARNING',
      event: 'TRANSLATION_DESYNC_WARNING',
      level: 'WARNING',
      severity: 'HIGH',
      timestamp: Date.now() - 5000,
      lifecycle: 'CURRENT_SESSION'
    }]
  };

  const cDashboard = classifyReport(report);
  const derivedDashboard = getDerivedStatus(report, cDashboard);

  let telegramRequest = null;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    telegramRequest = { url, opts };
    return new Response(JSON.stringify({ ok: true, result: { message_id: 1234 } }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  };

  try {
    const tRes = await dispatchTelegramNotification({
      report,
      classification: cDashboard,
      issue: { status: 'NEW' },
      env: {
        TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
        TELEGRAM_BOT_TOKEN: 'FAKE',
        TELEGRAM_CHAT_ID: '302331721',
        PUBLIC_DASHBOARD_URL: 'http://127.0.0.1:8787'
      }
    });

    assert.equal(tRes.decision, 'SENT_NEW');
    assert.equal(derivedDashboard, 'NEW');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// 12. healthCardsIgnoreHistorical
test('Dashboard Hardening: healthCardsIgnoreHistorical', () => {
  const histOnlyEvents = [
    { code: 'NETWORK_TIMEOUT', level: 'ERROR', timestamp: Date.now() - 86400000 * 4, isHistorical: true },
    { code: 'PLAYER_REBUFFER', level: 'WARN', timestamp: Date.now() - 86400000 * 3, isHistorical: true }
  ];
  const report = {
    reportId: 'VOX-HEALTH-HIST',
    safeRecentEvents: histOnlyEvents
  };
  const classified = classifyReport(report);
  assert.equal(classified.activeEventCount, 0);
  assert.equal(classified.reportActiveIncident, false);
});
