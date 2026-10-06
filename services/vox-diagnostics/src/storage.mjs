import { DEFAULT_RETENTION_MS } from './diagnostics.mjs';

export const CANONICAL_STATUSES = ['NEW', 'IN_PROGRESS', 'RESOLVED', 'KNOWN_ISSUE', 'IGNORED_TEST'];

export const STATUS_LABELS = {
  NEW: 'Новый',
  IN_PROGRESS: 'В работе',
  RESOLVED: 'Решено',
  KNOWN_ISSUE: 'Известная проблема',
  IGNORED_TEST: 'Тест / игнор',
};

/**
 * Нормализует строковый статус к каноническому виду:
 * NEW, IN_PROGRESS, RESOLVED, KNOWN_ISSUE, IGNORED_TEST
 */
export function normalizeStatus(rawStatus) {
  if (!rawStatus) return 'NEW';
  const s = String(rawStatus).toUpperCase().trim();
  if (s === 'REVIEWED' || s === 'TRIAGED' || s === 'IN_PROGRESS') return 'IN_PROGRESS';
  if (s === 'KNOWN' || s === 'KNOWN_ISSUE') return 'KNOWN_ISSUE';
  if (s === 'RESOLVED') return 'RESOLVED';
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
    const payloadJson = JSON.stringify(report);

    if (this.db) {
      const stmt = this.db.prepare(
        `INSERT INTO reports (
          report_id, created_at, schema_version, app_version, platform,
          device_family, error_category, payload_json, expires_at,
          status, developer_notes, report_purpose, error_signature
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
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
        errorSignature
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
      });
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
                error_category, payload_json, expires_at, status, developer_notes, report_purpose, error_signature
         FROM reports WHERE report_id = ?`
      );
      const row = await stmt.bind(reportId).first();
      if (!row) return null;
      return {
        ...row,
        payload: JSON.parse(row.payload_json),
      };
    } else {
      const row = this.inMemoryReports.get(reportId);
      if (!row) return null;
      return {
        ...row,
        payload: JSON.parse(row.payload_json),
      };
    }
  }

  /**
   * Обновляет статус и/или заметки разработчика для отчёта.
   */
  async updateReport(reportId, { status, developerNotes } = {}) {
    if (!reportId) return null;

    if (this.db) {
      const updates = [];
      const params = [];

      if (status !== undefined) {
        updates.push('status = ?');
        params.push(normalizeStatus(status));
      }
      if (developerNotes !== undefined) {
        updates.push('developer_notes = ?');
        params.push(developerNotes);
      }

      if (updates.length === 0) return this.getReportById(reportId);

      params.push(reportId);
      const query = `UPDATE reports SET ${updates.join(', ')} WHERE report_id = ?`;
      await this.db.prepare(query).bind(...params).run();
      return this.getReportById(reportId);
    } else {
      const existing = this.inMemoryReports.get(reportId);
      if (!existing) return null;
      if (status !== undefined) existing.status = normalizeStatus(status);
      if (developerNotes !== undefined) existing.developer_notes = developerNotes;
      return {
        ...existing,
        payload: JSON.parse(existing.payload_json),
      };
    }
  }

  /**
   * Удаляет отчёт по ID.
   */
  async deleteReportById(reportId) {
    if (!reportId) return false;

    if (this.db) {
      const stmt = this.db.prepare(`DELETE FROM reports WHERE report_id = ?`);
      const res = await stmt.bind(reportId).run();
      return (res?.meta?.changes || 0) > 0;
    } else {
      return this.inMemoryReports.delete(reportId);
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
      return count;
    } else {
      const count = this.inMemoryReports.size;
      this.inMemoryReports.clear();
      return count;
    }
  }

  /**
   * Возвращает список отчётов с фильтрацией и пагинацией.
   */
  async listReports({ platform, appVersion, errorCategory, status, reportPurpose, search, limit = 50, offset = 0 } = {}) {
    const lim = Math.min(Math.max(1, limit), 100);
    const off = Math.max(0, offset);

    if (this.db) {
      let query = `SELECT report_id, created_at, schema_version, app_version, platform,
                          device_family, error_category, expires_at, status, developer_notes,
                          report_purpose, error_signature
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
        query += ` AND (report_id LIKE ? OR device_family LIKE ? OR error_signature LIKE ?)`;
        params.push(`%${search}%`, `%${search}%`, `%${search}%`);
      }

      query += ` ORDER BY created_at DESC LIMIT ? OFFSET ?`;
      params.push(lim, off);

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
            (r.error_signature && r.error_signature.toLowerCase().includes(s))
        );
      }
      list.sort((a, b) => b.created_at - a.created_at);
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
      return (results || []).map(r => ({
        ...r,
        status: normalizeStatus(r.status),
        new_count: Number(r.new_count) || 0,
        in_progress_count: Number(r.in_progress_count) || 0,
        resolved_count: Number(r.resolved_count) || 0,
        known_issue_count: Number(r.known_issue_count) || 0,
        ignored_test_count: Number(r.ignored_test_count) || 0,
      }));
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
        if (r.created_at > g.last_seen) {
          g.last_seen = r.created_at;
          g.sample_report_id = r.report_id;
          g.status = normStatus;
        }
      }

      const list = Array.from(groups.values()).sort((a, b) => b.count - a.count || b.last_seen - a.last_seen);
      return list.slice(off, off + lim);
    }
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
      return res?.meta?.changes || 0;
    } else {
      let deleted = 0;
      for (const [id, rep] of this.inMemoryReports.entries()) {
        if (rep.expires_at < now) {
          this.inMemoryReports.delete(id);
          deleted++;
        }
      }
      return deleted;
    }
  }
}
