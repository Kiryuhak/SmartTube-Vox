-- SmartTube VOX 9.1: Rollback Migration for Final Six Incidents
-- Reverts the six reports back to NEW and cleans up the added notes and status history entries

UPDATE reports SET status = 'NEW', updated_at = 1792600500000, developer_notes = '' WHERE report_id IN ('VOX-D6BCA185', 'VOX-13A27B6D', 'VOX-FB2D5784', 'VOX-2C429C9F', 'VOX-ECD790E6');
UPDATE reports SET status = 'NEW', updated_at = 1792526721198, developer_notes = 'Отчёт DuneHD Pro Vision 4K (Android 11 API 30). Ошибка PLAYER_REBUFFER (BUFFER_POLICY_TOO_SMALL) при воспроизведении 2160p AV1 на 1080p дисплее. Не связан с Double Back.' WHERE report_id = 'VOX-D0DF1A08';

DELETE FROM report_notes WHERE created_at = 1792600500000 AND report_id IN ('VOX-D6BCA185', 'VOX-13A27B6D', 'VOX-FB2D5784', 'VOX-2C429C9F', 'VOX-ECD790E6', 'VOX-D0DF1A08');
DELETE FROM report_status_history WHERE changed_at = 1792600500000 AND report_id IN ('VOX-D6BCA185', 'VOX-13A27B6D', 'VOX-FB2D5784', 'VOX-2C429C9F', 'VOX-ECD790E6', 'VOX-D0DF1A08');
