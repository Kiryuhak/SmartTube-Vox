import { classifyReport, compareVoxVersions, technicalSummary, STATUSES } from './triage.mjs';

export const DEFAULT_RETENTION_DAYS = 30;
export const DEFAULT_RETENTION_MS = DEFAULT_RETENTION_DAYS * 24 * 60 * 60 * 1000;

export const CANONICAL_STATUSES = [
  'NEW',
  'IN_PROGRESS',
  'NEEDS_INFO',
  'FIXED_PENDING_VERIFICATION',
  'CLOSED',
  'KNOWN_ISSUE',
  'IGNORED_TEST',
  'RESOLVED',
];

export const STATUS_LABELS = {
  NEW: 'Новый',
  IN_PROGRESS: 'В работе',
  NEEDS_INFO: 'Нужны данные',
  FIXED_PENDING_VERIFICATION: 'Исправлено — ждёт проверки',
  CLOSED: 'Закрыто',
  RESOLVED: 'Решено',
  KNOWN_ISSUE: 'Известная проблема',
  IGNORED: 'Тест / игнор',
  IGNORED_TEST: 'Тест / игнор',
};

/**
 * Нормализует строковый статус к каноническому виду:
 * NEW, IN_PROGRESS, NEEDS_INFO, FIXED_PENDING_VERIFICATION, CLOSED, RESOLVED, KNOWN_ISSUE, IGNORED_TEST
 */
export function normalizeStatus(rawStatus) {
  if (!rawStatus) return 'NEW';
  const s = String(rawStatus).toUpperCase().trim();
  if (s === 'REVIEWED' || s === 'TRIAGED' || s === 'IN_PROGRESS') return 'IN_PROGRESS';
  if (s === 'NEEDS_INFO' || s === 'INFO_NEEDED') return 'NEEDS_INFO';
  if (s === 'FIXED' || s === 'FIXED_PENDING_VERIFICATION' || s === 'PENDING_VERIFICATION') return 'FIXED_PENDING_VERIFICATION';
  if (s === 'CLOSED') return 'CLOSED';
  if (s === 'RESOLVED') return 'RESOLVED';
  if (s === 'KNOWN' || s === 'KNOWN_ISSUE') return 'KNOWN_ISSUE';
  if (s === 'IGNORED_TEST' || s === 'TEST' || s === 'IGNORED') return 'IGNORED_TEST';
  if (s === 'NEW') return 'NEW';
  return s;
}

/**
 * Адаптер хранилища отчётов диагностики VOX.
 * Поддерживает Cloudflare D1 (когда передан env.DB) и in-memory хранилище (для тестов и локального запуска).
 */
export class ReportStorage {
  constructor(db = null) {
    this.db = db;
    this.inMemoryReports = new Map();
    this.statusHistory = [];
    this.notes = [];
    this.issues = new Map();
    this.audit = [];
    this.changes = [];
  }

  async recordChange(reportId, kind, action = null) {
    const now = Date.now();
    if (this.db) {
      const statements = [this.db.prepare('INSERT INTO report_changes (report_id, kind, changed_at) VALUES (?, ?, ?)').bind(reportId, kind, now)];
      if (action) statements.push(this.db.prepare('INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES (?, ?, ?, ?, ?)').bind(action, 'REPORT', reportId, now, 'ADMIN'));
      await this.db.batch(statements);
    } else {
      this.changes.push({ id: this.changes.length + 1, report_id: reportId, kind, changed_at: now });
      if (action) this.audit.push({ action, target_type: 'REPORT', target_id: reportId, created_at: now, actor: 'ADMIN' });
    }
  }

  /**
   * Сохраняет анонимизированный отчёт.
   */
  async saveReport(report, retentionMs = DEFAULT_RETENTION_MS) {
    const createdAt = report.receivedAt || report.timestamp || Date.now();
    const expiresAt = createdAt + retentionMs;
    const reportId = report.reportId;
    const schemaVersion = report.schema || 'vox-diagnostic-report-v2';
    const appVersion = report.appVersion || 'unknown';
    const platform = report.platform || 'unknown';
    const deviceFamily = `${report.manufacturer || ''} ${report.model || ''}`.trim() || 'unknown';
    const errorCategory = report.errorCategory || null;
    const reportPurpose = report.reportPurpose || report.purpose || (reportId && reportId.includes('TEST') ? 'TEST' : 'USER');
    const rawStatus = report.status || (reportPurpose === 'TEST' ? 'IGNORED_TEST' : 'NEW');
    const status = normalizeStatus(rawStatus);
    const developerNotes = report.developerNotes || report.developer_notes || '';
    const errorSignature = report.errorSignature || report.error_signature || '';
    const classification = classifyReport(report);
    const payloadJson = JSON.stringify({ ...report, structuredEvents: report.structuredEvents || classification.events, technicalSummary: report.technicalSummary || technicalSummary(report) });

    if (this.db) {
      const stmt = this.db.prepare(
        `INSERT INTO reports (
          report_id, created_at, schema_version, app_version, platform,
          device_family, error_category, payload_json, expires_at,
          status, developer_notes, report_purpose, error_signature,
          severity, subsystem, stage, event_error_category, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      );
      await stmt.bind(
        reportId,
        createdAt,
        schemaVersion,
        appVersion,
        platform,
        deviceFamily,
        errorCategory,
        payloadJson,
        expiresAt,
        status,
        developerNotes,
        reportPurpose,
        errorSignature,
        classification.severity,
        classification.subsystem,
        classification.stage,
        classification.errorCategory,
        createdAt
      ).run();
    } else {
      this.inMemoryReports.set(reportId, {
        id: this.inMemoryReports.size + 1,
        report_id: reportId,
        created_at: createdAt,
        schema_version: schemaVersion,
        app_version: appVersion,
        platform,
        device_family: deviceFamily,
        error_category: errorCategory,
        payload_json: payloadJson,
        expires_at: expiresAt,
        status,
        developer_notes: developerNotes,
        report_purpose: reportPurpose,
        error_signature: errorSignature,
        severity: classification.severity,
        subsystem: classification.subsystem,
        stage: classification.stage,
        event_error_category: classification.errorCategory,
        updated_at: createdAt,
      });
    }

    await this.recordChange(reportId, 'CREATED');
    if (errorSignature) await this.reopenRegressedIssue(errorSignature, appVersion);

    return {
      reportId,
      createdAt,
      expiresAt,
      status,
      reportPurpose,
      errorSignature,
    };
  }

  /**
   * Возвращает отчёт по его reportId.
   */
  async getReportById(reportId) {
    if (!reportId) return null;

    if (this.db) {
      const stmt = this.db.prepare(
        `SELECT report_id, created_at, schema_version, app_version, platform, device_family,
                error_category, payload_json, expires_at, status, developer_notes, report_purpose, error_signature,
                severity, subsystem, stage, event_error_category, updated_at
         FROM reports WHERE report_id = ?`
      );
      const row = await stmt.bind(reportId).first();
      if (!row) return null;
      const [history, notes, related, relatedIssue, audit] = await Promise.all([
        this.db.prepare('SELECT from_status AS fromStatus, to_status AS toStatus, changed_at AS changedAt, actor, reason FROM report_status_history WHERE report_id = ? ORDER BY id DESC LIMIT 50').bind(reportId).all(),
        this.db.prepare('SELECT note, created_at AS createdAt, actor FROM report_notes WHERE report_id = ? ORDER BY id DESC LIMIT 50').bind(reportId).all(),
        row.error_signature ? this.db.prepare('SELECT COUNT(*) AS count FROM reports WHERE error_signature = ?').bind(row.error_signature).first() : Promise.resolve({ count: 0 }),
        this.getIssue(row.error_signature),
        this.db.prepare('SELECT action, target_type AS targetType, target_id AS targetId, created_at AS createdAt, actor FROM admin_audit WHERE target_id = ? ORDER BY id DESC LIMIT 50').bind(reportId).all(),
      ]);
      return {
        ...row,
        payload: JSON.parse(row.payload_json),
        statusHistory: history.results || [],
        notes: notes.results || [],
        auditHistory: audit.results || [],
        relatedReportsCount: Math.max(0, Number(related?.count || 0) - 1),
        relatedIssue,
      };
    } else {
      const row = this.inMemoryReports.get(reportId);
      if (!row) return null;
      return {
        ...row,
        payload: JSON.parse(row.payload_json),
        statusHistory: this.statusHistory.filter(x => x.reportId === reportId).slice(-50).reverse(),
        notes: this.notes.filter(x => x.reportId === reportId).slice(-50).reverse(),
        auditHistory: this.audit.filter(x => x.target_id === reportId).slice(-50).reverse(),
        relatedReportsCount: row.error_signature ? [...this.inMemoryReports.values()].filter(x => x.error_signature === row.error_signature).length - 1 : 0,
        relatedIssue: await this.getIssue(row.error_signature),
      };
    }
  }

  /**
   * Обновляет статус и/или заметки разработчика для отчёта.
   */
  async updateReport(reportId, { status, developerNotes, reason = '', actor = 'ADMIN', patch = '', commit = '' } = {}) {
    if (!reportId) return null;
    const before = await this.getReportById(reportId);
    if (!before) return null;
    if (status !== undefined) {
      status = normalizeStatus(status);
      if (!STATUSES.includes(status)) throw new RangeError('Invalid status');
    }
    if (developerNotes !== undefined && (typeof developerNotes !== 'string' || developerNotes.length > 4000)) throw new RangeError('Invalid note');
    if (typeof reason !== 'string' || reason.length > 500) throw new RangeError('Invalid reason');
    const statusChanged = status !== undefined && status !== normalizeStatus(before.status);
    const noteChanged = developerNotes !== undefined && developerNotes !== before.developer_notes;
    if (!statusChanged && !noteChanged) return before;
    const now = Date.now();

    if (this.db) {
      const statements = [this.db.prepare('UPDATE reports SET status = ?, developer_notes = ?, updated_at = ? WHERE report_id = ?').bind(status ?? before.status, developerNotes ?? before.developer_notes, now, reportId)];
      if (statusChanged) statements.push(this.db.prepare('INSERT INTO report_status_history (report_id, from_status, to_status, changed_at, actor, reason) VALUES (?, ?, ?, ?, ?, ?)').bind(reportId, normalizeStatus(before.status), status, now, actor, reason));
      if (noteChanged && developerNotes) statements.push(this.db.prepare('INSERT INTO report_notes (report_id, note, created_at, actor) VALUES (?, ?, ?, ?)').bind(reportId, developerNotes, now, actor));
      statements.push(this.db.prepare('INSERT INTO report_changes (report_id, kind, changed_at) VALUES (?, ?, ?)').bind(reportId, statusChanged ? 'STATUS_CHANGED' : 'NOTE_ADDED', now));
      if (statusChanged) statements.push(this.db.prepare('INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES (?, ?, ?, ?, ?)').bind('STATUS_CHANGED', 'REPORT', reportId, now, actor));
      if (noteChanged) statements.push(this.db.prepare('INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES (?, ?, ?, ?, ?)').bind('NOTE_ADDED', 'REPORT', reportId, now, actor));
      await this.db.batch(statements);
      return this.getReportById(reportId);
    } else {
      const existing = this.inMemoryReports.get(reportId);
      if (statusChanged) {
        this.statusHistory.push({ reportId, fromStatus: normalizeStatus(existing.status), toStatus: status, changedAt: now, actor, reason });
        existing.status = status;
        this.audit.push({ action: 'STATUS_CHANGED', target_type: 'REPORT', target_id: reportId, created_at: now, actor });
      }
      if (noteChanged) {
        existing.developer_notes = developerNotes;
        if (developerNotes) this.notes.push({ reportId, note: developerNotes, createdAt: now, actor });
        this.audit.push({ action: 'NOTE_ADDED', target_type: 'REPORT', target_id: reportId, created_at: now, actor });
      }
      existing.updated_at = now;
      this.changes.push({ id: this.changes.length + 1, report_id: reportId, kind: statusChanged ? 'STATUS_CHANGED' : 'NOTE_ADDED', changed_at: now });
      return this.getReportById(reportId);
    }
  }

  async addNote(reportId, note, actor = 'ADMIN') {
    if (typeof note !== 'string' || !note.trim() || note.length > 4000) throw new RangeError('Invalid note');
    const report = await this.getReportById(reportId);
    if (!report) return null;
    return this.updateReport(reportId, { developerNotes: note.trim(), actor });
  }

  /**
   * Удаляет отчёт по ID.
   */
  async deleteReportById(reportId) {
    if (!reportId) return false;

    if (this.db) {
      const stmt = this.db.prepare(`DELETE FROM reports WHERE report_id = ?`);
      const res = await stmt.bind(reportId).run();
      if ((res?.meta?.changes || 0) > 0) await this.db.batch([
        this.db.prepare('DELETE FROM report_status_history WHERE report_id = ?').bind(reportId),
        this.db.prepare('DELETE FROM report_notes WHERE report_id = ?').bind(reportId),
        this.db.prepare('DELETE FROM report_changes WHERE report_id = ?').bind(reportId),
      ]);
      return (res?.meta?.changes || 0) > 0;
    } else {
      const deleted = this.inMemoryReports.delete(reportId);
      if (deleted) {
        this.statusHistory = this.statusHistory.filter(item => item.reportId !== reportId);
        this.notes = this.notes.filter(item => item.reportId !== reportId);
        this.changes = this.changes.filter(item => item.report_id !== reportId);
      }
      return deleted;
    }
  }

  /**
   * Удаляет ВСЕ отчёты и связанные метаданные (Safe Purge).
   */
  async deleteAllReports() {
    if (this.db) {
      const countRow = await this.db.prepare(`SELECT COUNT(*) as count FROM reports`).first();
      const count = countRow?.count || 0;
      await this.db.prepare(`DELETE FROM reports`).run();
      await this.db.batch([
        this.db.prepare('DELETE FROM report_status_history'),
        this.db.prepare('DELETE FROM report_notes'),
        this.db.prepare('DELETE FROM report_changes'),
        this.db.prepare('DELETE FROM issues'),
      ]);
      return count;
    } else {
      const count = this.inMemoryReports.size;
      this.inMemoryReports.clear();
      this.statusHistory = [];
      this.notes = [];
      this.changes = [];
      this.issues.clear();
      return count;
    }
  }

  /**
   * Возвращает список отчётов с фильтрацией и пагинацией.
   */
  async listReports({ platform, appVersion, errorCategory, status, reportPurpose, search, severity, device, signature, hasNotes, hasDuplicates, attention, since, sort = 'newest', cursor, limit = 50, offset = 0 } = {}) {
    const lim = Math.min(Math.max(1, limit), 100);
    const off = Math.max(0, offset);

    if (this.db) {
      let query = `SELECT report_id, created_at, schema_version, app_version, platform,
                          device_family, error_category, expires_at, status, developer_notes,
                          report_purpose, error_signature, severity, subsystem, stage,
                          event_error_category, updated_at,
                          (SELECT COUNT(*) FROM reports related WHERE related.error_signature = reports.error_signature AND related.report_id != reports.report_id AND reports.error_signature != '') AS related_count
                   FROM reports WHERE 1=1`;
      const params = [];

      if (platform) {
        query += ` AND platform = ?`;
        params.push(platform);
      }
      if (appVersion) {
        query += ` AND app_version = ?`;
        params.push(appVersion);
      }
      if (errorCategory) {
        query += ` AND error_category = ?`;
        params.push(errorCategory);
      }
      if (status) {
        const norm = normalizeStatus(status);
        if (norm === 'IN_PROGRESS') {
          query += ` AND status IN ('IN_PROGRESS', 'REVIEWED', 'TRIAGED', 'in_progress', 'reviewed', 'triaged')`;
        } else if (norm === 'KNOWN_ISSUE') {
          query += ` AND status IN ('KNOWN_ISSUE', 'KNOWN', 'known_issue', 'known')`;
        } else if (norm === 'IGNORED_TEST') {
          query += ` AND status IN ('IGNORED_TEST', 'TEST', 'ignored_test', 'test')`;
        } else if (norm === 'NEW') {
          query += ` AND status IN ('NEW', 'new')`;
        } else if (norm === 'RESOLVED') {
          query += ` AND status IN ('RESOLVED', 'resolved')`;
        } else {
          query += ` AND status = ?`;
          params.push(norm);
        }
      }
      if (reportPurpose) {
        query += ` AND report_purpose = ?`;
        params.push(reportPurpose);
      }
      if (search) {
        const term = `%${String(search).replace(/[\\%_]/g, '\\$&')}%`;
        query += ` AND (report_id LIKE ? ESCAPE '\\' OR device_family LIKE ? ESCAPE '\\' OR error_signature LIKE ? ESCAPE '\\' OR app_version LIKE ? ESCAPE '\\' OR error_category LIKE ? ESCAPE '\\' OR event_error_category LIKE ? ESCAPE '\\')`;
        params.push(term, term, term, term, term, term);
      }
      if (severity) { query += ' AND severity = ?'; params.push(severity); }
      if (device) { query += ' AND device_family LIKE ?'; params.push(`%${device}%`); }
      if (signature) { query += ' AND error_signature = ?'; params.push(signature); }
      if (hasNotes === true) query += " AND developer_notes != ''";
      if (hasDuplicates === true) query += " AND error_signature != '' AND (SELECT COUNT(*) FROM reports r2 WHERE r2.error_signature = reports.error_signature) > 1";
      if (attention === true) query += " AND (status IN ('NEW', 'IN_PROGRESS') OR severity IN ('HIGH', 'CRITICAL') OR EXISTS (SELECT 1 FROM issues WHERE issues.signature = reports.error_signature AND issues.reopened_at > 0 AND issues.status = 'IN_PROGRESS'))";
      if (since) { query += ' AND created_at >= ?'; params.push(Number(since)); }
      if (cursor && sort === 'newest') {
        const [created, id] = String(cursor).split(':');
        if (!Number.isSafeInteger(Number(created)) || !id) throw new RangeError('Invalid cursor');
        query += ' AND (created_at < ? OR (created_at = ? AND report_id < ?))';
        params.push(Number(created), Number(created), id);
      }
      const order = { newest: 'created_at DESC, report_id DESC', oldest: 'created_at ASC, report_id ASC', severity: "CASE severity WHEN 'CRITICAL' THEN 5 WHEN 'HIGH' THEN 4 WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 2 ELSE 1 END DESC, created_at DESC", repeats: 'related_count DESC, created_at DESC', status: 'status ASC, created_at DESC', version: 'app_version DESC, created_at DESC' }[sort] || 'created_at DESC, report_id DESC';
      query += ` ORDER BY ${order} LIMIT ? OFFSET ?`;
      params.push(lim, cursor ? 0 : off);

      const stmt = this.db.prepare(query);
      const { results } = await stmt.bind(...params).all();
      return (results || []).map(r => ({
        ...r,
        status: normalizeStatus(r.status),
      }));
    } else {
      let list = Array.from(this.inMemoryReports.values());
      if (platform) list = list.filter((r) => r.platform === platform);
      if (appVersion) list = list.filter((r) => r.app_version === appVersion);
      if (errorCategory) list = list.filter((r) => r.error_category === errorCategory);
      if (status) {
        const norm = normalizeStatus(status);
        list = list.filter((r) => normalizeStatus(r.status) === norm);
      }
      if (reportPurpose) list = list.filter((r) => r.report_purpose === reportPurpose);
      if (search) {
        const s = search.toLowerCase();
        list = list.filter(
          (r) =>
            r.report_id.toLowerCase().includes(s) ||
            r.device_family.toLowerCase().includes(s) ||
            (r.error_signature && r.error_signature.toLowerCase().includes(s)) ||
            (r.event_error_category && r.event_error_category.toLowerCase().includes(s))
        );
      }
      if (severity) list = list.filter(r => r.severity === severity);
      if (device) list = list.filter(r => r.device_family.toLowerCase().includes(device.toLowerCase()));
      if (signature) list = list.filter(r => r.error_signature === signature);
      if (hasNotes === true) list = list.filter(r => Boolean(r.developer_notes));
      if (hasDuplicates === true) list = list.filter(r => r.error_signature && [...this.inMemoryReports.values()].some(other => other.report_id !== r.report_id && other.error_signature === r.error_signature));
      if (attention === true) list = list.filter(r => ['NEW', 'IN_PROGRESS'].includes(normalizeStatus(r.status)) || ['HIGH', 'CRITICAL'].includes(r.severity) || (this.issues.get(r.error_signature)?.reopened_at > 0 && this.issues.get(r.error_signature)?.issue_status === 'IN_PROGRESS'));
      if (since) list = list.filter(r => r.created_at >= Number(since));
      if (cursor && sort === 'newest') {
        const [created, id] = String(cursor).split(':');
        if (!Number.isSafeInteger(Number(created)) || !id) throw new RangeError('Invalid cursor');
        list = list.filter(r => r.created_at < Number(created) || (r.created_at === Number(created) && r.report_id < id));
      }
      const level = { CRITICAL: 5, HIGH: 4, MEDIUM: 3, LOW: 2, INFO: 1 };
      list.sort((a, b) => sort === 'oldest' ? a.created_at - b.created_at || a.report_id.localeCompare(b.report_id)
        : sort === 'severity' ? (level[b.severity] || 0) - (level[a.severity] || 0) || b.created_at - a.created_at
        : sort === 'status' ? normalizeStatus(a.status).localeCompare(normalizeStatus(b.status)) || b.created_at - a.created_at
        : sort === 'version' ? b.app_version.localeCompare(a.app_version) || b.created_at - a.created_at
        : b.created_at - a.created_at || b.report_id.localeCompare(a.report_id));
      return list.slice(off, off + lim).map((r) => ({
        report_id: r.report_id,
        created_at: r.created_at,
        schema_version: r.schema_version,
        app_version: r.app_version,
        platform: r.platform,
        device_family: r.device_family,
        error_category: r.error_category,
        expires_at: r.expires_at,
        status: normalizeStatus(r.status),
        developer_notes: r.developer_notes,
        report_purpose: r.report_purpose,
        error_signature: r.error_signature,
        severity: r.severity,
        subsystem: r.subsystem,
        stage: r.stage,
        event_error_category: r.event_error_category,
        updated_at: r.updated_at,
        related_count: r.error_signature ? [...this.inMemoryReports.values()].filter(other => other.error_signature === r.error_signature && other.report_id !== r.report_id).length : 0,
      }));
    }
  }

  /**
   * Возвращает агрегированные отчёты, сгруппированные по error_signature (Частые проблемы).
   */
  async getGroupedIssues({ limit = 50, offset = 0, status } = {}) {
    const lim = Math.min(Math.max(1, limit), 100);
    const off = Math.max(0, offset);

    if (this.db) {
      let query = `
        SELECT error_signature,
               COUNT(*) as count,
               SUM(CASE WHEN UPPER(status) = 'NEW' THEN 1 ELSE 0 END) as new_count,
               SUM(CASE WHEN UPPER(status) IN ('IN_PROGRESS', 'REVIEWED', 'TRIAGED') THEN 1 ELSE 0 END) as in_progress_count,
               SUM(CASE WHEN UPPER(status) = 'RESOLVED' THEN 1 ELSE 0 END) as resolved_count,
               SUM(CASE WHEN UPPER(status) IN ('KNOWN_ISSUE', 'KNOWN') THEN 1 ELSE 0 END) as known_issue_count,
               SUM(CASE WHEN UPPER(status) IN ('IGNORED_TEST', 'TEST') THEN 1 ELSE 0 END) as ignored_test_count,
               MIN(created_at) as first_seen,
               MAX(created_at) as last_seen,
               MAX(report_id) as sample_report_id,
               MAX(CASE severity WHEN 'CRITICAL' THEN 5 WHEN 'HIGH' THEN 4 WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 2 ELSE 1 END) as severity_rank,
               error_category,
               status
        FROM reports
        WHERE error_signature IS NOT NULL AND error_signature != ''
      `;
      const params = [];
      if (status) {
        const norm = normalizeStatus(status);
        if (norm === 'IN_PROGRESS') {
          query += ` AND UPPER(status) IN ('IN_PROGRESS', 'REVIEWED', 'TRIAGED')`;
        } else if (norm === 'KNOWN_ISSUE') {
          query += ` AND UPPER(status) IN ('KNOWN_ISSUE', 'KNOWN')`;
        } else if (norm === 'IGNORED_TEST') {
          query += ` AND UPPER(status) IN ('IGNORED_TEST', 'TEST')`;
        } else {
          query += ` AND UPPER(status) = ?`;
          params.push(norm);
        }
      }
      query += ` GROUP BY error_signature ORDER BY count DESC, last_seen DESC LIMIT ? OFFSET ?`;
      params.push(lim, off);

      const stmt = this.db.prepare(query);
      const { results } = await stmt.bind(...params).all();
      return Promise.all((results || []).map(async r => ({
        ...r,
        status: normalizeStatus(r.status),
        new_count: Number(r.new_count) || 0,
        in_progress_count: Number(r.in_progress_count) || 0,
        resolved_count: Number(r.resolved_count) || 0,
        known_issue_count: Number(r.known_issue_count) || 0,
        ignored_test_count: Number(r.ignored_test_count) || 0,
        severity: ({ 5: 'CRITICAL', 4: 'HIGH', 3: 'MEDIUM', 2: 'LOW', 1: 'INFO' })[r.severity_rank] || 'INFO',
        affected_versions: await this.getAffectedVersions(r.error_signature),
        ...await this.getIssue(r.error_signature),
      })));
    } else {
      const groups = new Map();
      for (const r of this.inMemoryReports.values()) {
        const sig = r.error_signature;
        if (!sig) continue;
        const normStatus = normalizeStatus(r.status);
        if (status && normStatus !== normalizeStatus(status)) continue;

        if (!groups.has(sig)) {
          groups.set(sig, {
            error_signature: sig,
            count: 0,
            new_count: 0,
            in_progress_count: 0,
            resolved_count: 0,
            known_issue_count: 0,
            ignored_test_count: 0,
            first_seen: r.created_at,
            last_seen: r.created_at,
            sample_report_id: r.report_id,
            error_category: r.error_category,
            status: normStatus,
            severity: r.severity || 'INFO',
          });
        }
        const g = groups.get(sig);
        g.count++;
        if (normStatus === 'NEW') g.new_count++;
        else if (normStatus === 'IN_PROGRESS') g.in_progress_count++;
        else if (normStatus === 'RESOLVED') g.resolved_count++;
        else if (normStatus === 'KNOWN_ISSUE') g.known_issue_count++;
        else if (normStatus === 'IGNORED_TEST') g.ignored_test_count++;

        if (r.created_at < g.first_seen) g.first_seen = r.created_at;
        if (({ INFO: 1, LOW: 2, MEDIUM: 3, HIGH: 4, CRITICAL: 5 })[r.severity] > ({ INFO: 1, LOW: 2, MEDIUM: 3, HIGH: 4, CRITICAL: 5 })[g.severity]) g.severity = r.severity;
        if (r.created_at > g.last_seen) {
          g.last_seen = r.created_at;
          g.sample_report_id = r.report_id;
          g.status = normStatus;
        }
      }

      const list = Array.from(groups.values()).sort((a, b) => b.count - a.count || b.last_seen - a.last_seen);
      return Promise.all(list.slice(off, off + lim).map(async r => ({ ...r, affected_versions: await this.getAffectedVersions(r.error_signature), ...await this.getIssue(r.error_signature) })));
    }
  }

  async getAffectedVersions(signature) {
    if (this.db) {
      const { results } = await this.db.prepare('SELECT DISTINCT app_version FROM reports WHERE error_signature = ? ORDER BY app_version DESC LIMIT 12').bind(signature).all();
      return (results || []).map(row => row.app_version);
    }
    return [...new Set([...this.inMemoryReports.values()].filter(r => r.error_signature === signature).map(r => r.app_version))].sort().reverse();
  }

  async getIssue(signature) {
    const blank = { issue_status: 'NEW', fixed_in_version: '', fix_patch: '', fix_commit: '', title: '', reopened_at: 0 };
    if (!signature) return blank;
    if (this.db) {
      const row = await this.db.prepare('SELECT status AS issue_status, fixed_in_version, fix_patch, fix_commit, title, reopened_at FROM issues WHERE signature = ?').bind(signature).first();
      return row || blank;
    }
    return this.issues.get(signature) || blank;
  }

  async updateIssue(signature, patch, actor = 'ADMIN') {
    if (!signature || typeof signature !== 'string' || signature.length > 256) throw new RangeError('Invalid signature');
    const allowed = ['status', 'fixedInVersion', 'fixPatch', 'fixCommit', 'title', 'actor'];
    if (!patch || typeof patch !== 'object' || Object.keys(patch).some(key => !allowed.includes(key))) throw new RangeError('Invalid issue patch');
    const effectiveActor = patch.actor || actor;
    const normStatus = patch.status !== undefined ? normalizeStatus(patch.status) : undefined;
    if (normStatus !== undefined && !STATUSES.includes(normStatus)) throw new RangeError('Invalid issue status');
    for (const key of allowed.filter(key => key !== 'status' && key !== 'actor')) if (patch[key] !== undefined && (typeof patch[key] !== 'string' || patch[key].length > 256)) throw new RangeError(`Invalid ${key}`);
    const before = await this.getIssue(signature);
    const next = {
      issue_status: normStatus ?? before.issue_status,
      fixed_in_version: patch.fixedInVersion ?? before.fixed_in_version,
      fix_patch: patch.fixPatch ?? before.fix_patch,
      fix_commit: patch.fixCommit ?? before.fix_commit,
      title: patch.title ?? before.title,
      reopened_at: before.reopened_at || 0,
    };
    const now = Date.now();
    const action = (next.issue_status === 'RESOLVED' || next.issue_status === 'CLOSED') ? 'ISSUE_RESOLVED' : 'ISSUE_LINKED';
    if (this.db) {
      await this.db.batch([
        this.db.prepare('INSERT INTO issues (signature, status, fixed_in_version, fix_patch, fix_commit, title, reopened_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(signature) DO UPDATE SET status=excluded.status, fixed_in_version=excluded.fixed_in_version, fix_patch=excluded.fix_patch, fix_commit=excluded.fix_commit, title=excluded.title, updated_at=excluded.updated_at').bind(signature, next.issue_status, next.fixed_in_version, next.fix_patch, next.fix_commit, next.title, next.reopened_at, now),
        this.db.prepare('INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES (?, ?, ?, ?, ?)').bind(action, 'ISSUE', signature, now, effectiveActor),
      ]);
    } else {
      this.issues.set(signature, next);
      this.audit.push({ action, target_type: 'ISSUE', target_id: signature, created_at: now, actor: effectiveActor });
    }
    return next;
  }

  async reopenRegressedIssue(signature, appVersion) {
    const issue = await this.getIssue(signature);
    if (issue.issue_status !== 'RESOLVED' || !issue.fixed_in_version || compareVoxVersions(appVersion, issue.fixed_in_version) !== 0 && compareVoxVersions(appVersion, issue.fixed_in_version) !== 1) return false;
    const now = Date.now();
    if (this.db) {
      await this.db.batch([
        this.db.prepare("UPDATE issues SET status = 'IN_PROGRESS', reopened_at = ?, updated_at = ? WHERE signature = ?").bind(now, now, signature),
        this.db.prepare('INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES (?, ?, ?, ?, ?)').bind('ISSUE_REOPENED', 'ISSUE', signature, now, 'SYSTEM'),
      ]);
    } else {
      this.issues.set(signature, { ...issue, issue_status: 'IN_PROGRESS', reopened_at: now });
      this.audit.push({ action: 'ISSUE_REOPENED', target_type: 'ISSUE', target_id: signature, created_at: now, actor: 'SYSTEM' });
    }
    return true;
  }

  async listChanges(cursor = 0, limit = 50) {
    const start = Number(cursor);
    if (!Number.isSafeInteger(start) || start < 0) throw new RangeError('Invalid cursor');
    const size = Math.min(Math.max(1, Number(limit) || 50), 100);
    if (this.db) {
      const { results } = await this.db.prepare('SELECT id, report_id AS reportId, kind, changed_at AS changedAt FROM report_changes WHERE id > ? ORDER BY id ASC LIMIT ?').bind(start, size).all();
      const changes = results || [];
      return { changes, nextCursor: changes.at(-1)?.id || start };
    }
    const changes = this.changes.filter(c => c.id > start).slice(0, size).map(c => ({ id: c.id, reportId: c.report_id, kind: c.kind, changedAt: c.changed_at }));
    return { changes, nextCursor: changes.at(-1)?.id || start };
  }

  /**
   * Возвращает агрегированную статистику для панели управления.
   */
  async getStats(now = Date.now()) {
    const oneDayAgo = now - 24 * 60 * 60 * 1000;
    const sevenDaysAgo = now - 7 * 24 * 60 * 60 * 1000;

    if (this.db) {
      const [totalRow, dayRow, weekRow, countsRes, platformsRes, versionsRes, categoriesRes, topIssuesRes] = await Promise.all([
        this.db.prepare(`SELECT COUNT(*) as count FROM reports`).first(),
        this.db.prepare(`SELECT COUNT(*) as count FROM reports WHERE created_at >= ?`).bind(oneDayAgo).first(),
        this.db.prepare(`SELECT COUNT(*) as count FROM reports WHERE created_at >= ?`).bind(sevenDaysAgo).first(),
        this.db.prepare(`
          SELECT 
            SUM(CASE WHEN UPPER(status) = 'NEW' THEN 1 ELSE 0 END) as count_new,
            SUM(CASE WHEN UPPER(status) IN ('IN_PROGRESS', 'REVIEWED', 'TRIAGED') THEN 1 ELSE 0 END) as count_in_progress,
            SUM(CASE WHEN UPPER(status) = 'RESOLVED' THEN 1 ELSE 0 END) as count_resolved,
            SUM(CASE WHEN UPPER(status) IN ('KNOWN_ISSUE', 'KNOWN') THEN 1 ELSE 0 END) as count_known_issue,
            SUM(CASE WHEN UPPER(status) IN ('IGNORED_TEST', 'TEST') THEN 1 ELSE 0 END) as count_ignored_test
          FROM reports
        `).first(),
        this.db.prepare(`SELECT platform, COUNT(*) as count FROM reports GROUP BY platform ORDER BY count DESC`).all(),
        this.db.prepare(`SELECT app_version, COUNT(*) as count FROM reports GROUP BY app_version ORDER BY count DESC LIMIT 8`).all(),
        this.db.prepare(`SELECT error_category, COUNT(*) as count FROM reports WHERE error_category IS NOT NULL AND error_category != '' GROUP BY error_category ORDER BY count DESC LIMIT 8`).all(),
        this.db.prepare(`SELECT error_signature, COUNT(*) as count FROM reports WHERE error_signature IS NOT NULL AND error_signature != '' GROUP BY error_signature ORDER BY count DESC LIMIT 5`).all(),
      ]);

      const totalReports = totalRow?.count || 0;
      const reports24h = dayRow?.count || 0;
      const reports7d = weekRow?.count || 0;
      const newCount = Number(countsRes?.count_new) || 0;
      const inProgressCount = Number(countsRes?.count_in_progress) || 0;
      const resolvedCount = Number(countsRes?.count_resolved) || 0;
      const knownIssueCount = Number(countsRes?.count_known_issue) || 0;
      const ignoredTestCount = Number(countsRes?.count_ignored_test) || 0;
      const activeIssuesCount = newCount + inProgressCount + knownIssueCount;

      const statusBreakdown = [
        { status: 'NEW', label: 'Новый', count: newCount },
        { status: 'IN_PROGRESS', label: 'В работе', count: inProgressCount },
        { status: 'RESOLVED', label: 'Решено', count: resolvedCount },
        { status: 'KNOWN_ISSUE', label: 'Известная проблема', count: knownIssueCount },
        { status: 'IGNORED_TEST', label: 'Тест / игнор', count: ignoredTestCount },
      ];

      return {
        totalReports,
        reports24h,
        reports7d,
        newCount,
        inProgressCount,
        resolvedCount,
        knownIssueCount,
        ignoredTestCount,
        activeIssuesCount,
        statusBreakdown,
        platformBreakdown: platformsRes?.results || [],
        versionDistribution: versionsRes?.results || [],
        topCategories: categoriesRes?.results || [],
        topIssues: topIssuesRes?.results || [],
      };
    } else {
      const all = Array.from(this.inMemoryReports.values());
      const totalReports = all.length;
      const reports24h = all.filter((r) => r.created_at >= oneDayAgo).length;
      const reports7d = all.filter((r) => r.created_at >= sevenDaysAgo).length;

      let newCount = 0;
      let inProgressCount = 0;
      let resolvedCount = 0;
      let knownIssueCount = 0;
      let ignoredTestCount = 0;

      const platformsMap = new Map();
      const versionsMap = new Map();
      const categoriesMap = new Map();
      const issuesMap = new Map();

      for (const r of all) {
        const normStatus = normalizeStatus(r.status);
        if (normStatus === 'NEW') newCount++;
        else if (normStatus === 'IN_PROGRESS') inProgressCount++;
        else if (normStatus === 'RESOLVED') resolvedCount++;
        else if (normStatus === 'KNOWN_ISSUE') knownIssueCount++;
        else if (normStatus === 'IGNORED_TEST') ignoredTestCount++;

        platformsMap.set(r.platform, (platformsMap.get(r.platform) || 0) + 1);
        versionsMap.set(r.app_version, (versionsMap.get(r.app_version) || 0) + 1);
        if (r.error_category) {
          categoriesMap.set(r.error_category, (categoriesMap.get(r.error_category) || 0) + 1);
        }
        if (r.error_signature) {
          issuesMap.set(r.error_signature, (issuesMap.get(r.error_signature) || 0) + 1);
        }
      }

      const activeIssuesCount = newCount + inProgressCount + knownIssueCount;
      const statusBreakdown = [
        { status: 'NEW', label: 'Новый', count: newCount },
        { status: 'IN_PROGRESS', label: 'В работе', count: inProgressCount },
        { status: 'RESOLVED', label: 'Решено', count: resolvedCount },
        { status: 'KNOWN_ISSUE', label: 'Известная проблема', count: knownIssueCount },
        { status: 'IGNORED_TEST', label: 'Тест / игнор', count: ignoredTestCount },
      ];

      return {
        totalReports,
        reports24h,
        reports7d,
        newCount,
        inProgressCount,
        resolvedCount,
        knownIssueCount,
        ignoredTestCount,
        activeIssuesCount,
        statusBreakdown,
        platformBreakdown: Array.from(platformsMap.entries()).map(([platform, count]) => ({ platform, count })),
        versionDistribution: Array.from(versionsMap.entries()).map(([app_version, count]) => ({ app_version, count })).slice(0, 8),
        topCategories: Array.from(categoriesMap.entries()).map(([error_category, count]) => ({ error_category, count })).slice(0, 8),
        topIssues: Array.from(issuesMap.entries()).map(([error_signature, count]) => ({ error_signature, count })).slice(0, 5),
      };
    }
  }

  /**
   * Удаляет истёкшие отчёты (старше 30 дней).
   */
  async purgeExpiredReports(now = Date.now()) {
    if (this.db) {
      const stmt = this.db.prepare(`DELETE FROM reports WHERE expires_at < ?`);
      const res = await stmt.bind(now).run();
      await this.db.batch([
        this.db.prepare('DELETE FROM report_status_history WHERE NOT EXISTS (SELECT 1 FROM reports WHERE reports.report_id = report_status_history.report_id)'),
        this.db.prepare('DELETE FROM report_notes WHERE NOT EXISTS (SELECT 1 FROM reports WHERE reports.report_id = report_notes.report_id)'),
        this.db.prepare('DELETE FROM issues WHERE NOT EXISTS (SELECT 1 FROM reports WHERE reports.error_signature = issues.signature)'),
        this.db.prepare('DELETE FROM report_changes WHERE changed_at < ?').bind(now - DEFAULT_RETENTION_MS),
        this.db.prepare('DELETE FROM admin_audit WHERE created_at < ?').bind(now - DEFAULT_RETENTION_MS),
      ]);
      return res?.meta?.changes || 0;
    } else {
      let deleted = 0;
      for (const [id, rep] of this.inMemoryReports.entries()) {
        if (rep.expires_at < now) {
          this.inMemoryReports.delete(id);
          deleted++;
        }
      }
      this.statusHistory = this.statusHistory.filter(item => this.inMemoryReports.has(item.reportId));
      this.notes = this.notes.filter(item => this.inMemoryReports.has(item.reportId));
      this.changes = this.changes.filter(item => item.changed_at >= now - DEFAULT_RETENTION_MS);
      this.audit = this.audit.filter(item => item.created_at >= now - DEFAULT_RETENTION_MS);
      for (const signature of this.issues.keys()) if (![...this.inMemoryReports.values()].some(item => item.error_signature === signature)) this.issues.delete(signature);
      return deleted;
    }
  }
}
