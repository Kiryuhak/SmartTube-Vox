import test from 'node:test';
import assert from 'node:assert/strict';
import { structuredEvents, classifyReport } from '../src/triage.mjs';

/**
 * Gate C.6 & Gate D: Offline Node Test for VOT Failover Diagnostics & Triage Envelope.
 * PROMPT №163-R15 (Scenarios C6 & D1-D4).
 *
 * Verifications:
 * 1. Warning event from translation fallback maps to triage severity 'LOW'.
 * 2. Three dimensions remain strictly separated:
 *    - transport_http_status (responseCode 503)
 *    - client_error_category (SERVER_UNAVAILABLE)
 *    - triage_severity (LOW)
 * 3. Synthetic body marker 'Error 1102' is treated as body payload and does NOT alter transport HTTP status.
 * 4. DNS failure (UnknownHostException) yields no HTTP response code.
 * 5. Audio quality is NOT evaluated or altered (strictly uncomputed).
 */
test('Prompt 163-R15 Gate C.6 & Gate D: VOT Failover Diagnostics and Dimension Separation', () => {
  // Test 1: Full payload simulation from VoxTranslationStateMachine.toDiagnosticsContext()
  const reportFallback503 = {
    reportId: 'SYNTHETIC-R15-VOT-FALLBACK-001',
    timestamp: 1730765000000,
    safeRecentEvents: [
      {
        code: 'TRANSLATION_FALLBACK_ATTEMPT',
        level: 'WARNING',
        context: {
          responseCode: 503,
          reason: 'primary_backend_http_503',
          lookupElapsedMs: 50,
          requestElapsedMs: 100,
          providerWaitElapsedMs: 250,
          totalTranslationStartupMs: 900
        }
      }
    ]
  };

  const events = structuredEvents(reportFallback503);
  assert.equal(events.length, 1);
  const ev = events[0];

  // Invariant 1: level WARNING maps to triage severity LOW
  assert.equal(ev.event, 'TRANSLATION_FALLBACK_ATTEMPT');
  assert.equal(ev.level, 'WARNING');
  assert.equal(ev.severity, 'LOW', 'Warning fallback must map to triage priority LOW');

  // Invariant 2: Audio quality must be strictly absent / uncomputed
  assert.equal(ev.audioQuality, undefined);
  assert.equal(ev.audioBitrate, undefined);
  assert.equal(ev.isAudioDegraded, undefined);
  assert.notEqual(ev.severity, 'AUDIO_QUALITY_LOW');

  // Invariant 3: Dimension separation check
  const transportHttpStatus = ev.safeContext?.responseCode;
  const triageSeverity = ev.severity;
  assert.equal(transportHttpStatus, 503, 'Transport responseCode must remain 503');
  assert.equal(triageSeverity, 'LOW', 'Triage severity must remain LOW');
  assert.notEqual(transportHttpStatus, triageSeverity, 'Transport status and triage severity are distinct dimensions');

  // Test 2: DNS failure payload (UnknownHostException before HTTP response)
  const reportDnsFailure = {
    reportId: 'SYNTHETIC-R15-DNS-002',
    timestamp: 1730765000000,
    safeRecentEvents: [
      {
        code: 'TRANSLATION_FALLBACK_ATTEMPT',
        level: 'WARNING',
        context: {
          reason: 'dns_unknown_host'
        }
      }
    ]
  };

  const dnsEvents = structuredEvents(reportDnsFailure);
  assert.equal(dnsEvents.length, 1);
  assert.equal(dnsEvents[0].safeContext?.responseCode, undefined, 'DNS failure must have NO HTTP responseCode');
  assert.equal(dnsEvents[0].severity, 'LOW', 'Fallback event retains triage priority LOW');

  // Test 3: Synthetic Cloudflare Error 1102 body marker does not invent or assume HTTP status
  const reportCf1102Body = {
    reportId: 'SYNTHETIC-R15-CF1102-003',
    timestamp: 1730765000000,
    safeRecentEvents: [
      {
        code: 'TRANSLATION_PROVIDER_ERROR',
        level: 'ERROR',
        context: {
          reason: 'cloudflare_worker_error_1102'
        }
      }
    ]
  };

  const cfEvents = structuredEvents(reportCf1102Body);
  assert.equal(cfEvents.length, 1);
  // Absence of transport HTTP status in body must leave responseCode undefined, not invent 503 or 1102
  assert.equal(cfEvents[0].safeContext?.responseCode, undefined, 'Body marker alone must NOT invent HTTP status code');
  assert.equal(cfEvents[0].severity, 'MEDIUM', 'Single ERROR level yields MEDIUM severity');

  // Test 4: Negative controls / dynamic discrimination
  const reportLevels = {
    reportId: 'SYNTHETIC-R15-LEVELS-004',
    timestamp: 1730765000000,
    safeRecentEvents: [
      { code: 'CRASH_LOOP_DETECTED', level: 'CRITICAL' },
      { code: 'PLAYER_REBUFFER', level: 'ERROR' },
      { code: 'TRANSLATION_FALLBACK_ATTEMPT', level: 'WARNING' },
      { code: 'PLAYBACK_START', level: 'INFO' }
    ]
  };

  const levelEvents = structuredEvents(reportLevels);
  assert.equal(levelEvents[0].severity, 'CRITICAL');
  assert.equal(levelEvents[1].severity, 'MEDIUM');
  assert.equal(levelEvents[2].severity, 'LOW');
  assert.equal(levelEvents[3].severity, 'INFO');

  // Test 5: classifyReport execution
  const classification = classifyReport(reportFallback503);
  assert.ok(classification);
  assert.equal(classification.audioDegraded, undefined);
});
