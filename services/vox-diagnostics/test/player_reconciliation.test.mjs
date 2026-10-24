import test from 'node:test';
import assert from 'node:assert/strict';
import {
  parseSignature,
  reconcilePlayerReports,
  explainHttpErrorDiscrepancy
} from '../src/player_reconciliation.mjs';

test('Player Reconciliation: parseSignature parsing logic', () => {
  // Standard valid signature
  const p1 = parseSignature('PLAYER|PLAYER_HTTP_ERROR|ANDROID_TV_API34');
  assert.equal(p1.isValid, true);
  assert.equal(p1.category, 'PLAYER');
  assert.equal(p1.code, 'PLAYER_HTTP_ERROR');
  assert.equal(p1.platform, 'ANDROID_TV_API34');

  // Cross-category anomaly
  const p2 = parseSignature('OTA|OTA_RATE_LIMIT|ANDROID_TV_API34');
  assert.equal(p2.isValid, true);
  assert.equal(p2.category, 'OTA');
  assert.equal(p2.code, 'OTA_RATE_LIMIT');
  assert.equal(p2.platform, 'ANDROID_TV_API34');

  // Empty signature
  const p3 = parseSignature('');
  assert.equal(p3.isValid, false);
  assert.equal(p3.code, 'EMPTY_SIGNATURE');

  // Malformed signature (only 1 pipe)
  const p4 = parseSignature('PLAYER|REBUFFER');
  assert.equal(p4.isValid, false);
  assert.equal(p4.code, 'REBUFFER');
});

test('Player Reconciliation: Mathematical Invariant on exact D1 Snapshot (delta == 0)', () => {
  // Exact D1 signature counts for PLAYER category (237 baseline)
  const rawData = [
    { sig: 'PLAYER|PLAYER_REBUFFER|ANDROID_TV_API34', count: 37 },
    { sig: 'PLAYER|PLAYER_HTTP_ERROR|ANDROID_TV_API34', count: 35 },
    { sig: 'PLAYER|PLAYER_REBUFFER|ANDROID_TV_API30', count: 23 },
    { sig: 'PLAYER|PLAYER_HTTP_ERROR|ANDROID_TV_API30', count: 14 },
    { sig: 'PLAYER|PLAYER_HTTP_ERROR|GOOGLE_TV_API30', count: 8 }, // baseline before VOX-90A5A5F0
    { sig: 'PLAYER|PLAYER_REBUFFER|ANDROID_TV_API28', count: 8 },
    { sig: 'PLAYER|PLAYER_SOURCE_ERROR|ANDROID_TV_API34', count: 8 },
    { sig: 'PLAYER|PLAYER_REBUFFER|UNKNOWN_PLATFORM_API36', count: 7 },
    { sig: 'PLAYER|PLAYER_HTTP_ERROR|ANDROID_TV_API26', count: 6 },
    { sig: 'PLAYER|PLAYER_REBUFFER|GOOGLE_TV_API34', count: 6 },
    { sig: 'PLAYER|PLAYER_REBUFFER|UNKNOWN_PLATFORM_API28', count: 6 },
    { sig: 'PLAYER|PLAYER_HTTP_ERROR|GOOGLE_TV_API34', count: 5 },
    { sig: 'PLAYER|PLAYER_STARTUP_SLOW|ANDROID_TV_API34', count: 5 },
    { sig: 'PLAYER|UNKNOWN_CODE|ANDROID_TV_API34', count: 5 },
    { sig: 'PLAYER|PLAYER_OUT_OF_MEMORY|ANDROID_TV_API34', count: 4 },
    { sig: 'PLAYER|UNKNOWN_CODE|ANDROID_TV_API30', count: 4 },
    { sig: 'PLAYER|LIVE_STALL_RECOVERY_ATTEMPT|ANDROID_TV_API33', count: 3 },
    { sig: 'PLAYER|PLAYER_HTTP_ERROR|UNKNOWN_PLATFORM_API28', count: 3 },
    { sig: 'PLAYER|PLAYER_REBUFFER|ANDROID_TV_API31', count: 3 },
    { sig: 'PLAYER|PLAYER_REBUFFER|GOOGLE_TV_API30', count: 2 },
    { sig: 'PLAYER|PLAYER_STARTUP_SLOW|ANDROID_TV_API28', count: 3 },
    { sig: 'PLAYER|PLAYER_STARTUP_SLOW|ANDROID_TV_API31', count: 3 },
    { sig: 'PLAYER|LIVE_OFFSET_INVALID|ANDROID_TV_API34', count: 2 },
    { sig: 'PLAYER|PLAYER_HTTP_ERROR|ANDROID_TV_API28', count: 2 },
    { sig: 'PLAYER|PLAYER_REBUFFER|ANDROID_TV_API33', count: 2 },
    { sig: 'PLAYER|PLAYER_REBUFFER|UNKNOWN_PLATFORM_API29', count: 2 },
    { sig: 'PLAYER|PLAYER_RENDERER_ERROR|ANDROID_TV_API30', count: 2 },
    { sig: 'PLAYER|PLAYER_STARTUP_SLOW|ANDROID_TV_API33', count: 2 },
    { sig: 'PLAYER|PLAYER_STARTUP_SLOW|UNKNOWN_PLATFORM_API35', count: 2 },
    { sig: 'PLAYER|UNKNOWN_CODE|GOOGLE_TV_API30', count: 2 },
    { sig: 'PLAYER|UNKNOWN_CODE|UNKNOWN_PLATFORM_API35', count: 2 },
    { sig: 'OTA|OTA_RATE_LIMIT|ANDROID_TV_API34', count: 1 }, // cross anomaly
    { sig: 'PLAYER|LIVE_OFFSET_INVALID|ANDROID_TV_API30', count: 1 },
    { sig: 'PLAYER|PLAYER_DECODER_ERROR|ANDROID_TV_API34', count: 1 },
    { sig: 'PLAYER|PLAYER_DECODER_ERROR|UNKNOWN_PLATFORM_API36', count: 1 },
    { sig: 'PLAYER|PLAYER_HTTP_ERROR|GOOGLE_TV_API31', count: 1 },
    { sig: 'PLAYER|PLAYER_LOCAL_SOURCE_ERROR|ANDROID_TV_API34', count: 1 },
    { sig: 'PLAYER|PLAYER_REBUFFER|UNKNOWN_PLATFORM_API35', count: 1 },
    { sig: 'PLAYER|PLAYER_REBUFFER|UNKNOWN_PLATFORM_API37', count: 1 },
    { sig: 'PLAYER|PLAYER_RECOVERY_FAILED|ANDROID_TV_API34', count: 1 },
    { sig: 'PLAYER|PLAYER_RENDERER_ERROR|ANDROID_TV_API34', count: 1 },
    { sig: 'PLAYER|PLAYER_RENDERER_ERROR|GOOGLE_TV_API31', count: 1 },
    { sig: 'PLAYER|PLAYER_SOURCE_ERROR|ANDROID_TV_API30', count: 1 },
    { sig: 'PLAYER|PLAYER_STARTUP_SLOW|ANDROID_TV_API29', count: 1 },
    { sig: 'PLAYER|PLAYER_STARTUP_SLOW|ANDROID_TV_API30', count: 1 },
    { sig: 'PLAYER|PLAYER_STARTUP_SLOW|GOOGLE_TV_API28', count: 1 },
    { sig: 'PLAYER|PLAYER_STARTUP_SLOW|GOOGLE_TV_API30', count: 1 },
    { sig: 'PLAYER|PLAYER_STARTUP_SLOW|GOOGLE_TV_API34', count: 1 },
    { sig: 'PLAYER|UNKNOWN_CODE|ANDROID_TV_API28', count: 1 },
    { sig: 'PLAYER|UNKNOWN_CODE|ANDROID_TV_API31', count: 1 },
    { sig: 'PLAYER|UNKNOWN_CODE|GOOGLE_TV_API34', count: 1 },
    { sig: 'PLAYER|UNKNOWN_CODE|UNKNOWN_PLATFORM_API36', count: 1 },
  ];

  // Expand into synthetic reports list
  const reports = [];
  let id = 1;
  for (const item of rawData) {
    for (let i = 0; i < item.count; i++) {
      reports.push({
        id: id++,
        report_id: `VOX-${id}`,
        error_category: 'PLAYER',
        error_signature: item.sig,
        status: 'NEW'
      });
    }
  }

  // Also add some non-player reports to verify category isolation
  reports.push({ id: 999, error_category: 'OTA', error_signature: 'OTA|OTA_RATE_LIMIT|ANDROID_TV_API34', status: 'NEW' });
  reports.push({ id: 1000, error_category: 'DOWNLOAD', error_signature: 'DOWNLOAD|FAILED|ANDROID_TV_API34', status: 'NEW' });

  const result = reconcilePlayerReports(reports);

  assert.equal(result.playerCategoryCount, 237, 'PLAYER category count must be exactly 237');
  assert.equal(result.rawDistinct, 52, 'Must have exactly 52 distinct raw signatures');
  assert.equal(result.sumRaw, 237, 'Sum of raw signatures must equal 237');
  assert.equal(result.sumNormalized, 237, 'Sum of normalized codes must equal 237');
  assert.equal(result.delta, 0, 'Mathematical delta must be strictly 0');
  assert.equal(result.normalizedDelta, 0, 'Normalized delta must be strictly 0');
  assert.equal(result.isBalanced, true, 'Result must be balanced');

  // Verify HTTP total at baseline is exactly 74
  assert.equal(result.normalizedCodeCounts['PLAYER_HTTP_ERROR'], 74, 'At baseline (before VOX-90A5A5F0), PLAYER_HTTP_ERROR is 74');

  // Now dynamically add VOX-90A5A5F0 (+1 to GOOGLE_TV_API30)
  reports.push({
    id: 1001,
    report_id: 'VOX-90A5A5F0',
    error_category: 'PLAYER',
    error_signature: 'PLAYER|PLAYER_HTTP_ERROR|GOOGLE_TV_API30',
    status: 'NEW'
  });

  const resultAfterNewReport = reconcilePlayerReports(reports);
  assert.equal(resultAfterNewReport.playerCategoryCount, 238, 'After VOX-90A5A5F0, PLAYER category count is 238');
  assert.equal(resultAfterNewReport.normalizedCodeCounts['PLAYER_HTTP_ERROR'], 75, 'After VOX-90A5A5F0, PLAYER_HTTP_ERROR is 75');
  assert.equal(resultAfterNewReport.delta, 0, 'Delta remains strictly 0');

  // Verify anomaly detection
  assert.equal(result.anomalies.length, 1, 'Must detect exactly 1 anomaly (OTA_RATE_LIMIT under PLAYER)');
  assert.equal(result.anomalies[0].rawSignature, 'OTA|OTA_RATE_LIMIT|ANDROID_TV_API34');

  // Verify platform disambiguation
  assert.equal(result.platformCounts['UNKNOWN_PLATFORM_API28'], 9); // 6 rebuffer + 3 http
  assert.equal(result.platformCounts['ANDROID_TV_API28'], 14); // 8 rebuffer + 2 http + 3 slow + 1 unknown
});

test('Player Reconciliation: Discrepancy 74 vs 75 Explanation & Platform Disambiguation', () => {
  // Snapshot 1: Before report VOX-90A5A5F0 (74 reports)
  const httpReportsBefore = [
    ...Array(35).fill({ error_category: 'PLAYER', error_signature: 'PLAYER|PLAYER_HTTP_ERROR|ANDROID_TV_API34' }),
    ...Array(14).fill({ error_category: 'PLAYER', error_signature: 'PLAYER|PLAYER_HTTP_ERROR|ANDROID_TV_API30' }),
    ...Array(8).fill({ error_category: 'PLAYER', error_signature: 'PLAYER|PLAYER_HTTP_ERROR|GOOGLE_TV_API30' }), // 8
    ...Array(6).fill({ error_category: 'PLAYER', error_signature: 'PLAYER|PLAYER_HTTP_ERROR|ANDROID_TV_API26' }),
    ...Array(5).fill({ error_category: 'PLAYER', error_signature: 'PLAYER|PLAYER_HTTP_ERROR|GOOGLE_TV_API34' }),
    ...Array(3).fill({ error_category: 'PLAYER', error_signature: 'PLAYER|PLAYER_HTTP_ERROR|UNKNOWN_PLATFORM_API28' }), // 3
    ...Array(2).fill({ error_category: 'PLAYER', error_signature: 'PLAYER|PLAYER_HTTP_ERROR|ANDROID_TV_API28' }), // 2
    ...Array(1).fill({ error_category: 'PLAYER', error_signature: 'PLAYER|PLAYER_HTTP_ERROR|GOOGLE_TV_API31' }),
  ];

  const explanationBefore = explainHttpErrorDiscrepancy(httpReportsBefore);
  assert.equal(explanationBefore.totalHttpReports, 74, 'Before new report, HTTP total was 74');
  assert.equal(explanationBefore.platformBreakdown['GOOGLE_TV_API30'], 8);
  assert.equal(explanationBefore.platformBreakdown['UNKNOWN_PLATFORM_API28'], 3);
  assert.equal(explanationBefore.platformBreakdown['ANDROID_TV_API28'], 2);

  // Snapshot 2: After VOX-90A5A5F0 arrived (GOOGLE_TV_API30 became 9, total 75)
  const httpReportsAfter = [
    ...httpReportsBefore,
    { error_category: 'PLAYER', error_signature: 'PLAYER|PLAYER_HTTP_ERROR|GOOGLE_TV_API30' }
  ];

  const explanationAfter = explainHttpErrorDiscrepancy(httpReportsAfter);
  assert.equal(explanationAfter.totalHttpReports, 75, 'After new report VOX-90A5A5F0, HTTP total is 75');
  assert.equal(explanationAfter.platformBreakdown['GOOGLE_TV_API30'], 9);

  // Arithmetic verification
  const sumAfter =
    explanationAfter.platformBreakdown['ANDROID_TV_API34'] +
    explanationAfter.platformBreakdown['ANDROID_TV_API30'] +
    explanationAfter.platformBreakdown['GOOGLE_TV_API30'] +
    explanationAfter.platformBreakdown['ANDROID_TV_API26'] +
    explanationAfter.platformBreakdown['GOOGLE_TV_API34'] +
    explanationAfter.platformBreakdown['UNKNOWN_PLATFORM_API28'] +
    explanationAfter.platformBreakdown['ANDROID_TV_API28'] +
    explanationAfter.platformBreakdown['GOOGLE_TV_API31'];

  assert.equal(sumAfter, 75, 'Sum of all 8 platform groups is 75');
});
