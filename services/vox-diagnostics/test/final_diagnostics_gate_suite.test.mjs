import test from 'node:test';
import assert from 'node:assert/strict';

import {
  TelegramClient,
  dispatchTelegramNotification,
  resetTelegramState,
  maskChatId,
  maskBotToken,
  logTelegramEvent,
} from '../src/notifications/telegram.mjs';

import {
  ReportStorage,
  CANONICAL_STATUSES,
  PRIMARY_STATUSES,
} from '../src/storage.mjs';

import { classifyReport } from '../src/triage.mjs';
import { handleRequest } from '../src/diagnostics.mjs';
import { generateBackfillSql, generateRollbackSql } from '../scripts/backfill_vox91_statuses.mjs';

test.beforeEach(() => {
  resetTelegramState();
});

// Case 1: HTTP 200 / ok=true / valid message_id
test('Gate Case 1: HTTP 200 / ok=true / valid message_id acknowledges delivery', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    return new Response(JSON.stringify({ ok: true, result: { message_id: 777123 } }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  };

  try {
    const client = new TelegramClient('TEST_TOKEN_123');
    const res = await client.sendMessage({ chatId: '-100123', text: 'Hello gate' });
    assert.equal(res.ok, true);
    assert.equal(res.result.message_id, 777123);
    assert.equal(res.attempts, 1);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// Case 2: HTTP 200 / ok=false
test('Gate Case 2: HTTP 200 / ok=false is rejected and never marked delivered', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    return new Response(JSON.stringify({ ok: false, description: 'Bad Request: chat not found' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  };

  try {
    const client = new TelegramClient('TEST_TOKEN_123');
    const res = await client.sendMessage({ chatId: '-100999', text: 'Test chat' });
    assert.equal(res.ok, false);
    assert.equal(res.error, 'Bad Request: chat not found');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// Case 3: HTTP 429 Rate Limit
test('Gate Case 3: HTTP 429 respects retry_after and performs bounded retry', async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    if (calls === 1) {
      return new Response(JSON.stringify({ ok: false, parameters: { retry_after: 0.005 } }), {
        status: 429,
        headers: { 'Content-Type': 'application/json' },
      });
    }
    return new Response(JSON.stringify({ ok: true, result: { message_id: 9901 } }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  };

  try {
    const client = new TelegramClient('TEST_TOKEN_123');
    const res = await client.sendMessage({ chatId: '-100123', text: 'Rate limit test' });
    assert.equal(res.ok, true);
    assert.equal(res.result.message_id, 9901);
    assert.equal(calls, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// Case 4: HTTP 401/403 Permanent Error
test('Gate Case 4: HTTP 401/403 stops retry immediately without endless loop', async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return new Response(JSON.stringify({ ok: false, description: 'Forbidden: bot was blocked by the user' }), {
      status: 403,
      headers: { 'Content-Type': 'application/json' },
    });
  };

  try {
    const client = new TelegramClient('TEST_TOKEN_123');
    const res = await client.sendMessage({ chatId: '-100123', text: 'Forbidden test' });
    assert.equal(res.ok, false);
    assert.equal(res.isPermanent, true);
    assert.equal(calls, 1, 'Permanent error must NOT be retried');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// Case 5: DNS failure
test('Gate Case 5: DNS failure triggers bounded retry and captures network error', async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    throw new TypeError('fetch failed: getaddrinfo ENOTFOUND api.telegram.org');
  };

  try {
    const client = new TelegramClient('TEST_TOKEN_123');
    const res = await client.sendMessage({ chatId: '-100123', text: 'DNS fail test' });
    assert.equal(res.ok, false);
    assert.equal(res.isPermanent, false);
    assert.equal(calls, 3, 'Must perform bounded 3 retries');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// Case 6: TLS failure
test('Gate Case 6: TLS certificate failure is caught safely with bounded retry', async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    throw new Error('certificate has expired');
  };

  try {
    const client = new TelegramClient('TEST_TOKEN_123');
    const res = await client.sendMessage({ chatId: '-100123', text: 'TLS fail test' });
    assert.equal(res.ok, false);
    assert.equal(calls, 3);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// Case 7: Timeout
test('Gate Case 7: Timeout abort is classified and retried up to limit', async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    const err = new Error('The operation was aborted');
    err.name = 'AbortError';
    throw err;
  };

  try {
    const client = new TelegramClient('TEST_TOKEN_123');
    const res = await client.sendMessage({ chatId: '-100123', text: 'Timeout test' });
    assert.equal(res.ok, false);
    assert.equal(res.error, 'TIMEOUT');
    assert.equal(calls, 3);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// Case 8: HTTP 5xx Server Error
test('Gate Case 8: HTTP 5xx returns retryable error with bounded backoff', async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return new Response(JSON.stringify({ ok: false, description: 'Bad Gateway' }), {
      status: 502,
      headers: { 'Content-Type': 'application/json' },
    });
  };

  try {
    const client = new TelegramClient('TEST_TOKEN_123');
    const res = await client.sendMessage({ chatId: '-100123', text: '502 test' });
    assert.equal(res.ok, false);
    assert.equal(res.isPermanent, false);
    assert.equal(calls, 3);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// Case 9: Duplicate suppression
test('Gate Case 9: Duplicate incident within aggregation window is suppressed', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    return new Response(JSON.stringify({ ok: true, result: { message_id: 1101 } }), { status: 200 });
  };

  try {
    const report = {
      reportId: 'VOX-DEDUP-01',
      appVersion: '32.56-vox.9.1',
      errorSignature: 'PLAYER|BUFFER_STALL|TCL',
      safeRecentEvents: [{ timestamp: Date.now(), level: 'ERROR', category: 'PLAYER', code: 'PLAYER_REBUFFER' }]
    };
    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'TOKEN_XYZ',
      TELEGRAM_CHAT_ID: '-100123456789',
      TELEGRAM_AGGREGATION_WINDOW_MINUTES: '15'
    };

    const first = await dispatchTelegramNotification({
      report,
      classification: classifyReport(report),
      issue: { status: 'NEW' },
      env
    });
    assert.equal(first.deliveryState, 'DELIVERED');

    const second = await dispatchTelegramNotification({
      report,
      classification: classifyReport(report),
      issue: { status: 'NEW' },
      env
    });
    assert.equal(second.decision, 'SUPPRESSED_DUPLICATE');
    assert.equal(second.deliveryState, 'SUPPRESSED_DUPLICATE');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// Case 10: First incident always eligible
test('Gate Case 10: First incident delivery failure allows retry on next report before first successful delivery', async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    if (calls <= 3) {
      throw new Error('NETWORK_DROP');
    }
    return new Response(JSON.stringify({ ok: true, result: { message_id: 2202 } }), { status: 200 });
  };

  try {
    const report = {
      reportId: 'VOX-ELIG-01',
      appVersion: '32.56-vox.9.1',
      errorSignature: 'PLAYER|STALL_FAILOVER|TCL',
      safeRecentEvents: [{ timestamp: Date.now(), level: 'ERROR', category: 'PLAYER', code: 'PLAYER_REBUFFER' }]
    };
    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'TOKEN_XYZ',
      TELEGRAM_CHAT_ID: '-100123456789',
      TELEGRAM_AGGREGATION_WINDOW_MINUTES: '15'
    };

    const first = await dispatchTelegramNotification({
      report,
      classification: classifyReport(report),
      issue: { status: 'NEW' },
      env
    });
    assert.equal(first.decision, 'FAILED');

    // Next arrival should NOT be suppressed as duplicate because first never succeeded
    const second = await dispatchTelegramNotification({
      report,
      classification: classifyReport(report),
      issue: { status: 'NEW' },
      env
    });
    assert.equal(second.decision, 'SENT_NEW');
    assert.equal(second.deliveryState, 'DELIVERED');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// Case 11: Persistence after restart
test('Gate Case 11: Delivery state persists in storage and reloads correctly', async () => {
  const storage = new ReportStorage();
  const reportId = 'VOX-PERSIST-99';

  await storage.saveReport({
    reportId,
    appVersion: '32.56-vox.9.1',
    status: 'NEW',
    errorSignature: 'PLAYER|AUDIO_DROPOUT',
    safeRecentEvents: [{ code: 'AUDIO_TRACK_DESYNC', level: 'ERROR', timestamp: Date.now() }]
  });

  await storage.recordTelegramDelivery(reportId, {
    decision: 'SENT_NEW',
    messageId: 98765,
    notifiedAt: 1234567890,
  });

  const reloaded = await storage.getReportById(reportId);
  assert.ok(reloaded.payload.telegramDelivery);
  assert.equal(reloaded.payload.telegramDelivery.messageId, 98765);
  assert.equal(reloaded.payload.telegramDelivery.decision, 'SENT_NEW');
});

// Case 12: Delivered not resent
test('Gate Case 12: Delivered incident is not resent on subsequent calls within window', async () => {
  const originalFetch = globalThis.fetch;
  let netCalls = 0;
  globalThis.fetch = async () => {
    netCalls++;
    return new Response(JSON.stringify({ ok: true, result: { message_id: 3303 } }), { status: 200 });
  };

  try {
    const report = {
      reportId: 'VOX-NOT-RESENT-01',
      appVersion: '32.56-vox.9.1',
      errorSignature: 'UI|HUD_OVERLAY_COLLISION',
      safeRecentEvents: [{ timestamp: Date.now(), level: 'ERROR', category: 'UI', code: 'HUD_ERROR' }]
    };
    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'TOKEN_XYZ',
      TELEGRAM_CHAT_ID: '-100123456789',
      TELEGRAM_AGGREGATION_WINDOW_MINUTES: '15'
    };

    await dispatchTelegramNotification({
      report,
      classification: classifyReport(report),
      issue: { status: 'NEW' },
      env
    });
    assert.equal(netCalls, 1);

    // Call again with same report
    const res2 = await dispatchTelegramNotification({
      report,
      classification: classifyReport(report),
      issue: { status: 'NEW' },
      env
    });
    assert.equal(res2.decision, 'SUPPRESSED_DUPLICATE');
    assert.equal(netCalls, 1, 'Network call must NOT be repeated');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// Case 13: Missing config
test('Gate Case 13: Missing Telegram bot token or chat ID safely disables notifications', async () => {
  const envNoToken = {
    TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
    TELEGRAM_CHAT_ID: '-100123',
  };
  const envNoChat = {
    TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
    TELEGRAM_BOT_TOKEN: 'TOKEN_XYZ',
  };

  const report = {
    reportId: 'VOX-NOCFG-01',
    appVersion: '32.56-vox.9.1',
    safeRecentEvents: [{ timestamp: Date.now(), level: 'ERROR', code: 'ERR' }]
  };

  const res1 = await dispatchTelegramNotification({ report, classification: classifyReport(report), env: envNoToken });
  assert.equal(res1.decision, 'DISABLED');
  assert.equal(res1.reason, 'MISSING_ENV');

  const res2 = await dispatchTelegramNotification({ report, classification: classifyReport(report), env: envNoChat });
  assert.equal(res2.decision, 'DISABLED');
  assert.equal(res2.reason, 'MISSING_ENV');
});

// Case 14: Disabled feature
test('Gate Case 14: TELEGRAM_NOTIFICATIONS_ENABLED != true cleanly disables dispatch', async () => {
  const envDisabled = {
    TELEGRAM_NOTIFICATIONS_ENABLED: 'false',
    TELEGRAM_BOT_TOKEN: 'TOKEN_XYZ',
    TELEGRAM_CHAT_ID: '-100123',
  };

  const report = {
    reportId: 'VOX-DISAB-01',
    appVersion: '32.56-vox.9.1',
    safeRecentEvents: [{ timestamp: Date.now(), level: 'ERROR', code: 'ERR' }]
  };

  const res = await dispatchTelegramNotification({ report, classification: classifyReport(report), env: envDisabled });
  assert.equal(res.decision, 'DISABLED');
});

// Case 15: Secret masking
test('Gate Case 15: Secret masking ensures tokens and chat IDs are never printed', () => {
  const token = '123456789:ABCDefGhIjKlMnOpQrStUvWxYz';
  const chatId = '-1009876543210';

  const maskedT = maskBotToken(token);
  assert.ok(!maskedT.includes('ABCDefGhIjKlMnOpQrStUvWxYz'));
  assert.equal(maskedT, '12345***:ABC***');

  const maskedC = maskChatId(chatId);
  assert.ok(!maskedC.includes('987654'));
  assert.equal(maskedC, '***3210');
});

// Case 16: Concurrent queue processing
test('Gate Case 16: Concurrent dispatch processing resolves without race conditions', async () => {
  const originalFetch = globalThis.fetch;
  let counter = 0;
  globalThis.fetch = async () => {
    counter++;
    return new Response(JSON.stringify({ ok: true, result: { message_id: counter } }), { status: 200 });
  };

  try {
    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'TOKEN_XYZ',
      TELEGRAM_CHAT_ID: '-100123456789',
      TELEGRAM_AGGREGATION_WINDOW_MINUTES: '15'
    };

    const dispatches = Array.from({ length: 5 }, (_, i) => {
      const report = {
        reportId: `VOX-CONCURRENT-${i}`,
        appVersion: '32.56-vox.9.1',
        errorSignature: `CATEGORY_${i}|ERROR_${i}`,
        safeRecentEvents: [{ timestamp: Date.now(), level: 'ERROR', code: `ERR_${i}` }]
      };
      return dispatchTelegramNotification({
        report,
        classification: classifyReport(report),
        issue: { status: 'NEW' },
        env
      });
    });

    const results = await Promise.all(dispatches);
    assert.equal(results.length, 5);
    for (const r of results) {
      assert.equal(r.decision, 'SENT_NEW');
      assert.equal(r.deliveryState, 'DELIVERED');
    }
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// Case 17: Synthetic incident handling
test('Gate Case 17: Synthetic test incidents are suppressed from prod chat', async () => {
  const envProdOnly = {
    TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
    TELEGRAM_BOT_TOKEN: 'TOKEN_XYZ',
    TELEGRAM_CHAT_ID: '-100PROD_CHAT',
  };

  const synthReport = {
    reportId: 'VOX-TEST-SYNTH-01',
    reportPurpose: 'TEST',
    appVersion: '32.56-vox.9.1',
    errorSignature: 'PLAYER|TELEGRAM_FINAL_E2E_TEST|ANDROID_TV_API34',
    safeRecentEvents: [{ timestamp: Date.now(), level: 'INFO', code: 'TEST_EVENT' }]
  };

  const res = await dispatchTelegramNotification({
    report: synthReport,
    classification: classifyReport(synthReport),
    issue: { status: 'NEW' },
    env: envProdOnly
  });

  // Without test chat, test incident is suppressed from prod
  assert.equal(res.decision, 'SUPPRESSED_TEST');
});

// Case 18: Status independence from Telegram
test('Gate Case 18: Report ingestion succeeds HTTP 201 even when Telegram delivery throws', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    throw new Error('FATAL_TELEGRAM_CRASH');
  };

  try {
    const storage = new ReportStorage();
    const env = {
      TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
      TELEGRAM_BOT_TOKEN: 'TOKEN_XYZ',
      TELEGRAM_CHAT_ID: '-100123',
    };

    const req = new Request('http://localhost/v1/report', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        schema: 'vox-diagnostic-report-v1',
        reportId: 'VOX-INDEPENDENCE-01',
        appVersion: '32.56-vox.9.1',
        platform: 'Android TV',
        manufacturer: 'TCL',
        model: 'Smart TV Pro',
        errorCategory: 'PLAYER',
        safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'ERROR', timestamp: Date.now() }],
      }),
    });

    const res = await handleRequest(req, env, storage);
    assert.equal(res.status, 201, 'Ingestion must return 201 Created despite Telegram failure');

    const stored = await storage.getReportById('VOX-INDEPENDENCE-01');
    assert.ok(stored);
    assert.equal(stored.payload.telegramDelivery?.decision, 'FAILED');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// Case 19: Dashboard canonical issue grouping
test('Gate Case 19: Dashboard groups issues by canonical signature with accurate aggregate metrics', async () => {
  const storage = new ReportStorage();
  const signature = 'PLAYER|BUFFER_STALL|TCL_API34';

  await storage.saveReport({
    reportId: 'VOX-GRP-1',
    timestamp: 1000,
    appVersion: '32.56-vox.9.1',
    errorSignature: signature,
    errorCategory: 'PLAYER',
    severity: 'MEDIUM',
    status: 'NEW',
    safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'WARN', timestamp: 1000 }]
  });

  await storage.saveReport({
    reportId: 'VOX-GRP-2',
    timestamp: 2500,
    appVersion: '32.56-vox.9.1',
    errorSignature: signature,
    errorCategory: 'PLAYER',
    severity: 'CRITICAL',
    status: 'NEW',
    safeRecentEvents: [{ code: 'PLAYER_REBUFFER', level: 'ERROR', timestamp: 2500 }]
  });

  const grouped = await storage.getGroupedIssues();
  const group = grouped.find(g => g.error_signature === signature);
  assert.ok(group);
  assert.equal(group.count, 2);
  assert.equal(group.first_seen, 1000);
  assert.equal(group.last_seen, 2500);
  assert.equal(group.severity, 'CRITICAL');
});

// Case 20: Rollback migration
test('Gate Case 20: Rollback SQL accurately reverses backfill without deleting raw reports', () => {
  const rollbackSql = generateRollbackSql();
  assert.ok(rollbackSql.includes('UPDATE issues SET status = \'NEW\''));
  assert.ok(rollbackSql.includes('UPDATE reports SET status = \'NEW\''));
  assert.ok(!rollbackSql.includes('DROP TABLE reports'));
  assert.ok(!rollbackSql.includes('DELETE FROM reports'));
});
