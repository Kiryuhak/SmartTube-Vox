import test from 'node:test';
import assert from 'node:assert/strict';
import { safeContext } from '../src/triage.mjs';

test('163-R19 Gate C.1: Status conflict and precedence between responseCode and httpStatus', () => {
  // Case A: Differing valid codes -> conflict recorded, responseCode takes precedence
  const inputA = { responseCode: 503, httpStatus: 403 };
  const resA = safeContext(inputA);
  assert.equal(resA.responseCode, 503, 'responseCode must take precedence over httpStatus');
  assert.equal(resA.statusConflict, 'CONFLICT', 'statusConflict must be CONFLICT');
  assert.equal(resA.responseCodeConflict, 'CONFLICT', 'responseCodeConflict must be CONFLICT');
  assert.equal(resA.httpStatus, undefined, 'httpStatus must not leak into safeContext');

  // Case B: Identical valid codes -> no conflict
  const inputB = { responseCode: 503, httpStatus: 503 };
  const resB = safeContext(inputB);
  assert.equal(resB.responseCode, 503);
  assert.equal(resB.statusConflict, undefined);
  assert.equal(resB.responseCodeConflict, undefined);

  // Case C: String responseCode and numeric httpStatus match -> no conflict, legacy responseCode preserved
  const inputC = { responseCode: '503', httpStatus: 503 };
  const resC = safeContext(inputC);
  assert.equal(resC.responseCode, '503');
  assert.equal(resC.statusConflict, undefined);

  // Case D: Only httpStatus present (number) -> normalized to canonical numeric responseCode
  const inputD = { httpStatus: 429 };
  const resD = safeContext(inputD);
  assert.equal(resD.responseCode, 429);
  assert.equal(resD.statusConflict, undefined);
  assert.equal(resD.httpStatus, undefined);

  // Case E: Only httpStatus present (string) -> normalized to canonical numeric responseCode
  const inputE = { httpStatus: '503' };
  const resE = safeContext(inputE);
  assert.equal(resE.responseCode, 503);
  assert.equal(resE.statusConflict, undefined);
  assert.equal(resE.httpStatus, undefined);
});

test('163-R19 Gate C.2: Strict validity boundaries (100-599) and rejection of invalid / garbage inputs', () => {
  // Valid numeric httpStatus codes: 100, 403, 429, 500, 503, 599
  const validCodes = [100, 403, 429, 500, 503, 599, '100', '403', '429', '500', '503', '599', ' 503 '];
  for (const c of validCodes) {
    const res = safeContext({ httpStatus: c });
    assert.equal(typeof res.responseCode, 'number', `Code ${c} must be normalized to number`);
    assert.equal(res.responseCode, Number(String(c).trim()), `Code ${c} must match numeric value`);
  }

  // Non-canonical / invalid inputs: 0, 99, 600, 1102, null, undefined, NaN, Infinity, true, [], {}, "Error 1102", "503garbage"
  const invalidInputs = [
    0, 99, 600, 1102, null, undefined, NaN, Infinity, -503,
    true, false, [], {}, { a: 1 },
    '0', '99', '600', '1102', 'Error 1102', '503garbage', '503 abc',
    'HTTP 503', 'abc503', '', '   ', 'x'.repeat(1000)
  ];

  for (const inv of invalidInputs) {
    const res = safeContext({ httpStatus: inv });
    assert.equal(res.responseCode, undefined, `Input ${JSON.stringify(inv)} must NOT produce responseCode`);
    assert.equal(res.httpStatus, undefined, 'httpStatus must be deleted');
  }

  // Verify that an invalid responseCode is also stripped
  for (const inv of [0, 99, 600, 1102, 'Error 1102', 'garbage']) {
    const res = safeContext({ responseCode: inv });
    assert.equal(res.responseCode, undefined, `Invalid responseCode ${inv} must be stripped`);
  }
});

test('163-R19 Gate C.3: Zero PII or secret leakage into safeContext', () => {
  const sensitiveInput = {
    responseCode: 503,
    httpStatus: 503,
    url: 'https://example.com/videoplayback?expire=1793794480&key=AIzaSySecret',
    authorization: 'Bearer ya29.secret_token',
    cookie: 'SID=secret_session; SSID=secret_ssid',
    rawStack: 'Error at com.liskovsoft.youtubeapi.service.YouTubeServiceManager.switchNextClientNow:42',
    userId: 'user_12345',
    token: 'jwt_secret_token',
    clientIp: '192.168.2.114',
    macAddress: 'AA:BB:CC:DD:EE:FF'
  };

  const res = safeContext(sensitiveInput);
  assert.equal(res.responseCode, 503);
  assert.equal(res.httpStatus, undefined);
  assert.equal(res.url, undefined);
  assert.equal(res.authorization, undefined);
  assert.equal(res.cookie, undefined);
  assert.equal(res.rawStack, undefined);
  assert.equal(res.userId, undefined);
  assert.equal(res.token, undefined);
  assert.equal(res.clientIp, undefined);
  assert.equal(res.macAddress, undefined);

  // Verify only allowed safe keys exist
  const keys = Object.keys(res);
  for (const k of keys) {
    assert.ok(['responseCode', 'statusConflict', 'responseCodeConflict'].includes(k), `Unexpected key leaked: ${k}`);
  }
});

test('163-R19 Gate C.4: Idempotency and input immutability', () => {
  const original = Object.freeze({
    responseCode: 503,
    httpStatus: 503,
    bufferedDurationMs: 15000,
    causeClass: 'HttpDataSourceException'
  });

  // safeContext must not throw when input is frozen (immutability)
  const pass1 = safeContext(original);
  assert.equal(pass1.responseCode, 503);
  assert.equal(pass1.bufferedDurationMs, 15000);
  assert.equal(pass1.causeClass, 'HttpDataSourceException');

  // Second pass: safeContext(safeContext(x))
  const pass2 = safeContext(pass1);
  assert.deepEqual(pass2, pass1, 'safeContext must be strictly idempotent');
});
