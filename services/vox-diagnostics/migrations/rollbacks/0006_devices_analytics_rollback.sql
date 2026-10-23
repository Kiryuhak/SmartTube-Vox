-- Rollback Migration 0006
DROP TABLE IF EXISTS daily_analytics;
DROP TABLE IF EXISTS installations;
DROP INDEX IF EXISTS idx_reports_installation_id;
-- Note: SQLite / D1 does not support dropping a column directly in ALTER TABLE prior to SQLite 3.35.5,
-- but the column is nullable with default NULL so removing indexes and tables completely rolls back state.
