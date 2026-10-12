import { execSync } from 'child_process';
import fs from 'fs';

const now = Date.now();

const fae2b2_prev = "Принято в работу. Повторяющиеся OTA NETWORK_ERROR при ручной проверке обновлений на Google TV W2 / Android 11. Проверить OTA transport, DNS/connect/TLS/HTTP classification, Stable channel и корректное различение NO_UPDATE vs реальной сетевой ошибки. Связать с текущим updater hardening.";
const fae2b2_patch35 = "Исправлено в Patch #35. Реализована классификация сетевых и HTTP ошибок OTA, детерминированная обработка 404 манифеста с безопасным fallback на GitHub Releases API, защита от ложных сетевых ошибок при актуальной версии, а также single-flight / debounce guard для предотвращения параллельных проверок. Установленная версия 32.56-vox.8.1 корректно определяется как актуальная без сетевых сбоев. Требуется подтверждение на Google TV W2.";
const fae2b2_full = `${fae2b2_prev}\n\n[Patch #35]: ${fae2b2_patch35}`;

const r4a04bd_prev = "Принято в работу. Исследовать PLAYER_REBUFFER на VP9 1080p/2160p, Adaptive Buffering / network throughput и отдельную текущую проблему OTA HTTP_ERROR. Отчёт schema v3 использовать как регрессионный кейс Patch #34+.";
const r4a04bd_patch35 = "Исправлено в Patch #35. Реализовано адаптивное масштабирование буфера воспроизведения для 4K/VP9 (до 128 MB target bytes и динамическое расширение minBuffer с гистерезисом), детерминированный классификатор причин буферизации (DECODER_LIMITED, BUFFER_POLICY_TOO_SMALL, NETWORK_LIMITED, CDN_THROTTLED, BITRATE_TOO_HIGH), а также устранение ложных OTA HTTP_ERROR 403 при частых проверках через single-flight guard. Успешно верифицировано на Android TV Emulator API 34 (VOX-0C69F5).";
const r4a04bd_full = `${r4a04bd_prev}\n\n[Patch #35]: ${r4a04bd_patch35}`;

const escapeSql = (str) => str.replace(/'/g, "''");

const sqlStatements = [
  // VOX-FAE2B2
  `UPDATE reports SET status = 'FIXED_PENDING_VERIFICATION', developer_notes = '${escapeSql(fae2b2_full)}', updated_at = ${now} WHERE report_id = 'VOX-FAE2B2';`,
  `INSERT INTO report_status_history (report_id, from_status, to_status, changed_at, actor, reason) VALUES ('VOX-FAE2B2', 'IN_PROGRESS', 'FIXED_PENDING_VERIFICATION', ${now}, 'ADMIN', 'Patch #35 OTA updater hardening');`,
  `INSERT INTO report_notes (report_id, note, created_at, actor) VALUES ('VOX-FAE2B2', '${escapeSql(fae2b2_patch35)}', ${now}, 'ADMIN');`,
  `INSERT INTO report_changes (report_id, kind, changed_at) VALUES ('VOX-FAE2B2', 'STATUS_CHANGED', ${now});`,
  `INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES ('STATUS_CHANGED', 'REPORT', 'VOX-FAE2B2', ${now}, 'ADMIN');`,
  `INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES ('NOTE_ADDED', 'REPORT', 'VOX-FAE2B2', ${now}, 'ADMIN');`,

  // VOX-4A04BD
  `UPDATE reports SET status = 'FIXED_PENDING_VERIFICATION', developer_notes = '${escapeSql(r4a04bd_full)}', updated_at = ${now} WHERE report_id = 'VOX-4A04BD';`,
  `INSERT INTO report_status_history (report_id, from_status, to_status, changed_at, actor, reason) VALUES ('VOX-4A04BD', 'IN_PROGRESS', 'FIXED_PENDING_VERIFICATION', ${now}, 'ADMIN', 'Patch #35 4K adaptive buffering and OTA hardening');`,
  `INSERT INTO report_notes (report_id, note, created_at, actor) VALUES ('VOX-4A04BD', '${escapeSql(r4a04bd_patch35)}', ${now}, 'ADMIN');`,
  `INSERT INTO report_changes (report_id, kind, changed_at) VALUES ('VOX-4A04BD', 'STATUS_CHANGED', ${now});`,
  `INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES ('STATUS_CHANGED', 'REPORT', 'VOX-4A04BD', ${now}, 'ADMIN');`,
  `INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES ('NOTE_ADDED', 'REPORT', 'VOX-4A04BD', ${now}, 'ADMIN');`
];

const sql = sqlStatements.join('\n');
const tempFile = 'temp_update_patch35.sql';
fs.writeFileSync(tempFile, sql, 'utf8');

console.log('Executing SQL on remote D1 for Patch #35 report transitions...');
try {
  const res = execSync(`npx.cmd wrangler d1 execute vox_diagnostics_db --remote --file=${tempFile}`, { encoding: 'utf8' });
  console.log(res);
} finally {
  if (fs.existsSync(tempFile)) {
    fs.unlinkSync(tempFile);
  }
}
