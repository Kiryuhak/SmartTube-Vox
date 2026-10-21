import fs from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

const backupPath = 'C:/Users/user/Desktop/SmartTube-VOX-Nightly-Backup/vox_diagnostics_prod_backup_20261021_final_six.sql';
const db = new DatabaseSync(':memory:');
db.exec(fs.readFileSync(backupPath, 'utf8'));

console.log('Restored baseline:');
console.log('Total reports:', db.prepare('SELECT count(*) as c FROM reports').get().c);
console.log('NEW reports:', db.prepare("SELECT count(*) as c FROM reports WHERE status = 'NEW'").get().c);
console.log('Notes:', db.prepare('SELECT count(*) as c FROM report_notes').get().c);
console.log('History:', db.prepare('SELECT count(*) as c FROM report_status_history').get().c);

// 1. Apply 0005_vox91_final_six.sql
const migrationSql = fs.readFileSync('services/vox-diagnostics/migrations/0005_vox91_final_six.sql', 'utf8');
db.exec(migrationSql);

console.log('\n--- After applying 0005_vox91_final_six.sql ---');
console.log('Total reports:', db.prepare('SELECT count(*) as c FROM reports').get().c);
console.log('NEW reports:', db.prepare("SELECT count(*) as c FROM reports WHERE status = 'NEW'").get().c);
console.log('FIXED_AWAITING_VERIFICATION:', db.prepare("SELECT count(*) as c FROM reports WHERE status = 'FIXED_AWAITING_VERIFICATION'").get().c);
console.log('INVESTIGATING:', db.prepare("SELECT count(*) as c FROM reports WHERE status = 'INVESTIGATING'").get().c);
console.log('Notes:', db.prepare('SELECT count(*) as c FROM report_notes').get().c);
console.log('History:', db.prepare('SELECT count(*) as c FROM report_status_history').get().c);

// 2. Test Idempotency (running again should change 0 reports because status is no longer NEW)
const updateCountBefore = db.prepare("SELECT count(*) as c FROM reports WHERE status = 'NEW'").get().c;
db.exec(migrationSql);
console.log('Idempotency check: NEW count after re-run:', db.prepare("SELECT count(*) as c FROM reports WHERE status = 'NEW'").get().c);

// 3. Rollback
const rollbackSql = fs.readFileSync('services/vox-diagnostics/migrations/0005_vox91_final_six_rollback.sql', 'utf8');
db.exec(rollbackSql);

console.log('\n--- After applying 0005_vox91_final_six_rollback.sql ---');
console.log('Total reports:', db.prepare('SELECT count(*) as c FROM reports').get().c);
console.log('NEW reports:', db.prepare("SELECT count(*) as c FROM reports WHERE status = 'NEW'").get().c);
console.log('Notes:', db.prepare('SELECT count(*) as c FROM report_notes').get().c);
console.log('History:', db.prepare('SELECT count(*) as c FROM report_status_history').get().c);
console.log('DuneHD note restored:', db.prepare("SELECT developer_notes FROM reports WHERE report_id = 'VOX-D0DF1A08'").get().developer_notes.includes('DuneHD Pro Vision 4K'));
