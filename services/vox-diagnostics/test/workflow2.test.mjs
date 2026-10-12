import test from 'node:test';
import assert from 'node:assert/strict';
import {
  extractReportIssues,
  groupOtaReports,
  groupRebufferReports,
  getNeedsAttentionSummary,
  STATUSES
} from '../src/triage.mjs';

test('Diagnostics Workflow 2.0: Multi-issue report extracts primary and secondary issues', () => {
  const mockReport = {
    reportId: 'VOX-4A04BD',
    appVersion: '32.56-vox.8.2-beta.2',
    playbackSessionId: '2',
    safeRecentEvents: [
      {
        event: 'IMAGE_REQUEST_FAILED',
        category: 'APP',
        subsystem: 'APP',
        level: 'WARNING',
        severity: 'LOW',
        repeatCount: 5,
        timestamp: 1000
      },
      {
        event: 'OTA_FAILED',
        category: 'OTA',
        subsystem: 'OTA',
        level: 'ERROR',
        severity: 'MEDIUM',
        safeContext: { errorCode: 'HTTP_ERROR' },
        repeatCount: 1,
        timestamp: 2000
      },
      {
        event: 'PLAYER_REBUFFER',
        category: 'PLAYER',
        subsystem: 'PLAYER',
        level: 'ERROR',
        severity: 'HIGH',
        safeContext: {
          playbackSessionId: '2',
          rebufferCount: '3',
          selectedHeight: '2160',
          selectedCodec: 'vp9',
          bitrate: '45309378'
        },
        repeatCount: 3,
        timestamp: 3000
      }
    ]
  };

  const { primaryIssue, secondaryIssues, allIssues } = extractReportIssues(mockReport);

  assert.equal(primaryIssue, 'PLAYER · REBUFFER');
  assert.ok(secondaryIssues.includes('OTA · FAILED'));
  assert.ok(secondaryIssues.includes('APP · REQUEST_FAILED'));
  assert.equal(allIssues.length, 3);
});

test('Diagnostics Workflow 2.0: OTA grouping aggregates by channel, version and reason', () => {
  const otaReports = [
    {
      report_id: 'R1',
      channel: 'stable',
      app_version: '32.56-vox.8.1',
      device_family: 'Google W2',
      error_signature: 'OTA_FAILED',
      created_at: 1000
    },
    {
      report_id: 'R2',
      channel: 'stable',
      app_version: '32.56-vox.8.1',
      device_family: 'Google W2',
      error_signature: 'OTA_FAILED',
      created_at: 2000
    },
    {
      report_id: 'R3',
      channel: 'beta',
      app_version: '32.56-vox.8.2-beta.2',
      device_family: 'Emulator',
      error_signature: 'RATE_LIMIT',
      created_at: 3000
    }
  ];

  const groups = groupOtaReports(otaReports);
  assert.equal(groups.length, 2);

  const stableGroup = groups.find(g => g.channel === 'stable');
  assert.ok(stableGroup);
  assert.equal(stableGroup.count, 2);
  assert.equal(stableGroup.reportIds.length, 2);
  assert.equal(stableGroup.firstSeen, 1000);
  assert.equal(stableGroup.lastSeen, 2000);
});

test('Diagnostics Workflow 2.0: Rebuffer grouping aggregates by sessionId, resolution, codec', () => {
  const reports = [
    {
      report_id: 'VOX-4A04BD',
      payload_json: JSON.stringify({
        safeRecentEvents: [
          {
            code: 'PLAYER_REBUFFER',
            context: {
              playbackSessionId: '2',
              selectedHeight: '2160',
              selectedCodec: 'vp9',
              rebufferCount: '3'
            }
          },
          {
            code: 'PLAYER_REBUFFER',
            context: {
              playbackSessionId: '1',
              selectedHeight: '1080',
              selectedCodec: 'vp9',
              rebufferCount: '1'
            }
          }
        ]
      })
    }
  ];

  const groups = groupRebufferReports(reports);
  assert.equal(groups.length, 2);

  const group4K = groups.find(g => g.resolution === '2160');
  assert.ok(group4K);
  assert.equal(group4K.codec, 'vp9');
  assert.equal(group4K.count, 3);
});

test('Diagnostics Workflow 2.0: Needs attention summary prioritizes active issues', () => {
  const reports = [
    {
      status: 'IN_PROGRESS',
      payload_json: {
        safeRecentEvents: [{ event: 'OTA_FAILED', subsystem: 'OTA', level: 'ERROR' }]
      }
    },
    {
      status: 'IN_PROGRESS',
      payload_json: {
        safeRecentEvents: [{ event: 'OTA_FAILED', subsystem: 'OTA', level: 'ERROR' }]
      }
    },
    {
      status: 'FIXED_PENDING_VERIFICATION',
      payload_json: {
        safeRecentEvents: [{ event: 'PLAYER_REBUFFER', subsystem: 'PLAYER', level: 'ERROR' }]
      }
    },
    {
      status: 'RESOLVED',
      payload_json: {
        safeRecentEvents: [{ event: 'DOWNLOAD_FAILED', subsystem: 'DOWNLOAD', level: 'ERROR' }]
      }
    }
  ];

  const summary = getNeedsAttentionSummary(reports);
  assert.equal(summary.length, 2);
  assert.equal(summary[0].issue, 'OTA · FAILED');
  assert.equal(summary[0].count, 2);
  assert.equal(summary[1].issue, 'PLAYER · REBUFFER');
  assert.equal(summary[1].count, 1);
});

test('Diagnostics Workflow 2.0: Status lifecycle includes FIXED_PENDING_VERIFICATION', () => {
  assert.ok(STATUSES.includes('NEW'));
  assert.ok(STATUSES.includes('TRIAGED'));
  assert.ok(STATUSES.includes('IN_PROGRESS'));
  assert.ok(STATUSES.includes('KNOWN_ISSUE'));
  assert.ok(STATUSES.includes('FIXED_PENDING_VERIFICATION'));
  assert.ok(STATUSES.includes('RESOLVED'));
  assert.ok(STATUSES.includes('IGNORED_TEST'));
  assert.ok(STATUSES.includes('WONT_FIX'));
});
