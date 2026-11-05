import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { structuredEvents, safeContext, classifyReport } from '../src/triage.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

/**
 * Gate C: Offline Wire Contract Bridge Test
 * PROMPT №163-R17 (Verification of actual client serialized wire payload through Worker triage).
 *
 * Verifications:
 * 1. Wire Payload Provenance:
 *    Consumes prompt_163_r17_wire_fixture.json generated directly by Prompt163R17DiagnosticsEnvelopeFixtureTest.kt
 *    using production VoxLogEvent.toJson().
 * 2. Contract Gap Confirmation (Loss on verified offline wire path):
 *    Proves that on the production path, 'httpStatus: 503' is stripped by safeContext() because
 *    SAFE_CONTEXT_KEYS only permits 'responseCode', and no intermediate renaming mapper exists.
 * 3. Dimension Separation:
 *    Level WARNING maps to triage priority 'LOW' without computing or modifying audio quality.
 * 4. Negative Controls:
 *    - DNS scenario: no HTTP status manufactured.
 *    - Error 1102 in body: does not create an HTTP responseCode.
 *    - PII sentinels: tokens/passwords stripped from safeContext.
 */

test('Prompt 163-R17 Gate C.1: Actual Client Wire Payload Ingestion & HTTP Status Loss Confirmation', () => {
  const fixturePath = path.join(__dirname, 'fixtures', 'prompt_163_r17_wire_fixture.json');
  assert.ok(fs.existsSync(fixturePath), 'Wire fixture file must exist');

  const rawText = fs.readFileSync(fixturePath, 'utf8');
  const wireReport = JSON.parse(rawText);

  // Invariant 1: Wire payload contains exact client producer fields
  assert.equal(wireReport.schema, 'vox-diagnostic-report-v3');
  assert.equal(wireReport.safeRecentEvents.length, 1);
  const rawEvent = wireReport.safeRecentEvents[0];
  assert.equal(rawEvent.code, 'TRANSLATION_FALLBACK_ATTEMPT');
  assert.equal(rawEvent.context.httpStatus, '503', 'Wire JSON has raw httpStatus 503');
  assert.equal(rawEvent.context.httpClass, '5xx_SERVER_ERROR');
  assert.equal(rawEvent.context.primaryFailure, 'HTTP 503 for /video-translation/translate');

  // Invariant 2: Ingestion through Worker structuredEvents
  const events = structuredEvents(wireReport);
  assert.equal(events.length, 1);
  const ev = events[0];

  // Invariant 3: Timing and cache keys survive
  assert.equal(ev.safeContext.reuseSource, 'NONE');
  assert.equal(ev.safeContext.requestElapsedMs, '100');

  // Invariant 4: CONTRACT_GAP_CONFIRMED - httpStatus is lost on verified offline wire path
  assert.equal(ev.safeContext.httpStatus, undefined, 'httpStatus is stripped by SAFE_CONTEXT_KEYS');
  assert.equal(ev.safeContext.httpClass, undefined, 'httpClass is stripped by SAFE_CONTEXT_KEYS');
  assert.equal(ev.safeContext.primaryFailure, undefined, 'primaryFailure is stripped by SAFE_CONTEXT_KEYS');
  assert.equal(ev.safeContext.state, undefined, 'state is stripped by SAFE_CONTEXT_KEYS');
  assert.equal(ev.safeContext.fallbackCount, undefined, 'fallbackCount is stripped by SAFE_CONTEXT_KEYS');

  // Invariant 5: No mapper transforms httpStatus -> responseCode
  assert.equal(ev.safeContext.responseCode, undefined, 'responseCode is NOT populated by any mapper');

  // Invariant 6: Triage severity is LOW, audio quality strictly uncomputed
  assert.equal(ev.event, 'TRANSLATION_FALLBACK_ATTEMPT');
  assert.equal(ev.severity, 'LOW', 'WARNING fallback maps to triage severity LOW');
  assert.equal(ev.audioQuality, undefined, 'audioQuality is strictly uncomputed');
  assert.equal(ev.isAudioDegraded, undefined, 'isAudioDegraded is strictly uncomputed');
});

test('Prompt 163-R17 Gate C.2: Negative Controls (DNS, Error 1102 Body, PII Sentinels)', () => {
  // Scenario 2A: DNS Failure (No HTTP response code)
  const dnsReport = {
    schema: 'vox-diagnostic-report-v3',
    reportId: 'VOX-SYNTHETIC-DNS-R17',
    timestamp: 1730768100000,
    safeRecentEvents: [
      {
        code: 'TRANSLATION_FALLBACK_ATTEMPT',
        level: 'WARNING',
        context: {
          reason: 'dns_resolution_failed',
          errorCategory: 'dns',
          lookupElapsedMs: 12
        }
      }
    ]
  };

  const dnsEvents = structuredEvents(dnsReport);
  assert.equal(dnsEvents.length, 1);
  assert.equal(dnsEvents[0].safeContext.responseCode, undefined, 'DNS failure has no responseCode');
  assert.equal(dnsEvents[0].safeContext.errorCategory, 'dns');

  // Scenario 2B: Body marker 'Error 1102' does not forge transport HTTP status
  const cf1102Report = {
    schema: 'vox-diagnostic-report-v3',
    reportId: 'VOX-SYNTHETIC-CF1102-R17',
    timestamp: 1730768200000,
    safeRecentEvents: [
      {
        code: 'TRANSLATION_FALLBACK_ATTEMPT',
        level: 'WARNING',
        message: 'Worker returned Error 1102: Execution limit exceeded',
        context: {
          reason: 'Worker_error_1102_in_body',
          lookupElapsedMs: 20
        }
      }
    ]
  };

  const cfEvents = structuredEvents(cf1102Report);
  assert.equal(cfEvents.length, 1);
  assert.equal(cfEvents[0].safeContext.responseCode, undefined, 'Error 1102 in body text does not manufacture HTTP code');

  // Scenario 2C: PII / Secret sentinels are stripped
  const piiContext = {
    responseCode: 503,
    authToken: 'secret_oauth_token_12345',
    password: 'super_secret_password',
    cookie: 'session_cookie=abcde',
    url: 'https://youtube.com/watch?v=private_user_video'
  };

  const sanitized = safeContext(piiContext);
  assert.equal(sanitized.responseCode, 503);
  assert.equal(sanitized.authToken, undefined, 'Tokens must be stripped');
  assert.equal(sanitized.password, undefined, 'Passwords must be stripped');
  assert.equal(sanitized.cookie, undefined, 'Cookies must be stripped');
  assert.equal(sanitized.url, undefined, 'Raw URLs must be stripped');
});
