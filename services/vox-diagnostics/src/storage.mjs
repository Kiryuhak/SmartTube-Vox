import { DEFAULT_RETENTION_MS } from './diagnostics.mjs';

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
    const createdAt = report.receivedAt || Date.now();
    const expiresAt = createdAt + retentionMs;
    const reportId = report.reportId;
    const schemaVersion = report.schema || 'vox-diagnostic-report-v2';
    const appVersion = report.appVersion || 'unknown';
    const platform = report.platform || 'unknown';
    const deviceFamily = `${report.manufacturer || ''} ${report.model || ''}`.trim() || 'unknown';
    const errorCategory = report.errorCategory || null;
    const payloadJson = JSON.stringify(report);

    if (this.db) {
      const stmt = this.db.prepare(
        `INSERT INTO reports (report_id, created_at, schema_version, app_version, platform, device_family, error_category, payload_json, expires_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
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
        expiresAt
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
      });
    }

    return {
      reportId,
      createdAt,
      expiresAt,
    };
  }

  /**
   * Возвращает отчёт по его reportId.
   */
  async getReportById(reportId) {
    if (!reportId) return null;

    if (this.db) {
      const stmt = this.db.prepare(
        `SELECT report_id, created_at, schema_version, app_version, platform, device_family, error_category, payload_json, expires_at
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
   * Возвращает список отчётов с фильтрацией и пагинацией.
   */
  async listReports({ platform, appVersion, errorCategory, search, limit = 50, offset = 0 } = {}) {
    const lim = Math.min(Math.max(1, limit), 100);
    const off = Math.max(0, offset);

    if (this.db) {
      let query = `SELECT report_id, created_at, schema_version, app_version, platform, device_family, error_category, expires_at FROM reports WHERE 1=1`;
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
      if (search) {
        query += ` AND (report_id LIKE ? OR device_family LIKE ?)`;
        params.push(`%${search}%`, `%${search}%`);
      }

      query += ` ORDER BY created_at DESC LIMIT ? OFFSET ?`;
      params.push(lim, off);

      const stmt = this.db.prepare(query);
      const { results } = await stmt.bind(...params).all();
      return results || [];
    } else {
      let list = Array.from(this.inMemoryReports.values());
      if (platform) list = list.filter((r) => r.platform === platform);
      if (appVersion) list = list.filter((r) => r.app_version === appVersion);
      if (errorCategory) list = list.filter((r) => r.error_category === errorCategory);
      if (search) {
        const s = search.toLowerCase();
        list = list.filter(
          (r) => r.report_id.toLowerCase().includes(s) || r.device_family.toLowerCase().includes(s)
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
      }));
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
