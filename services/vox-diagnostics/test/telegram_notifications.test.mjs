import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  TelegramClient,
  dispatchTelegramNotification,
  resetTelegramState,
  maskChatId,
  maskBotToken,
  logTelegramEvent
} from '../src/notifications/telegram.mjs';
import {
  createSanitizedTelegramBundle,
  sanitizeUrl,
  sanitizeContext,
  generateTelegramBundleFilename
} from '../src/notifications/sanitizer.mjs';
import {
  formatNewIssueMessage,
  formatRegressionMessage,
  formatAggregateMessage,
  buildDashboardKeyboard
} from '../src/notifications/telegram_formatter.mjs';
import { classifyReport } from '../src/triage.mjs';
import { handleRequest } from '../src/diagnostics.mjs';
import { ReportStorage } from '../src/storage.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const fixturesDir = path.resolve(__dirname, 'fixtures');

test.beforeEach(() => {
  resetTelegramState();
});

test('bundleRemovesSecrets: removes tokens, cookies, passwords, auth headers', () => {
  const dirty = {
    Authorization: 'Bearer secret_token_123',
    Cookie: 'session=xyz987',
    token: 'jwt_token_abc',
    password: 'super_secret_pw',
    user: 'kiryuha',
    safeField: 'normal_value',
    nested: {
      refreshToken: 'refresh_999',
      signedUrl: 'https://video.example.com?sig=secret_hash_value',
      count: 42
    }
  };

  const clean = sanitizeContext(dirty);
  assert.equal(clean.Authorization, undefined);
  assert.equal(clean.Cookie, undefined);
  assert.equal(clean.token, undefined);
  assert.equal(clean.password, undefined);
  assert.equal(clean.safeField, 'normal_value');
  assert.equal(clean.nested.refreshToken, undefined);
  assert.equal(clean.nested.count, 42);
  assert.equal(clean.nested.signedUrl, undefined, 'signedUrl must be completely removed');
});

test('bundleRemovesSignedUrls: strips signature and token query parameters from video/audio urls', () => {
  const googlevideoUrl = 'https://rr1---sn-4g5ednss.googlevideo.com/videoplayback?expire=1792039950&ei=AbCdEf&ip=192.168.1.1&id=o-ABC&itag=251&source=youtube&requiressl=yes&spc=token123&vprv=1&mime=audio%2Fwebm&ns=xyz&gir=yes&clen=4000000&dur=240&lmt=123&keepalive=yes&fexp=123&sig=SIGNATURE_HASH_XYZ&key=cms1';
  const clean = sanitizeUrl(googlevideoUrl);

  assert.ok(!clean.includes('sig='), 'Must not contain sig=');
  assert.ok(!clean.includes('token123'), 'Must not contain token');
  assert.ok(!clean.includes('SIGNATURE_HASH_XYZ'), 'Must not contain signature hash');
  assert.equal(clean, 'https://rr1---sn-4g5ednss.googlevideo.com/videoplayback');
});

test('bundleLimitsHistory: caps safeRecentEvents to 30 events maximum', () => {
  const events = [];
  for (let i = 0; i < 50; i++) {
    events.push({
      timestamp: Date.now() + i,
      category: 'PLAYER',
      code: `EVENT_${i}`,
      level: 'INFO',
      message: `Message ${i}`
    });
  }

  const bundle = createSanitizedTelegramBundle(
    { reportId: 'VOX-LIMIT-TEST', appVersion: '32.56-vox.9.1', safeRecentEvents: events },
    { errorCategory: 'PLAYER', severity: 'INFO' },
    {}
  );

  assert.equal(bundle.safeRecentEvents.length, 30, 'Must limit to 30 events');
  assert.equal(bundle.safeRecentEvents[29].code, 'EVENT_49', 'Must keep the newest events');
});

test('dashboardLinkIncluded: inline keyboard contains valid dashboard link', () => {
  const keyboard = buildDashboardKeyboard('https://diagnostics.smarttube.app', 'report', 'VOX-D0DF1A08');
  assert.ok(keyboard);
  assert.ok(keyboard.inline_keyboard);
  assert.equal(keyboard.inline_keyboard[0][0].text, '📊 Открыть в Dashboard');
  assert.equal(keyboard.inline_keyboard[0][0].url, 'https://diagnostics.smarttube.app/admin#report=VOX-D0DF1A08');
});

test('newActiveIssueSendsTelegram and attaches sanitized json', async () => {
  const requests = [];
  const originalFetch = globalThis.fetch;

  globalThis.fetch = async (url, opts) => {
    requests.push({ url, opts });
    return new Response(JSON.stringify({ ok: true, result: { message_id: 12345 } }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  };

  try {
    const report = {
      reportId: 'VOX-88A19B02',
      appVersion: '32.56-vox.9.1',
      platform: 'Android TV',
      device: { manufacturer: 'TCL', model: 'Smart TV Pro', osVersion: '14 (API 34)' },
      safeRecentEvents: [
        { timestamp: Date.now(), level: 'ERROR', category: 'PLAYER', code: 'PLAYER_HTTP_ERROR', message: 'HTTP 403' }
      ]
    };
    const classification = classifyReport(report);

    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'FAKE_BOT_TOKEN',
      TELEGRAM_CHAT_ID: '-1001234567890',
      DASHBOARD_BASE_URL: 'https://diagnostics.smarttube.app'
    };

    const res = await dispatchTelegramNotification({
      report,
      classification,
      issue: { key: 'PLAYER_HTTP_403', title: 'HTTP 403', status: 'NEW' },
      env
    });

    assert.equal(res.decision, 'SENT_NEW');
    assert.equal(res.messageId, 12345);
    assert.equal(requests.length, 1);
    assert.ok(requests[0].url.includes('sendDocument'));
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('historicalOnlyDoesNotSend: suppresses telegram alert for historical-only reports', async () => {
  const requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    requests.push({ url, opts });
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };

  try {
    const raw = JSON.parse(fs.readFileSync(path.join(fixturesDir, 'vox-diagnostic-VOX-D134CD89.json'), 'utf8'));
    const payload = raw.payload || raw;
    const classification = classifyReport(payload);

    assert.equal(classification.reportActiveIncident, false, 'Classification must be historical-only');

    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'FAKE_BOT_TOKEN',
      TELEGRAM_CHAT_ID: '-1001234567890'
    };

    const res = await dispatchTelegramNotification({
      report: payload,
      classification,
      issue: { status: 'RESOLVED' },
      env
    });

    assert.equal(res.decision, 'SUPPRESSED_HISTORICAL');
    assert.equal(requests.length, 0, 'Must NOT make any Telegram network calls');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('ignoredTestDoesNotSend: suppresses telegram alert for synthetic / test reports to prod chat', async () => {
  const requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    requests.push({ url, opts });
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };

  try {
    const report = {
      reportId: 'VOX-TEST-SYNTHETIC',
      reportPurpose: 'TEST',
      appVersion: '32.56-vox.9.1',
      model: 'Robolectric',
      safeRecentEvents: [
        { timestamp: Date.now(), level: 'ERROR', category: 'PLAYER', code: 'TEST_ERROR', message: 'Test error' }
      ]
    };
    const classification = classifyReport(report);

    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'FAKE_BOT_TOKEN',
      TELEGRAM_CHAT_ID: '-1001234567890'
    };

    const res = await dispatchTelegramNotification({
      report,
      classification,
      issue: { status: 'NEW' },
      env
    });

    assert.equal(res.decision, 'SUPPRESSED_TEST');
    assert.equal(requests.length, 0, 'Must NOT send to prod chat');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('duplicateWithinWindowDoesNotSend: suppresses duplicate issues inside 15m aggregation window', async () => {
  const requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    requests.push({ url, opts });
    return new Response(JSON.stringify({ ok: true, result: { message_id: 100 } }), { status: 200 });
  };

  try {
    const report1 = {
      reportId: 'VOX-DUP-1',
      appVersion: '32.56-vox.9.1',
      errorSignature: 'PLAYER|BUFFER_POLICY|ANDROID_TV_API30',
      safeRecentEvents: [{ timestamp: Date.now(), level: 'ERROR', category: 'PLAYER', code: 'PLAYER_REBUFFER' }]
    };
    const report2 = {
      reportId: 'VOX-DUP-2',
      appVersion: '32.56-vox.9.1',
      errorSignature: 'PLAYER|BUFFER_POLICY|ANDROID_TV_API30',
      safeRecentEvents: [{ timestamp: Date.now(), level: 'ERROR', category: 'PLAYER', code: 'PLAYER_REBUFFER' }]
    };

    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'FAKE_BOT_TOKEN',
      TELEGRAM_CHAT_ID: '-1001234567890',
      TELEGRAM_AGGREGATION_WINDOW_MINUTES: '15'
    };

    const res1 = await dispatchTelegramNotification({
      report: report1,
      classification: classifyReport(report1),
      issue: { status: 'NEW' },
      env
    });
    assert.equal(res1.decision, 'SENT_NEW');
    assert.equal(requests.length, 1);

    // Second report with identical signature within window
    const res2 = await dispatchTelegramNotification({
      report: report2,
      classification: classifyReport(report2),
      issue: { status: 'NEW' },
      env
    });
    assert.equal(res2.decision, 'SUPPRESSED_DUPLICATE');
    assert.equal(res2.repeatCount, 1);
    assert.equal(requests.length, 1, 'Must NOT send duplicate message');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('regressionSendsCriticalAlert: sends critical alert when resolved issue reopens on fresh report', async () => {
  const requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    requests.push({ url, opts });
    return new Response(JSON.stringify({ ok: true, result: { message_id: 200 } }), { status: 200 });
  };

  try {
    const report = {
      reportId: 'VOX-REGRESS-01',
      appVersion: '32.56-vox.9.1',
      device: { manufacturer: 'TCL', model: 'Smart TV Pro', osVersion: '14' },
      safeRecentEvents: [
        { timestamp: Date.now(), level: 'ERROR', category: 'PLAYER', code: 'PLAYER_HTTP_ERROR', message: 'HTTP 403 returned' }
      ]
    };

    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'FAKE_BOT_TOKEN',
      TELEGRAM_CHAT_ID: '-1001234567890'
    };

    const res = await dispatchTelegramNotification({
      report,
      classification: classifyReport(report),
      issue: {
        key: 'PLAYER_HTTP_403',
        status: 'RESOLVED',
        fixedInVersion: '32.56-vox.9.0',
        reopened: true
      },
      env
    });

    assert.equal(res.decision, 'SENT_REGRESSION');
    assert.equal(requests.length, 1);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('telegram429UsesBoundedRetry and respects retry_after', async () => {
  let attempts = 0;
  const originalFetch = globalThis.fetch;

  globalThis.fetch = async () => {
    attempts++;
    if (attempts === 1) {
      return new Response(JSON.stringify({ ok: false, parameters: { retry_after: 0.05 } }), { status: 429 });
    }
    return new Response(JSON.stringify({ ok: true, result: { message_id: 300 } }), { status: 200 });
  };

  try {
    const client = new TelegramClient('FAKE_TOKEN');
    const res = await client.sendMessage({ chatId: '123', text: 'Hello' });

    assert.equal(res.ok, true);
    assert.equal(attempts, 2, 'Must retry on 429');
    assert.equal(res.result.message_id, 300);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('telegram401DoesNotRetryForever: aborts immediately on 401 unauthorized', async () => {
  let attempts = 0;
  const originalFetch = globalThis.fetch;

  globalThis.fetch = async () => {
    attempts++;
    return new Response(JSON.stringify({ ok: false, description: 'Unauthorized' }), { status: 401 });
  };

  try {
    const client = new TelegramClient('INVALID_TOKEN');
    const res = await client.sendMessage({ chatId: '123', text: 'Hello' });

    assert.equal(res.ok, false);
    assert.equal(attempts, 1, 'Must NOT retry 401');
    assert.equal(res.status, 401);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('telegramFailureDoesNotFailIngestion: ingestion succeeds even if telegram network fails', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    throw new Error('Telegram Connection Refused');
  };

  try {
    const storage = new ReportStorage();
    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'FAKE_BOT_TOKEN',
      TELEGRAM_CHAT_ID: '-1001234567890'
    };

    const payload = {
      schema: 'vox-diagnostic-report-v2',
      appVersion: '32.56-vox.9.1',
      platform: 'Android TV',
      deviceFamily: 'TCL Smart TV Pro',
      manufacturer: 'TCL',
      model: 'Smart TV Pro',
      safeRecentEvents: [
        { timestamp: Date.now(), level: 'ERROR', category: 'PLAYER', code: 'PLAYER_HTTP_ERROR', message: 'Test error' }
      ]
    };

    const req = new Request('http://localhost/v1/report', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });

    const res = await handleRequest(req, env, storage);
    assert.equal(res.status, 201, 'Ingestion must return 201 Created despite Telegram failure');

    const body = await res.json();
    assert.equal(body.status, 'ok');
    assert.ok(body.reportId);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('Real Fixture VOX-D0DF1A08: triggers Telegram NEW alert for DuneHD player rebuffer', async () => {
  const requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    requests.push({ url, opts });
    return new Response(JSON.stringify({ ok: true, result: { message_id: 400 } }), { status: 200 });
  };

  try {
    const raw = JSON.parse(fs.readFileSync(path.join(fixturesDir, 'vox-diagnostic-VOX-D0DF1A08.json'), 'utf8'));
    const payload = raw.payload || raw;
    const classification = classifyReport(payload);

    assert.equal(classification.reportActiveIncident, true, 'VOX-D0DF1A08 must be an active incident');
    assert.equal(classification.primaryEvent?.event, 'PLAYER_REBUFFER');

    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'FAKE_BOT_TOKEN',
      TELEGRAM_CHAT_ID: '-1001234567890'
    };

    const res = await dispatchTelegramNotification({
      report: payload,
      classification,
      issue: { key: 'PLAYER_REBUFFER_BUFFER_POLICY', title: 'Buffer Policy Too Small', status: 'NEW' },
      env
    });

    assert.equal(res.decision, 'SENT_NEW');
    assert.equal(requests.length, 1);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('Real Fixture VOX-BAE1F61E: old-version events suppress Telegram alert', async () => {
  const requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    requests.push({ url, opts });
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };

  try {
    const raw = JSON.parse(fs.readFileSync(path.join(fixturesDir, 'vox-diagnostic-VOX-BAE1F61E.json'), 'utf8'));
    const payload = raw.payload || raw;
    const classification = classifyReport(payload);

    assert.equal(classification.reportActiveIncident, false, 'VOX-BAE1F61E must have reportActiveIncident=false');

    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'FAKE_BOT_TOKEN',
      TELEGRAM_CHAT_ID: '-1001234567890'
    };

    const res = await dispatchTelegramNotification({
      report: payload,
      classification,
      issue: { status: 'RESOLVED' },
      env
    });

    assert.equal(res.decision, 'SUPPRESSED_HISTORICAL');
    assert.equal(requests.length, 0);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('Report VOX-707B7E10: stale/historical events yield SUPPRESSED_HISTORICAL', async () => {
  const requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    requests.push({ url, opts });
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };

  try {
    const report = {
      reportId: 'VOX-707B7E10',
      status: 'NEW',
      appVersion: '32.56-vox.9.1',
      device: { manufacturer: 'TCL', model: 'Beyond TV', osVersion: '11' },
      safeRecentEvents: [
        {
          timestamp: Date.now() - 3600000,
          category: 'PLAYER',
          code: 'PLAYER_REBUFFER',
          level: 'WARN',
          isHistorical: true
        }
      ]
    };

    const classification = {
      reportActiveIncident: false,
      activeEventCount: 0,
      historicalEventCount: 1,
      signature: 'PLAYER|REBUFFER|ANDROID_TV'
    };

    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'FAKE_BOT_TOKEN',
      TELEGRAM_CHAT_ID: '302331721'
    };

    const res = await dispatchTelegramNotification({
      report,
      classification,
      issue: { status: 'NEW' },
      env
    });

    assert.equal(res.decision, 'SUPPRESSED_HISTORICAL');
    assert.equal(requests.length, 0, 'No Telegram message should be sent for historical report');
  } finally {
    globalThis.fetch = originalFetch;
  }
});


// ==========================================
// SECTION 24: TELEGRAM UNIT TESTS (10 REQUIRED)
// ==========================================

test('section24_1_fakeIncidentProducesValidRequest', async () => {
  const requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    requests.push({ url, opts });
    return new Response(JSON.stringify({ ok: true, result: { message_id: 10001 } }), { status: 200 });
  };

  try {
    const report = {
      reportId: 'VOX-REQ-VALID-01',
      appVersion: '32.56-vox.9.1',
      safeRecentEvents: [{ timestamp: Date.now(), level: 'ERROR', category: 'PLAYER', code: 'PLAYER_HTTP_ERROR' }]
    };
    const res = await dispatchTelegramNotification({
      report,
      classification: classifyReport(report),
      issue: { status: 'NEW' },
      env: {
        TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
        TELEGRAM_BOT_TOKEN: '123456:FAKE_BOT_TOKEN_XYZ',
        TELEGRAM_CHAT_ID: '-100987654321'
      }
    });

    assert.equal(res.deliveryState, 'DELIVERED');
    assert.equal(requests.length, 1);
    assert.ok(requests[0].url.includes('/sendDocument'));
    assert.equal(requests[0].opts.method, 'POST');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('section24_2_parseSuccessResponse', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    return new Response(JSON.stringify({ ok: true, result: { message_id: 8888, date: 1700000000 } }), { status: 200 });
  };

  try {
    const client = new TelegramClient('TOKEN_123');
    const res = await client.sendMessage({ chatId: '123', text: 'Test' });
    assert.equal(res.ok, true);
    assert.equal(res.result.message_id, 8888);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('section24_3_http200WithOkFalseYieldsFailed', async () => {
  const originalFetch = globalThis.fetch;
  // HTTP 200 but ok=false (Telegram API error)
  globalThis.fetch = async () => {
    return new Response(JSON.stringify({ ok: false, description: 'Bad Request: chat not found', error_code: 400 }), { status: 200 });
  };

  try {
    const client = new TelegramClient('TOKEN_123');
    const res = await client.sendMessage({ chatId: 'invalid_chat', text: 'Test' });
    assert.equal(res.ok, false, 'HTTP 200 with ok=false MUST yield ok: false');
    assert.equal(res.error, 'Bad Request: chat not found');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('section24_4_rateLimit429ParsesRetryAfter', async () => {
  let callCount = 0;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    callCount++;
    if (callCount === 1) {
      return new Response(JSON.stringify({ ok: false, parameters: { retry_after: 0.01 } }), { status: 429 });
    }
    return new Response(JSON.stringify({ ok: true, result: { message_id: 777 } }), { status: 200 });
  };

  try {
    const client = new TelegramClient('TOKEN_123');
    const res = await client.sendMessage({ chatId: '123', text: 'Rate limit test' });
    assert.equal(res.ok, true);
    assert.equal(callCount, 2, 'Must retry after retry_after duration');
    assert.equal(res.result.message_id, 777);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('section24_5_retryableErrorSchedulesBoundedRetry', async () => {
  let callCount = 0;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    callCount++;
    return new Response('Server Error', { status: 503 });
  };

  try {
    const client = new TelegramClient('TOKEN_123');
    const res = await client.sendMessage({ chatId: '123', text: '503 test' });
    assert.equal(res.ok, false);
    assert.equal(callCount, 3, 'Must stop at MAX_RETRIES (3)');
    assert.equal(res.isPermanent, false, '5xx is retryable, not permanent');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('section24_6_permanentErrorStopsRetryImmediately', async () => {
  let callCount = 0;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    callCount++;
    return new Response(JSON.stringify({ ok: false, description: 'Forbidden: bot was blocked by the user' }), { status: 403 });
  };

  try {
    const client = new TelegramClient('TOKEN_123');
    const res = await client.sendMessage({ chatId: '123', text: 'Blocked test' });
    assert.equal(res.ok, false);
    assert.equal(callCount, 1, '403 must stop immediately without retry');
    assert.equal(res.isPermanent, true, '403 is a permanent error');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('section24_7_newIncidentBypassesSuppressionBeforeFirstDelivered', async () => {
  let callCount = 0;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    callCount++;
    if (callCount <= 3) {
      // First dispatch attempts fail due to temporary network error
      throw new Error('Network unreachable');
    }
    // Second attempt succeeds
    return new Response(JSON.stringify({ ok: true, result: { message_id: 555 } }), { status: 200 });
  };

  try {
    const report = {
      reportId: 'VOX-FIRST-FAIL-01',
      appVersion: '32.56-vox.9.1',
      errorSignature: 'PLAYER|FIRST_DELIVERY_FAIL',
      safeRecentEvents: [{ timestamp: Date.now(), level: 'ERROR', category: 'PLAYER', code: 'PLAYER_HTTP_ERROR' }]
    };
    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'TOKEN_123',
      TELEGRAM_CHAT_ID: '-100123456789'
    };

    // First attempt: Fails
    const res1 = await dispatchTelegramNotification({
      report,
      classification: classifyReport(report),
      issue: { status: 'NEW' },
      env
    });
    assert.equal(res1.decision, 'FAILED');

    // Second attempt: Same incident MUST NOT be suppressed because it was never delivered!
    const res2 = await dispatchTelegramNotification({
      report,
      classification: classifyReport(report),
      issue: { status: 'NEW' },
      env
    });
    assert.equal(res2.decision, 'SENT_NEW');
    assert.equal(res2.deliveryState, 'DELIVERED');
    assert.equal(res2.messageId, 555);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('section24_8_duplicateAfterDeliverySuppressed', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    return new Response(JSON.stringify({ ok: true, result: { message_id: 444 } }), { status: 200 });
  };

  try {
    const report = {
      reportId: 'VOX-DUP-DELIVERY-01',
      appVersion: '32.56-vox.9.1',
      errorSignature: 'PLAYER|DUP_AFTER_DELIVERY',
      safeRecentEvents: [{ timestamp: Date.now(), level: 'ERROR', category: 'PLAYER', code: 'PLAYER_HTTP_ERROR' }]
    };
    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'TOKEN_123',
      TELEGRAM_CHAT_ID: '-100123456789',
      TELEGRAM_AGGREGATION_WINDOW_MINUTES: '15'
    };

    // First: Delivered
    const res1 = await dispatchTelegramNotification({
      report,
      classification: classifyReport(report),
      issue: { status: 'NEW' },
      env
    });
    assert.equal(res1.deliveryState, 'DELIVERED');

    // Second: Within cooldown -> SUPPRESSED_DUPLICATE
    const res2 = await dispatchTelegramNotification({
      report,
      classification: classifyReport(report),
      issue: { status: 'NEW' },
      env
    });
    assert.equal(res2.deliveryState, 'SUPPRESSED_DUPLICATE');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('section24_9_zeroSecretsLogged', () => {
  // 1. Bot token masking
  const token = '123456789:AAH_super_secret_bot_token_value';
  const maskedToken = maskBotToken(token);
  assert.ok(!maskedToken.includes('super_secret_bot_token_value'), 'Secret must be masked');
  assert.equal(maskedToken, '12345***:AAH***');

  // 2. Chat ID masking
  const chatId = '-1001234567890';
  const maskedChat = maskChatId(chatId);
  assert.ok(!maskedChat.includes('123456'), 'Chat id middle must be masked');
  assert.equal(maskedChat, '***7890');

  // 3. Logger masking
  const originalLog = console.log;
  let loggedOutput = '';
  console.log = (...args) => {
    loggedOutput += args.join(' ') + '\n';
  };

  try {
    logTelegramEvent('TEST_EVENT', {
      token: '123456789:SECRET_ABC',
      chatId: '-1009988776655',
      safeField: 'hello'
    });
    assert.ok(!loggedOutput.includes('SECRET_ABC'), 'Logger must never log raw token');
    assert.ok(!loggedOutput.includes('998877'), 'Logger must never log raw chat id');
    assert.ok(loggedOutput.includes('safeField=hello'));
  } finally {
    console.log = originalLog;
  }
});

test('section24_10_queuedIncidentPersistsAndReloadsSafely', () => {
  const report = {
    reportId: 'VOX-QUEUE-TEST-01',
    appVersion: '32.56-vox.9.1',
    platform: 'Android TV',
    errorSignature: 'PLAYER|QUEUE_PERSIST',
    safeRecentEvents: [{ timestamp: Date.now(), level: 'ERROR', category: 'PLAYER', code: 'PLAYER_HTTP_ERROR' }]
  };
  const bundle = createSanitizedTelegramBundle(report, classifyReport(report), { status: 'NEW' });
  const serialized = JSON.stringify(bundle);
  const reloaded = JSON.parse(serialized);

  assert.equal(reloaded.reportId, 'VOX-QUEUE-TEST-01');
  assert.equal(reloaded.appVersion, '32.56-vox.9.1');
  assert.ok(reloaded.safeRecentEvents.length > 0);
  assert.equal(reloaded.Authorization, undefined);
  assert.equal(reloaded.token, undefined);
});
