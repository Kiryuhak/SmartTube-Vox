import { handleRequest } from './diagnostics.mjs';
import { ReportStorage } from './storage.mjs';

export default {
  async fetch(request, env, ctx) {
    return handleRequest(request, env);
  },

  /**
   * Cron trigger handler: автоматически очищает отчёты старше 30 дней.
   */
  async scheduled(event, env, ctx) {
    if (env.DB) {
      const storage = new ReportStorage(env.DB);
      const deleted = await storage.purgeExpiredReports(Date.now());
      console.log(`[Cron] Purged ${deleted} expired diagnostic reports.`);
    }
  },
};
