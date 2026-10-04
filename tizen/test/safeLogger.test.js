const assert = require('assert');
const { TizenSafeLogger, VoxLogLevel, VoxLogCategory, VoxLogCode } = require('../src/diagnostics/TizenSafeLogger');

function run() {
  console.log('Testing Tizen Safe Logger & Sanitization...');

  const logger = new TizenSafeLogger();
  logger.clearLogs();

  // Test 1: Info & Error logging
  logger.i(VoxLogCategory.APP, VoxLogCode.APP_START, 'Приложение запущено');
  logger.e(VoxLogCategory.PLAYER, VoxLogCode.AVPLAY_ERROR, 'Сбой воспроизведения', { codec: 'av1' }, new Error('Decode error'));

  const events = logger.getEvents();
  assert.strictEqual(events.length, 2);
  assert.strictEqual(events[0].code, VoxLogCode.AVPLAY_ERROR); // Newest first
  assert.strictEqual(events[1].code, VoxLogCode.APP_START);

  // Test 2: Sanitization of sensitive data
  logger.e(
    VoxLogCategory.YANDEX_AUTH,
    VoxLogCode.AUTH_FAILED,
    'Auth error with access_token=secret_abc and Bearer 123456 at https://example.com/stream?sig=999&token=abc and IP 192.168.1.50',
    { password: 'mypassword', ip: '10.0.0.1' }
  );

  const authEvent = logger.getEvents()[0];
  assert.strictEqual(authEvent.message.includes('secret_abc'), false);
  assert.strictEqual(authEvent.message.includes('123456'), false);
  assert.strictEqual(authEvent.message.includes('sig=999'), false);
  assert.strictEqual(authEvent.message.includes('192.168.1.50'), false);
  assert.strictEqual(authEvent.context.password.includes('mypassword'), false);
  assert.strictEqual(authEvent.context.ip.includes('10.0.0.1'), false);

  // Test 3: Clear logs
  logger.clearLogs();
  assert.strictEqual(logger.getEvents().length, 0);
  assert.strictEqual(logger.getFormattedJournal(), 'Ошибок пока не зафиксировано.');

  console.log('  ✓ Tizen Safe Logger tests PASS');
}

module.exports = { run };
