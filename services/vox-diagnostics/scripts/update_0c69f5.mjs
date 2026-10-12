import { execSync } from 'child_process';
import fs from 'fs';

const now = Date.now();
const reportId = 'VOX-0C69F5';
const fromStatus = 'NEW';
const toStatus = 'IGNORED_TEST';
const developerNote = 'Emulator acceptance report Patch #35. Regression evidence extracted for HUD/network/player hardening. Not a physical user incident.';
const reason = 'Emulator acceptance report Patch #35 fixture';

const escapeSql = (str) => str.replace(/'/g, "''");

const sqlStatements = [
  `UPDATE reports SET status = '${toStatus}', report_purpose = 'EMULATOR_TEST', developer_notes = '${escapeSql(developerNote)}', updated_at = ${now} WHERE report_id = '${reportId}';`,
  `INSERT INTO report_status_history (report_id, from_status, to_status, changed_at, actor, reason) VALUES ('${reportId}', '${fromStatus}', '${toStatus}', ${now}, 'ADMIN', '${escapeSql(reason)}');`,
  `INSERT INTO report_notes (report_id, note, created_at, actor) VALUES ('${reportId}', '${escapeSql(developerNote)}', ${now}, 'ADMIN');`,
  `INSERT INTO report_changes (report_id, kind, changed_at) VALUES ('${reportId}', 'STATUS_CHANGED', ${now});`,
  `INSERT INTO report_changes (report_id, kind, changed_at) VALUES ('${reportId}', 'NOTE_ADDED', ${now});`,
  `INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES ('STATUS_CHANGED', 'REPORT', '${reportId}', ${now}, 'ADMIN');`,
  `INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES ('NOTE_ADDED', 'REPORT', '${reportId}', ${now}, 'ADMIN');`
];

const sql = sqlStatements.join('\n');
const tempFile = 'temp_update_0c69f5.sql';
fs.writeFileSync(tempFile, sql, 'utf8');

console.log('Executing SQL on remote D1 for VOX-0C69F5 transition...');
try {
  const res = execSync(`npx.cmd wrangler d1 execute vox_diagnostics_db --remote --file=${tempFile}`, { encoding: 'utf8' });
  console.log(res);
} finally {
  if (fs.existsSync(tempFile)) {
    fs.unlinkSync(tempFile);
  }
}
