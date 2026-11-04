import test from 'node:test';
import assert from 'node:assert/strict';
import { structuredEvents, classifyReport } from '../src/triage.mjs';

/**
 * Gate C.4 / Gate D: Offline Node Test for Worker Triage Function.
 * PROMPT №163-R14 (Targeted Verification of telemetry WARNING -> triage priority LOW).
 *
 * Verifications:
 * 1. Warning-level event (e.g. VOT fallback) resolves to triage severity 'LOW'.
 * 2. Triage priority 'LOW' is strictly a ticketing/triage severity, not audio quality degradation.
 * 3. Multi-severity discrimination ensures non-constant mapping (CRITICAL, MEDIUM, LOW, INFO).
 * 4. Zero external network, zero worker deployments, zero production modifications.
 */
test('Prompt 163-R14 Gate C.4: Telemetry level WARNING maps to triage severity LOW (not audio degradation)', () => {
  // Test Case 1: VOT Fallback warning event maps to triage severity LOW
  const reportWarning = {
    reportId: 'SYNTHETIC-R14-VOT-001',
    timestamp: 1730764800000,
    safeRecentEvents: [
      {
        code: 'VOT_FALLBACK_TRIGGERED',
        level: 'WARNING',
        context: { httpCode: 503, reason: 'primary_backend_timeout' }
      }
    ]
  };

  const events = structuredEvents(reportWarning);
  assert.equal(events.length, 1);
  const ev = events[0];
  assert.equal(ev.event, 'VOT_FALLBACK_TRIGGERED');
  assert.equal(ev.level, 'WARNING');
  assert.equal(ev.severity, 'LOW', 'WARNING level must map to triage priority LOW');

  // Verify that audio quality is NOT computed or marked as degraded by the triage function
  assert.equal(ev.audioQuality, undefined, 'audioQuality property must not exist on event');
  assert.equal(ev.audioBitrate, undefined, 'audioBitrate property must not exist on event');
  assert.equal(ev.isAudioDegraded, undefined, 'isAudioDegraded property must not exist on event');
  assert.notEqual(ev.severity, 'AUDIO_QUALITY_LOW', 'severity LOW is triage priority, not audio quality');

  // Test Case 2: Multi-level discrimination check (verify severity calculation is dynamic, not a constant fiction)
  const reportMulti = {
    reportId: 'SYNTHETIC-R14-MULTI-002',
    timestamp: 1730764800000,
    safeRecentEvents: [
      { code: 'CRASH_LOOP_DETECTED', level: 'CRITICAL' },
      { code: 'PLAYER_REBUFFER', level: 'ERROR' },
      { code: 'VOT_FALLBACK_TRIGGERED', level: 'WARNING' },
      { code: 'PLAYBACK_START', level: 'INFO' }
    ]
  };

  const multiEvents = structuredEvents(reportMulti);
  assert.equal(multiEvents.length, 4);
  assert.equal(multiEvents[0].severity, 'CRITICAL', 'CRITICAL event must yield CRITICAL severity');
  assert.equal(multiEvents[1].severity, 'MEDIUM', 'Single ERROR event must yield MEDIUM severity');
  assert.equal(multiEvents[2].severity, 'LOW', 'WARNING event must yield LOW severity');
  assert.equal(multiEvents[3].severity, 'INFO', 'INFO event must yield INFO severity');

  // Test Case 3: classifyReport integration
  const classification = classifyReport(reportWarning);
  assert.ok(classification, 'classifyReport must return classification object');
  assert.equal(classification.audioDegraded, undefined, 'audioDegraded must not be set by triage');
});
