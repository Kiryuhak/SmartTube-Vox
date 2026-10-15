import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { classifyReport } from '../src/triage.mjs';
import { ReportStorage, CANONICAL_STATUSES, normalizeStatus } from '../src/storage.mjs';
import { AutoTriageService } from '../src/automation/auto_triage.mjs';
import { VOX_9_1_STATUS_MATRIX, VOX_9_1_ACTIVE_ISSUES, REFERENCE_REPORTS, analyzeFixtureReports, generateBackfillSql } from '../scripts/backfill_vox91_statuses.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const fixturesDir = path.resolve(__dirname, 'fixtures');

test('VOX 9.1 Status Matrix Integrity and Canonical Statuses', () => {
  assert.equal(VOX_9_1_STATUS_MATRIX.length, 17, 'All 17 requested status matrix items must be defined');

  const expectedKeys = [
    'PLAYER_DPAD_DIMMING_CONFLICT',
    'VOT_OVERLAY_JUMP',
    'VOT_SOURCE_LANG_RUSSIAN_DUB',
    'VOT_READY_CACHE_LOOKUP',
    'VOT_PENDING_CANCEL',
    'VOT_TRACK_RESTORE',
    'OTA_SIGNATURE_ANDROID9PLUS',
    'OTA_ABI_ASSET_SELECTION',
    'PLAYER_HTTP_403',
    'VOT_TRANSLATION_TIMEOUT',
    'OTA_PARTIAL_APK',
    'OTA_RATE_LIMIT',
    'DOWNLOAD_STATE_INCONSISTENT',
    'HISTORICAL_DIAGNOSTIC_NOISE',
    'PLAYER_DOUBLE_BACK_PAUSE_REGRESSION',
    'BACKGROUND_PLAYBACK_PAUSE_REGRESSION',
    'VOT_FAST_VOCALS_TRANSLATION_FAILURE'
  ];

  for (const key of expectedKeys) {
    const item = VOX_9_1_STATUS_MATRIX.find(i => i.key === key);
    assert.ok(item, `Matrix item ${key} must exist`);
    assert.ok(CANONICAL_STATUSES.includes(item.targetStatus), `Target status ${item.targetStatus} must be canonical`);
    assert.equal(item.fixedInVersion, '32.56-vox.9.1', `Fixed in version for ${key} must be 32.56-vox.9.1`);
    assert.ok(item.title, `Item ${key} must have a title`);
    assert.ok(item.notes, `Item ${key} must have notes`);
  }
});

test('VOX-D134CD89: Historical Events Isolation (No Stale Trigger as Active Incident)', () => {
  const filePath = path.join(fixturesDir, 'vox-diagnostic-VOX-D134CD89.json');
  assert.ok(fs.existsSync(filePath), 'Fixture VOX-D134CD89 must exist');

  const raw = JSON.parse(fs.readFileSync(filePath, 'utf8'));
  const payload = raw.payload || raw;
  const classified = classifyReport(payload);

  assert.equal(classified.reportActiveIncident, false, 'Report must not have an active incident');
  assert.equal(classified.activeEventCount, 0, 'Active event count must be 0 (all events 24h+ old)');
  assert.equal(classified.historicalEventCount, 10, 'All 10 events must be marked historical');
  assert.equal(classified.isHistorical, true, 'isHistorical must be true');
});

test('VOX-BAE1F61E, VOX-82E64C77, VOX-BDDBE424 Fixtures Classification', () => {
  const analysis = analyzeFixtureReports();
  assert.equal(analysis.length, 5, 'All 5 reference fixtures must be analyzed');

  const historicalFixtures = analysis.filter(item => item.reportId !== 'VOX-D0DF1A08');
  for (const item of historicalFixtures) {
    assert.equal(item.activeEventCount, 0, `Fixture ${item.reportId} must have 0 active events`);
    assert.equal(item.reportActiveIncident, false, `Fixture ${item.reportId} must have reportActiveIncident=false`);
    assert.ok(item.historicalEventCount > 0, `Fixture ${item.reportId} must have historical events`);
  }

  const d0 = analysis.find(item => item.reportId === 'VOX-D0DF1A08');
  assert.ok(d0, 'VOX-D0DF1A08 fixture must be analyzed');
  assert.equal(d0.activeEventCount, 1, 'VOX-D0DF1A08 must have 1 active event');
  assert.equal(d0.reportActiveIncident, true, 'VOX-D0DF1A08 must have reportActiveIncident=true');
});

test('Auto Triage: VOX-D134CD89 with fixed issue does NOT trigger false regression reopen', async () => {
  const storage = new ReportStorage();

  // Pre-seed issue as RESOLVED in 32.56-vox.9.1
  await storage.updateIssue('PLAYER|PLAYER_HTTP_ERROR|ANDROID_TV_API34', {
    status: 'RESOLVED',
    fixedInVersion: '32.56-vox.9.1',
    title: 'ExoPlayer HTTP 403 recovery'
  });

  const filePath = path.join(fixturesDir, 'vox-diagnostic-VOX-D134CD89.json');
  const raw = JSON.parse(fs.readFileSync(filePath, 'utf8'));
  const payload = raw.payload || raw;

  await storage.saveReport({
    reportId: 'VOX-D134CD89',
    ...payload,
    status: 'NEW'
  });

  const triage = new AutoTriageService(storage);
  const summary = await triage.runTriage({ cursor: 0, dryRun: false });

  assert.equal(summary.reopenedIssues, 0, 'Must NOT reopen resolved issue');
  assert.equal(summary.knownIssues, 1, 'Must mark report as KNOWN_ISSUE');

  const issue = await storage.getIssue('PLAYER|PLAYER_HTTP_ERROR|ANDROID_TV_API34');
  assert.equal(issue.issue_status, 'RESOLVED', 'Issue must remain RESOLVED');

  const report = await storage.getReportById('VOX-D134CD89');
  assert.equal(report.status, 'KNOWN_ISSUE');
  assert.match(report.developer_notes, /activeEventCount=0/);
});

test('Backfill SQL generation is valid and contains expected statements', () => {
  const sql = generateBackfillSql();
  assert.ok(sql.includes('UPDATE issues SET status ='), 'Must update issues');
  assert.ok(sql.includes('VOX-D134CD89'), 'Must contain VOX-D134CD89');
  assert.ok(sql.includes('VOX-82E64C77'), 'Must contain VOX-82E64C77');
  assert.ok(sql.includes('VOX-BAE1F61E'), 'Must contain VOX-BAE1F61E');
  assert.ok(sql.includes('VOX-BDDBE424'), 'Must contain VOX-BDDBE424');
  assert.ok(sql.includes('PLAYER|DOUBLE_BACK_PAUSE'), 'Must contain double back issue');
  assert.ok(sql.includes('PLAYER|BACKGROUND_PLAYBACK_PAUSED'), 'Must contain background playback issue');
  assert.ok(sql.includes('TRANSLATION|FAST_VOCALS_FAILURE'), 'Must contain fast vocals translation issue');
});

test('VOX 9.1 Active Issues and Forum Regressions Matrix Integrity', () => {
  assert.equal(VOX_9_1_ACTIVE_ISSUES.length, 1, 'Must have 1 active issue (PLAYER_REBUFFER_BUFFER_POLICY)');

  const expectedResolvedForumRegressions = [
    'PLAYER_DOUBLE_BACK_PAUSE_REGRESSION',
    'BACKGROUND_PLAYBACK_PAUSE_REGRESSION',
    'VOT_FAST_VOCALS_TRANSLATION_FAILURE'
  ];

  for (const key of expectedResolvedForumRegressions) {
    const item = VOX_9_1_STATUS_MATRIX.find(i => i.key === key);
    assert.ok(item, `Resolved issue ${key} must exist in VOX_9_1_STATUS_MATRIX`);
    assert.equal(item.targetStatus, 'RESOLVED', `Status for ${key} must be RESOLVED after physical TV gate`);
    assert.equal(item.fixedInVersion, '32.56-vox.9.1', `Resolved issue ${key} must have fixedInVersion set to 32.56-vox.9.1`);
    assert.equal(item.fixCommit, '9a2feb20b', `Resolved issue ${key} must have fixCommit set to 9a2feb20b`);
    assert.equal(item.physicallyVerified, true, `Resolved issue ${key} must be physically verified`);
    assert.ok(item.title, `Item ${key} must have a title`);
    assert.ok(item.notes, `Item ${key} must have notes`);
  }

  const rebuffer = VOX_9_1_ACTIVE_ISSUES.find(i => i.key === 'PLAYER_REBUFFER_BUFFER_POLICY');
  assert.ok(rebuffer, 'PLAYER_REBUFFER_BUFFER_POLICY must exist');
  assert.equal(rebuffer.targetStatus, 'NEW', 'Status for rebuffer must be NEW');
  assert.equal(rebuffer.fixedInVersion, undefined, 'Rebuffer issue must NOT have fixedInVersion set');
});

test('VOX-D0DF1A08 Fixture Classification: Active Player Rebuffer Incident (Separate from Double Back)', () => {
  const filePath = path.join(fixturesDir, 'vox-diagnostic-VOX-D0DF1A08.json');
  assert.ok(fs.existsSync(filePath), 'Fixture VOX-D0DF1A08 must exist');

  const raw = JSON.parse(fs.readFileSync(filePath, 'utf8'));
  const payload = raw.payload || raw;
  const classified = classifyReport(payload);

  assert.equal(classified.activeEventCount, 1, 'Must have 1 active event');
  assert.equal(classified.errorCategory, 'PLAYER');
  assert.equal(classified.primaryEvent?.event, 'PLAYER_REBUFFER');
  assert.equal(classified.signature, 'PLAYER|PLAYER_REBUFFER|ANDROID_TV_API30');
  assert.notEqual(classified.signature, 'PLAYER|DOUBLE_BACK_PAUSE|ANDROID_TV_API34');
  assert.notEqual(classified.signature, 'PLAYER|PLAYER_HTTP_ERROR|ANDROID_TV_API34');
});

