import { classifyReport, compareVoxVersions, technicalSummary, normalizePlatformTag, isSyntheticTestReport, STATUSES } from './triage.mjs';

export const DEFAULT_RETENTION_DAYS = 30;
export const DEFAULT_RETENTION_MS = DEFAULT_RETENTION_DAYS * 24 * 60 * 60 * 1000;

export const PRIMARY_STATUSES = [
  'NEW',
  'INVESTIGATING',
  'ROOT_CAUSE_FOUND',
  'FIX_IMPLEMENTED',
  'TESTING',
  'FIXED_AWAITING_VERIFICATION',
  'VERIFIED',
  'REGRESSION',
  'KNOWN_LIMITATION',
  'IGNORED',
];

export const CANONICAL_STATUSES = [
  ...PRIMARY_STATUSES,
  'TRIAGED',
  'IN_PROGRESS',
  'NEEDS_INFO',
  'DUPLICATE',
  'FIXED_PENDING_VERIFY',
  'FIXED_PENDING_VERIFICATION',
  'FIXED_PENDING_VALIDATION',
  'KNOWN_ISSUE',
  'RESOLVED',
  'CLOSED',
  'IGNORED_TEST',
  'WONT_FIX',
];

export const STATUS_LABELS = {
  NEW: 'Новый',
  INVESTIGATING: 'В работе',
  ROOT_CAUSE_FOUND: 'Причина найдена',
  FIX_IMPLEMENTED: 'Исправлено в коде',
  TESTING: 'На проверке',
  FIXED_AWAITING_VERIFICATION: 'Исправлено — ждёт проверки',
  VERIFIED: 'Исправлено — подтверждено',
  REGRESSION: 'Повторная ошибка',
  KNOWN_LIMITATION: 'Известное ограничение',
  IGNORED: 'Игнорируется',
  // Обратная совместимость с легаси-статусами
  IN_PROGRESS: 'В работе',
  TRIAGED: 'Разобран',
  NEEDS_INFO: 'Нужны данные',
  DUPLICATE: 'Дубликат',
  FIXED_PENDING_VERIFY: 'Исправлено — ждёт проверки',
  FIXED_PENDING_VERIFICATION: 'Исправлено — ждёт проверки',
  FIXED_PENDING_VALIDATION: 'Исправлено — ждёт проверки',
  KNOWN_ISSUE: 'Известная проблема',
  RESOLVED: 'Решено',
  CLOSED: 'Закрыто',
  IGNORED_TEST: 'Тест / игнор',
  WONT_FIX: 'Не будет исправлено',
};

export const STATUS_COLORS = {
  NEW: '#6b7280',
  INVESTIGATING: '#3b82f6',
  ROOT_CAUSE_FOUND: '#8b5cf6',
  FIX_IMPLEMENTED: '#14b8a6',
  TESTING: '#f59e0b',
  FIXED_AWAITING_VERIFICATION: '#06b6d4',
  VERIFIED: '#10b981',
  REGRESSION: '#ef4444',
  KNOWN_LIMITATION: '#64748b',
  IGNORED: '#475569',
  // Легаси алиасы
  IN_PROGRESS: '#3b82f6',
  TRIAGED: '#3b82f6',
  FIXED_PENDING_VERIFY: '#06b6d4',
  FIXED_PENDING_VERIFICATION: '#06b6d4',
  FIXED_PENDING_VALIDATION: '#06b6d4',
  RESOLVED: '#10b981',
  CLOSED: '#10b981',
  KNOWN_ISSUE: '#64748b',
  IGNORED_TEST: '#475569',
  WONT_FIX: '#64748b',
  DUPLICATE: '#64748b',
};

export const ALLOWED_STATUS_TRANSITIONS = {
  NEW: new Set(['NEW', 'INVESTIGATING', 'IN_PROGRESS', 'ROOT_CAUSE_FOUND', 'FIX_IMPLEMENTED', 'TRIAGED', 'DUPLICATE', 'FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFY', 'FIXED_PENDING_VERIFICATION', 'FIXED_PENDING_VALIDATION', 'RESOLVED', 'VERIFIED', 'CLOSED', 'IGNORED', 'IGNORED_TEST', 'KNOWN_LIMITATION', 'KNOWN_ISSUE', 'NEEDS_INFO', 'WONT_FIX']),
  INVESTIGATING: new Set(['INVESTIGATING', 'IN_PROGRESS', 'ROOT_CAUSE_FOUND', 'FIX_IMPLEMENTED', 'TESTING', 'FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFY', 'FIXED_PENDING_VERIFICATION', 'FIXED_PENDING_VALIDATION', 'VERIFIED', 'RESOLVED', 'CLOSED', 'DUPLICATE', 'IGNORED', 'IGNORED_TEST', 'NEEDS_INFO', 'KNOWN_LIMITATION', 'KNOWN_ISSUE', 'WONT_FIX', 'NEW', 'TRIAGED']),
  IN_PROGRESS: new Set(['INVESTIGATING', 'IN_PROGRESS', 'ROOT_CAUSE_FOUND', 'FIX_IMPLEMENTED', 'TESTING', 'FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFY', 'FIXED_PENDING_VERIFICATION', 'FIXED_PENDING_VALIDATION', 'VERIFIED', 'RESOLVED', 'CLOSED', 'DUPLICATE', 'IGNORED', 'IGNORED_TEST', 'NEEDS_INFO', 'KNOWN_LIMITATION', 'KNOWN_ISSUE', 'WONT_FIX', 'NEW', 'TRIAGED']),
  ROOT_CAUSE_FOUND: new Set(['ROOT_CAUSE_FOUND', 'FIX_IMPLEMENTED', 'TESTING', 'FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFY', 'FIXED_PENDING_VERIFICATION', 'INVESTIGATING', 'IN_PROGRESS', 'KNOWN_LIMITATION', 'KNOWN_ISSUE', 'IGNORED', 'IGNORED_TEST']),
  FIX_IMPLEMENTED: new Set(['FIX_IMPLEMENTED', 'TESTING', 'FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFY', 'FIXED_PENDING_VERIFICATION', 'INVESTIGATING', 'IN_PROGRESS', 'REGRESSION']),
  TESTING: new Set(['TESTING', 'FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFY', 'FIXED_PENDING_VERIFICATION', 'VERIFIED', 'RESOLVED', 'REGRESSION', 'INVESTIGATING', 'IN_PROGRESS', 'FIX_IMPLEMENTED']),
  FIXED_AWAITING_VERIFICATION: new Set(['FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFY', 'FIXED_PENDING_VERIFICATION', 'FIXED_PENDING_VALIDATION', 'VERIFIED', 'RESOLVED', 'CLOSED', 'REGRESSION', 'INVESTIGATING', 'IN_PROGRESS', 'TESTING', 'IGNORED', 'IGNORED_TEST', 'KNOWN_LIMITATION', 'KNOWN_ISSUE', 'DUPLICATE']),
  FIXED_PENDING_VERIFY: new Set(['FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFY', 'FIXED_PENDING_VERIFICATION', 'FIXED_PENDING_VALIDATION', 'VERIFIED', 'RESOLVED', 'CLOSED', 'REGRESSION', 'INVESTIGATING', 'IN_PROGRESS', 'TESTING', 'IGNORED', 'IGNORED_TEST', 'KNOWN_LIMITATION', 'KNOWN_ISSUE', 'DUPLICATE']),
  FIXED_PENDING_VERIFICATION: new Set(['FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFY', 'FIXED_PENDING_VERIFICATION', 'FIXED_PENDING_VALIDATION', 'VERIFIED', 'RESOLVED', 'CLOSED', 'REGRESSION', 'INVESTIGATING', 'IN_PROGRESS', 'TESTING', 'IGNORED', 'IGNORED_TEST', 'KNOWN_LIMITATION', 'KNOWN_ISSUE', 'DUPLICATE']),
  FIXED_PENDING_VALIDATION: new Set(['FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFY', 'FIXED_PENDING_VERIFICATION', 'FIXED_PENDING_VALIDATION', 'VERIFIED', 'RESOLVED', 'CLOSED', 'REGRESSION', 'INVESTIGATING', 'IN_PROGRESS', 'TESTING', 'IGNORED', 'IGNORED_TEST', 'KNOWN_LIMITATION', 'KNOWN_ISSUE', 'DUPLICATE']),
  VERIFIED: new Set(['VERIFIED', 'RESOLVED', 'CLOSED', 'REGRESSION', 'INVESTIGATING', 'IN_PROGRESS', 'IGNORED', 'IGNORED_TEST']),
  RESOLVED: new Set(['VERIFIED', 'RESOLVED', 'CLOSED', 'REGRESSION', 'INVESTIGATING', 'IN_PROGRESS', 'IGNORED', 'IGNORED_TEST']),
  CLOSED: new Set(['VERIFIED', 'RESOLVED', 'CLOSED', 'REGRESSION', 'INVESTIGATING', 'IN_PROGRESS', 'IGNORED', 'IGNORED_TEST']),
  REGRESSION: new Set(['REGRESSION', 'INVESTIGATING', 'IN_PROGRESS', 'ROOT_CAUSE_FOUND', 'FIX_IMPLEMENTED', 'TESTING']),
  TRIAGED: new Set(['INVESTIGATING', 'IN_PROGRESS', 'ROOT_CAUSE_FOUND', 'FIX_IMPLEMENTED', 'FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFY', 'FIXED_PENDING_VERIFICATION', 'FIXED_PENDING_VALIDATION', 'VERIFIED', 'RESOLVED', 'CLOSED', 'DUPLICATE', 'IGNORED', 'IGNORED_TEST', 'NEEDS_INFO', 'KNOWN_LIMITATION', 'KNOWN_ISSUE', 'WONT_FIX', 'NEW']),
  DUPLICATE: new Set(['DUPLICATE', 'INVESTIGATING', 'IN_PROGRESS', 'IGNORED', 'IGNORED_TEST', 'NEW', 'VERIFIED', 'RESOLVED']),
  IGNORED: new Set(['IGNORED', 'IGNORED_TEST', 'INVESTIGATING', 'IN_PROGRESS', 'NEW', 'WONT_FIX', 'KNOWN_LIMITATION']),
  IGNORED_TEST: new Set(['IGNORED', 'IGNORED_TEST', 'INVESTIGATING', 'IN_PROGRESS', 'NEW', 'WONT_FIX', 'KNOWN_LIMITATION']),
  NEEDS_INFO: new Set(['NEEDS_INFO', 'INVESTIGATING', 'IN_PROGRESS', 'VERIFIED', 'RESOLVED', 'CLOSED', 'IGNORED', 'IGNORED_TEST', 'NEW']),
  KNOWN_LIMITATION: new Set(['KNOWN_LIMITATION', 'KNOWN_ISSUE', 'INVESTIGATING', 'IN_PROGRESS', 'FIX_IMPLEMENTED', 'FIXED_AWAITING_VERIFICATION', 'VERIFIED', 'RESOLVED', 'CLOSED', 'IGNORED', 'IGNORED_TEST']),
  KNOWN_ISSUE: new Set(['KNOWN_LIMITATION', 'KNOWN_ISSUE', 'INVESTIGATING', 'IN_PROGRESS', 'FIX_IMPLEMENTED', 'FIXED_AWAITING_VERIFICATION', 'VERIFIED', 'RESOLVED', 'CLOSED', 'IGNORED', 'IGNORED_TEST']),
  WONT_FIX: new Set(['WONT_FIX', 'INVESTIGATING', 'IN_PROGRESS', 'NEW', 'IGNORED', 'IGNORED_TEST', 'KNOWN_LIMITATION']),
};

export function isValidStatusTransition(fromStatus, toStatus) {
  if (!fromStatus || !toStatus) return false;
  const from = normalizeStatus(fromStatus);
  const to = normalizeStatus(toStatus);
  if (!from || !to) return false;
  if (from === to) return true;
  const allowed = ALLOWED_STATUS_TRANSITIONS[from];
  return allowed ? allowed.has(to) : false;
}

export function normalizeStatus(rawStatus) {
  if (!rawStatus) return 'NEW';
  const s = String(rawStatus).toUpperCase().trim();
  if (s === 'INVESTIGATING') return 'INVESTIGATING';
  if (s === 'ROOT_CAUSE_FOUND') return 'ROOT_CAUSE_FOUND';
  if (s === 'FIX_IMPLEMENTED') return 'FIX_IMPLEMENTED';
  if (s === 'TESTING') return 'TESTING';
  if (s === 'FIXED_AWAITING_VERIFICATION') return 'FIXED_AWAITING_VERIFICATION';
  if (s === 'VERIFIED') return 'VERIFIED';
  if (s === 'REGRESSION') return 'REGRESSION';
  if (s === 'KNOWN_LIMITATION') return 'KNOWN_LIMITATION';
  if (s === 'IGNORED') return 'IGNORED';

  // Легаси-совместимость
  if (s === 'DUPLICATE' || s === 'DUP') return 'DUPLICATE';
  if (s === 'FIXED_PENDING_VERIFY') return 'FIXED_PENDING_VERIFY';
  if (s === 'FIXED_PENDING_VERIFICATION' || s === 'PENDING_VERIFICATION' || s === 'FIXED') return 'FIXED_PENDING_VERIFICATION';
  if (s === 'FIXED_PENDING_VALIDATION') return 'FIXED_PENDING_VERIFICATION';
  if (s === 'TRIAGED') return 'TRIAGED';
  if (s === 'REVIEWED' || s === 'IN_PROGRESS') return 'IN_PROGRESS';
  if (s === 'NEEDS_INFO' || s === 'INFO_NEEDED') return 'NEEDS_INFO';
  if (s === 'CLOSED') return 'CLOSED';
  if (s === 'RESOLVED') return 'RESOLVED';
  if (s === 'KNOWN' || s === 'KNOWN_ISSUE') return 'KNOWN_ISSUE';
  if (s === 'IGNORED_TEST' || s === 'TEST') return 'IGNORED_TEST';
  if (s === 'WONT_FIX' || s === 'WONTFIX') return 'WONT_FIX';
  if (s === 'NEW') return 'NEW';
  return s;
}

export function toCanonicalStatus(rawStatus) {
  if (!rawStatus) return 'NEW';
  const s = String(rawStatus).toUpperCase().trim();
  if (s === 'NEW') return 'NEW';
  if (['INVESTIGATING', 'IN_PROGRESS', 'TRIAGED', 'REVIEWED', 'NEEDS_INFO'].includes(s)) return 'INVESTIGATING';
  if (s === 'ROOT_CAUSE_FOUND') return 'ROOT_CAUSE_FOUND';
  if (s === 'FIX_IMPLEMENTED') return 'FIX_IMPLEMENTED';
  if (s === 'TESTING') return 'TESTING';
  if (['FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFY', 'FIXED_PENDING_VERIFICATION', 'FIXED_PENDING_VALIDATION', 'PENDING_VERIFICATION', 'FIXED'].includes(s)) return 'FIXED_AWAITING_VERIFICATION';
  if (['VERIFIED', 'RESOLVED', 'CLOSED'].includes(s)) return 'VERIFIED';
  if (['REGRESSION', 'REOPENED'].includes(s)) return 'REGRESSION';
  if (['KNOWN_LIMITATION', 'KNOWN_ISSUE', 'KNOWN'].includes(s)) return 'KNOWN_LIMITATION';
  if (['IGNORED', 'IGNORED_TEST', 'TEST', 'WONT_FIX', 'WONTFIX', 'DUPLICATE', 'DUP'].includes(s)) return 'IGNORED';
  return 'NEW';
}

export const CANONICAL_INCIDENT_STATUSES = [
  ...PRIMARY_STATUSES,
];

export function toCanonicalIncidentStatus(rawStatus) {
  return toCanonicalStatus(rawStatus);
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
    this.installations = new Map();
    this.heartbeats = new Map();
    this.dailyAnalytics = new Map();
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
    const installationId = report.installationId || report.installation_id || null;
    const schemaVersion = report.schema || 'vox-diagnostic-report-v2';
    const appVersion = report.appVersion || 'unknown';
    const platform = report.platform || 'unknown';
    const deviceFamily = `${report.manufacturer || ''} ${report.model || ''}`.trim() || 'unknown';
    const classification = classifyReport(report);
    const errorCategory = report.errorCategory || classification.errorCategory || (classification.primaryEvent ? (classification.primaryEvent.category || classification.primaryEvent.errorCategory) : null) || null;
    const isSynth = isSyntheticTestReport({ ...report, reportId, deviceFamily });
    const reportPurpose = isSynth ? 'TEST' : (report.reportPurpose || report.purpose || 'USER');
    const rawStatus = report.status || (isSynth ? 'IGNORED_TEST' : 'NEW');
    const status = normalizeStatus(rawStatus);
    const developerNotes = report.developerNotes || report.developer_notes || '';
    const platformTag = normalizePlatformTag(report);
    const errorSignature = report.errorSignature || report.error_signature || classification.signature || (errorCategory ? `${errorCategory}|UNKNOWN|${platformTag}` : (reportPurpose === 'AUTO_BETA' ? `SYSTEM|AUTO_BETA_ROUTINE|${platformTag}` : `GENERAL|USER_REPORT|${platformTag}`));
    const payloadJson = JSON.stringify({ ...report, errorCategory, errorSignature, structuredEvents: report.structuredEvents || classification.events, technicalSummary: report.technicalSummary || technicalSummary(report) });

    if (this.db) {
      const stmt = this.db.prepare(
        `INSERT INTO reports (
          report_id, created_at, schema_version, app_version, platform,
          device_family, error_category, payload_json, expires_at,
          status, developer_notes, report_purpose, error_signature,
          severity, subsystem, stage, event_error_category, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(report_id) DO UPDATE SET
          payload_json = excluded.payload_json,
          updated_at = excluded.updated_at`
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
    if (installationId) {
      await this.recordInstallationActivity({
        installationId,
        manufacturer: report.manufacturer || '',
        model: report.model || '',
        platform,
        osVersion: report.osVersion || '',
        sdkInt: report.sdkInt || 0,
        appVersion,
        timestamp: createdAt,
        isError: Boolean(errorCategory),
        isHeartbeat: false,
        reportId,
        status,
      });
    }
    if (errorSignature && classification.reportActiveIncident !== false && !isSynth && reportPurpose !== 'TEST') {
      await this.reopenRegressedIssue(errorSignature, appVersion);
    }

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
      if (!CANONICAL_STATUSES.includes(status) && !STATUSES.includes(status)) {
        throw new RangeError(`Invalid status: ${status}`);
      }
      const currentNorm = normalizeStatus(before.status);
      if (currentNorm && currentNorm !== status && !isValidStatusTransition(currentNorm, status)) {
        throw new RangeError(`Invalid status transition from ${currentNorm} to ${status}`);
      }
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

  async recordTelegramDelivery(reportId, deliveryResult = {}) {
    if (!reportId) return;
    const now = Date.now();
    const meta = {
      decision: deliveryResult.decision || 'UNKNOWN',
      notifiedAt: deliveryResult.notifiedAt || now,
      messageId: deliveryResult.messageId || null,
      repeatCount: deliveryResult.repeatCount || 0,
      error: deliveryResult.error || null,
      delivery_state: ['SENT_NEW', 'SENT_REGRESSION', 'SENT_AGGREGATE'].includes(deliveryResult.decision)
        ? 'SENT'
        : ['SUPPRESSED_DUPLICATE', 'SUPPRESSED_HISTORICAL', 'SUPPRESSED_TEST', 'DISABLED'].includes(deliveryResult.decision)
          ? 'SKIPPED'
          : deliveryResult.decision === 'FAILED'
            ? 'FAILED'
            : 'PENDING',
      attempt_count: deliveryResult.attemptCount || (deliveryResult.error ? 3 : 1),
      last_error_class: deliveryResult.error ? (deliveryResult.error.split(' ')[0] || 'DISPATCH_ERROR') : null,
      sent_at: ['SENT_NEW', 'SENT_REGRESSION', 'SENT_AGGREGATE'].includes(deliveryResult.decision) ? (deliveryResult.notifiedAt || now) : null,
    };

    if (this.db) {
      try {
        const report = await this.getReportById(reportId);
        if (report) {
          const payload = report.payload || {};
          payload.telegramDelivery = meta;
          await this.db.prepare('UPDATE reports SET payload_json = ?, updated_at = ? WHERE report_id = ?')
            .bind(JSON.stringify(payload), now, reportId).run();
        }
      } catch {}
    } else {
      const existing = this.inMemoryReports.get(reportId);
      if (existing) {
        try {
          const payload = typeof existing.payload_json === 'string' ? JSON.parse(existing.payload_json) : (existing.payload_json || {});
          payload.telegramDelivery = meta;
          existing.payload_json = JSON.stringify(payload);
          existing.telegramDelivery = meta;
        } catch {}
      }
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
  async listReports({ platform, appVersion, errorCategory, status, statuses, reportPurpose, search, severity, device, signature, hasNotes, hasDuplicates, attention, since, until, sort = 'newest', cursor, limit = 50, offset = 0, includePayload = false } = {}) {
    const lim = Math.min(Math.max(1, limit), 100);
    const off = Math.max(0, offset);

    if (this.db) {
      const payloadCol = includePayload ? 'payload_json, ' : '';
      let query = `SELECT report_id, created_at, schema_version, app_version, platform,
                          device_family, error_category, ${payloadCol}expires_at, status, developer_notes,
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
      if (Array.isArray(statuses) && statuses.length > 0) {
        const placeholders = statuses.map(() => '?').join(', ');
        query += ` AND status IN (${placeholders})`;
        params.push(...statuses.map(s => normalizeStatus(s)));
      } else if (status) {
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
      if (attention === true) query += " AND status NOT IN ('IGNORED_TEST', 'TEST', 'ignored_test', 'test', 'RESOLVED', 'resolved') AND (status IN ('NEW', 'IN_PROGRESS') OR severity IN ('HIGH', 'CRITICAL') OR EXISTS (SELECT 1 FROM issues WHERE issues.signature = reports.error_signature AND issues.reopened_at > 0 AND issues.status = 'IN_PROGRESS'))";
      if (since) { query += ' AND created_at >= ?'; params.push(Number(since)); }
      if (until) { query += ' AND created_at <= ?'; params.push(Number(until)); }
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
      if (Array.isArray(statuses) && statuses.length > 0) {
        const normStatuses = statuses.map(s => normalizeStatus(s));
        list = list.filter(r => normStatuses.includes(normalizeStatus(r.status)));
      } else if (status) {
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
      if (attention === true) list = list.filter(r => !['IGNORED_TEST', 'RESOLVED'].includes(normalizeStatus(r.status)) && (['NEW', 'IN_PROGRESS'].includes(normalizeStatus(r.status)) || ['HIGH', 'CRITICAL'].includes(r.severity) || (this.issues.get(r.error_signature)?.reopened_at > 0 && this.issues.get(r.error_signature)?.issue_status === 'IN_PROGRESS')));
      if (since) list = list.filter(r => r.created_at >= Number(since));
      if (until) list = list.filter(r => r.created_at <= Number(until));
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
      const actualOffset = cursor ? 0 : off;
      return list.slice(actualOffset, actualOffset + lim).map((r) => ({
        report_id: r.report_id,
        created_at: r.created_at,
        schema_version: r.schema_version,
        app_version: r.app_version,
        platform: r.platform,
        device_family: r.device_family,
        error_category: r.error_category,
        ...(includePayload ? { payload_json: r.payload_json, payload: r.payload } : {}),
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
    const blank = {
      signature: signature || '',
      status: 'NEW',
      issue_status: 'NEW',
      fixed_in_version: '',
      fix_patch: '',
      fix_commit: '',
      title: '',
      duplicate_of: null,
      occurrences: 0,
      first_seen: 0,
      last_seen: 0,
      reopened_at: 0,
      affected_versions: [],
    };
    if (!signature) return blank;

    let occurrences = 0;
    let firstSeen = 0;
    let lastSeen = 0;
    let affectedVersions = [];

    if (this.db) {
      try {
        const statsRow = await this.db.prepare('SELECT COUNT(*) AS count, MIN(created_at) AS first_seen, MAX(created_at) AS last_seen FROM reports WHERE error_signature = ?').bind(signature).first();
        if (statsRow) {
          occurrences = Number(statsRow.count) || 0;
          firstSeen = Number(statsRow.first_seen) || 0;
          lastSeen = Number(statsRow.last_seen) || 0;
        }
        affectedVersions = await this.getAffectedVersions(signature);
      } catch (_) {}

      try {
        const row = await this.db.prepare('SELECT status AS issue_status, fixed_in_version, fix_patch, fix_commit, title, reopened_at, duplicate_of FROM issues WHERE signature = ?').bind(signature).first();
        if (row) {
          const norm = normalizeStatus(row.issue_status);
          return {
            signature,
            status: norm,
            issue_status: norm,
            fixed_in_version: row.fixed_in_version || '',
            fix_patch: row.fix_patch || '',
            fix_commit: row.fix_commit || '',
            title: row.title || '',
            duplicate_of: row.duplicate_of || null,
            reopened_at: row.reopened_at || 0,
            occurrences,
            first_seen: firstSeen,
            last_seen: lastSeen,
            affected_versions: affectedVersions,
          };
        }
      } catch (_) {
        const fallbackRow = await this.db.prepare('SELECT status AS issue_status, fixed_in_version, fix_patch, fix_commit, title, reopened_at FROM issues WHERE signature = ?').bind(signature).first();
        if (fallbackRow) {
          const norm = normalizeStatus(fallbackRow.issue_status);
          return {
            signature,
            status: norm,
            issue_status: norm,
            fixed_in_version: fallbackRow.fixed_in_version || '',
            fix_patch: fallbackRow.fix_patch || '',
            fix_commit: fallbackRow.fix_commit || '',
            title: fallbackRow.title || '',
            duplicate_of: null,
            reopened_at: fallbackRow.reopened_at || 0,
            occurrences,
            first_seen: firstSeen,
            last_seen: lastSeen,
            affected_versions: affectedVersions,
          };
        }
      }
      return { ...blank, occurrences, first_seen: firstSeen, last_seen: lastSeen, affected_versions: affectedVersions };
    }

    const reportsForSig = [...this.inMemoryReports.values()].filter(r => r.error_signature === signature);
    occurrences = reportsForSig.length;
    if (reportsForSig.length > 0) {
      firstSeen = Math.min(...reportsForSig.map(r => r.created_at));
      lastSeen = Math.max(...reportsForSig.map(r => r.created_at));
      affectedVersions = [...new Set(reportsForSig.map(r => r.app_version))].sort().reverse();
    }

    const stored = this.issues.get(signature);
    if (stored) {
      const norm = normalizeStatus(stored.issue_status || stored.status);
      return {
        signature,
        status: norm,
        issue_status: norm,
        fixed_in_version: stored.fixed_in_version || '',
        fix_patch: stored.fix_patch || '',
        fix_commit: stored.fix_commit || '',
        title: stored.title || '',
        duplicate_of: stored.duplicate_of || null,
        reopened_at: stored.reopened_at || 0,
        occurrences: stored.occurrences !== undefined ? stored.occurrences : occurrences,
        first_seen: stored.first_seen || firstSeen,
        last_seen: stored.last_seen || lastSeen,
        affected_versions: stored.affected_versions || affectedVersions,
      };
    }

    return { ...blank, occurrences, first_seen: firstSeen, last_seen: lastSeen, affected_versions: affectedVersions };
  }

  async updateIssue(signature, patch, actor = 'ADMIN') {
    if (!signature || typeof signature !== 'string' || signature.length > 256) throw new RangeError('Invalid signature');
    const allowed = [
      'status', 'fixedInVersion', 'fixPatch', 'fixCommit', 'title', 'actor',
      'duplicateOf', 'duplicate_of', 'occurrences',
      'rootCause', 'root_cause', 'verificationMethod', 'verification_method',
      'verificationResult', 'verification_result', 'verificationEnvironment', 'verification_environment',
      'evidence', 'reason'
    ];
    if (!patch || typeof patch !== 'object' || Object.keys(patch).some(key => !allowed.includes(key))) throw new RangeError('Invalid issue patch');
    const effectiveActor = patch.actor || actor;
    const normStatus = patch.status !== undefined ? normalizeStatus(patch.status) : undefined;
    if (normStatus !== undefined && !CANONICAL_STATUSES.includes(normStatus) && !STATUSES.includes(normStatus)) {
      throw new RangeError(`Invalid issue status: ${patch.status}`);
    }

    for (const key of allowed.filter(key => key !== 'status' && key !== 'actor' && key !== 'occurrences')) {
      if (patch[key] !== undefined && patch[key] !== null && (typeof patch[key] !== 'string' || patch[key].length > 256)) throw new RangeError(`Invalid ${key}`);
    }

    const before = await this.getIssue(signature);
    const currentNorm = normalizeStatus(before.issue_status || before.status);
    if (normStatus !== undefined && currentNorm && currentNorm !== normStatus && !isValidStatusTransition(currentNorm, normStatus)) {
      throw new RangeError(`Invalid status transition from ${currentNorm} to ${normStatus}`);
    }

    if (normStatus === 'VERIFIED') {
      const hasEvidence = Boolean(
        (patch.reason && patch.reason.trim().length > 0) ||
        (patch.evidence && patch.evidence.trim().length > 0) ||
        (patch.verificationMethod && patch.verificationMethod.trim().length > 0) ||
        (patch.verification_method && patch.verification_method.trim().length > 0) ||
        (patch.verificationResult && patch.verificationResult.trim().length > 0) ||
        (patch.verification_result && patch.verification_result.trim().length > 0) ||
        (patch.fixCommit && patch.fixCommit.trim().length > 0) ||
        (patch.fix_commit && patch.fix_commit.trim().length > 0) ||
        (before.fix_commit && before.fix_commit.trim().length > 0) ||
        (before.fix_patch && before.fix_patch.trim().length > 0)
      );
      if (!hasEvidence) {
        throw new RangeError('Transition to VERIFIED requires verification evidence or commit reference');
      }
    }

    const rawDup = patch.duplicateOf ?? patch.duplicate_of;
    const duplicateOf = rawDup !== undefined ? (rawDup ? String(rawDup).trim() : null) : (before.duplicate_of || null);

    const next = {
      signature,
      status: normStatus ?? currentNorm ?? 'NEW',
      issue_status: normStatus ?? currentNorm ?? 'NEW',
      fixed_in_version: patch.fixedInVersion ?? before.fixed_in_version ?? '',
      fix_patch: patch.fixPatch ?? before.fix_patch ?? '',
      fix_commit: patch.fixCommit ?? before.fix_commit ?? '',
      title: patch.title ?? before.title ?? '',
      duplicate_of: duplicateOf,
      reopened_at: before.reopened_at || 0,
      occurrences: patch.occurrences !== undefined ? Number(patch.occurrences) : before.occurrences,
      first_seen: before.first_seen,
      last_seen: before.last_seen,
      affected_versions: before.affected_versions,
    };

    const now = Date.now();
    let action = 'ISSUE_LINKED';
    if (next.issue_status === 'RESOLVED' || next.issue_status === 'CLOSED') {
      action = 'ISSUE_RESOLVED';
    } else if (next.issue_status === 'DUPLICATE' || duplicateOf) {
      action = 'ISSUE_DUPLICATED';
    }

    if (this.db) {
      try {
        await this.db.batch([
          this.db.prepare('INSERT INTO issues (signature, status, fixed_in_version, fix_patch, fix_commit, title, reopened_at, duplicate_of, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(signature) DO UPDATE SET status=excluded.status, fixed_in_version=excluded.fixed_in_version, fix_patch=excluded.fix_patch, fix_commit=excluded.fix_commit, title=excluded.title, duplicate_of=excluded.duplicate_of, updated_at=excluded.updated_at').bind(signature, next.issue_status, next.fixed_in_version, next.fix_patch, next.fix_commit, next.title, next.reopened_at, next.duplicate_of, now),
          this.db.prepare('INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES (?, ?, ?, ?, ?)').bind(action, 'ISSUE', signature, now, effectiveActor),
        ]);
      } catch (_) {
        await this.db.batch([
          this.db.prepare('INSERT INTO issues (signature, status, fixed_in_version, fix_patch, fix_commit, title, reopened_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(signature) DO UPDATE SET status=excluded.status, fixed_in_version=excluded.fixed_in_version, fix_patch=excluded.fix_patch, fix_commit=excluded.fix_commit, title=excluded.title, updated_at=excluded.updated_at').bind(signature, next.issue_status, next.fixed_in_version, next.fix_patch, next.fix_commit, next.title, next.reopened_at, now),
          this.db.prepare('INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES (?, ?, ?, ?, ?)').bind(action, 'ISSUE', signature, now, effectiveActor),
        ]);
      }
    } else {
      this.issues.set(signature, next);
      this.audit.push({ action, target_type: 'ISSUE', target_id: signature, created_at: now, actor: effectiveActor });
    }
    return next;
  }

  async reopenRegressedIssue(signature, appVersion, reopenStatus = null) {
    const issue = await this.getIssue(signature);
    const curStatus = normalizeStatus(issue.status || issue.issue_status);
    const isResolved = curStatus === 'RESOLVED' || curStatus === 'CLOSED';
    const isPendingVerify = curStatus === 'FIXED_PENDING_VERIFY' || curStatus === 'FIXED_PENDING_VERIFICATION' || curStatus === 'FIXED_PENDING_VALIDATION';

    if (!isResolved && !isPendingVerify) return false;

    if (isResolved) {
      if (!issue.fixed_in_version) return false;
      const cmp = compareVoxVersions(appVersion, issue.fixed_in_version);
      if (cmp !== 0 && cmp !== 1) return false;
    }

    const now = Date.now();
    const targetStatus = reopenStatus || (curStatus === 'INVESTIGATING' ? 'INVESTIGATING' : 'IN_PROGRESS');

    if (this.db) {
      await this.db.batch([
        this.db.prepare('UPDATE issues SET status = ?, reopened_at = ?, updated_at = ? WHERE signature = ?').bind(targetStatus, now, now, signature),
        this.db.prepare('INSERT INTO admin_audit (action, target_type, target_id, created_at, actor) VALUES (?, ?, ?, ?, ?)').bind('ISSUE_REOPENED', 'ISSUE', signature, now, 'SYSTEM'),
      ]);
    } else {
      this.issues.set(signature, { ...issue, status: targetStatus, issue_status: targetStatus, reopened_at: now, updated_at: now });
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
            SUM(CASE WHEN UPPER(status) = 'TRIAGED' THEN 1 ELSE 0 END) as count_triaged,
            SUM(CASE WHEN UPPER(status) IN ('IN_PROGRESS', 'REVIEWED', 'INVESTIGATING', 'ROOT_CAUSE_FOUND', 'FIX_IMPLEMENTED', 'TESTING') THEN 1 ELSE 0 END) as count_in_progress,
            SUM(CASE WHEN UPPER(status) IN ('FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFICATION', 'FIXED') THEN 1 ELSE 0 END) as count_fixed_awaiting,
            SUM(CASE WHEN UPPER(status) IN ('RESOLVED', 'VERIFIED') THEN 1 ELSE 0 END) as count_resolved,
            SUM(CASE WHEN UPPER(status) = 'CLOSED' THEN 1 ELSE 0 END) as count_closed,
            SUM(CASE WHEN UPPER(status) IN ('KNOWN_LIMITATION', 'KNOWN_ISSUE', 'KNOWN') THEN 1 ELSE 0 END) as count_known_issue,
            SUM(CASE WHEN UPPER(status) IN ('IGNORED_TEST', 'IGNORED', 'TEST', 'WONT_FIX', 'WONTFIX', 'DUPLICATE', 'DUP') THEN 1 ELSE 0 END) as count_ignored_test,
            SUM(CASE WHEN UPPER(COALESCE(status, '')) NOT IN ('NEW', 'TRIAGED', 'IN_PROGRESS', 'REVIEWED', 'INVESTIGATING', 'ROOT_CAUSE_FOUND', 'FIX_IMPLEMENTED', 'TESTING', 'FIXED_AWAITING_VERIFICATION', 'FIXED_PENDING_VERIFICATION', 'FIXED', 'RESOLVED', 'VERIFIED', 'CLOSED', 'KNOWN_LIMITATION', 'KNOWN_ISSUE', 'KNOWN', 'IGNORED_TEST', 'IGNORED', 'TEST', 'WONT_FIX', 'WONTFIX', 'DUPLICATE', 'DUP') THEN 1 ELSE 0 END) as count_other
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
      const triagedCount = Number(countsRes?.count_triaged) || 0;
      const inProgressCount = Number(countsRes?.count_in_progress) || 0;
      const fixedAwaitingCount = Number(countsRes?.count_fixed_awaiting) || 0;
      const resolvedCount = Number(countsRes?.count_resolved) || 0;
      const closedCount = Number(countsRes?.count_closed) || 0;
      const knownIssueCount = Number(countsRes?.count_known_issue) || 0;
      const ignoredTestCount = Number(countsRes?.count_ignored_test) || 0;
      const otherCount = Number(countsRes?.count_other) || 0;
      const activeIssuesCount = newCount + inProgressCount + knownIssueCount;

      let liveCount = 0;
      let translationCount = 0;
      let downloadCount = 0;
      let networkCount = 0;
      for (const cat of (categoriesRes?.results || [])) {
        const c = String(cat.error_category || '').toUpperCase();
        if (c === 'LIVE' || c === 'PLAYBACK') liveCount += Number(cat.count) || 0;
        if (c === 'TRANSLATION') translationCount += Number(cat.count) || 0;
        if (c === 'DOWNLOAD') downloadCount += Number(cat.count) || 0;
        if (c === 'NETWORK') networkCount += Number(cat.count) || 0;
      }
      const attentionCount = newCount + inProgressCount;

      const statusBreakdown = [
        { status: 'NEW', label: 'Новый', count: newCount },
        { status: 'IN_PROGRESS', label: 'В работе', count: inProgressCount },
        { status: 'RESOLVED', label: 'Решено', count: resolvedCount },
        { status: 'KNOWN_ISSUE', label: 'Известная проблема', count: knownIssueCount },
        { status: 'IGNORED_TEST', label: 'Тест / игнор', count: ignoredTestCount },
      ];

      return {
        totalReports,
        total: totalReports,
        reports24h,
        last24Hours: reports24h,
        reports7d,
        last7Days: reports7d,
        newCount,
        triagedCount,
        inProgressCount,
        fixedAwaitingCount,
        resolvedCount,
        closedCount,
        knownIssueCount,
        knownCount: knownIssueCount,
        ignoredTestCount,
        otherCount,
        unknownCount: otherCount,
        activeIssuesCount,
        attentionCount,
        liveCount,
        translationCount,
        downloadCount,
        networkCount,
        counts: {
          count_new: newCount,
          count_triaged: triagedCount,
          count_in_progress: inProgressCount,
          count_fixed_awaiting: fixedAwaitingCount,
          count_resolved: resolvedCount,
          count_closed: closedCount,
          count_known: knownIssueCount,
          count_ignored_test: ignoredTestCount,
          count_other: otherCount,
        },
        statusCounts: {
          NEW: newCount,
          IN_PROGRESS: inProgressCount,
          TRIAGED: triagedCount,
          INVESTIGATING: inProgressCount,
          FIXED_AWAITING_VERIFICATION: fixedAwaitingCount,
          RESOLVED: resolvedCount,
          CLOSED: closedCount,
          KNOWN_LIMITATION: knownIssueCount,
          KNOWN_ISSUE: knownIssueCount,
          IGNORED_TEST: ignoredTestCount,
          OTHER: otherCount,
        },
        statusBreakdown: [
          { status: 'NEW', label: 'Новый', count: newCount },
          { status: 'IN_PROGRESS', label: 'В работе', count: inProgressCount },
          { status: 'RESOLVED', label: 'Решено', count: resolvedCount },
          { status: 'KNOWN_ISSUE', label: 'Известная проблема', count: knownIssueCount },
          { status: 'IGNORED_TEST', label: 'Тест / игнор', count: ignoredTestCount },
        ],
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
      let triagedCount = 0;
      let inProgressCount = 0;
      let fixedAwaitingCount = 0;
      let resolvedCount = 0;
      let closedCount = 0;
      let knownIssueCount = 0;
      let ignoredTestCount = 0;
      let otherCount = 0;

      const platformsMap = new Map();
      const versionsMap = new Map();
      const categoriesMap = new Map();
      const issuesMap = new Map();

      let liveCount = 0;
      let translationCount = 0;
      let downloadCount = 0;
      let networkCount = 0;
      let attentionCount = 0;

      for (const r of all) {
        const normStatus = normalizeStatus(r.status);
        if (normStatus === 'NEW') newCount++;
        else if (normStatus === 'TRIAGED') triagedCount++;
        else if (normStatus === 'IN_PROGRESS' || normStatus === 'INVESTIGATING' || normStatus === 'TESTING' || normStatus === 'FIX_IMPLEMENTED' || normStatus === 'ROOT_CAUSE_FOUND') inProgressCount++;
        else if (normStatus === 'FIXED_AWAITING_VERIFICATION' || normStatus === 'FIXED_PENDING_VERIFICATION') fixedAwaitingCount++;
        else if (normStatus === 'RESOLVED' || normStatus === 'VERIFIED') resolvedCount++;
        else if (normStatus === 'CLOSED') closedCount++;
        else if (normStatus === 'KNOWN_LIMITATION' || normStatus === 'KNOWN_ISSUE') knownIssueCount++;
        else if (normStatus === 'IGNORED_TEST' || normStatus === 'IGNORED' || normStatus === 'WONT_FIX' || normStatus === 'DUPLICATE') ignoredTestCount++;
        else otherCount++;

        const c = String(r.error_category || '').toUpperCase();
        if (c === 'LIVE' || c === 'PLAYBACK') liveCount++;
        if (c === 'TRANSLATION') translationCount++;
        if (c === 'DOWNLOAD') downloadCount++;
        if (c === 'NETWORK') networkCount++;

        const sig = String(r.error_signature || '').toUpperCase();
        if (sig.includes('LIVE_SEEK') || sig.includes('PACKAGING') || sig.includes('FALLBACK') || sig.includes('TRANSLAT') || sig.includes('IMAGE') || r.severity === 'CRITICAL' || r.severity === 'HIGH' || Number(r.related_count || 0) >= 2) {
          attentionCount++;
        }

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
        total: totalReports,
        reports24h,
        last24Hours: reports24h,
        reports7d,
        last7Days: reports7d,
        newCount,
        triagedCount,
        inProgressCount,
        fixedAwaitingCount,
        resolvedCount,
        closedCount,
        knownIssueCount,
        knownCount: knownIssueCount,
        ignoredTestCount,
        otherCount,
        unknownCount: otherCount,
        activeIssuesCount,
        attentionCount,
        liveCount,
        translationCount,
        downloadCount,
        networkCount,
        counts: {
          count_new: newCount,
          count_triaged: triagedCount,
          count_in_progress: inProgressCount,
          count_fixed_awaiting: fixedAwaitingCount,
          count_resolved: resolvedCount,
          count_closed: closedCount,
          count_known: knownIssueCount,
          count_ignored_test: ignoredTestCount,
          count_other: otherCount,
        },
        statusCounts: {
          NEW: newCount,
          IN_PROGRESS: inProgressCount,
          TRIAGED: triagedCount,
          INVESTIGATING: inProgressCount,
          FIXED_AWAITING_VERIFICATION: fixedAwaitingCount,
          RESOLVED: resolvedCount,
          CLOSED: closedCount,
          KNOWN_LIMITATION: knownIssueCount,
          KNOWN_ISSUE: knownIssueCount,
          IGNORED_TEST: ignoredTestCount,
          OTHER: otherCount,
        },
        statusBreakdown: [
          { status: 'NEW', label: 'Новый', count: newCount },
          { status: 'IN_PROGRESS', label: 'В работе', count: inProgressCount },
          { status: 'RESOLVED', label: 'Решено', count: resolvedCount },
          { status: 'KNOWN_ISSUE', label: 'Известная проблема', count: knownIssueCount },
          { status: 'IGNORED_TEST', label: 'Тест / игнор', count: ignoredTestCount },
        ],
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

  async recordInstallationActivity({
    installationId,
    manufacturer = '',
    model = '',
    platform = '',
    osVersion = '',
    sdkInt = 0,
    appVersion = '',
    timestamp = Date.now(),
    isError = false,
    isHeartbeat = false,
    reportId = null,
    status = 'NEW'
  } = {}) {
    if (!installationId || typeof installationId !== 'string') return null;
    const cleanId = installationId.trim();
    if (!cleanId) return null;

    let inst = this.installations.get(cleanId);
    if (!inst) {
      inst = {
        installationId: cleanId,
        manufacturer,
        model,
        platform,
        androidVersion: osVersion,
        sdkInt,
        currentAppVersion: appVersion,
        firstSeenAt: timestamp,
        lastSeenAt: timestamp,
        lastHeartbeatAt: isHeartbeat ? timestamp : 0,
        reportsCount: isHeartbeat ? 0 : 1,
        errorCount: isError ? 1 : 0,
        appVersionHistory: appVersion ? [appVersion] : [],
        reports: [],
      };
      this.installations.set(cleanId, inst);
    } else {
      inst.lastSeenAt = Math.max(inst.lastSeenAt || 0, timestamp);
      if (manufacturer && !inst.manufacturer) inst.manufacturer = manufacturer;
      if (model && !inst.model) inst.model = model;
      if (platform && !inst.platform) inst.platform = platform;
      if (osVersion && !inst.androidVersion) inst.androidVersion = osVersion;
      if (sdkInt && !inst.sdkInt) inst.sdkInt = sdkInt;
      if (appVersion) {
        inst.currentAppVersion = appVersion;
        if (!inst.appVersionHistory.includes(appVersion)) {
          inst.appVersionHistory.push(appVersion);
        }
      }
      if (isHeartbeat) {
        inst.lastHeartbeatAt = timestamp;
      } else {
        inst.reportsCount = (inst.reportsCount || 0) + 1;
      }
      if (isError) {
        inst.errorCount = (inst.errorCount || 0) + 1;
      }
    }

    if (reportId && !isHeartbeat) {
      if (!inst.reports.some(r => r.reportId === reportId)) {
        inst.reports.push({ reportId, status, timestamp, errorCategory: isError ? 'ERROR' : null });
      }
    }

    if (this.db) {
      try {
        const stmt = this.db.prepare(
          `INSERT INTO installations (
            installation_id, manufacturer, model, platform, android_version,
            sdk_int, current_app_version, first_seen_at, last_seen_at,
            last_heartbeat_at, reports_count, error_count, app_version_history
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(installation_id) DO UPDATE SET
            manufacturer = CASE WHEN excluded.manufacturer != '' THEN excluded.manufacturer ELSE installations.manufacturer END,
            model = CASE WHEN excluded.model != '' THEN excluded.model ELSE installations.model END,
            platform = CASE WHEN excluded.platform != '' THEN excluded.platform ELSE installations.platform END,
            android_version = CASE WHEN excluded.android_version != '' THEN excluded.android_version ELSE installations.android_version END,
            sdk_int = CASE WHEN excluded.sdk_int > 0 THEN excluded.sdk_int ELSE installations.sdk_int END,
            current_app_version = CASE WHEN excluded.current_app_version != '' THEN excluded.current_app_version ELSE installations.current_app_version END,
            last_seen_at = excluded.last_seen_at,
            last_heartbeat_at = CASE WHEN excluded.last_heartbeat_at > 0 THEN excluded.last_heartbeat_at ELSE installations.last_heartbeat_at END,
            reports_count = installations.reports_count + excluded.reports_count,
            error_count = installations.error_count + excluded.error_count,
            app_version_history = excluded.app_version_history`
        );
        await stmt.bind(
          cleanId,
          inst.manufacturer || '',
          inst.model || '',
          inst.platform || '',
          inst.androidVersion || '',
          inst.sdkInt || 0,
          inst.currentAppVersion || '',
          inst.firstSeenAt,
          inst.lastSeenAt,
          inst.lastHeartbeatAt || 0,
          isHeartbeat ? 0 : 1,
          isError ? 1 : 0,
          JSON.stringify(inst.appVersionHistory)
        ).run();
      } catch (err) {}
    }

    return inst;
  }

  async recordHeartbeat(data = {}) {
    const { installationId, appVersion, platform, manufacturer, model, sdkInt, timestamp } = data;
    if (!installationId || typeof installationId !== 'string') {
      throw new Error('installationId is required for heartbeat');
    }
    const ts = timestamp || Date.now();
    return this.recordInstallationActivity({
      installationId,
      appVersion,
      platform,
      manufacturer,
      model,
      sdkInt,
      timestamp: ts,
      isHeartbeat: true,
      isError: false,
    });
  }

  async getDevices({ search = '', limit = 50, offset = 0 } = {}) {
    const term = search ? search.toLowerCase().trim() : '';

    const allInstMap = new Map();
    for (const [id, inst] of this.installations.entries()) {
      allInstMap.set(id, { ...inst, reports: [...(inst.reports || [])] });
    }

    const unassignedReports = [];
    const allReports = Array.from(this.inMemoryReports.values());
    for (const r of allReports) {
      let p = {};
      try { p = typeof r.payload_json === 'string' ? JSON.parse(r.payload_json) : (r.payload || {}); } catch (_) {}
      const installId = r.installation_id || p.installationId || p.installation_id;
      const reportId = r.report_id || r.reportId;
      const status = normalizeStatus(r.status || 'NEW');
      const ts = r.created_at || r.createdAt || Date.now();
      const model = p.model || r.device_family || 'Unknown Model';
      const manufacturer = p.manufacturer || '';

      if (installId && typeof installId === 'string' && installId.trim()) {
        const cleanId = installId.trim();
        let inst = allInstMap.get(cleanId);
        if (!inst) {
          inst = {
            installationId: cleanId,
            manufacturer,
            model,
            platform: r.platform || '',
            androidVersion: p.osVersion || '',
            sdkInt: p.sdkInt || 0,
            currentAppVersion: r.app_version || 'unknown',
            firstSeenAt: ts,
            lastSeenAt: ts,
            lastHeartbeatAt: 0,
            reportsCount: 0,
            errorCount: r.error_category ? 1 : 0,
            appVersionHistory: r.app_version ? [r.app_version] : [],
            reports: [],
          };
          allInstMap.set(cleanId, inst);
        }
        if (!inst.reports.some(item => item.reportId === reportId)) {
          inst.reports.push({ reportId, status, timestamp: ts, errorCategory: r.error_category });
          inst.reportsCount = inst.reports.length;
        }
        inst.firstSeenAt = Math.min(inst.firstSeenAt || ts, ts);
        inst.lastSeenAt = Math.max(inst.lastSeenAt || ts, ts);
      } else {
        unassignedReports.push({
          reportId,
          model,
          manufacturer,
          status,
          appVersion: r.app_version || 'unknown',
          createdAt: ts,
          errorCategory: r.error_category,
        });
      }
    }

    const modelGroupsMap = new Map();
    for (const inst of allInstMap.values()) {
      if (term) {
        const matches = (inst.installationId && inst.installationId.toLowerCase().includes(term)) ||
          (inst.model && inst.model.toLowerCase().includes(term)) ||
          (inst.manufacturer && inst.manufacturer.toLowerCase().includes(term)) ||
          (inst.currentAppVersion && inst.currentAppVersion.toLowerCase().includes(term));
        if (!matches) continue;
      }

      const modelKey = inst.model || 'Unknown Model';
      let group = modelGroupsMap.get(modelKey);
      if (!group) {
        group = {
          model: modelKey,
          manufacturer: inst.manufacturer || '',
          installationsCount: 0,
          reportsCount: 0,
          installations: [],
        };
        modelGroupsMap.set(modelKey, group);
      }

      const activeInvestigations = inst.reports.filter(r => r.status === 'INVESTIGATING').length;
      const independentIncidents = new Set(inst.reports.map(r => r.errorCategory || r.reportId)).size;

      group.installations.push({
        installationId: inst.installationId,
        manufacturer: inst.manufacturer,
        model: inst.model,
        androidVersion: inst.androidVersion,
        sdkInt: inst.sdkInt,
        voxVersion: inst.currentAppVersion,
        firstSeenAt: inst.firstSeenAt,
        lastSeenAt: inst.lastSeenAt,
        reportsCount: inst.reportsCount,
        activeInvestigationsCount: activeInvestigations,
        independentIncidentsCount: independentIncidents,
        appVersionHistory: inst.appVersionHistory || [],
        reports: inst.reports,
      });

      group.installationsCount++;
      group.reportsCount += inst.reportsCount;
    }

    const models = Array.from(modelGroupsMap.values());
    models.sort((a, b) => b.reportsCount - a.reportsCount);

    return {
      totalInstallations: allInstMap.size,
      modelsCount: models.length,
      models: models.slice(offset, offset + limit),
      unassignedReportsCount: unassignedReports.length,
      unassignedReports,
    };
  }

  async getAnalytics({ period = '7d', now = Date.now() } = {}) {
    const periodMap = {
      '1d': 24 * 3600 * 1000,
      '3d': 3 * 24 * 3600 * 1000,
      '7d': 7 * 24 * 3600 * 1000,
      '30d': 30 * 24 * 3600 * 1000,
      '90d': 90 * 24 * 3600 * 1000,
      'all': 365 * 24 * 3600 * 1000,
    };
    const durationMs = periodMap[period] || periodMap['7d'];
    const periodStart = now - durationMs;
    const onlineThreshold = now - (45 * 60 * 1000);

    const devicesData = await this.getDevices({ limit: 10000 });
    const allInstallations = [];
    for (const m of devicesData.models) {
      allInstallations.push(...m.installations);
    }

    let activeNow = 0;
    let activeInPeriod = 0;
    let newInPeriod = 0;
    let returningInPeriod = 0;
    let upgradedInstallations = 0;
    let installationsWithErrors = 0;
    let totalIncidents = 0;

    const versionDist = Object.create(null);
    const manufacturerDist = Object.create(null);
    const modelDist = Object.create(null);
    const apiDist = Object.create(null);
    const errorCatDist = Object.create(null);
    const versionTransitions = [];

    const daysCount = Math.max(1, Math.min(90, Math.ceil(durationMs / (24 * 3600 * 1000))));
    const timeline = [];
    for (let d = daysCount - 1; d >= 0; d--) {
      const dStart = now - (d + 1) * 24 * 3600 * 1000;
      const dEnd = now - d * 24 * 3600 * 1000;
      const dateKey = new Date(dStart).toISOString().slice(0, 10);
      timeline.push({ date: dateKey, start: dStart, end: dEnd, active: 0, new: 0, errors: 0 });
    }

    for (const inst of allInstallations) {
      const lastSeen = inst.lastSeenAt || 0;
      const firstSeen = inst.firstSeenAt || lastSeen;

      if (lastSeen >= onlineThreshold) {
        activeNow++;
      }

      if (lastSeen >= periodStart) {
        activeInPeriod++;
        if (firstSeen >= periodStart) {
          newInPeriod++;
        } else {
          returningInPeriod++;
        }

        if (inst.appVersionHistory && inst.appVersionHistory.length > 1) {
          upgradedInstallations++;
          for (let i = 0; i < inst.appVersionHistory.length - 1; i++) {
            versionTransitions.push({
              from: inst.appVersionHistory[i],
              to: inst.appVersionHistory[i + 1],
              count: 1,
            });
          }
        }

        const hasErrors = (inst.reports && inst.reports.some(r => r.errorCategory)) || inst.errorCount > 0;
        if (hasErrors) {
          installationsWithErrors++;
        }

        if (inst.voxVersion) versionDist[inst.voxVersion] = (versionDist[inst.voxVersion] || 0) + 1;
        if (inst.manufacturer) manufacturerDist[inst.manufacturer] = (manufacturerDist[inst.manufacturer] || 0) + 1;
        if (inst.model) modelDist[inst.model] = (modelDist[inst.model] || 0) + 1;
        if (inst.sdkInt) {
          const apiLabel = `API ${inst.sdkInt}${inst.androidVersion ? ' (Android ' + inst.androidVersion + ')' : ''}`;
          apiDist[apiLabel] = (apiDist[apiLabel] || 0) + 1;
        }

        for (const bucket of timeline) {
          if (lastSeen >= bucket.start && lastSeen < bucket.end) {
            bucket.active++;
          }
          if (firstSeen >= bucket.start && firstSeen < bucket.end) {
            bucket.new++;
          }
          if (hasErrors && lastSeen >= bucket.start && lastSeen < bucket.end) {
            bucket.errors++;
          }
        }
      }
    }

    for (const r of this.inMemoryReports.values()) {
      const ts = r.created_at || r.createdAt || 0;
      if (ts >= periodStart && r.error_category) {
        errorCatDist[r.error_category] = (errorCatDist[r.error_category] || 0) + 1;
        totalIncidents++;
      }
    }

    const errorRatePercent = activeInPeriod > 0
      ? Math.round((installationsWithErrors / activeInPeriod) * 1000) / 10
      : 0;
    const incidentFrequency = activeInPeriod > 0
      ? Math.round((totalIncidents / activeInPeriod) * 100) / 100
      : 0;

    return {
      period,
      activeNow,
      activeInPeriod,
      totalRegistered: allInstallations.length,
      newInstallations: newInPeriod,
      returningInstallations: returningInPeriod,
      upgradedInstallations,
      installationsWithErrors,
      errorRatePercent,
      incidentFrequency,
      totalIncidents,
      timeline,
      versionDistribution: versionDist,
      manufacturerDistribution: manufacturerDist,
      modelDistribution: modelDist,
      apiLevelDistribution: apiDist,
      errorCategoryDistribution: errorCatDist,
      versionTransitions,
    };
  }

}

export { ReportStorage as DiagnosticsStorage };
