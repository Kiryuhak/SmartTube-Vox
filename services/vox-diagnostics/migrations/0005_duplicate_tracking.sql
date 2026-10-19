-- SmartTube VOX Diagnostics: Duplicate Tracking and Admin Statuses
ALTER TABLE issues ADD COLUMN duplicate_of TEXT DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_issues_duplicate_of ON issues(duplicate_of);
