-- Additive migration. Existing reports, statuses and developer_notes stay intact.
ALTER TABLE reports ADD COLUMN severity TEXT NOT NULL DEFAULT 'INFO';
ALTER TABLE reports ADD COLUMN subsystem TEXT NOT NULL DEFAULT '';
ALTER TABLE reports ADD COLUMN stage TEXT NOT NULL DEFAULT '';
ALTER TABLE reports ADD COLUMN event_error_category TEXT NOT NULL DEFAULT '';
ALTER TABLE reports ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;

UPDATE reports SET severity = 'MEDIUM' WHERE error_category IS NOT NULL AND error_category != '';
UPDATE reports SET subsystem = UPPER(error_category)
  WHERE UPPER(error_category) IN ('DOWNLOAD', 'PLAYER', 'PLAYBACK', 'BACKGROUND', 'OTA', 'OFFLINE', 'TRANSLATION', 'NETWORK');
UPDATE reports SET subsystem = 'PLAYER' WHERE subsystem = 'PLAYBACK';
UPDATE reports SET updated_at = created_at WHERE updated_at = 0;

CREATE TABLE IF NOT EXISTS report_status_history (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id TEXT NOT NULL,
  from_status TEXT NOT NULL,
  to_status TEXT NOT NULL,
  changed_at INTEGER NOT NULL,
  actor TEXT NOT NULL CHECK(actor IN ('ADMIN', 'AUTOMATION', 'SYSTEM')),
  reason TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_report_status_history_report ON report_status_history(report_id, changed_at);

CREATE TABLE IF NOT EXISTS report_notes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id TEXT NOT NULL,
  note TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  actor TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_report_notes_report ON report_notes(report_id, created_at);

CREATE TABLE IF NOT EXISTS issues (
  signature TEXT PRIMARY KEY,
  status TEXT NOT NULL DEFAULT 'NEW',
  fixed_in_version TEXT NOT NULL DEFAULT '',
  fix_patch TEXT NOT NULL DEFAULT '',
  fix_commit TEXT NOT NULL DEFAULT '',
  title TEXT NOT NULL DEFAULT '',
  reopened_at INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS admin_audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  action TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  actor TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS report_changes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  changed_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_report_changes_id ON report_changes(id);

CREATE INDEX IF NOT EXISTS idx_reports_updated ON reports(updated_at, report_id);
CREATE INDEX IF NOT EXISTS idx_reports_severity ON reports(severity);
