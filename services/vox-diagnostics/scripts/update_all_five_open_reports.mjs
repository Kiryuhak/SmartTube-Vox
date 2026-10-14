import { execSync } from 'child_process';
import fs from 'fs';

const now = Date.now();
const toStatus = 'FIXED_PENDING_VERIFICATION';
const actor = 'ADMIN';

const escapeSql = (str) => str.replace(/'/g, "''");

const reportsToUpdate = [
  {
    reportId: 'VOX-18433B96',
    fromStatus: 'NEW',
    append: false,
    newNote: 'Реализовано каноническое восстановление при PLAYER HTTP 403 (one-shot PlayerCacheRecoveryPolicy): очистка кэша плеера, переключение источника/клиента и автоматический перезапуск видео через reloadVideo() с сохранением позиции воспроизведения. Защита от бесконечного цикла (ровно 1 попытка перезапуска, при повторном 403 переход в терминальное состояние с уведомлением пользователя). Тесты Player403RecoverySuiteTest пройдены. Требуется физическая верификация на TCL TV.',
    reason: 'Реализовано каноническое восстановление HTTP 403'
  },
  {
    reportId: 'VOX-C91ECD64',
    fromStatus: 'NEW',
    append: false,
    newNote: 'Устранено ошибочное присвоение категории OTA несвязанным событиям UI, Translation и Player в структурированных событиях отчёта. Реализована дедупликация и кулдаун для повторных OTA_RATE_LIMIT, а также корректное сохранение истории проверок. Эмуляторный запуск подтверждён тестами OtaRateLimitSuiteTest и service_api_cli.test.mjs.',
    reason: 'Исправлена изоляция категорий событий и дедупликация OTA rate-limit'
  },
  {
    reportId: 'VOX-A-2EA162',
    fromStatus: 'IN_PROGRESS',
    append: true,
    newNote: '[Проход подготовки VOX 9]: Все инварианты Patch #34 (сброс liveOffsetMs в 0 для сессий VOD, структурированный объект downloadPerformance, отсутствие загрязнения текущей сигнатуры старыми ошибками рендерера) подтверждены регрессионным набором тестов VoxPatch34RegressionSuiteTest. Статус переведён в FIXED_PENDING_VERIFICATION; ожидается физическая верификация на ТВ TCL.',
    reason: 'Подтверждены инварианты изоляции сессий и структуры метрик'
  },
  {
    reportId: 'VOX-A-53605B',
    fromStatus: 'IN_PROGRESS',
    append: true,
    newNote: '[Проход подготовки VOX 9]: Отчёт поступил с устаревшей версии 32.56-vox.8.1. В версии 8.2 действует автоматический тюнинг авто-кодеков (VoxDeviceAutoTuner), отдающий приоритет аппаратному VP9 4K перед нестабильным программным декодированием AV1. Поведение подтверждено тестами VoxPatch34RegressionSuiteTest. Статус переведён в FIXED_PENDING_VERIFICATION; ожидается физическая проверка на устройстве KTC.',
    reason: 'Подтверждён приоритет аппаратного VP9 над AV1 на Android 14'
  },
  {
    reportId: 'VOX-A-870711',
    fromStatus: 'IN_PROGRESS',
    append: true,
    newNote: '[Проход подготовки VOX 9]: Отчёт поступил с устаревшей версии 32.56-vox.8.1 (Android 10 API 29). В версии 8.2 активирован профиль STANDARD (1080p, аппаратный VP9/AVC, исключение программного AV1 848x848) и адаптивные буферы. Поведение подтверждено тестами VoxPatch34RegressionSuiteTest. Статус переведён в FIXED_PENDING_VERIFICATION; ожидается физическая проверка на устройстве VS KM1.',
    reason: 'Подтверждена корректность профиля STANDARD для Android 10 API 29'
  }
];

const sqlStatements = [];

for (const r of reportsToUpdate) {
  const noteSql = escapeSql(r.newNote);
  const reasonSql = escapeSql(r.reason);
  
  if (r.append) {
    sqlStatements.push(`UPDATE reports SET status = '${toStatus}', developer_notes = developer_notes || '\n\n' || '${noteSql}', updated_at = ${now} WHERE report_id = '${r.reportId}';`);
  } else {
    sqlStatements.push(`UPDATE reports SET status = '${toStatus}', developer_notes = '${noteSql}', updated_at = ${now} WHERE report_id = '${r.reportId}';`);
  }

  sqlStatements.push(`INSERT INTO report_status_history (report_id, from_status, to_status, changed_at, actor, reason) VALUES ('${r.reportId}', '${r.fromStatus}', '${toStatus}', ${now}, '${actor}', '${reasonSql}');`);
  sqlStatements.push(`INSERT INTO report_notes (report_id, note, created_at, actor) VALUES ('${r.reportId}', '${noteSql}', ${now}, '${actor}');`);
  sqlStatements.push(`INSERT INTO report_changes (report_id, kind, changed_at) VALUES ('${r.reportId}', 'STATUS_CHANGED', ${now});`);
  sqlStatements.push(`INSERT INTO report_changes (report_id, kind, changed_at) VALUES ('${r.reportId}', 'NOTE_ADDED', ${now});`);
  sqlStatements.push(`INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES ('STATUS_CHANGED', 'REPORT', '${r.reportId}', ${now}, '${actor}');`);
  sqlStatements.push(`INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES ('NOTE_ADDED', 'REPORT', '${r.reportId}', ${now}, '${actor}');`);
}

const sql = sqlStatements.join('\n');
const tempFile = 'temp_update_all_open_reports.sql';
fs.writeFileSync(tempFile, sql, 'utf8');

console.log('Executing SQL on remote D1 for all 5 open reports...');
try {
  const res = execSync(`npx.cmd wrangler d1 execute vox_diagnostics_db --remote --file=${tempFile}`, { encoding: 'utf8' });
  console.log(res);
} finally {
  if (fs.existsSync(tempFile)) {
    fs.unlinkSync(tempFile);
  }
}
