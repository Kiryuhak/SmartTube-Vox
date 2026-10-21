-- SmartTube VOX 9.1: Final Six Incident Status Reconciliation
-- Applies targeted updates to the 6 remaining NEW incidents with audit trails

-- 1. VOX-D6BCA185 (TCL Smart TV Pro, API 34): HTTP 403 / Source Expiry -> FIXED_AWAITING_VERIFICATION
UPDATE reports SET status = 'FIXED_AWAITING_VERIFICATION', updated_at = 1792600500000, developer_notes = '[VOX 9.1 Triage] Group: PLAYER_HTTP_ERROR. Fix: 5066fabe4 / 64d5116a4' WHERE report_id = 'VOX-D6BCA185' AND status = 'NEW';
INSERT INTO report_notes (report_id, note, created_at, actor) VALUES ('VOX-D6BCA185', '[VOX 9.1 Triage] Group: PLAYER_HTTP_ERROR. Fix: 5066fabe4 / 64d5116a4', 1792600500000, 'ADMIN');
INSERT INTO report_status_history (report_id, from_status, to_status, changed_at, actor, reason) VALUES ('VOX-D6BCA185', 'NEW', 'FIXED_AWAITING_VERIFICATION', 1792600500000, 'ADMIN', '[VOX 9.1 Triage] Group: PLAYER_HTTP_ERROR. Fix: 5066fabe4 / 64d5116a4');

-- 2. VOX-13A27B6D (TCL Smart TV Pro, API 34): HTTP 403 / Stale Cached Telemetry -> FIXED_AWAITING_VERIFICATION
UPDATE reports SET status = 'FIXED_AWAITING_VERIFICATION', updated_at = 1792600500000, developer_notes = '[VOX 9.1 Triage] Group: PLAYER_HTTP_ERROR. Fix: 5066fabe4 / 64d5116a4 (stale cached error from Oct 20)' WHERE report_id = 'VOX-13A27B6D' AND status = 'NEW';
INSERT INTO report_notes (report_id, note, created_at, actor) VALUES ('VOX-13A27B6D', '[VOX 9.1 Triage] Group: PLAYER_HTTP_ERROR. Fix: 5066fabe4 / 64d5116a4 (stale cached error from Oct 20)', 1792600500000, 'ADMIN');
INSERT INTO report_status_history (report_id, from_status, to_status, changed_at, actor, reason) VALUES ('VOX-13A27B6D', 'NEW', 'FIXED_AWAITING_VERIFICATION', 1792600500000, 'ADMIN', '[VOX 9.1 Triage] Group: PLAYER_HTTP_ERROR. Fix: 5066fabe4 / 64d5116a4 (stale cached error from Oct 20)');

-- 3. VOX-FB2D5784 (Google AOSP TV on x86, API 34): 4K VP9 Buffer Policy Threshold -> FIXED_AWAITING_VERIFICATION
UPDATE reports SET status = 'FIXED_AWAITING_VERIFICATION', updated_at = 1792600500000, developer_notes = '[VOX 9.1 Triage] Group: PLAYER_REBUFFER. Fix: 5066fabe4 (buffer freeze watchdog & policy expansion)' WHERE report_id = 'VOX-FB2D5784' AND status = 'NEW';
INSERT INTO report_notes (report_id, note, created_at, actor) VALUES ('VOX-FB2D5784', '[VOX 9.1 Triage] Group: PLAYER_REBUFFER. Fix: 5066fabe4 (buffer freeze watchdog & policy expansion)', 1792600500000, 'ADMIN');
INSERT INTO report_status_history (report_id, from_status, to_status, changed_at, actor, reason) VALUES ('VOX-FB2D5784', 'NEW', 'FIXED_AWAITING_VERIFICATION', 1792600500000, 'ADMIN', '[VOX 9.1 Triage] Group: PLAYER_REBUFFER. Fix: 5066fabe4 (buffer freeze watchdog & policy expansion)');

-- 4. VOX-2C429C9F (Google AOSP TV on x86, API 34): 4K VP9 Buffer Policy Threshold -> FIXED_AWAITING_VERIFICATION
UPDATE reports SET status = 'FIXED_AWAITING_VERIFICATION', updated_at = 1792600500000, developer_notes = '[VOX 9.1 Triage] Group: PLAYER_REBUFFER. Fix: 5066fabe4 (buffer freeze watchdog & policy expansion)' WHERE report_id = 'VOX-2C429C9F' AND status = 'NEW';
INSERT INTO report_notes (report_id, note, created_at, actor) VALUES ('VOX-2C429C9F', '[VOX 9.1 Triage] Group: PLAYER_REBUFFER. Fix: 5066fabe4 (buffer freeze watchdog & policy expansion)', 1792600500000, 'ADMIN');
INSERT INTO report_status_history (report_id, from_status, to_status, changed_at, actor, reason) VALUES ('VOX-2C429C9F', 'NEW', 'FIXED_AWAITING_VERIFICATION', 1792600500000, 'ADMIN', '[VOX 9.1 Triage] Group: PLAYER_REBUFFER. Fix: 5066fabe4 (buffer freeze watchdog & policy expansion)');

-- 5. VOX-ECD790E6 (Google TV on x86, API 28): Android 9 Buffer Fill Latency -> INVESTIGATING
UPDATE reports SET status = 'INVESTIGATING', updated_at = 1792600500000, developer_notes = '[VOX 9.1 Triage] Group: PLAYER_STARTUP_SLOW. Reason: Активное профилирование buffer fill на Android 9' WHERE report_id = 'VOX-ECD790E6' AND status = 'NEW';
INSERT INTO report_notes (report_id, note, created_at, actor) VALUES ('VOX-ECD790E6', '[VOX 9.1 Triage] Group: PLAYER_STARTUP_SLOW. Reason: Активное профилирование buffer fill на Android 9', 1792600500000, 'ADMIN');
INSERT INTO report_status_history (report_id, from_status, to_status, changed_at, actor, reason) VALUES ('VOX-ECD790E6', 'NEW', 'INVESTIGATING', 1792600500000, 'ADMIN', '[VOX 9.1 Triage] Group: PLAYER_STARTUP_SLOW. Reason: Активное профилирование buffer fill на Android 9');

-- 6. VOX-D0DF1A08 (DuneHD Pro Vision 4K, API 30): DuneHD AV1 4K Rebuffer -> INVESTIGATING
UPDATE reports SET status = 'INVESTIGATING', updated_at = 1792600500000, developer_notes = '[VOX 9.1 Triage] Group: PLAYER_REBUFFER. Reason: Буферизация AV1 4K на DuneHD (BUFFER_POLICY_TOO_SMALL), оставлен в INVESTIGATING для дополнительного профилирования' WHERE report_id = 'VOX-D0DF1A08' AND status = 'NEW';
INSERT INTO report_notes (report_id, note, created_at, actor) VALUES ('VOX-D0DF1A08', '[VOX 9.1 Triage] Group: PLAYER_REBUFFER. Reason: Буферизация AV1 4K на DuneHD (BUFFER_POLICY_TOO_SMALL), оставлен в INVESTIGATING для дополнительного профилирования', 1792600500000, 'ADMIN');
INSERT INTO report_status_history (report_id, from_status, to_status, changed_at, actor, reason) VALUES ('VOX-D0DF1A08', 'NEW', 'INVESTIGATING', 1792600500000, 'ADMIN', '[VOX 9.1 Triage] Group: PLAYER_REBUFFER. Reason: Буферизация AV1 4K на DuneHD (BUFFER_POLICY_TOO_SMALL), оставлен в INVESTIGATING для дополнительного профилирования');
