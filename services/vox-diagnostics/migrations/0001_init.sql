-- Migration 0001: Initial reports table for VOX diagnostics
CREATE TABLE IF NOT EXISTS reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id TEXT UNIQUE NOT NULL,
  created_at INTEGER NOT NULL,
  schema_version TEXT NOT NULL,
  app_version TEXT NOT NULL,
  platform TEXT NOT NULL,
  device_family TEXT NOT NULL,
  error_category TEXT,
  payload_json TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_reports_report_id ON reports(report_id);
CREATE INDEX IF NOT EXISTS idx_reports_created_at ON reports(created_at);
CREATE INDEX IF NOT EXISTS idx_reports_expires_at ON reports(expires_at);
