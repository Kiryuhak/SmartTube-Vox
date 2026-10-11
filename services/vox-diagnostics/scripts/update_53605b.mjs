import { execSync } from 'child_process';

const reportId = 'VOX-A-53605B';
const now = Date.now();
const note = 'Patch #31: расследование повторной буферизации VOD на KTC 4K SMART TV PH, Android 14 / API34. В отчёте зафиксированы два PLAYER_REBUFFER при фактически выбранном 1080p AV1, несмотря на AUTO-профиль STANDARD с maxQuality 2160p и preferred codec VP9. Дополнительно проверяется lifecycle MediaSession/background playback. Исправление и диагностика выполняются в Patch #31; требуется последующая проверка на исходном KTC.';
const reason = 'Patch #31 investigation';

const escapeSql = (str) => str.replace(/'/g, "''");

const sql = [
  `UPDATE reports SET status = 'IN_PROGRESS', developer_notes = '${escapeSql(note)}', updated_at = ${now} WHERE report_id = '${reportId}';`,
  `INSERT INTO report_status_history (report_id, from_status, to_status, changed_at, actor, reason) VALUES ('${reportId}', 'NEW', 'IN_PROGRESS', ${now}, 'ADMIN', '${escapeSql(reason)}');`,
  `INSERT INTO report_notes (report_id, note, created_at, actor) VALUES ('${reportId}', '${escapeSql(note)}', ${now}, 'ADMIN');`,
  `INSERT INTO report_changes (report_id, kind, changed_at) VALUES ('${reportId}', 'STATUS_CHANGED', ${now});`,
  `INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES ('STATUS_CHANGED', 'REPORT', '${reportId}', ${now}, 'ADMIN');`,
  `INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES ('NOTE_ADDED', 'REPORT', '${reportId}', ${now}, 'ADMIN');`
].join('\n');

import fs from 'fs';
fs.writeFileSync('temp_update_53605b.sql', sql, 'utf8');

console.log('Executing SQL on remote D1...');
const res = execSync('npx.cmd wrangler d1 execute vox_diagnostics_db --remote --file=temp_update_53605b.sql', { encoding: 'utf8' });
console.log(res);

fs.unlinkSync('temp_update_53605b.sql');
