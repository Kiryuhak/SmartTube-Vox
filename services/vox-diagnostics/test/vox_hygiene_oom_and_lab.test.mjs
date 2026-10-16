import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import {
  classifyReport,
  isSyntheticTestReport,
  getDerivedStatus,
  buildClassificationSignature,
} from '../src/triage.mjs';
import { dispatchTelegramNotification } from '../src/notifications/telegram.mjs';
import { ReportStorage } from '../src/storage.mjs';
import { handleRequest } from '../src/diagnostics.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

function loadFixture(filename) {
  const fullPath = join(__dirname, 'fixtures', filename);
  return JSON.parse(readFileSync(fullPath, 'utf8'));
}

test('VOX-4D15A94E: primary fresh issue is PLAYER_OUT_OF_MEMORY and older issues are historical', () => {
  const report = loadFixture('vox-diagnostic-VOX-4D15A94E.json');
  const classified = classifyReport(report);

  assert.equal(classified.primaryEvent.event, 'PLAYER_OUT_OF_MEMORY');
  assert.equal(classified.reportActiveIncident, true);
  assert.equal(classified.errorCategory, 'PLAYER');

  // Verify historical context separation: 403, OTA, download are historical
  const historicalEvents = classified.events.filter(e => e.lifecycle === 'HISTORICAL');
  assert.ok(historicalEvents.length >= 3, 'Must have at least 3 historical events');
  assert.ok(historicalEvents.some(e => e.event === 'PLAYER_HTTP_ERROR' && e.safeContext?.responseCode === '403'));
  assert.ok(historicalEvents.some(e => e.event === 'OTA_FAILED'));
  assert.ok(historicalEvents.some(e => e.event === 'DOWNLOAD_STATE_INCONSISTENT'));

  // Fresh active event is PLAYER_OUT_OF_MEMORY
  const activeEvents = classified.events.filter(e => e.lifecycle !== 'HISTORICAL' && !e.recovered);
  assert.equal(activeEvents.length, 1);
  assert.equal(activeEvents[0].event, 'PLAYER_OUT_OF_MEMORY');

  // Safe memory telemetry is captured without leakage
  const memContext = activeEvents[0].safeContext;
  assert.equal(memContext.causeClass, 'OutOfMemoryError');
  assert.equal(memContext.maxHeapMb, '256');
  assert.equal(memContext.totalHeapMb, '254');
  assert.equal(memContext.lowMemory, 'true');
  assert.equal(memContext.activePlayers, '1');
  assert.equal(memContext.recreationCount, '1');

  // Signature check
  const sig = buildClassificationSignature(report, classified);
  assert.equal(sig, 'PLAYER|PLAYER|PLAYBACK|PLAYER_OUT_OF_MEMORY|NONE|ANDROID_TV_API34');
});

test('VOX-ACE7107E: automated OTA lab report on emulator is classified as IGNORED_TEST and excluded from active alerts', async () => {
  const report = loadFixture('vox-diagnostic-VOX-ACE7107E.json');

  // 1. Synthetic test report detection
  assert.equal(isSyntheticTestReport(report), true);

  // 2. Derived operational status
  const derivedStatus = getDerivedStatus(report);
  assert.equal(derivedStatus, 'IGNORED_TEST');

  // 3. Telegram alert suppression
  const tgResult = await dispatchTelegramNotification({
    report,
    classification: classifyReport(report),
    issue: {},
    env: {
      TELEGRAM_NOTIFICATIONS_ENABLED: true,
      TELEGRAM_BOT_TOKEN: 'test-token',
      TELEGRAM_CHAT_ID: '-1001234567890', // Production chat
      // No TELEGRAM_TEST_CHAT_ID -> suppressed
    },
  });
  assert.equal(tgResult.decision, 'SUPPRESSED_TEST');

  // 4. Ingestion into storage marks status IGNORED_TEST and excludes from attention queries
  const storage = new ReportStorage();
  const ingestReq = new Request('https://diag.smarttube.app/v1/report', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(report),
  });
  const env = {
    TELEGRAM_NOTIFICATIONS_ENABLED: 'true',
    TELEGRAM_BOT_TOKEN: 'test-token',
    TELEGRAM_CHAT_ID: '-1001234567890',
  };

  const response = await handleRequest(ingestReq, env, storage);
  assert.equal(response.status, 201);

  const storedReport = await storage.getReportById(report.reportId);
  assert.ok(storedReport);
  assert.equal(storedReport.status, 'IGNORED_TEST');

  // Attention query must NOT include IGNORED_TEST
  const attentionReports = await storage.listReports({ attention: true });
  assert.ok(!attentionReports.some(r => r.reportId === 'VOX-ACE7107E'));
});
