import fs from 'node:fs';
import crypto from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';

const backupPath = 'C:/Users/user/Desktop/SmartTube-VOX-Nightly-Backup/vox_diagnostics_prod_backup_20261021_final_six.sql';
const stats = fs.statSync(backupPath);
const buf = fs.readFileSync(backupPath);
const hash = crypto.createHash('sha256').update(buf).digest('hex');

console.log('Backup Path:', backupPath);
console.log('File Size:', stats.size, 'bytes (' + (stats.size / 1024).toFixed(2) + ' KB)');
console.log('SHA256:', hash);

// Isolated Restore Rehearsal
const db = new DatabaseSync(':memory:');
const sql = buf.toString('utf8');
db.exec(sql);

const repCount = db.prepare('SELECT count(*) as c FROM reports').get().c;
const notesCount = db.prepare('SELECT count(*) as c FROM report_notes').get().c;
const histCount = db.prepare('SELECT count(*) as c FROM report_status_history').get().c;
const issuesCount = db.prepare('SELECT count(*) as c FROM issues').get().c;
const newReportsCount = db.prepare("SELECT count(*) as c FROM reports WHERE status = 'NEW'").get().c;

console.log('Restored in-memory SQLite counts:');
console.log('- Total Reports:', repCount);
console.log('- Report Notes:', notesCount);
console.log('- Status History:', histCount);
console.log('- Canonical Issues:', issuesCount);
console.log('- NEW Reports:', newReportsCount);

// Test applying the planned update on this restored database
const plannedUpdates = [
  { id: 'VOX-D6BCA185', status: 'FIXED_AWAITING_VERIFICATION', note: '[VOX 9.1 Triage] Group: PLAYER_HTTP_ERROR. Fix: 5066fabe4 / 64d5116a4' },
  { id: 'VOX-13A27B6D', status: 'FIXED_AWAITING_VERIFICATION', note: '[VOX 9.1 Triage] Group: PLAYER_HTTP_ERROR. Fix: 5066fabe4 / 64d5116a4 (stale cached error from Oct 20)' },
  { id: 'VOX-FB2D5784', status: 'FIXED_AWAITING_VERIFICATION', note: '[VOX 9.1 Triage] Group: PLAYER_REBUFFER. Fix: 5066fabe4 (buffer freeze watchdog & policy expansion)' },
  { id: 'VOX-2C429C9F', status: 'FIXED_AWAITING_VERIFICATION', note: '[VOX 9.1 Triage] Group: PLAYER_REBUFFER. Fix: 5066fabe4 (buffer freeze watchdog & policy expansion)' },
  { id: 'VOX-ECD790E6', status: 'INVESTIGATING', note: '[VOX 9.1 Triage] Group: PLAYER_STARTUP_SLOW. Reason: Активное профилирование buffer fill на Android 9' },
  { id: 'VOX-D0DF1A08', status: 'INVESTIGATING', note: '[VOX 9.1 Triage] Group: PLAYER_REBUFFER. Reason: Буферизация AV1 4K на DuneHD (BUFFER_POLICY_TOO_SMALL), требует дополнительного профилирования сетевого буфера' }
];

const updateStmt = db.prepare("UPDATE reports SET status = ?, updated_at = ?, developer_notes = ? WHERE report_id = ? AND status = 'NEW'");
const insertNoteStmt = db.prepare("INSERT INTO report_notes (report_id, note, created_at, actor) VALUES (?, ?, ?, 'ADMIN')");
const insertHistStmt = db.prepare("INSERT INTO report_status_history (report_id, from_status, to_status, changed_at, actor, reason) VALUES (?, 'NEW', ?, ?, 'ADMIN', ?)");

const now = Date.now();
let affectedRows = 0;

db.exec('BEGIN TRANSACTION');
for (const u of plannedUpdates) {
  const res = updateStmt.run(u.status, now, u.note, u.id);
  affectedRows += res.changes;
  if (res.changes > 0) {
    insertNoteStmt.run(u.id, u.note, now);
    insertHistStmt.run(u.id, u.status, now, u.note);
  }
}
db.exec('COMMIT');

console.log('\n--- Rehearsal Update Execution ---');
console.log('Affected rows:', affectedRows);
const postRepCount = db.prepare('SELECT count(*) as c FROM reports').get().c;
const postNewCount = db.prepare("SELECT count(*) as c FROM reports WHERE status = 'NEW'").get().c;
const postFixedCount = db.prepare("SELECT count(*) as c FROM reports WHERE status = 'FIXED_AWAITING_VERIFICATION'").get().c;
const postInvestigatingCount = db.prepare("SELECT count(*) as c FROM reports WHERE status = 'INVESTIGATING'").get().c;
const postNotesCount = db.prepare('SELECT count(*) as c FROM report_notes').get().c;
const postHistCount = db.prepare('SELECT count(*) as c FROM report_status_history').get().c;

console.log('Post-Update In-Memory Counts:');
console.log('- Total Reports:', postRepCount, '(expected 58)');
console.log('- NEW Reports:', postNewCount, '(expected 0)');
console.log('- FIXED_AWAITING_VERIFICATION:', postFixedCount, '(19 + 4 = 23)');
console.log('- INVESTIGATING:', postInvestigatingCount, '(1 + 2 = 3)');
console.log('- Notes count:', postNotesCount, '(52 + 6 = 58)');
console.log('- Status History count:', postHistCount, '(50 + 6 = 56)');

// Idempotency check: run again
let secondRunChanges = 0;
for (const u of plannedUpdates) {
  const res = updateStmt.run(u.status, now, u.note, u.id);
  secondRunChanges += res.changes;
}
console.log('Idempotency second run changes:', secondRunChanges, '(expected 0)');

// Rollback rehearsal
const rollbackStmt = db.prepare("UPDATE reports SET status = 'NEW', updated_at = ?, developer_notes = '' WHERE report_id = ?");
let rollbackChanges = 0;
for (const u of plannedUpdates) {
  const res = rollbackStmt.run(now, u.id);
  rollbackChanges += res.changes;
}
console.log('Rollback rehearsal changes:', rollbackChanges, '(expected 6)');
const rollbackNewCount = db.prepare("SELECT count(*) as c FROM reports WHERE status = 'NEW'").get().c;
console.log('Post-Rollback NEW Count:', rollbackNewCount, '(expected 6)');
