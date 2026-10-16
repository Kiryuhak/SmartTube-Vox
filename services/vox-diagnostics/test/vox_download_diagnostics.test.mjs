import test from 'node:test';
import assert from 'node:assert/strict';
import {
  USER_FRIENDLY_ISSUE_LABELS_RU,
  getFriendlyIssueLabel,
  classifyReport,
} from '../src/triage.mjs';

test('Download Canonical Errors: Russian friendly labels present and correct', () => {
  assert.equal(getFriendlyIssueLabel('DOWNLOAD_FAILED'), 'Сбой скачивания видео');
  assert.equal(getFriendlyIssueLabel('DOWNLOAD_STATE_INCONSISTENT'), 'Несоответствие состояния загрузки');
  assert.equal(getFriendlyIssueLabel('DOWNLOAD_TRANSLATION_STALLED'), 'Зависание ожидания перевода при скачивании');
  assert.equal(getFriendlyIssueLabel('DOWNLOAD_STORAGE_LOW'), 'Недостаточно места на диске для скачивания');
  assert.equal(getFriendlyIssueLabel('DOWNLOAD_PACKAGING_FAILED'), 'Сбой упаковки медиаконтейнера (MKV)');
  assert.equal(getFriendlyIssueLabel('DOWNLOAD_FINALIZE_FAILED'), 'Сбой финализации и сохранения файла');
  assert.equal(getFriendlyIssueLabel('DOWNLOAD_STATE_REPAIRED'), 'Состояние загрузки автоматически восстановлено');
});

test('Download Recovery: earlier DOWNLOAD_FAILED is recovered by newer DOWNLOAD_COMPLETED', () => {
  const now = Date.now();
  const report = {
    timestamp: now,
    safeRecentEvents: [
      {
        event: 'DOWNLOAD_FAILED',
        subsystem: 'DOWNLOAD',
        level: 'ERROR',
        timestamp: now - 60000,
        safeContext: { downloadId: 'dl_rec_1', reason: 'NETWORK_TIMEOUT' }
      },
      {
        event: 'DOWNLOAD_COMPLETED',
        subsystem: 'DOWNLOAD',
        level: 'INFO',
        timestamp: now - 10000,
        safeContext: { downloadId: 'dl_rec_1' }
      }
    ]
  };

  const classified = classifyReport(report);
  assert.equal(classified.events[0].recovered, true);
  assert.equal(classified.events[0].recoveryEvent, 'DOWNLOAD_COMPLETED');
  assert.equal(classified.reportActiveIncident, false);
});

test('Historical Download Errors: stale download errors are marked HISTORICAL and excluded from active incident', () => {
  const now = Date.now();
  const report = {
    timestamp: now,
    safeRecentEvents: [
      {
        event: 'DOWNLOAD_STATE_INCONSISTENT',
        subsystem: 'DOWNLOAD',
        level: 'ERROR',
        timestamp: now - 7200000, // 2 hours ago (> 1 hour = historical)
        safeContext: { downloadId: 'dl_hist_1' }
      }
    ]
  };

  const classified = classifyReport(report);
  assert.equal(classified.events[0].lifecycle, 'HISTORICAL');
  assert.equal(classified.reportActiveIncident, false);
  assert.equal(classified.historicalEventCount, 1);
  assert.equal(classified.activeEventCount, 0);
});
