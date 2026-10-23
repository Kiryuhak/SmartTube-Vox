import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync, existsSync } from 'node:fs';

function readMigration(filename) {
  const rootUrl = new URL(`../migrations/${filename}`, import.meta.url);
  if (existsSync(rootUrl)) return readFileSync(rootUrl, 'utf8');
  const rollbackUrl = new URL(`../migrations/rollbacks/${filename}`, import.meta.url);
  if (existsSync(rollbackUrl)) return readFileSync(rollbackUrl, 'utf8');
  throw new Error(`Migration file not found: ${filename}`);
}

test('Migration 0006 Gate: Application to local SQLite database, compatibility, indexes and rollback', () => {
  const db = new DatabaseSync(':memory:');

  // 1. Apply baseline migrations 0001, 0002, 0003, 0005
  db.exec(readMigration('0001_init.sql'));
  db.exec(readMigration('0002_operations.sql'));
  db.exec(readMigration('0003_admin_22.sql'));
  db.exec(readMigration('0005_duplicate_tracking.sql'));

  // 2. Insert existing sample reports prior to migration 0006
  db.prepare(`
    INSERT INTO reports (
      report_id, created_at, schema_version, app_version, platform,
      device_family, error_category, payload_json, expires_at,
      status, developer_notes, report_purpose, error_signature,
      severity, subsystem, stage, event_error_category, updated_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    'VOX-PRE-MIGRATION-01',
    Date.now(),
    'vox-diagnostic-report-v2',
    '32.56-vox.9.1',
    'Android TV',
    'Xiaomi Mi Box S',
    'PLAYER',
    JSON.stringify({ reportId: 'VOX-PRE-MIGRATION-01' }),
    Date.now() + 86400000,
    'NEW',
    'Existing notes',
    'USER',
    'PLAYER|BUFFER_STALL|ANDROID_TV',
    'MEDIUM',
    'PLAYER',
    'PLAYBACK',
    'PLAYER',
    Date.now()
  );

  // Verify baseline report exists
  const baselineCount = db.prepare('SELECT COUNT(*) as cnt FROM reports').get().cnt;
  assert.equal(baselineCount, 1);

  // 3. Apply migration 0006
  db.exec(readMigration('0006_devices_analytics.sql'));

  // Verify column installation_id exists on reports
  const columns = db.prepare("PRAGMA table_info(reports)").all().map(c => c.name);
  assert.ok(columns.includes('installation_id'), 'reports table must have installation_id column');

  // Verify baseline report preserved with NULL installation_id
  const preReport = db.prepare("SELECT * FROM reports WHERE report_id = 'VOX-PRE-MIGRATION-01'").get();
  assert.equal(preReport.report_id, 'VOX-PRE-MIGRATION-01');
  assert.equal(preReport.developer_notes, 'Existing notes');
  assert.equal(preReport.installation_id, null);

  // 4. Verify installations and daily_analytics tables and indexes exist
  const tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table'").all().map(t => t.name);
  assert.ok(tables.includes('installations'), 'installations table must exist');
  assert.ok(tables.includes('daily_analytics'), 'daily_analytics table must exist');

  // Verify index idx_reports_installation_id exists
  const indexes = db.prepare("SELECT name FROM sqlite_master WHERE type='index'").all().map(i => i.name);
  assert.ok(indexes.includes('idx_reports_installation_id'), 'idx_reports_installation_id must exist');
  assert.ok(indexes.includes('idx_installations_last_seen'), 'idx_installations_last_seen must exist');

  // 5. Insert installation and daily record
  db.prepare(`
    INSERT INTO installations (
      installation_id, manufacturer, model, platform, android_version,
      sdk_int, current_app_version, first_seen_at, last_seen_at,
      last_heartbeat_at, reports_count, error_count, app_version_history
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    'inst-test-mig-01',
    'Xiaomi',
    'Mi Box S',
    'Android TV',
    '9',
    28,
    '32.56-vox.9.2',
    Date.now(),
    Date.now(),
    Date.now(),
    1,
    0,
    JSON.stringify(['32.56-vox.9.2'])
  );

  const instRow = db.prepare("SELECT * FROM installations WHERE installation_id = 'inst-test-mig-01'").get();
  assert.equal(instRow.model, 'Mi Box S');

  // 6. Test Rollback
  db.exec(readMigration('0006_devices_analytics_rollback.sql'));

  const postRollbackTables = db.prepare("SELECT name FROM sqlite_master WHERE type='table'").all().map(t => t.name);
  assert.ok(!postRollbackTables.includes('installations'), 'installations table must be dropped after rollback');
  assert.ok(!postRollbackTables.includes('daily_analytics'), 'daily_analytics table must be dropped after rollback');

  // Existing reports data must be 100% intact after rollback
  const reportAfterRollback = db.prepare("SELECT * FROM reports WHERE report_id = 'VOX-PRE-MIGRATION-01'").get();
  assert.equal(reportAfterRollback.report_id, 'VOX-PRE-MIGRATION-01');
  assert.equal(reportAfterRollback.developer_notes, 'Existing notes');

  db.close();
});
