import test from 'node:test';
import assert from 'node:assert/strict';
import { ReportStorage } from '../src/storage.mjs';
import { AutoTriageService, runAutoTriage } from '../src/automation/auto_triage.mjs';
import { compareVoxVersions, parseVoxVersion } from '../src/triage.mjs';

test('Auto Triage: Version parsing and comparison for dev, rc, and release', () => {
  assert.equal(compareVoxVersions('32.56-vox.7', '32.56-vox.8-rc1'), -1);
  assert.equal(compareVoxVersions('32.56-vox.8-dev', '32.56-vox.8-rc1'), -1);
  assert.equal(compareVoxVersions('32.56-vox.8-rc1', '32.56-vox.8'), -1);
  assert.equal(compareVoxVersions('32.56-vox.8-rc1', '32.56-vox.8-rc1'), 0);
  assert.equal(compareVoxVersions('32.56-vox.8-rc2', '32.56-vox.8-rc1'), 1);
  assert.equal(compareVoxVersions('32.56-vox.8', '32.56-vox.8-rc1'), 1);
});

test('Auto Triage TEST A: New unique HIGH report -> NEW -> IN_PROGRESS and creates Issue', async () => {
  const storage = new ReportStorage();
  await storage.saveReport({
    reportId: 'VOX-A-NEW001',
    schema: 'vox-diagnostic-report-v2',
    platform: 'Android',
    appVersion: '32.56-vox.8-rc1',
    manufacturer: 'Sony',
    model: 'Bravia 4K',
    errorCategory: 'DOWNLOAD',
    stage: 'PACKAGING',
    severity: 'HIGH',
    errorSignature: 'DOWNLOAD|MUX_FAILED|ANDROID_API34',
    status: 'NEW',
    reportPurpose: 'USER'
  });

  const triage = new AutoTriageService(storage);
  const summary = await triage.runTriage({ cursor: 0, dryRun: false });

  assert.equal(summary.newIssues, 1);
  assert.equal(summary.statusChanges, 1);
  assert.equal(summary.criticalAlerts.length, 1);

  const report = await storage.getReportById('VOX-A-NEW001');
  assert.equal(report.status, 'IN_PROGRESS');
  assert.match(report.developer_notes, /Новая проблема высокой важности/);

  const issue = await storage.getIssue('DOWNLOAD|MUX_FAILED|ANDROID_API34');
  assert.equal(issue.issue_status, 'NEW');
  assert.match(issue.title, /Ошибка упаковки/);
});

test('Auto Triage TEST B: Duplicate report of active Issue -> KNOWN_ISSUE', async () => {
  const storage = new ReportStorage();
  // Register known issue
  await storage.updateIssue('PLAYER|BUFFERING_STALL|ANDROID_API30', {
    status: 'IN_PROGRESS',
    title: 'Зависание буферизации на слабых чипсетах'
  });

  await storage.saveReport({
    reportId: 'VOX-A-DUP001',
    schema: 'vox-diagnostic-report-v2',
    platform: 'Android',
    appVersion: '32.56-vox.8-rc1',
    manufacturer: 'TCL',
    model: 'BeyondTV',
    errorCategory: 'PLAYER',
    errorSignature: 'PLAYER|BUFFERING_STALL|ANDROID_API30',
    status: 'NEW',
    reportPurpose: 'USER'
  });

  const triage = new AutoTriageService(storage);
  const summary = await triage.runTriage({ cursor: 0, dryRun: false });

  assert.equal(summary.duplicates, 1);
  assert.equal(summary.knownIssues, 1);
  assert.equal(summary.newIssues, 0);

  const report = await storage.getReportById('VOX-A-DUP001');
  assert.equal(report.status, 'KNOWN_ISSUE');
  assert.match(report.developer_notes, /Связано с известной проблемой/);
});

test('Auto Triage TEST C: Historical report of resolved issue -> NO reopen, marks KNOWN_ISSUE', async () => {
  const storage = new ReportStorage();
  // Resolved in 32.56-vox.8
  await storage.updateIssue('BACKGROUND|AUDIO_FOCUS_LOST|ANDROID_API30', {
    status: 'RESOLVED',
    fixedInVersion: '32.56-vox.8',
    title: 'Остановка фонового звука на TUVIO'
  });

  // Report from older version 32.56-vox.7
  await storage.saveReport({
    reportId: 'VOX-A-HIST01',
    schema: 'vox-diagnostic-report-v2',
    platform: 'Android',
    appVersion: '32.56-vox.7',
    manufacturer: 'Tuvio',
    model: 'TD50UFBSV1',
    errorCategory: 'BACKGROUND',
    errorSignature: 'BACKGROUND|AUDIO_FOCUS_LOST|ANDROID_API30',
    status: 'NEW',
    reportPurpose: 'USER'
  });

  const triage = new AutoTriageService(storage);
  const summary = await triage.runTriage({ cursor: 0, dryRun: false });

  assert.equal(summary.reopenedIssues, 0);
  assert.equal(summary.knownIssues, 1);

  const issue = await storage.getIssue('BACKGROUND|AUDIO_FOCUS_LOST|ANDROID_API30');
  assert.equal(issue.issue_status, 'RESOLVED'); // Issue remains RESOLVED

  const report = await storage.getReportById('VOX-A-HIST01');
  assert.equal(report.status, 'KNOWN_ISSUE');
  assert.match(report.developer_notes, /Историческое проявление/);
});

test('Auto Triage TEST D: Fixed-version report of resolved issue -> REGRESSION REOPEN', async () => {
  const storage = new ReportStorage();
  // Resolved in 32.56-vox.8
  await storage.updateIssue('DOWNLOAD|DUNE_PACKAGING|ANDROID_API28', {
    status: 'RESOLVED',
    fixedInVersion: '32.56-vox.8',
    title: 'Упаковка MKV на DuneHD'
  });

  // New report from 32.56-vox.8-rc1 (or 32.56-vox.8)
  await storage.saveReport({
    reportId: 'VOX-A-REG001',
    schema: 'vox-diagnostic-report-v2',
    platform: 'Android',
    appVersion: '32.56-vox.8',
    manufacturer: 'Dune',
    model: 'Pro Vision 4K',
    errorCategory: 'DOWNLOAD',
    severity: 'HIGH',
    errorSignature: 'DOWNLOAD|DUNE_PACKAGING|ANDROID_API28',
    status: 'NEW',
    reportPurpose: 'USER'
  });

  const triage = new AutoTriageService(storage);
  const summary = await triage.runTriage({ cursor: 0, dryRun: false });

  assert.equal(summary.reopenedIssues, 1);
  assert.equal(summary.statusChanges, 1);
  assert.equal(summary.criticalAlerts.length, 1);

  const issue = await storage.getIssue('DOWNLOAD|DUNE_PACKAGING|ANDROID_API28');
  assert.equal(issue.issue_status, 'IN_PROGRESS'); // Reopened!
  assert.ok(issue.reopened_at > 0);

  const report = await storage.getReportById('VOX-A-REG001');
  assert.equal(report.status, 'IN_PROGRESS');
  assert.match(report.developer_notes, /Возможная регрессия/);
});

test('Auto Triage TEST E: Idempotency check (same input twice causes 0 duplicate mutations)', async () => {
  const storage = new ReportStorage();
  await storage.saveReport({
    reportId: 'VOX-A-IDEM01',
    schema: 'vox-diagnostic-report-v2',
    platform: 'Android',
    appVersion: '32.56-vox.8-rc1',
    manufacturer: 'Xiaomi',
    model: 'Mi Box S',
    errorCategory: 'OTA',
    errorSignature: 'OTA|HASH_MISMATCH|ANDROID_API28',
    status: 'NEW',
    reportPurpose: 'USER'
  });

  const triage = new AutoTriageService(storage);
  const run1 = await triage.runTriage({ cursor: 0, dryRun: false });
  assert.equal(run1.statusChanges, 1);
  assert.equal(run1.newIssues, 1);

  const reportAfter1 = await storage.getReportById('VOX-A-IDEM01');
  const notesAfter1 = reportAfter1.developer_notes;

  // Run 2 with the same cursor
  const run2 = await triage.runTriage({ cursor: 0, dryRun: false });
  assert.equal(run2.statusChanges, 0); // No extra transitions
  assert.equal(run2.newIssues, 0);

  const reportAfter2 = await storage.getReportById('VOX-A-IDEM01');
  assert.equal(reportAfter2.developer_notes, notesAfter1); // Note is not duplicated
});

test('Auto Triage TEST F: Dry-run mode produces planned mutations with ZERO DB changes', async () => {
  const storage = new ReportStorage();
  await storage.saveReport({
    reportId: 'VOX-A-DRY001',
    schema: 'vox-diagnostic-report-v2',
    platform: 'Android',
    appVersion: '32.56-vox.8-rc1',
    manufacturer: 'Nvidia',
    model: 'Shield TV Pro',
    errorCategory: 'PLAYER',
    severity: 'MEDIUM',
    errorSignature: 'PLAYER|AUDIO_TRACK_INIT|ANDROID_API33',
    status: 'NEW',
    reportPurpose: 'USER'
  });

  const triage = new AutoTriageService(storage);
  const drySummary = await triage.runTriage({ cursor: 0, dryRun: true });

  assert.equal(drySummary.dryRun, true);
  assert.equal(drySummary.plannedMutations.length, 2); // CREATE_ISSUE + REPORT_STATUS
  assert.equal(drySummary.updatedReports, 0); // 0 DB mutations

  const report = await storage.getReportById('VOX-A-DRY001');
  assert.equal(report.status, 'NEW'); // Unchanged in DB!
  assert.equal(report.developer_notes, ''); // Unchanged in DB!
});

test('Auto Triage TEST G: Test report -> IGNORED_TEST', async () => {
  const storage = new ReportStorage();
  await storage.saveReport({
    reportId: 'VOX-A-TEST-SMOKE-99',
    schema: 'vox-diagnostic-report-v2',
    platform: 'Android',
    appVersion: '32.56-vox.8-rc1',
    manufacturer: 'Google',
    model: 'Android TV Emulator',
    errorCategory: 'SYSTEM',
    errorSignature: 'SYSTEM|TEST_SMOKE|EMULATOR',
    status: 'NEW',
    reportPurpose: 'TEST'
  });

  const triage = new AutoTriageService(storage);
  const summary = await triage.runTriage({ cursor: 0, dryRun: false });

  assert.equal(summary.ignoredTests, 1);
  assert.equal(summary.statusChanges, 1);

  const report = await storage.getReportById('VOX-A-TEST-SMOKE-99');
  assert.equal(report.status, 'IGNORED_TEST');
  assert.match(report.developer_notes, /классифицирован как тестовый/);
});

test('Auto Triage TEST H: Auto-resolve is strictly PROHIBITED', async () => {
  const storage = new ReportStorage();
  await storage.saveReport({
    reportId: 'VOX-A-RESOLVE-CHECK',
    schema: 'vox-diagnostic-report-v2',
    platform: 'Android',
    appVersion: '32.56-vox.8-rc1',
    manufacturer: 'Sony',
    model: 'Bravia',
    errorCategory: 'DOWNLOAD',
    status: 'NEW',
    reportPurpose: 'USER'
  });

  const triage = new AutoTriageService(storage);
  const summary = await triage.runTriage({ cursor: 0, dryRun: false });

  const report = await storage.getReportById('VOX-A-RESOLVE-CHECK');
  assert.notEqual(report.status, 'RESOLVED');
  assert.ok(!summary.plannedMutations.some(m => m.toStatus === 'RESOLVED'));
});
