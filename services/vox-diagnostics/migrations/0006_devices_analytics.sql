-- Migration 0006: Installation ID, Devices Admin and Live Analytics Heartbeat
-- Additive migration. Completely backwards compatible with 0001-0005.

-- 1. Add installation_id column to reports table
ALTER TABLE reports ADD COLUMN installation_id TEXT DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_reports_installation_id ON reports(installation_id);

-- 2. Installations table for device-fleet aggregation without PII or Google Account ID
CREATE TABLE IF NOT EXISTS installations (
  installation_id TEXT PRIMARY KEY,
  manufacturer TEXT NOT NULL DEFAULT '',
  model TEXT NOT NULL DEFAULT '',
  platform TEXT NOT NULL DEFAULT '',
  android_version TEXT NOT NULL DEFAULT '',
  sdk_int INTEGER NOT NULL DEFAULT 0,
  current_app_version TEXT NOT NULL DEFAULT '',
  first_seen_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  last_heartbeat_at INTEGER NOT NULL DEFAULT 0,
  reports_count INTEGER NOT NULL DEFAULT 0,
  error_count INTEGER NOT NULL DEFAULT 0,
  app_version_history TEXT NOT NULL DEFAULT '[]'
);

CREATE INDEX IF NOT EXISTS idx_installations_last_seen ON installations(last_seen_at);
CREATE INDEX IF NOT EXISTS idx_installations_heartbeat ON installations(last_heartbeat_at);
CREATE INDEX IF NOT EXISTS idx_installations_model ON installations(model);
CREATE INDEX IF NOT EXISTS idx_installations_app_ver ON installations(current_app_version);

-- 3. Daily analytics aggregates for fast time-series queries without full table scans
CREATE TABLE IF NOT EXISTS daily_analytics (
  date_key TEXT PRIMARY KEY,
  active_installations INTEGER NOT NULL DEFAULT 0,
  new_installations INTEGER NOT NULL DEFAULT 0,
  total_reports INTEGER NOT NULL DEFAULT 0,
  error_installations INTEGER NOT NULL DEFAULT 0,
  metrics_json TEXT NOT NULL DEFAULT '{}'
);

-- Backfill installations from existing report payloads if available
-- Note: Existing reports without installationId will safely remain NULL and not merge into a dummy device.
