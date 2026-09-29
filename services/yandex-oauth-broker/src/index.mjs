import { handleRequest } from './broker.mjs';

export default {
  fetch(request, env) {
    return handleRequest(request, env);
  },
};

/** One SQLite-backed Durable Object serializes limits across all Worker locations. */
export class RateGate {
  constructor(ctx) {
    this.storage = ctx.storage;
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS limits (
      key TEXT PRIMARY KEY, window_start INTEGER NOT NULL,
      count INTEGER NOT NULL, last_seen INTEGER NOT NULL
    )`);
    this.sql.exec('CREATE INDEX IF NOT EXISTS limits_age ON limits(last_seen)');
  }

  async fetch(request) {
    if (request.method !== 'POST' || new URL(request.url).pathname !== '/check') {
      return new Response(null, { status: 404 });
    }
    let payload;
    try { payload = await request.json(); } catch { return new Response(null, { status: 400 }); }
    if (!payload || typeof payload.ip !== 'string' || payload.ip.length > 64
      || typeof payload.code_hash !== 'string' || !/^[0-9a-f]{64}$/.test(payload.code_hash)) {
      return new Response(null, { status: 400 });
    }
    const isRefresh = payload.type === 'refresh';
    const now = Date.now();
    const rules = isRefresh
      ? [
          ['global_refresh', 300, 60_000],
          [`ip_refresh:${payload.ip}`, 20, 60_000],
          [`refresh_token:${payload.code_hash}`, 1, 5_000],
        ]
      : [
          ['global', 600, 60_000],
          [`ip:${payload.ip}`, 30, 60_000],
          [`code:${payload.code_hash}`, 1, 5_000],
        ];
    try {
      const allowed = this.storage.transactionSync(() => {
        const updates = [];
        for (const [key, limit, windowMs] of rules) {
          const row = this.sql.exec('SELECT window_start, count FROM limits WHERE key = ?', key).toArray()[0];
          const fresh = !row || now - row.window_start >= windowMs;
          const count = fresh ? 0 : row.count;
          if (count >= limit) return false;
          updates.push([key, fresh ? now : row.window_start, count + 1]);
        }
        for (const [key, start, count] of updates) {
          this.sql.exec(`INSERT INTO limits(key, window_start, count, last_seen)
            VALUES (?, ?, ?, ?) ON CONFLICT(key) DO UPDATE SET
            window_start = excluded.window_start, count = excluded.count,
            last_seen = excluded.last_seen`, key, start, count, now);
        }
        this.sql.exec("DELETE FROM limits WHERE key NOT LIKE 'global%' AND last_seen < ?", now - 600_000);
        return true;
      });
      return new Response(null, { status: allowed ? 204 : 429 });
    } catch {
      return new Response(null, { status: 503 });
    }
  }
}
