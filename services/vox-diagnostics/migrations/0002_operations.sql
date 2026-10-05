-- Migration 0002: Add status, developer_notes, report_purpose, error_signature for operations
ALTER TABLE reports ADD COLUMN status TEXT DEFAULT 'NEW';
ALTER TABLE reports ADD COLUMN developer_notes TEXT DEFAULT '';
ALTER TABLE reports ADD COLUMN report_purpose TEXT DEFAULT 'USER';
ALTER TABLE reports ADD COLUMN error_signature TEXT DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_reports_status ON reports(status);
CREATE INDEX IF NOT EXISTS idx_reports_purpose ON reports(report_purpose);
CREATE INDEX IF NOT EXISTS idx_reports_signature ON reports(error_signature);
