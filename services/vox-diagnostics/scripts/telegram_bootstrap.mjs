#!/usr/bin/env node
/**
 * SmartTube VOX Diagnostics — Safe Developer Chat Bootstrap Tool
 * 
 * Назначение:
 * Безопасное определение Telegram Chat ID разработчика, отправившего /start боту @SmartTubeVoxDiagnosticsBot.
 * 
 * Принципы безопасности:
 * 1. НИКОГДА не выводит бот-токен, приватные ключи или секреты.
 * 2. НИКОГДА не печатает полный JSON-ответ Telegram или текст сообщений.
 * 3. Извлекает ТОЛЬКО: chat.id, chat.type, user.id, date.
 * 4. Не сбрасывает и не заменяет активный webhook без явного указания.
 * 5. Формирует готовую команду npx wrangler secret put TELEGRAM_CHAT_ID.
 */

import readline from 'readline';
import { TelegramClient, maskBotToken } from '../src/notifications/telegram.mjs';

function askQuestion(query) {
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });
  return new Promise(resolve => rl.question(query, ans => {
    rl.close();
    resolve(ans.trim());
  }));
}

async function main() {
  console.log('====================================================');
  console.log('SmartTube VOX 9.1 — Safe Developer Chat Bootstrap');
  console.log('====================================================\n');

  let token = process.env.TELEGRAM_BOT_TOKEN;
  const adminSecret = process.env.ADMIN_SECRET;
  const workerUrl = process.env.WORKER_URL || 'https://vox-diagnostics.amn2402.workers.dev';

  // Режим 1: Если задан ADMIN_SECRET, запрашиваем через защищённый Admin API Worker
  if (adminSecret) {
    console.log(`[1/3] Запрос через Cloudflare Worker Admin API: ${workerUrl}...`);
    try {
      const res = await fetch(`${workerUrl}/v1/admin/telegram/bootstrap`, {
        headers: { Authorization: `Bearer ${adminSecret}` },
      });
      if (res.ok) {
        const data = await res.json();
        console.log('[2/3] Ответ Worker Admin API получен.');
        if (data.hasWebhook) {
          console.log(`ℹ️ Webhook активен: ${data.webhookUrl}`);
        }
        if (data.detectedUpdates && data.detectedUpdates.length > 0) {
          console.log('\n Найдены входящие обновления от разработчика:');
          data.detectedUpdates.forEach((u, i) => {
            console.log(`\n--- Обновление #${i + 1} ---`);
            console.log(`  Chat ID:   ${u.chatId}`);
            console.log(`  Chat Type: ${u.chatType || 'private'}`);
            console.log(`  User ID:   ${u.userId}`);
            console.log(`  Date:      ${new Date(u.date * 1000).toISOString()}`);
          });

          const primaryChatId = data.detectedUpdates[0].chatId;
          console.log('\n====================================================');
          console.log('РЕКОМЕНДУЕМОЕ ДЕЙСТВИЕ:');
          console.log(`Установите Chat ID в секреты Cloudflare Worker:`);
          console.log(`\n  npx.cmd wrangler secret put TELEGRAM_CHAT_ID`);
          console.log(`  (введите: ${primaryChatId})`);
          console.log('====================================================\n');
          return;
        } else {
          console.log('ℹ️ Очередь getUpdates пуста (возможно, сообщение было прочитано или активен webhook).');
        }
      } else {
        console.log(`⚠️ Worker Admin API вернул статус ${res.status}`);
      }
    } catch (e) {
      console.log(`⚠️ Ошибка запроса к Worker: ${e.message}`);
    }
  }

  // Режим 2: Прямой опрос Telegram Bot API
  if (!token) {
    console.log('Для прямого опроса Bot API укажите токен (значение не будет сохранено):');
    token = await askQuestion('Введите TELEGRAM_BOT_TOKEN: ');
  }

  if (!token) {
    console.error('❌ Токен не указан. Завершение работы.');
    process.exit(1);
  }

  console.log(`\n[1/3] Проверка токена: ${maskBotToken(token)}...`);
  const client = new TelegramClient(token);

  const meRes = await client.getMe();
  if (!meRes.ok) {
    console.error(`❌ Ошибка getMe: ${meRes.error} (код ${meRes.status || 'unknown'})`);
    process.exit(1);
  }

  const bot = meRes.result;
  console.log(` Бот найден: @${bot.username} (ID: ${bot.id}, Name: ${bot.first_name})`);

  console.log('\n[2/3] Проверка статуса Webhook...');
  const hookRes = await client.getWebhookInfo();
  if (hookRes.ok && hookRes.result?.url) {
    console.log(`ℹ️ На боте уже настроен Webhook: ${hookRes.result.url}`);
    console.log(`   Pending update count: ${hookRes.result.pending_update_count || 0}`);
    console.log('   При активном Webhook метод getUpdates не возвращает данные.');
    console.log('   Сообщения отсылаются напрямую в Worker.');
    return;
  }

  console.log(' Webhook не настроен, опрос getUpdates...');
  const upRes = await client.getUpdates({ limit: 10 });
  if (!upRes.ok) {
    console.error(`❌ Ошибка getUpdates: ${upRes.error}`);
    process.exit(1);
  }

  const updates = upRes.result || [];
  console.log(`[3/3] Получено обновлений: ${updates.length}`);

  const privateMessages = updates
    .filter(u => u.message && u.message.chat && u.message.chat.type === 'private')
    .map(u => ({
      chatId: u.message.chat.id,
      userId: u.message.from?.id,
      date: u.message.date,
    }));

  if (privateMessages.length === 0) {
    console.log('\n⚠️ Входящих личных сообщений не найдено.');
    console.log('Откройте Telegram, найдите бота @' + bot.username + ' и отправьте команду /start.');
    console.log('Затем перезапустите данный скрипт.');
    return;
  }

  console.log('\n Найдены сообщения от разработчика:');
  const uniqueChats = new Map();
  privateMessages.forEach(m => uniqueChats.set(m.chatId, m));

  for (const [chatId, meta] of uniqueChats.entries()) {
    console.log(`\n--- Chat ID: ${chatId} ---`);
    console.log(`  User ID: ${meta.userId}`);
    console.log(`  Date:    ${new Date(meta.date * 1000).toISOString()}`);
  }

  const targetChatId = Array.from(uniqueChats.keys())[0];
  console.log('\n====================================================');
  console.log('РЕКОМЕНДУЕМАЯ КОМАНДА ДЛЯ СОХРАНЕНИЯ CHAT ID:');
  console.log(`\n  cd services/vox-diagnostics`);
  console.log(`  npx.cmd wrangler secret put TELEGRAM_CHAT_ID`);
  console.log(`  (введите: ${targetChatId})`);
  console.log('====================================================\n');
}

main().catch(err => {
  console.error('Непредвиденная ошибка:', err.message);
  process.exit(1);
});
