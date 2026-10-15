#!/usr/bin/env node

/**
 * SmartTube VOX Diagnostics — Telegram Notification Test Script
 * 
 * Отправляет тестовое синтетическое сообщение и прикрепляет файл VOX-TELEGRAM-TEST.json
 * для проверки работоспособности токена и чата.
 * 
 * Использование:
 * node scripts/test_telegram_notification.mjs
 */

import { TelegramClient } from '../src/notifications/telegram.mjs';
import { formatTestMessage, buildDashboardKeyboard } from '../src/notifications/telegram_formatter.mjs';

async function main() {
  const token = process.env.TELEGRAM_BOT_TOKEN;
  const chatId = process.env.TELEGRAM_TEST_CHAT_ID || process.env.TELEGRAM_CHAT_ID;
  const threadId = process.env.TELEGRAM_MESSAGE_THREAD_ID;
  const baseUrl = process.env.DASHBOARD_BASE_URL || 'https://diagnostics.smarttube.app';

  if (!token || !chatId) {
    console.log('TELEGRAM_TEST_RESULT: SKIPPED_NO_CONFIG');
    console.log('Для тестирования отправки задайте TELEGRAM_BOT_TOKEN и TELEGRAM_CHAT_ID (или TELEGRAM_TEST_CHAT_ID)');
    process.exit(0);
  }

  console.log(`Отправка тестового уведомления в чат ${chatId}...`);
  const client = new TelegramClient(token);

  const syntheticBundle = {
    $schema: 'https://smarttube.app/schemas/vox-diagnostic-telegram-v1.json',
    schemaVersion: '1.0.0',
    reportId: 'VOX-TELEGRAM-TEST',
    receivedAt: Date.now(),
    appVersion: '32.56-vox.9.1',
    appVersionCode: 2446016,
    platform: 'Android TV',
    incident: {
      status: 'NEW',
      severity: 'INFO',
      category: 'TEST',
      errorSignature: 'SYSTEM|TELEGRAM_TEST|ANDROID_TV',
      reopened: false
    },
    classification: {
      errorCategory: 'SYSTEM',
      subsystem: 'NOTIFICATIONS',
      stage: 'TEST',
      severity: 'INFO',
      activeEventCount: 0,
      historicalEventCount: 0
    },
    device: {
      family: 'Synthetic Test Runner',
      manufacturer: 'SmartTube VOX',
      model: 'Diagnostics CI',
      tier: 'Test',
      sdkInt: 34,
      osVersion: '14'
    },
    technicalSummary: 'Синтетический тестовый запуск проверки канала Telegram',
    safeRecentEvents: [
      {
        timestamp: Date.now(),
        category: 'SYSTEM',
        code: 'TELEGRAM_TEST_DISPATCHED',
        level: 'INFO',
        message: 'Проверка отправки сообщений в Telegram Bot API'
      }
    ]
  };

  const jsonBuffer = Buffer.from(JSON.stringify(syntheticBundle, null, 2), 'utf8');
  const filename = 'VOX-TELEGRAM-TEST.json';
  const caption = formatTestMessage('Ручной запуск тестового скрипта test_telegram_notification.mjs');
  const keyboard = buildDashboardKeyboard(baseUrl, 'report', 'VOX-TELEGRAM-TEST');

  const res = await client.sendDocument({
    chatId,
    documentBuffer: jsonBuffer,
    filename,
    caption,
    parseMode: 'HTML',
    replyMarkup: keyboard,
    messageThreadId: threadId,
  });

  if (res.ok) {
    console.log('TELEGRAM_TEST_RESULT: SUCCESS');
    console.log(`Сообщение успешно отправлено, message_id: ${res.result?.message_id}`);
  } else {
    console.error('TELEGRAM_TEST_RESULT: FAILED');
    console.error(`Ошибка отправки: ${res.error} (status: ${res.status || 'N/A'})`);
    process.exit(1);
  }
}

main().catch(err => {
  console.error('Непредвиденная ошибка:', err);
  process.exit(1);
});
