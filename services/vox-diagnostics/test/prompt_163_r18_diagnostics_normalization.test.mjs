import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { safeContext, structuredEvents, classifyReport } from '../src/triage.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

/**
 * Gate C: Local Implementation Verification Test for Prompt №163-R18
 * Verifies Option A (Worker-side Ingress Normalization):
 * - httpStatus (number or string) normalized to canonical integer responseCode (100-599).
 * - Existing valid responseCode preserved.
 * - Precedence: responseCode is canonical.
 * - Conflict detection: differing valid codes set statusConflict = 'CONFLICT'.
 * - Rejection of invalid codes: 0, 99, 600, 1102, strings, HTML.
 * - Zero leakage: httpStatus is never output in safeContext.
 * - Real R17 wire fixture now recovers responseCode 503.
 */

test('163-R18 Gate C.1: httpStatus normalization from number and string', () => {
  // Case A: Number 503 in httpStatus
  const ctxNum = safeContext({ httpStatus: 503, requestElapsedMs: '100' });
  assert.equal(ctxNum.responseCode, 503, 'Number 503 must be normalized to responseCode 503');
  assert.equal(ctxNum.httpStatus, undefined, 'httpStatus must be stripped from output');
  assert.equal(ctxNum.requestElapsedMs, '100');
  assert.equal(ctxNum.statusConflict, undefined);

  // Case B: String '503' in httpStatus
  const ctxStr = safeContext({ httpStatus: '503', reuseSource: 'NONE' });
  assert.equal(ctxStr.responseCode, 503, 'String 503 must be normalized to integer responseCode 503');
  assert.equal(ctxStr.httpStatus, undefined, 'httpStatus must be stripped from output');
  assert.equal(ctxStr.reuseSource, 'NONE');
  assert.equal(ctxStr.statusConflict, undefined);
});

test('163-R18 Gate C.2: Existing valid responseCode preserved and canonical precedence', () => {
  // Case A: Existing responseCode 403 preserved (number)
  const ctx403 = safeContext({ responseCode: 403, causeClass: 'InvalidResponseCodeException' });
  assert.equal(ctx403.responseCode, 403);
  assert.equal(ctx403.statusConflict, undefined);

  // Case B: Existing responseCode '403' preserved (string)
  const ctx403Str = safeContext({ responseCode: '403' });
  assert.equal(ctx403Str.responseCode, '403');
  assert.equal(ctx403Str.statusConflict, undefined);

  // Case C: Identical codes in both fields (no conflict)
  const ctxSame = safeContext({ responseCode: 503, httpStatus: '503' });
  assert.equal(ctxSame.responseCode, 503);
  assert.equal(ctxSame.statusConflict, undefined);

  // Case D: Conflicting valid codes (responseCode canonical + statusConflict)
  const ctxConflict = safeContext({ responseCode: 403, httpStatus: '503' });
  assert.equal(ctxConflict.responseCode, 403, 'Canonical responseCode 403 has precedence');
  assert.equal(ctxConflict.statusConflict, 'CONFLICT', 'statusConflict must be flagged');
  assert.equal(ctxConflict.httpStatus, undefined);
});

test('163-R18 Gate C.3: Strict rejection of invalid codes, boundaries, and sentinels', () => {
  const invalidInputs = [
    null, undefined, NaN, -1, 0, 99, 600, 1102,
    '0', '99', '600', '1102', 'Error 1102', '503 Service Unavailable',
    'http://evil.com', '<script>', 'x'.repeat(128)
  ];

  for (const invalid of invalidInputs) {
    const res = safeContext({ httpStatus: invalid });
    assert.equal(res.responseCode, undefined, `Invalid code ${invalid} must not produce responseCode`);
    assert.equal(res.httpStatus, undefined);
  }

  // PII and secrets are strictly stripped
  const piiContext = safeContext({
    httpStatus: 503,
    token: 'super_secret_token',
    password: 'my_password',
    cookie: 'session_cookie',
    primaryFailure: 'HTTP 503 for /video-translation/translate'
  });
  assert.equal(piiContext.responseCode, 503);
  assert.equal(piiContext.token, undefined);
  assert.equal(piiContext.password, undefined);
  assert.equal(piiContext.cookie, undefined);
  assert.equal(piiContext.primaryFailure, undefined);
});

test('163-R18 Gate C.4: Ingestion of actual Prompt 163-R17 Wire Fixture', () => {
  const fixturePath = path.join(__dirname, 'fixtures', 'prompt_163_r17_wire_fixture.json');
  assert.ok(fs.existsSync(fixturePath), 'Wire fixture must exist');

  const wireReport = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
  const events = structuredEvents(wireReport);
  assert.equal(events.length, 1);
  const ev = events[0];

  // Verified fix: responseCode is now recovered as 503!
  assert.equal(ev.safeContext.responseCode, 503, 'responseCode 503 successfully normalized from wire httpStatus');
  assert.equal(ev.safeContext.httpStatus, undefined, 'Raw httpStatus key not leaked to output');
  assert.equal(ev.safeContext.requestElapsedMs, '100', 'Timing metric preserved');
  assert.equal(ev.safeContext.reuseSource, 'NONE', 'Cache reuse preserved');

  // Classification of event severity
  assert.equal(ev.severity, 'LOW', 'Fallback event severity remains LOW');
});

test('163-R18 Gate C.5: Negative controls (DNS UnknownHostException and Cloudflare 1102 marker)', () => {
  // Scenario 1: DNS failure (no HTTP response code)
  const dnsReport = {
    schema: 'vox-diagnostic-report-v3',
    safeRecentEvents: [{
      code: 'TRANSLATION_FALLBACK_ATTEMPT',
      level: 'WARNING',
      context: {
        errorCategory: 'dns',
        reason: 'UnknownHostException'
      }
    }]
  };
  const dnsEvents = structuredEvents(dnsReport);
  assert.equal(dnsEvents[0].safeContext.responseCode, undefined, 'DNS failure must have no responseCode');
  assert.equal(dnsEvents[0].safeContext.errorCategory, 'dns');

  // Scenario 2: Body text marker 'Error 1102' does not manufacture HTTP code
  const cfReport = {
    schema: 'vox-diagnostic-report-v3',
    safeRecentEvents: [{
      code: 'TRANSLATION_FALLBACK_ATTEMPT',
      level: 'WARNING',
      context: {
        reason: 'Error 1102'
      }
    }]
  };
  const cfEvents = structuredEvents(cfReport);
  assert.equal(cfEvents[0].safeContext.responseCode, undefined, 'Error 1102 in body text must not manufacture HTTP code');
});
