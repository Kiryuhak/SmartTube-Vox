-- SmartTube VOX 9.1: Targeted Migration 0004 Rollback
-- Reverts all 29 target reports and issues to their pre-migration baseline

UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-82E64C77';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-BAE1F61E';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-BDDBE424';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-D134CD89';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-D0DF1A08';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-6561494F';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-936CFDE4';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-F8C23801';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-8A73CDB3';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-61FF0AF8';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-78A43F30';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-9CF8B14A';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-08125C76';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-F0B4A75B';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-707B7E10';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-4D15A94E';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-EAB92F2F';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-19F57287';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-DE23D34D';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-DACBE715';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-99400330';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-67C3A751';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-6D5DE6B3';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-7A85888E';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-072E7194';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-CBC3A737';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-A30168DD';
UPDATE reports SET status = 'IGNORED_TEST', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-16B0A28B';
UPDATE reports SET status = 'NEW', developer_notes = '', updated_at = 1792597235016 WHERE report_id = 'VOX-ACE7107E';
DELETE FROM report_status_history WHERE reason IN ('VOX_9_1_BACKFILL_STATUS_MATRIX', 'VOX_9_1_HISTORICAL_BACKFILL');
DELETE FROM report_notes WHERE actor = 'AUTOMATION';
DELETE FROM report_changes WHERE kind = 'STATUS_CHANGED' AND changed_at = 1792526721198;
DELETE FROM admin_audit WHERE actor = 'AUTOMATION' AND created_at = 1792526721198;
DELETE FROM issues WHERE fixed_in_version = '32.56-vox.9.1';