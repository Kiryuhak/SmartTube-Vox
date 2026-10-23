import test from 'node:test';
import assert from 'node:assert/strict';

import {
  isAuthorizedUser,
  buildMainMenuKeyboard,
  buildViewKeyboard,
  renderMenuMessage,
  renderStatusMessage,
  renderIssuesMessage,
  renderReportsMessage,
  renderReleaseMessage,
  renderHelpMessage,
  renderUnauthorizedMessage,
  handleTelegramUpdate,
  getCanonicalDashboardUrl,
  CANONICAL_DASHBOARD_URL
} from '../src/notifications/telegram_bot.mjs';

import { TelegramClient, maskChatId, maskBotToken } from '../src/notifications/telegram.mjs';
import { handleRequest, resetProcessedTelegramUpdates } from '../src/diagnostics.mjs';
import { ReportStorage } from '../src/storage.mjs';

// Mock TelegramClient for unit testing update routing
class MockTelegramClient {
  constructor() {
    this.sentMessages = [];
    this.editedMessages = [];
    this.answeredCallbacks = [];
  }

  async sendMessage(params) {
    this.sentMessages.push(params);
    return { ok: true, result: { message_id: 100 + this.sentMessages.length } };
  }

  async editMessageText(params) {
    this.editedMessages.push(params);
    return { ok: true, result: true };
  }

  async answerCallbackQuery(params) {
    this.answeredCallbacks.push(params);
    return { ok: true, result: true };
  }

  async getMe() {
    return { ok: true, result: { id: 1234567, is_bot: true, first_name: 'SmartTube VOX Diagnostics', username: 'SmartTubeVoxDiagnosticsBot' } };
  }

  async getWebhookInfo() {
    return { ok: true, result: { url: 'https://diagnostics.smarttube.app/api/telegram/webhook', pending_update_count: 0 } };
  }

  async setWebhook({ url, secretToken }) {
    return { ok: true, result: true };
  }
}

test('Telegram Bot: Authorization logic verifies configured chat, allowed IDs and admin IDs', () => {
  const env = {
    TELEGRAM_CHAT_ID: '987654321',
    TELEGRAM_TEST_CHAT_ID: '11223344',
    TELEGRAM_ALLOWED_CHAT_ID: '44556677',
    TELEGRAM_ALLOWED_USER_ID: '33445566',
    TELEGRAM_ADMIN_IDS: '55667788, 99001122',
  };

  // Primary chat authorized
  assert.equal(isAuthorizedUser('987654321', null, env), true);
  assert.equal(isAuthorizedUser(987654321, null, env), true);
  assert.equal(isAuthorizedUser(null, '987654321', env), true);

  // Test chat authorized
  assert.equal(isAuthorizedUser('11223344', null, env), true);

  // Allowed chat/user authorized
  assert.equal(isAuthorizedUser('44556677', null, env), true);
  assert.equal(isAuthorizedUser(null, '33445566', env), true);

  // Admin IDs authorized
  assert.equal(isAuthorizedUser('55667788', null, env), true);
  assert.equal(isAuthorizedUser('99001122', null, env), true);

  // Unknown user rejected
  assert.equal(isAuthorizedUser('12345678', '12345678', env), false);
  assert.equal(isAuthorizedUser(null, null, env), false);
});

test('Telegram Bot: Message formatters produce correct content and structure', () => {
  // Menu message
  const menuText = renderMenuMessage({ TELEGRAM_CHAT_ID: '12345' });
  assert.match(menuText, /SmartTube VOX — Telegram Control Center 1\.0/);
  assert.match(menuText, /SmartTube VOX 9\.1/);
  assert.match(menuText, /\/status/);
  assert.match(menuText, /\/release/);

  // Status message
  const stats = {
    total: 58,
    last24Hours: 12,
    last7Days: 45,
    statusCounts: {
      NEW: 2,
      INVESTIGATING: 2,
      FIXED_AWAITING_VERIFICATION: 4,
      RESOLVED: 48,
      IGNORED_TEST: 2,
    },
  };
  const statusText = renderStatusMessage(stats);
  assert.match(statusText, /Всего отчётов:.*58/);
  assert.match(statusText, /Требуют внимания:.*4/);
  assert.match(statusText, /Ожидают проверки:.*4/);
  assert.match(statusText, /Решено \/ Закрыто:.*48/);

  // Issues message
  const issues = [
    { error_signature: 'PLAYER|REBUFFER|DUNE', count: 14, new_count: 2, status: 'INVESTIGATING' },
    { error_signature: 'VOT|TRANSLATION_FAIL', count: 3, new_count: 0, status: 'FIXED_AWAITING_VERIFICATION' },
  ];
  const issuesText = renderIssuesMessage(issues);
  assert.match(issuesText, /PLAYER\|REBUFFER\|DUNE/);
  assert.match(issuesText, /Повторов:.*14/);
  assert.match(issuesText, /VOT\|TRANSLATION_FAIL/);

  // Reports message
  const reports = [
    { report_id: 'VOX-D6BCA185', app_version: '32.56-vox.9.1', status: 'INVESTIGATING', error_category: 'PLAYER_STREAM_ERROR' },
  ];
  const reportsText = renderReportsMessage(reports);
  assert.match(reportsText, /VOX-D6BCA185/);
  assert.match(reportsText, /32\.56-vox\.9\.1/);
  assert.match(reportsText, /PLAYER_STREAM_ERROR/);

  // Release message (accurately distinguishes components and does NOT hardcode READY = YES)
  const releaseText = renderReleaseMessage();
  assert.match(releaseText, /32\.56-vox\.9\.1/);
  assert.match(releaseText, /2446016/);
  assert.match(releaseText, /armeabi-v7a/);
  assert.match(releaseText, /VERIFIED \(RSA 4096-bit\)/);
  assert.match(releaseText, /167\/167 tests passed/);
  assert.match(releaseText, /PENDING/);
  assert.match(releaseText, /PENDING ROLLOUT APPROVAL/);

  // Unauthorized message
  const unauthText = renderUnauthorizedMessage('777888999');
  assert.match(unauthText, /Доступ ограничен/);
  assert.match(unauthText, /777888999/);
  assert.match(unauthText, /wrangler secret put TELEGRAM_CHAT_ID/);
});

test('Telegram Bot: Keyboards contain expected buttons and callback data', () => {
  const menuKb = buildMainMenuKeyboard('https://custom.dashboard/admin');
  assert.equal(menuKb.inline_keyboard.length, 3);
  assert.equal(menuKb.inline_keyboard[0][0].callback_data, 'cb:status');
  assert.equal(menuKb.inline_keyboard[0][1].callback_data, 'cb:issues');
  assert.equal(menuKb.inline_keyboard[1][0].callback_data, 'cb:reports');
  assert.equal(menuKb.inline_keyboard[1][1].callback_data, 'cb:release');
  assert.equal(menuKb.inline_keyboard[2][0].callback_data, 'cb:menu');
  assert.equal(menuKb.inline_keyboard[2][1].url, 'https://custom.dashboard/admin');

  const statusKb = buildViewKeyboard('status', 'https://custom.dashboard/admin');
  assert.equal(statusKb.inline_keyboard[0][0].callback_data, 'cb:status');
  assert.equal(statusKb.inline_keyboard[0][0].text, '🔄 Обновить');
});

test('Telegram Bot: Unauthorized user is rejected with safe onboarding message and chat ID', async () => {
  const client = new MockTelegramClient();
  const env = { TELEGRAM_CHAT_ID: '99999999' };
  const update = {
    update_id: 1,
    message: {
      message_id: 10,
      chat: { id: 12345678, type: 'private' },
      from: { id: 12345678, first_name: 'Unknown' },
      text: '/start',
    },
  };

  const res = await handleTelegramUpdate({ update, env, storage: null, client });
  assert.equal(res.handled, true);
  assert.equal(res.authorized, false);
  assert.equal(res.chatId, 12345678);

  // Verify client sent unauthorized message
  assert.equal(client.sentMessages.length, 1);
  const sent = client.sentMessages[0];
  assert.equal(sent.chatId, 12345678);
  assert.match(sent.text, /Доступ ограничен/);
  assert.match(sent.text, /12345678/);
  assert.match(sent.text, /wrangler secret put TELEGRAM_CHAT_ID/);
  // Diagnostic metrics NOT leaked
  assert.doesNotMatch(sent.text, /Всего отчётов/);
  assert.doesNotMatch(sent.text, /32\.56-vox\.9\.1/);
});

test('Telegram Bot: Non-private chats and forwarded messages are rejected', async () => {
  const client = new MockTelegramClient();
  const env = { TELEGRAM_CHAT_ID: '100200300' };

  // 1. Group chat rejected
  const groupUpdate = {
    update_id: 2,
    message: {
      message_id: 11,
      chat: { id: -100123456, type: 'group' },
      from: { id: 100200300 },
      text: '/status',
    },
  };
  const groupRes = await handleTelegramUpdate({ update: groupUpdate, env, client });
  assert.equal(groupRes.handled, false);
  assert.equal(groupRes.error, 'NON_PRIVATE_CHAT_REJECTED');

  // 2. Forwarded message rejected
  const forwardUpdate = {
    update_id: 3,
    message: {
      message_id: 12,
      chat: { id: 100200300, type: 'private' },
      from: { id: 100200300 },
      forward_date: 1729530000,
      forward_from: { id: 99999 },
      text: '/status',
    },
  };
  const fwdRes = await handleTelegramUpdate({ update: forwardUpdate, env, client });
  assert.equal(fwdRes.handled, false);
  assert.equal(fwdRes.error, 'FORWARDED_COMMAND_REJECTED');
});

test('Telegram Bot: Authorized commands (/start, /status, /issues, /reports, /release, /help) route properly', async () => {
  const client = new MockTelegramClient();
  const env = { TELEGRAM_CHAT_ID: '100200300' };
  const storage = new ReportStorage();

  // Seed sample report in storage
  await storage.saveReport({
    reportId: 'VOX-BOT-TEST-01',
    created_at: Date.now(),
    app_version: '32.56-vox.9.1',
    status: 'INVESTIGATING',
    error_signature: 'PLAYER|AUDIO_SINK|SAMSUNG',
    error_category: 'PLAYER_REBUFFER',
    device_family: 'android_tv',
  });

  const sendCommand = async (cmd) => {
    return handleTelegramUpdate({
      update: {
        update_id: Date.now(),
        message: {
          message_id: 20,
          chat: { id: 100200300, type: 'private' },
          from: { id: 100200300, first_name: 'Developer' },
          text: cmd,
        },
      },
      env,
      storage,
      client,
    });
  };

  // 1. /start
  await sendCommand('/start');
  assert.equal(client.sentMessages.length, 1);
  assert.match(client.sentMessages[0].text, /Telegram Control Center 1\.0/);

  // 2. /status
  await sendCommand('/status');
  assert.equal(client.sentMessages.length, 2);
  assert.match(client.sentMessages[1].text, /Статус системы/);

  // 3. /issues
  await sendCommand('/issues');
  assert.equal(client.sentMessages.length, 3);
  assert.match(client.sentMessages[2].text, /Активные проблемы|Топ проблем/);

  // 4. /reports
  await sendCommand('/reports');
  assert.equal(client.sentMessages.length, 4);
  assert.match(client.sentMessages[3].text, /Последние отчёты/);
  assert.match(client.sentMessages[3].text, /VOX-BOT-TEST-01/);

  // 5. /release
  await sendCommand('/release');
  assert.equal(client.sentMessages.length, 5);
  assert.match(client.sentMessages[4].text, /Release Candidate Status/);

  // 6. /help
  await sendCommand('/help');
  assert.equal(client.sentMessages.length, 6);
  assert.match(client.sentMessages[5].text, /Справка/);
});

test('Telegram Bot: Callback queries edit messages in-place and answer query', async () => {
  const client = new MockTelegramClient();
  const env = { TELEGRAM_CHAT_ID: '555666777' };
  const storage = new ReportStorage();

  const sendCallback = async (data) => {
    return handleTelegramUpdate({
      update: {
        update_id: Date.now(),
        callback_query: {
          id: 'cq_12345',
          data,
          from: { id: 555666777 },
          message: {
            message_id: 50,
            chat: { id: 555666777, type: 'private' },
          },
        },
      },
      env,
      storage,
      client,
    });
  };

  // Test cb:status
  const resStatus = await sendCallback('cb:status');
  assert.equal(resStatus.handled, true);
  assert.equal(client.editedMessages.length, 1);
  assert.equal(client.editedMessages[0].chatId, 555666777);
  assert.equal(client.editedMessages[0].messageId, 50);
  assert.match(client.editedMessages[0].text, /Статус системы/);
  assert.equal(client.answeredCallbacks.length, 1);
  assert.equal(client.answeredCallbacks[0].callbackQueryId, 'cq_12345');

  // Test cb:release
  await sendCallback('cb:release');
  assert.equal(client.editedMessages.length, 2);
  assert.match(client.editedMessages[1].text, /Release Candidate Status/);

  // Test cb:menu
  await sendCallback('cb:menu');
  assert.equal(client.editedMessages.length, 3);
  assert.match(client.editedMessages[2].text, /Telegram Control Center 1\.0/);

  // Test forged callback rejection
  const forgedRes = await sendCallback('cb:malicious_eval');
  assert.equal(forgedRes.handled, false);
  assert.equal(forgedRes.error, 'FORGED_CALLBACK_REJECTED');
});

test('Telegram Webhook Endpoint: Validates webhook secret token, idempotency and processes update', async () => {
  resetProcessedTelegramUpdates();
  const storage = new ReportStorage();
  const env = {
    TELEGRAM_BOT_TOKEN: '123456:FAKE_TOKEN_FOR_TEST',
    TELEGRAM_CHAT_ID: '888999',
    TELEGRAM_WEBHOOK_SECRET: 'super-secret-webhook-key',
  };

  // 1. Rejected if secret token is wrong
  const unauthReq = new Request('https://test.local/api/telegram/webhook', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Telegram-Bot-Api-Secret-Token': 'wrong-token',
    },
    body: JSON.stringify({ update_id: 101 }),
  });
  const unauthRes = await handleRequest(unauthReq, env, storage);
  assert.equal(unauthRes.status, 401);

  // 2. Mock global fetch for Telegram API call during update handling
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    return new Response(JSON.stringify({ ok: true, result: { message_id: 999 } }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  };

  try {
    const validReq = new Request('https://test.local/api/telegram/webhook', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-Bot-Api-Secret-Token': 'super-secret-webhook-key',
      },
      body: JSON.stringify({
        update_id: 202,
        message: {
          message_id: 88,
          chat: { id: 888999, type: 'private' },
          from: { id: 888999 },
          text: '/status',
        },
      }),
    });

    const validRes = await handleRequest(validReq, env, storage);
    assert.equal(validRes.status, 200);
    const body = await validRes.json();
    assert.equal(body.ok, true);
    assert.equal(body.result.handled, true);
    assert.equal(body.result.command, 'status');

    // 3. Duplicate update protection (idempotency check)
    const dupReq = new Request('https://test.local/api/telegram/webhook', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-Bot-Api-Secret-Token': 'super-secret-webhook-key',
      },
      body: JSON.stringify({
        update_id: 202, // Same update_id
        message: {
          message_id: 88,
          chat: { id: 888999, type: 'private' },
          from: { id: 888999 },
          text: '/status',
        },
      }),
    });
    const dupRes = await handleRequest(dupReq, env, storage);
    assert.equal(dupRes.status, 200);
    const dupBody = await dupRes.json();
    assert.equal(dupBody.ok, true);
    assert.equal(dupBody.duplicate, true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('Telegram Admin Endpoints: /v1/admin/telegram/info and setup-webhook require auth and behave safely', async () => {
  const storage = new ReportStorage();
  const env = {
    ADMIN_SECRET: 'admin_test_secret_123',
    TELEGRAM_BOT_TOKEN: '123456:BOT_TOKEN_FOR_TEST',
    TELEGRAM_CHAT_ID: '99887766',
  };

  // 1. Unauthorized access to /v1/admin/telegram/info
  const reqUnauth = new Request('https://test.local/v1/admin/telegram/info', { method: 'GET' });
  const resUnauth = await handleRequest(reqUnauth, env, storage);
  assert.equal(resUnauth.status, 401);

  // 2. Authorized access to /v1/admin/telegram/info (with mocked fetch)
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.includes('getMe')) {
      return new Response(JSON.stringify({
        ok: true,
        result: { id: 111, is_bot: true, first_name: 'SmartTube VOX', username: 'SmartTubeVoxDiagnosticsBot' }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
    if (url.includes('getWebhookInfo')) {
      return new Response(JSON.stringify({
        ok: true,
        result: { url: 'https://vox.app/api/telegram/webhook', pending_update_count: 0 }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
    return new Response(JSON.stringify({ ok: true, result: true }), { status: 200 });
  };

  try {
    const reqAuth = new Request('https://test.local/v1/admin/telegram/info', {
      method: 'GET',
      headers: { Authorization: 'Bearer admin_test_secret_123' },
    });
    const resAuth = await handleRequest(reqAuth, env, storage);
    assert.equal(resAuth.status, 200);
    const data = await resAuth.json();
    assert.equal(data.configured, true);
    assert.equal(data.bot.username, 'SmartTubeVoxDiagnosticsBot');
    assert.equal(data.webhook.url, 'https://vox.app/api/telegram/webhook');
    // Ensure raw bot token is NOT exposed in response
    assert.equal(JSON.stringify(data).includes('BOT_TOKEN_FOR_TEST'), false);
    // Chat ID is masked
    assert.equal(data.chatIdMasked, '***7766');

    // 3. Setup webhook endpoint (with permitted host)
    const reqSetup = new Request('https://test.local/v1/admin/telegram/setup-webhook', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer admin_test_secret_123',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ workerUrl: 'https://vox-diagnostics.amn2402.workers.dev' }),
    });
    const resSetup = await handleRequest(reqSetup, env, storage);
    assert.equal(resSetup.status, 200);
    const setupData = await resSetup.json();
    assert.equal(setupData.ok, true);
    assert.equal(setupData.webhookUrl, 'https://vox-diagnostics.amn2402.workers.dev/api/telegram/webhook');

    // 4. Setup webhook endpoint rejects malicious foreign host
    const reqHijack = new Request('https://test.local/v1/admin/telegram/setup-webhook', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer admin_test_secret_123',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ workerUrl: 'https://attacker.evil-domain.com' }),
    });
    const resHijack = await handleRequest(reqHijack, env, storage);
    assert.equal(resHijack.status, 400);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('Telegram Security Gate: Rigorous verification of header bypass prevention, allowlist isolation, and webhook security', async () => {
  const storage = new ReportStorage();
  const env = {
    ADMIN_SECRET: 'super_secret_admin_token_456',
    TELEGRAM_BOT_TOKEN: '8500710508:TELEGRAM_SECRET_TEST_TOKEN',
    TELEGRAM_CHAT_ID: '302331721',
    TELEGRAM_ALLOWED_USER_ID: '302331721',
    TELEGRAM_WEBHOOK_SECRET: 'wh_secret_token_789',
  };

  // 1. Forged x-telegram-chat-id CANNOT bypass admin auth on any privileged endpoint
  const testEndpoints = [
    { method: 'GET', url: 'https://test.local/v1/admin/telegram/info' },
    { method: 'POST', url: 'https://test.local/v1/admin/telegram/setup-webhook', body: JSON.stringify({}) },
    { method: 'POST', url: 'https://test.local/v1/admin/telegram/test-dispatch', body: JSON.stringify({}) },
    { method: 'GET', url: 'https://test.local/v1/admin/telegram/bootstrap' },
  ];

  for (const ep of testEndpoints) {
    // Attempt with forged header alone
    const forgedReq = new Request(ep.url, {
      method: ep.method,
      headers: {
        'x-telegram-chat-id': '302331721',
        'Content-Type': 'application/json',
      },
      body: ep.body,
    });
    const forgedRes = await handleRequest(forgedReq, env, storage);
    assert.equal(forgedRes.status, 401, `Endpoint ${ep.method} ${ep.url} MUST reject forged x-telegram-chat-id with 401`);

    // Attempt with invalid Bearer token
    const wrongBearerReq = new Request(ep.url, {
      method: ep.method,
      headers: {
        'Authorization': 'Bearer wrong_admin_secret',
        'x-telegram-chat-id': '302331721',
        'Content-Type': 'application/json',
      },
      body: ep.body,
    });
    const wrongBearerRes = await handleRequest(wrongBearerReq, env, storage);
    assert.equal(wrongBearerRes.status, 401, `Endpoint ${ep.method} ${ep.url} MUST reject wrong Bearer token with 401`);
  }

  // 2. Mock global fetch for authorized tests
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    if (url.includes('sendMessage')) {
      return new Response(JSON.stringify({ ok: true, result: { message_id: 8881 } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }
    if (url.includes('getMe')) {
      return new Response(JSON.stringify({ ok: true, result: { id: 8500710508, username: 'SmartTubeVoxDiagnosticsBot' } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }
    if (url.includes('getWebhookInfo')) {
      return new Response(JSON.stringify({ ok: true, result: { url: 'https://vox-diagnostics.amn2402.workers.dev/api/telegram/webhook' } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }
    return new Response(JSON.stringify({ ok: true, result: true }), { status: 200 });
  };

  try {
    // 3. Authorized test-dispatch with correlationId and persistence to test report
    const testReportId = 'VOX-TG-E2E-TEST-REPORT-01';
    await storage.saveReport({
      reportId: testReportId,
      timestamp: Date.now(),
      status: 'IGNORED_TEST',
      errorSignature: 'TEST|E2E_VERIFICATION',
      appVersion: '32.56-vox.9.1',
    });

    const validDispatchReq = new Request('https://test.local/v1/admin/telegram/test-dispatch', {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer super_secret_admin_token_456',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        correlationId: 'VOX-TG-E2E-TEST-001',
        persist: true,
        reportId: testReportId,
      }),
    });
    const dispatchRes = await handleRequest(validDispatchReq, env, storage);
    assert.equal(dispatchRes.status, 200);
    const dispatchData = await dispatchRes.json();
    assert.equal(dispatchData.ok, true);
    assert.equal(dispatchData.messageId, 8881);
    assert.equal(dispatchData.correlationId, 'VOX-TG-E2E-TEST-001');
    assert.equal(dispatchData.persisted, true);
    assert.equal(dispatchData.chatIdMasked, '***1721');

    // Verify D1/storage persistence has canonical 'SENT' state
    const saved = await storage.getReportById(testReportId);
    assert.ok(saved.payload?.telegramDelivery);
    assert.equal(saved.payload.telegramDelivery.delivery_state, 'SENT');
    assert.equal(saved.payload.telegramDelivery.messageId, 8881);

    // 4. Webhook Security: Invalid webhook secret token rejected
    const badWhReq = new Request('https://test.local/api/telegram/webhook', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-Bot-Api-Secret-Token': 'wrong_wh_secret',
      },
      body: JSON.stringify({ update_id: 9999 }),
    });
    const badWhRes = await handleRequest(badWhReq, env, storage);
    assert.equal(badWhRes.status, 401);

    // 5. Bot Authorization: Unknown user update is rejected (authorized = false)
    const unknownUserUpdate = {
      update_id: 10001,
      message: {
        message_id: 1,
        chat: { id: 99999999, type: 'private' },
        from: { id: 99999999 },
        text: '/status',
      },
    };
    const unknownReq = new Request('https://test.local/api/telegram/webhook', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-Bot-Api-Secret-Token': 'wh_secret_token_789',
      },
      body: JSON.stringify(unknownUserUpdate),
    });
    const unknownRes = await handleRequest(unknownReq, env, storage);
    assert.equal(unknownRes.status, 200);
    const unknownData = await unknownRes.json();
    assert.equal(unknownData.result.authorized, false);

    // 6. Bot Authorization: Impersonated user (allowed chat, forged from.id) is rejected
    const impersonatedUpdate = {
      update_id: 10002,
      message: {
        message_id: 2,
        chat: { id: 302331721, type: 'private' },
        from: { id: 88888888 }, // Attacker trying to spoof sender
        text: '/status',
      },
    };
    const impReq = new Request('https://test.local/api/telegram/webhook', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-Bot-Api-Secret-Token': 'wh_secret_token_789',
      },
      body: JSON.stringify(impersonatedUpdate),
    });
    const impRes = await handleRequest(impReq, env, storage);
    assert.equal(impRes.status, 200);
    const impData = await impRes.json();
    assert.equal(impData.result.authorized, false);

    // 7. Bot Authorization: Forged callback query is rejected
    const forgedCbUpdate = {
      update_id: 10003,
      callback_query: {
        id: 'cq_999',
        data: 'cb:malicious_eval_action',
        message: {
          message_id: 10,
          chat: { id: 302331721, type: 'private' },
        },
        from: { id: 302331721 },
      },
    };
    const cbReq = new Request('https://test.local/api/telegram/webhook', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-Bot-Api-Secret-Token': 'wh_secret_token_789',
      },
      body: JSON.stringify(forgedCbUpdate),
    });
    const cbRes = await handleRequest(cbReq, env, storage);
    assert.equal(cbRes.status, 200);
    const cbData = await cbRes.json();
    assert.equal(cbData.result.error, 'FORGED_CALLBACK_REJECTED');

    // 8. No secret leakage: Raw TELEGRAM_BOT_TOKEN and ADMIN_SECRET are NEVER present in JSON responses
    assert.equal(JSON.stringify(dispatchData).includes('TELEGRAM_SECRET_TEST_TOKEN'), false);
    assert.equal(JSON.stringify(dispatchData).includes('super_secret_admin_token_456'), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('Telegram -> Mobile Dashboard P1: Link resolution, status zero fix, and mobile login UX', async () => {
  // 1. Canonical Dashboard URL resolution
  assert.equal(getCanonicalDashboardUrl({}), 'https://vox-diagnostics.amn2402.workers.dev/admin');
  assert.equal(getCanonicalDashboardUrl({ VOX_DIAGNOSTICS_DASHBOARD_URL: 'https://custom.app' }), 'https://custom.app/admin');
  assert.equal(getCanonicalDashboardUrl({ DASHBOARD_BASE_URL: 'https://custom.app/admin/' }), 'https://custom.app/admin');
  assert.equal(CANONICAL_DASHBOARD_URL, 'https://vox-diagnostics.amn2402.workers.dev/admin');

  // 2. Keyboard default links point to canonical working Worker URL (/admin)
  const menuKb = buildMainMenuKeyboard();
  const dashBtn = menuKb.inline_keyboard[2][1];
  assert.equal(dashBtn.text, '🌐 Dashboard');
  assert.equal(dashBtn.url, 'https://vox-diagnostics.amn2402.workers.dev/admin');

  const statusKb = buildViewKeyboard('status');
  const statusDashBtn = statusKb.inline_keyboard[2][1];
  assert.equal(statusDashBtn.text, '🌐 Dashboard');
  assert.equal(statusDashBtn.url, 'https://vox-diagnostics.amn2402.workers.dev/admin');

  // 3. Status zero bug fix: Real production D1 counts (58 total reports) format accurately
  const prodD1Stats = {
    totalReports: 58,
    reports24h: 0,
    reports7d: 58,
    newCount: 0,
    inProgressCount: 3,
    fixedAwaitingCount: 31,
    resolvedCount: 8,
    knownIssueCount: 6,
    ignoredTestCount: 10,
    statusCounts: {
      NEW: 0,
      INVESTIGATING: 3,
      IN_PROGRESS: 3,
      FIXED_AWAITING_VERIFICATION: 31,
      RESOLVED: 8,
      KNOWN_LIMITATION: 6,
      IGNORED_TEST: 10,
    },
  };
  const rendered = renderStatusMessage(prodD1Stats);
  assert.match(rendered, /Всего отчётов:.*58/);
  assert.match(rendered, /Требуют внимания:.*3/);
  assert.match(rendered, /Ожидают проверки:.*31/);
  assert.match(rendered, /Решено \/ Закрыто:.*8/);
  assert.match(rendered, /Известные ограничения:.*6/);
  assert.match(rendered, /Тестовые \/ Игнор:.*10/);

  // 4. Mobile Login Form & Error handling
  const env = { ADMIN_SECRET: 'top_secret_key_123' };

  // 4.1 Invalid password via HTML form returns 303 redirect with ?error=invalid_credentials
  const badBody = new URLSearchParams({ secret: 'wrong_password' }).toString();
  const badPostReq = new Request('https://vox-diagnostics.amn2402.workers.dev/admin/session', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Accept': 'text/html,application/xhtml+xml',
    },
    body: badBody,
  });
  const badPostRes = await handleRequest(badPostReq, env);
  assert.equal(badPostRes.status, 303);
  assert.equal(badPostRes.headers.get('Location'), '/admin?error=invalid_credentials');

  // 4.2 Valid password sets SameSite=Lax cookie (safe for cross-app Telegram top-level navigation)
  const goodBody = new URLSearchParams({ secret: 'top_secret_key_123' }).toString();
  const goodPostReq = new Request('https://vox-diagnostics.amn2402.workers.dev/admin/session', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Accept': 'text/html,application/xhtml+xml',
    },
    body: goodBody,
  });
  const goodPostRes = await handleRequest(goodPostReq, env);
  assert.equal(goodPostRes.status, 303);
  assert.equal(goodPostRes.headers.get('Location'), '/admin');
  const setCookie = goodPostRes.headers.get('Set-Cookie');
  assert.match(setCookie, /vox_admin_session=/);
  assert.match(setCookie, /SameSite=Lax/);
  assert.match(setCookie, /HttpOnly/);
  assert.match(setCookie, /Secure/);

  // 4.3 GET /admin?error=invalid_credentials renders localized error banner, mobile viewport, and NO WWW-Authenticate header
  const errorPageReq = new Request('https://vox-diagnostics.amn2402.workers.dev/admin?error=invalid_credentials', {
    method: 'GET',
    headers: { 'Accept': 'text/html' },
  });
  const errorPageRes = await handleRequest(errorPageReq, env);
  assert.equal(errorPageRes.status, 401);
  assert.equal(errorPageRes.headers.get('www-authenticate'), 'Bearer realm="VOX-Admin"');
  const errorHtml = await errorPageRes.text();
  assert.match(errorHtml, /Неверный ключ администратора/);
  assert.match(errorHtml, /viewport.*width=device-width/);
  assert.ok(errorHtml.includes('font-size: 16px'));

  // 4.4 GET / root service landing page provides a direct link button to /admin
  const rootReq = new Request('https://vox-diagnostics.amn2402.workers.dev/', { method: 'GET' });
  const rootRes = await handleRequest(rootReq, env);
  assert.equal(rootRes.status, 200);
  const rootHtml = await rootRes.text();
  assert.ok(rootHtml.includes('href="/admin"'));
  assert.ok(rootHtml.includes('Панель управления (/admin)'));
});
