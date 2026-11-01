import test from 'node:test';
import assert from 'node:assert/strict';
import {
  classifyReport,
  getDerivedStatus,
  calculateEventFreshness,
  buildClassificationSignature,
} from '../src/triage.mjs';

test('Phase C: Old trigger + current report version is classified as historical, not active incident', () => {
  const now = 1700000000000;
  const age11HoursMs = Math.round(11.17 * 3600 * 1000);
  const oldEventTs = now - age11HoursMs;

  const report = {
    reportId: 'VOX-7C2D73AB',
    timestamp: now,
    reportCreatedAt: now,
    appVersion: '32.56-vox.9.2-rc.9',
    triggerEvent: 'PLAYER_RECOVERY_FAILED',
    safeRecentEvents: [
      {
        timestamp: oldEventTs,
        eventAt: oldEventTs,
        level: 'ERROR',
        category: 'PLAYER',
        code: 'PLAYER_RECOVERY_FAILED',
        message: 'Recovery failed during overnight soak',
        appVersion: '32.56-vox.9.2-rc.8',
        safeContext: {
          reason: 'dns_fail',
          appVersion: '32.56-vox.9.2-rc.8',
        },
      },
    ],
  };

  const classified = classifyReport(report);
  assert.equal(classified.reportActiveIncident, false, 'Report must not be marked as active incident');
  assert.equal(classified.isHistorical, true, 'Report must be marked as historical');
  assert.equal(classified.activeEventCount, 0, 'Active event count must be 0');

  const derived = getDerivedStatus(report, classified);
  assert.equal(derived, 'HISTORICAL', 'Derived status must be HISTORICAL');

  const freshness = calculateEventFreshness(report, classified);
  assert.equal(freshness, 'HISTORICAL', 'Event freshness must be HISTORICAL');
});

test('Phase C: Old trigger + same version is classified as historical due to bounded recency', () => {
  const now = 1700000000000;
  const age24HoursMs = Math.round(23.63 * 3600 * 1000);
  const oldEventTs = now - age24HoursMs;

  const report = {
    reportId: 'VOX-B80B660D',
    timestamp: now,
    reportCreatedAt: now,
    appVersion: '32.56-vox.9.2-rc.9',
    triggerEvent: 'AUTH_FAILED',
    safeRecentEvents: [
      {
        timestamp: oldEventTs,
        eventAt: oldEventTs,
        level: 'WARNING',
        category: 'YANDEX_AUTH',
        code: 'AUTH_FAILED',
        message: 'Token expired',
        appVersion: '32.56-vox.9.2-rc.9',
        safeContext: {
          appVersion: '32.56-vox.9.2-rc.9',
        },
      },
    ],
  };

  const classified = classifyReport(report);
  assert.equal(classified.reportActiveIncident, false, 'Stale event on same version must not be active incident');
  assert.equal(classified.isHistorical, true, 'Must be historical');
  assert.equal(classified.activeEventCount, 0);

  const derived = getDerivedStatus(report, classified);
  assert.equal(derived, 'HISTORICAL');
});

test('Phase C: Fresh trigger + same version is classified as active incident', () => {
  const now = 1700000000000;
  const freshTs = now - 120000; // 2 minutes ago

  const report = {
    reportId: 'VOX-FRESH-001',
    timestamp: now,
    reportCreatedAt: now,
    appVersion: '32.56-vox.9.2-rc.9',
    triggerEvent: 'PLAYER_HTTP_ERROR',
    safeRecentEvents: [
      {
        timestamp: freshTs,
        eventAt: freshTs,
        level: 'ERROR',
        category: 'PLAYER',
        code: 'PLAYER_HTTP_ERROR',
        message: 'HTTP 503 from backend',
        appVersion: '32.56-vox.9.2-rc.9',
      },
    ],
  };

  const classified = classifyReport(report);
  assert.equal(classified.reportActiveIncident, true, 'Fresh event must be classified as active incident');
  assert.equal(classified.isHistorical, false);
  assert.equal(classified.activeEventCount, 1);

  const derived = getDerivedStatus(report, classified);
  assert.equal(derived, 'NEW');

  const freshness = calculateEventFreshness(report, classified);
  assert.equal(freshness, 'NEW_AFTER_RELEASE');
});

test('Phase C: Missing version and timestamps handled gracefully without crashes', () => {
  const legacyReport = {
    reportId: 'VOX-LEGACY-001',
    safeRecentEvents: [
      {
        level: 'WARNING',
        code: 'PLAYER_REBUFFER',
        message: 'Rebuffer with missing timestamps',
      },
    ],
  };

  const classified = classifyReport(legacyReport);
  assert.ok(classified, 'Must classify without exception');
  assert.ok(Array.isArray(classified.events));
  assert.equal(classified.events.length, 1);
});

test('Phase C: Out-of-order events correctly ordered chronologically', () => {
  const now = 1700000000000;
  const report = {
    reportId: 'VOX-ORDER-001',
    timestamp: now,
    safeRecentEvents: [
      { timestamp: now - 10000, code: 'NEWEST_EVENT', level: 'INFO' },
      { timestamp: now - 30000, code: 'OLDEST_EVENT', level: 'INFO' },
      { timestamp: now - 20000, code: 'MIDDLE_EVENT', level: 'INFO' },
    ],
  };

  const classified = classifyReport(report);
  assert.equal(classified.events[0].event, 'OLDEST_EVENT');
  assert.equal(classified.events[1].event, 'MIDDLE_EVENT');
  assert.equal(classified.events[2].event, 'NEWEST_EVENT');
});

test('Phase C: Recovered error + stale trigger does not trigger false regression', () => {
  const now = 1700000000000;
  const report = {
    reportId: 'VOX-RECOV-001',
    timestamp: now,
    reportCreatedAt: now,
    appVersion: '32.56-vox.9.2-rc.9',
    triggerEvent: 'PLAYER_HTTP_ERROR',
    safeRecentEvents: [
      {
        timestamp: now - (40 * 60 * 1000), // 40m ago (stale)
        level: 'ERROR',
        category: 'PLAYER',
        code: 'PLAYER_HTTP_ERROR',
        message: 'HTTP error',
      },
      {
        timestamp: now - (39 * 60 * 1000),
        level: 'INFO',
        category: 'PLAYER',
        code: 'PLAYER_PLAYBACK_PROGRESS',
        message: 'Playback resumed smoothly',
      },
    ],
  };

  const classified = classifyReport(report);
  assert.equal(classified.reportActiveIncident, false);
  assert.equal(classified.isHistorical, true);
});

test('Phase C: Independent fresh fatal signal properly classified even with older errors present', () => {
  const now = 1700000000000;
  const report = {
    reportId: 'VOX-MULTI-001',
    timestamp: now,
    reportCreatedAt: now,
    appVersion: '32.56-vox.9.2-rc.9',
    safeRecentEvents: [
      {
        timestamp: now - (50 * 60 * 1000), // 50m ago
        level: 'ERROR',
        category: 'PLAYER',
        code: 'PLAYER_RECOVERY_FAILED',
        message: 'Old recovery fail',
      },
      {
        timestamp: now - 60000, // 1m ago (fresh)
        level: 'ERROR',
        category: 'PLAYER',
        code: 'PLAYER_OUT_OF_MEMORY',
        message: 'OOM in current session',
      },
    ],
  };

  const classified = classifyReport(report);
  assert.equal(classified.reportActiveIncident, true, 'Fresh OOM must be classified as active incident');
  assert.equal(classified.primaryEvent?.event, 'PLAYER_OUT_OF_MEMORY');
  assert.equal(classified.activeEventCount, 1);
  assert.equal(classified.historicalEventCount, 1);
});

test('Phase C: Historical events still preserved in output without field loss', () => {
  const now = 1700000000000;
  const report = {
    reportId: 'VOX-HIST-PRESERVE-001',
    timestamp: now,
    appVersion: '32.56-vox.9.2-rc.9',
    safeRecentEvents: [
      {
        timestamp: now - (12 * 3600 * 1000),
        level: 'ERROR',
        category: 'PLAYER',
        code: 'PLAYER_RECOVERY_FAILED',
        message: 'Important historical context',
        safeContext: { reason: 'exoplayer', mode: 'live_voice' },
      },
    ],
  };

  const classified = classifyReport(report);
  assert.equal(classified.events.length, 1);
  assert.equal(classified.events[0].event, 'PLAYER_RECOVERY_FAILED');
  assert.equal(classified.events[0].safeContext.reason, 'exoplayer');
  assert.equal(classified.events[0].safeContext.mode, 'live_voice');
  assert.equal(classified.events[0].isHistorical, true);
});

test('Phase C: CAS status transitions unaffected for resolved/fixed reports', () => {
  const reportResolved = {
    reportId: 'VOX-RES-001',
    status: 'RESOLVED',
    appVersion: '32.56-vox.9.2-rc.9',
    triggerEvent: 'PLAYER_RECOVERY_FAILED',
    safeRecentEvents: [
      {
        timestamp: Date.now() - 3600000,
        level: 'ERROR',
        code: 'PLAYER_RECOVERY_FAILED',
      },
    ],
  };

  const derived = getDerivedStatus(reportResolved);
  assert.equal(derived, 'RESOLVED', 'Developer marked status RESOLVED must be preserved and never reverted to INVESTIGATING');

  const reportPendingVerify = {
    ...reportResolved,
    status: 'FIXED_AWAITING_VERIFICATION',
  };
  assert.equal(getDerivedStatus(reportPendingVerify), 'FIXED_AWAITING_VERIFICATION');
});
