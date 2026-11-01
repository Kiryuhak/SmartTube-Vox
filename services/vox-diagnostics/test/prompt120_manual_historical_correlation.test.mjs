import test from 'node:test';
import assert from 'node:assert/strict';
import { classifyReport } from '../src/triage.mjs';

test('VOX-2760B640 Provenance: USER_MANUAL >30min stale links to historical event of same version', () => {
  const reportTs = Date.parse('2026-11-01T16:15:42.000Z');
  const eventTs = Date.parse('2026-11-01T15:40:00.000Z'); // 35 minutes earlier (>30m threshold)

  const payload = {
    reportId: 'VOX-2760B640',
    appVersion: '32.56-vox.9.2-rc.12',
    platform: 'Android TV',
    reportPurpose: 'USER_MANUAL',
    createdAt: reportTs,
    errorCategory: 'PLAYER',
    safeRecentEvents: [
      {
        event: 'DVR_SEEK_REQUEST',
        category: 'PLAYER',
        timestamp: eventTs - 18000,
        level: 'INFO',
        appVersion: '32.56-vox.9.2-rc.12'
      },
      {
        event: 'PLAYER_RECOVERY_FAILED',
        category: 'PLAYER',
        timestamp: eventTs,
        level: 'ERROR',
        severity: 'CRITICAL',
        appVersion: '32.56-vox.9.2-rc.12',
        safeContext: {
          recoveryStage: 'RECOVERY_EXHAUSTED',
          networkFailureType: 'BUFFER_STALL',
          selectedHeight: '2160',
          codec: 'vp9'
        }
      }
    ]
  };

  const c = classifyReport(payload);

  // Stale guard guarantees:
  assert.equal(c.isHistorical, true, 'Report must be flagged historical (not live crash)');
  assert.equal(c.reportActiveIncident, false, 'Must not spawn a false live incident alert');

  // Provenance guarantees:
  assert.ok(c.referencedHistoricalEvent, 'Must link to referenced historical failure');
  assert.equal(c.referencedHistoricalEventId, 'PLAYER_RECOVERY_FAILED');
  assert.equal(c.referencedHistoricalEventTimestamp, eventTs);
  assert.equal(c.referencedHistoricalEventAppVersion, '32.56-vox.9.2-rc.12');
});

test('VOX-2760B640 Provenance: different version historical error is not linked as same-version reference', () => {
  const reportTs = Date.parse('2026-11-01T16:15:42.000Z');
  const eventTs = Date.parse('2026-11-01T15:40:00.000Z');

  const payload = {
    reportId: 'VOX-DIFF-VER',
    appVersion: '32.56-vox.9.2-rc.13',
    platform: 'Android TV',
    reportPurpose: 'USER_MANUAL',
    createdAt: reportTs,
    safeRecentEvents: [
      {
        event: 'PLAYER_RECOVERY_FAILED',
        category: 'PLAYER',
        timestamp: eventTs,
        level: 'ERROR',
        severity: 'CRITICAL',
        appVersion: '32.56-vox.9.2-rc.12' // old version
      }
    ]
  };

  const c = classifyReport(payload);
  assert.equal(c.isHistorical, true);
  assert.equal(c.reportActiveIncident, false);
  assert.equal(c.referencedHistoricalEvent, null, 'Must not link error from old version as same-version reference');
});
