import { normalizeStatus, STATUS_LABELS } from './storage.mjs';
import crypto from 'crypto';

/**
 * Модуль аутентификации и панели администратора SmartTube VOX Diagnostics.
 */

/**
 * Проверяет права администратора.
 * Защита по умолчанию: FAIL CLOSED.
 * Доступ разрешается только при валидном ADMIN_SECRET (Bearer или подписанная сессия)
 * либо явном ALLOW_DEV_AUTH без секрета в тестовом окружении.
 */
export const SERVICE_SCOPES = [
  'reports:read',
  'reports:download',
  'reports:update_status',
  'reports:add_note',
  'issues:read',
  'issues:update',
  'issues:add_note',
];

/**
 * Разрешает аутентификацию запроса и возвращает атрибуты:
 * { authorized, actor: 'ADMIN' | 'ANTIGRAVITY' | 'ANONYMOUS', scopes: string[] }
 */
export function resolveAuth(request, env = {}) {
  // Категорически запрещено передавать секрет в query-параметрах URL
  const url = new URL(request.url, 'http://localhost');
  if (url.searchParams.has('token') || url.searchParams.has('secret') || url.searchParams.has('admin_token')) {
    return { authorized: false, actor: 'ANONYMOUS', scopes: [] };
  }

  const secret = env.ADMIN_SECRET;
  const serviceToken = env.VOX_DIAGNOSTICS_SERVICE_TOKEN || env.SERVICE_TOKEN;

  // 1. Bearer токен в заголовке Authorization
  const authHeader = request.headers.get('authorization') || '';
  if (authHeader.startsWith('Bearer ')) {
    const token = authHeader.substring(7).trim();
    if (serviceToken && secureEqual(token, serviceToken)) {
      return { authorized: true, actor: 'ANTIGRAVITY', scopes: [...SERVICE_SCOPES] };
    }
    if (secret && secureEqual(token, secret)) {
      return { authorized: true, actor: 'ADMIN', scopes: [...SERVICE_SCOPES, 'admin:all'] };
    }
  }

  // 2. Подписанная HttpOnly cookie-сессия браузера
  const cookieHeader = request.headers.get('cookie') || '';
  const match = cookieHeader.match(/(?:^|;\s*)vox_admin_session=([^;]+)/);
  if (match && secret && verifyAdminSession(match[1], secret)) {
    return { authorized: true, actor: 'ADMIN', scopes: [...SERVICE_SCOPES, 'admin:all'] };
  }

  // 3. Режим локального тестирования без настроенных секретов
  if (!secret && !serviceToken && (env.ALLOW_DEV_AUTH === true || env.ALLOW_DEV_AUTH === 'true')) {
    return { authorized: true, actor: 'ADMIN', scopes: [...SERVICE_SCOPES, 'admin:all'] };
  }

  return { authorized: false, actor: 'ANONYMOUS', scopes: [] };
}

/**
 * Проверяет права администратора или сервисного вызова.
 * Защита по умолчанию: FAIL CLOSED.
 */
export function checkAdminAuth(request, env = {}, requiredScope = null) {
  const auth = resolveAuth(request, env);
  if (!auth.authorized) return false;
  if (requiredScope && !auth.scopes.includes(requiredScope) && !auth.scopes.includes('admin:all')) {
    return false;
  }
  return true;
}

function secureEqual(left, right) {
  const a = Buffer.from(String(left));
  const b = Buffer.from(String(right));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

export function createAdminSession(secret, now = Date.now()) {
  const expires = now + 8 * 60 * 60 * 1000;
  const nonce = crypto.randomBytes(16).toString('hex');
  const payload = `${expires}.${nonce}`;
  const signature = crypto.createHmac('sha256', secret).update(payload).digest('hex');
  return `${payload}.${signature}`;
}

export function verifyAdminSession(value, secret, now = Date.now()) {
  const parts = String(value).split('.');
  if (parts.length !== 3 || !/^\d{13}$/.test(parts[0]) || !/^[a-f0-9]{32}$/.test(parts[1]) || !/^[a-f0-9]{64}$/.test(parts[2])) return false;
  const expires = Number(parts[0]);
  if (expires <= now || expires > now + 8 * 60 * 60 * 1000) return false;
  const expected = crypto.createHmac('sha256', secret).update(`${parts[0]}.${parts[1]}`).digest('hex');
  return secureEqual(parts[2], expected);
}

export function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * Генерирует современную страницу панели управления диагностикой.
 * Полностью соответствует строгой политике CSP (script-src 'self').
 * Интерактивная логика вынесена в /admin/app.js.
 */
export function renderAdminHtml(reports = [], stats = {}, groupedIssues = []) {
  const totalReports = stats.totalReports || reports.length;
  const reports24h = stats.reports24h || 0;
  const reports7d = stats.reports7d || 0;
  const activeIssues = stats.activeIssuesCount || 0;
  const newCount = stats.newCount ?? 0;
  const inProgressCount = stats.inProgressCount ?? 0;
  const resolvedCount = stats.resolvedCount ?? 0;
  const knownIssueCount = stats.knownIssueCount ?? 0;
  const ignoredTestCount = stats.ignoredTestCount ?? 0;
  const attentionCount = stats.attentionCount ?? 0;

  return `<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta http-equiv="Content-Security-Policy" content="default-src 'self' data:; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none';">
  <meta http-equiv="X-Content-Type-Options" content="nosniff">
  <title>SmartTube VOX — Консоль диагностики и инцидентов</title>
  <style>
    :root {
      --bg: #090d16;
      --surface: #111726;
      --surface-card: #131c2e;
      --surface-alt: #172238;
      --surface-hover: #1c2942;
      --surface-active: #223250;
      --border: #222f46;
      --border-subtle: #1a2538;
      --border-focus: #3b82f6;
      --text: #e2e8f0;
      --text-bright: #ffffff;
      --text-muted: #8492a6;
      --text-dim: #64748b;
      --accent: #3b82f6;
      --accent-hover: #2563eb;
      --accent-active: #1d4ed8;
      --accent-glow: rgba(59, 130, 246, 0.2);
      --success: #10b981;
      --warning: #f59e0b;
      --danger: #ef4444;
      --purple: #8b5cf6;
      --radius-sm: 6px;
      --radius: 10px;
      --radius-lg: 14px;
      --radius-full: 9999px;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
      background: var(--bg);
      color: var(--text);
      line-height: 1.5;
      padding: 20px 24px;
      min-height: 100vh;
    }
    :focus-visible { outline: 2px solid #93c5fd !important; outline-offset: 2px; }

    /* Top Bar */
    .header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 20px;
      padding-bottom: 16px;
      border-bottom: 1px solid var(--border);
      flex-wrap: wrap;
      gap: 12px;
    }
    .logo {
      display: flex;
      align-items: center;
      gap: 10px;
      font-size: 19px;
      font-weight: 700;
      color: var(--text-bright);
      letter-spacing: -0.2px;
    }
    .live-dot {
      width: 8px;
      height: 8px;
      background: var(--success);
      border-radius: 50%;
      box-shadow: 0 0 8px var(--success);
      animation: pulse-dot 2s infinite ease-in-out;
    }
    @keyframes pulse-dot { 0%, 100% { opacity: 1; transform: scale(1); } 50% { opacity: 0.4; transform: scale(0.85); } }
    .badge-vox {
      background: var(--accent);
      color: #fff;
      font-weight: 700;
      font-size: 11px;
      padding: 3px 8px;
      border-radius: var(--radius-sm);
      letter-spacing: 0.6px;
      text-transform: uppercase;
    }
    .header-actions {
      display: flex;
      gap: 10px;
      align-items: center;
      flex-wrap: wrap;
    }
    .retention-pill {
      font-size: 12px;
      color: var(--text-muted);
      background: var(--surface);
      border: 1px solid var(--border);
      padding: 5px 10px;
      border-radius: var(--radius-sm);
    }

    /* KPI Cards */
    .kpi-section {
      margin-bottom: 20px;
    }
    .kpi-primary-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 14px;
      margin-bottom: 12px;
    }
    .kpi-card {
      background: var(--surface-card);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      padding: 16px 18px;
      text-align: left;
      color: inherit;
      cursor: pointer;
      transition: all 0.2s ease;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      position: relative;
      overflow: hidden;
    }
    .kpi-card::before {
      content: '';
      position: absolute;
      top: 0; left: 0; right: 0;
      height: 3px;
      background: transparent;
      transition: background 0.2s;
    }
    .kpi-card[data-metric="NEW"]::before { background: var(--accent); }
    .kpi-card[data-metric="IN_PROGRESS"]::before { background: var(--warning); }
    .kpi-card[data-metric="attention"]::before { background: var(--danger); }
    .kpi-card[data-metric="RESOLVED"]::before { background: var(--success); }
    .kpi-card:hover {
      border-color: var(--border-focus);
      background: var(--surface-alt);
      transform: translateY(-1px);
    }
    .kpi-title {
      font-size: 12px;
      color: var(--text-muted);
      text-transform: uppercase;
      font-weight: 600;
      letter-spacing: 0.5px;
      margin-bottom: 4px;
    }
    .kpi-val {
      font-size: 32px;
      font-weight: 800;
      line-height: 1.1;
      color: var(--text-bright);
      margin: 4px 0 6px 0;
    }
    .kpi-sub {
      font-size: 12px;
      color: var(--text-dim);
    }

    /* Secondary KPI Strip */
    .kpi-strip {
      display: flex;
      gap: 10px;
      overflow-x: auto;
      padding-bottom: 4px;
    }
    .strip-item {
      background: var(--surface);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-sm);
      padding: 8px 14px;
      font-size: 12px;
      color: var(--text-muted);
      display: flex;
      align-items: center;
      gap: 8px;
      cursor: pointer;
      white-space: nowrap;
      transition: all 0.15s;
    }
    .strip-item:hover {
      border-color: var(--border);
      color: var(--text-bright);
      background: var(--surface-alt);
    }
    .strip-item strong {
      color: var(--text-bright);
      font-weight: 700;
    }

    /* Nav Tabs */
    .nav-tabs {
      display: flex;
      gap: 8px;
      margin-bottom: 16px;
      border-bottom: 1px solid var(--border);
      padding-bottom: 2px;
    }
    .nav-tab {
      background: none;
      border: none;
      color: var(--text-muted);
      font-size: 14px;
      font-weight: 600;
      padding: 10px 18px;
      cursor: pointer;
      border-bottom: 2px solid transparent;
      transition: all 0.2s;
      border-radius: var(--radius-sm) var(--radius-sm) 0 0;
    }
    .nav-tab:hover {
      color: var(--text-bright);
      background: rgba(255, 255, 255, 0.03);
    }
    .nav-tab.active {
      color: #fff;
      border-bottom-color: var(--accent);
      background: var(--surface);
    }

    /* Toolbar & Filters */
    .toolbar-container {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      padding: 12px 14px;
      margin-bottom: 16px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .toolbar-main {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
      align-items: center;
    }
    .search-wrapper {
      position: relative;
      flex: 1;
      min-width: 260px;
    }
    .search-input {
      width: 100%;
      background: var(--surface-card);
      border: 1px solid var(--border);
      color: var(--text-bright);
      padding: 8px 32px 8px 12px;
      border-radius: var(--radius-sm);
      font-size: 13px;
      height: 36px;
      outline: none;
      transition: all 0.2s;
    }
    .search-clear {
      position: absolute;
      right: 8px;
      top: 50%;
      transform: translateY(-50%);
      background: none;
      border: none;
      color: var(--text-muted);
      cursor: pointer;
      font-size: 14px;
      padding: 2px 6px;
      display: none;
    }
    .search-input:focus, .select-filter:focus {
      border-color: var(--border-focus);
      box-shadow: 0 0 0 3px var(--accent-glow);
    }
    .select-filter {
      background: var(--surface-card);
      border: 1px solid var(--border);
      color: var(--text-bright);
      padding: 6px 12px;
      border-radius: var(--radius-sm);
      font-size: 13px;
      height: 36px;
      outline: none;
      cursor: pointer;
      transition: all 0.2s;
    }
    .toolbar-extra {
      display: none;
      gap: 10px;
      flex-wrap: wrap;
      padding-top: 10px;
      border-top: 1px solid var(--border-subtle);
      align-items: center;
    }
    .toolbar-extra.open {
      display: flex;
    }

    /* Buttons */
    .btn {
      background: var(--surface-alt);
      border: 1px solid var(--border);
      color: var(--text);
      padding: 6px 14px;
      border-radius: var(--radius-sm);
      cursor: pointer;
      font-size: 13px;
      font-weight: 500;
      height: 36px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      transition: all 0.15s ease;
      white-space: nowrap;
    }
    .btn:hover {
      background: var(--surface-hover);
      color: var(--text-bright);
      border-color: #3b4d6b;
    }
    .btn-primary {
      background: var(--accent);
      border-color: var(--accent);
      color: #fff;
      font-weight: 600;
    }
    .btn-primary:hover {
      background: var(--accent-hover);
      border-color: var(--accent-hover);
    }
    .btn-danger {
      background: rgba(239, 68, 68, 0.12);
      border-color: var(--danger);
      color: var(--danger);
    }
    .btn-danger:hover {
      background: var(--danger);
      color: #fff;
    }
    .btn-outline {
      background: transparent;
    }
    .btn-sm {
      height: 28px;
      padding: 3px 10px;
      font-size: 12px;
    }

    /* Tags & Status Chips */
    .tag {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 2px 8px;
      border-radius: var(--radius-full);
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.2px;
    }
    .tag-android { background: rgba(16, 185, 129, 0.15); color: var(--success); }
    .tag-tizen { background: rgba(59, 130, 246, 0.15); color: var(--accent); }
    .tag-error { background: rgba(239, 68, 68, 0.15); color: #f87171; }
    .tag-status-new { background: rgba(59, 130, 246, 0.15); color: #60a5fa; }
    .tag-status-in_progress, .tag-status-reviewed { background: rgba(245, 158, 11, 0.15); color: #fbbf24; }
    .tag-status-needs_info { background: rgba(168, 85, 247, 0.15); color: #c084fc; }
    .tag-status-fixed_pending_verification { background: rgba(20, 184, 166, 0.15); color: #2dd4bf; }
    .tag-status-closed { background: rgba(16, 185, 129, 0.15); color: #34d399; }
    .tag-status-known, .tag-status-known_issue { background: rgba(148, 163, 184, 0.15); color: #cbd5e1; }
    .tag-status-resolved { background: rgba(16, 185, 129, 0.15); color: #34d399; }
    .tag-status-test, .tag-status-ignored, .tag-status-ignored_test { background: rgba(156, 163, 175, 0.15); color: #9ca3af; }
    .tag-purpose-test { background: rgba(156, 163, 175, 0.2); color: var(--text-muted); font-size: 10px; }

    /* Tables */
    .table-container {
      background: var(--surface-card);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      overflow-x: auto;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      text-align: left;
      font-size: 13px;
    }
    th, td {
      padding: 12px 14px;
      border-bottom: 1px solid var(--border);
      vertical-align: middle;
    }
    th {
      background: var(--surface);
      color: var(--text-muted);
      font-weight: 600;
      text-transform: uppercase;
      font-size: 11px;
      letter-spacing: 0.5px;
      position: sticky;
      top: 0;
      z-index: 2;
    }
    tr:hover td {
      background: rgba(255, 255, 255, 0.02);
    }
    .report-id {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      color: var(--accent);
      font-weight: 600;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }
    .report-id:hover {
      text-decoration: underline;
    }
    .status-select {
      min-width: 130px;
      border: 1px solid var(--border);
      border-radius: var(--radius-full);
      padding: 4px 20px 4px 10px;
      background: var(--surface);
      color: var(--text);
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
      outline: none;
    }
    .status-select[data-status="NEW"] { color: #93c5fd; border-color: rgba(147, 197, 253, 0.3); }
    .status-select[data-status="IN_PROGRESS"] { color: #fbbf24; border-color: rgba(251, 191, 36, 0.3); }
    .status-select[data-status="NEEDS_INFO"] { color: #c084fc; border-color: rgba(192, 132, 252, 0.3); }
    .status-select[data-status="FIXED_PENDING_VERIFICATION"] { color: #2dd4bf; border-color: rgba(45, 212, 191, 0.3); }
    .status-select[data-status="CLOSED"] { color: #34d399; border-color: rgba(52, 211, 153, 0.3); }
    .status-select[data-status="RESOLVED"] { color: #6ee7b7; border-color: rgba(110, 231, 183, 0.3); }
    .status-select[data-status="KNOWN_ISSUE"] { color: #cbd5e1; border-color: rgba(203, 213, 225, 0.3); }
    .status-select[data-status="IGNORED_TEST"], .status-select[data-status="IGNORED"] { color: #9ca3af; border-color: rgba(156, 163, 175, 0.3); }
    .note-preview {
      display: block;
      max-width: 180px;
      color: var(--text-muted);
      font-size: 11px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      margin-top: 4px;
    }
    .table-controls {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-top: 14px;
    }

    /* Side Drawer / Modal */
    .modal-overlay {
      display: none;
      position: fixed;
      top: 0; left: 0; right: 0; bottom: 0;
      background: rgba(0, 0, 0, 0.75);
      backdrop-filter: blur(4px);
      justify-content: flex-end;
      align-items: stretch;
      z-index: 1000;
    }
    .modal {
      background: var(--surface-card);
      border-left: 1px solid var(--border);
      width: min(48vw, 840px);
      height: 100vh;
      max-height: 100vh;
      display: flex;
      flex-direction: column;
      box-shadow: -10px 0 40px rgba(0, 0, 0, 0.7);
      animation: slide-in 0.25s cubic-bezier(0.16, 1, 0.3, 1);
    }
    @keyframes slide-in {
      from { transform: translateX(100%); }
      to { transform: translateX(0); }
    }
    @media (max-width: 960px) {
      .modal { width: 100vw; border-left: none; }
    }
    .modal-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 16px 22px;
      border-bottom: 1px solid var(--border);
      background: var(--surface);
    }
    .modal-body {
      padding: 20px 22px;
      overflow-y: auto;
      flex: 1;
    }
    .modal-tabs {
      display: flex;
      gap: 6px;
      margin-bottom: 16px;
      border-bottom: 1px solid var(--border);
      overflow-x: auto;
    }
    .modal-tab-btn {
      background: none;
      border: none;
      color: var(--text-muted);
      padding: 8px 14px;
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
      border-bottom: 2px solid transparent;
      white-space: nowrap;
      transition: all 0.15s;
    }
    .modal-tab-btn:hover { color: var(--text-bright); }
    .modal-tab-btn.active { color: var(--accent); border-bottom-color: var(--accent); }

    .section-card {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      padding: 16px;
      margin-bottom: 16px;
    }
    .section-title {
      font-size: 12px;
      font-weight: 700;
      color: var(--accent);
      margin-bottom: 12px;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    pre {
      background: #050811;
      border: 1px solid var(--border);
      padding: 14px;
      border-radius: var(--radius-sm);
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 12px;
      overflow-x: auto;
      color: #93c5fd;
      line-height: 1.5;
    }
    .notes-textarea {
      width: 100%;
      min-height: 70px;
      background: #050811;
      border: 1px solid var(--border);
      color: var(--text-bright);
      padding: 10px 12px;
      border-radius: var(--radius-sm);
      font-size: 13px;
      margin-top: 6px;
      resize: vertical;
      font-family: inherit;
      outline: none;
    }
    .notes-textarea:focus {
      border-color: var(--border-focus);
      box-shadow: 0 0 0 2px var(--accent-glow);
    }

    /* Grid layout */
    .grid-2 {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
      gap: 16px;
    }

    /* Action menu dropdown */
    .action-menu-dropdown {
      position: relative;
      display: inline-block;
    }
    .action-menu-popup {
      display: none;
      position: absolute;
      right: 0;
      top: 100%;
      background: var(--surface-card);
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
      box-shadow: 0 10px 25px rgba(0, 0, 0, 0.6);
      min-width: 170px;
      z-index: 50;
      padding: 4px 0;
    }
    .action-menu-popup.open { display: block; }
    .action-menu-item {
      display: flex;
      align-items: center;
      gap: 8px;
      width: 100%;
      padding: 8px 14px;
      background: none;
      border: none;
      color: var(--text);
      font-size: 12px;
      text-align: left;
      cursor: pointer;
    }
    .action-menu-item:hover {
      background: var(--surface-hover);
      color: var(--text-bright);
    }

    /* Timeline & logs */
    .timeline-item {
      border-left: 2px solid var(--border);
      padding: 6px 0 12px 14px;
      margin-left: 6px;
      position: relative;
    }
    .timeline-item::before {
      content: '';
      position: absolute;
      left: -5px;
      top: 8px;
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--border);
    }
    .timeline-item.error::before { background: var(--danger); box-shadow: 0 0 6px var(--danger); }
    .timeline-item.warning::before { background: var(--warning); }
    .timeline-item.info::before { background: var(--accent); }
    .timeline-item.recovery::before { background: var(--success); box-shadow: 0 0 6px var(--success); }

    .toast {
      position: fixed;
      bottom: 24px;
      right: 24px;
      padding: 10px 18px;
      border-radius: var(--radius-sm);
      background: #064e3b;
      color: #d1fae5;
      box-shadow: 0 10px 30px rgba(0, 0, 0, 0.7);
      z-index: 3000;
      font-size: 13px;
      font-weight: 500;
      border: 1px solid #059669;
    }
    .toast.error { background: #7f1d1d; color: #fee2e2; border-color: #dc2626; }
    #refreshState.spinning { display: inline-block; animation: rotate 0.8s linear infinite; color: var(--accent); }
    @keyframes rotate { to { transform: rotate(360deg); } }

    /* Issues list */
    #viewIssues .table-container { background: transparent; border: 0; overflow: visible; }
    #viewIssues table, #viewIssues tbody { display: block; width: 100%; }
    #viewIssues thead { display: none; }
    #viewIssues tbody { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 540px), 1fr)); gap: 16px; }
    #viewIssues tbody tr { display: flex; align-content: flex-start; flex-wrap: wrap; gap: 8px 15px; min-width: 0; padding: 16px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-card); transition: border-color 0.2s; }
    #viewIssues tbody tr:hover { border-color: #3b5072; }
    #viewIssues tbody td { display: block; border: 0; padding: 0; min-width: 0; }
    #viewIssues tbody td:first-child { flex: 1 1 100%; min-height: 50px; overflow-wrap: anywhere; }
    #viewIssues tbody td:nth-child(2) { flex: 1 1 100%; }
    #viewIssues tbody td:nth-child(n+3):nth-child(-n+7) { min-width: 52px; }
    #viewIssues tbody td:nth-child(8) { flex: 1 1 100%; color: var(--text-muted); font-size: 11px; }
    #viewIssues tbody td:nth-child(9) { flex: 1 1 100%; }
    #viewIssues tbody td:last-child { flex: 1 1 100%; display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
    #viewIssues tbody td:last-child input, #viewIssues tbody td:last-child select { flex: 1 1 95px; min-width: 95px !important; width: auto !important; }
    #viewIssues tbody td:last-child button { flex: 1 1 100%; }
    #viewIssues tbody td:nth-child(3)::before { content: 'Всего'; }
    #viewIssues tbody td:nth-child(4)::before { content: 'Новых'; }
    #viewIssues tbody td:nth-child(5)::before { content: 'В работе'; }
    #viewIssues tbody td:nth-child(6)::before { content: 'Решено'; }
    #viewIssues tbody td:nth-child(7)::before { content: 'Известно'; }
    #viewIssues tbody td:nth-child(n+3):nth-child(-n+7)::before { display: block; color: var(--text-dim); font-size: 10px; text-transform: uppercase; margin-bottom: 3px; }
  </style>
</head>
<body>
  <!-- Header -->
  <div class="header">
    <div class="logo">
      <div class="live-dot" title="Служба активна"></div>
      <span>SmartTube VOX</span>
      <span class="badge-vox">ДИАГНОСТИКА</span>
    </div>
    <div class="header-actions">
      <span class="retention-pill">Хранение: 30 дней</span>
      <button class="btn btn-primary" id="refreshButton">
        <span>Обновить</span>
      </button>
      <span id="refreshState" aria-live="polite"></span>
      <div class="action-menu-dropdown">
        <button class="btn" id="headerMoreBtn" title="Ещё действия">⋯</button>
        <div class="action-menu-popup" id="headerMorePopup">
          <button class="action-menu-item" id="purgeAllBtn" style="color: var(--danger);">🗑️ Очистить все логи</button>
        </div>
      </div>
    </div>
  </div>

  <!-- Primary KPIs + Secondary Strip -->
  <div class="kpi-section">
    <div class="kpi-primary-grid">
      <button class="kpi-card" type="button" data-metric="NEW" title="Показать новые отчёты">
        <div>
          <div class="kpi-title">Новые</div>
          <div class="kpi-val" id="kpiNew" style="color: #60a5fa;">${newCount}</div>
        </div>
        <div class="kpi-sub">Требуют первичного анализа</div>
      </button>
      <button class="kpi-card" type="button" data-metric="IN_PROGRESS" title="Показать отчёты в работе">
        <div>
          <div class="kpi-title">В работе</div>
          <div class="kpi-val" id="kpiInProgress" style="color: var(--warning);">${inProgressCount}</div>
        </div>
        <div class="kpi-sub">Назначены и исследуются</div>
      </button>
      <button class="kpi-card" type="button" data-metric="attention" title="Показать критические и повторяющиеся проблемы">
        <div>
          <div class="kpi-title">Требуют внимания</div>
          <div class="kpi-val" id="kpiAttention" style="color: #f87171;">${attentionCount}</div>
        </div>
        <div class="kpi-sub">Повторы и критические сбои</div>
      </button>
      <button class="kpi-card" type="button" data-metric="RESOLVED" title="Показать решённые проблемы">
        <div>
          <div class="kpi-title">Решено</div>
          <div class="kpi-val" id="kpiResolved" style="color: var(--success);">${resolvedCount}</div>
        </div>
        <div class="kpi-sub">Закрытые инциденты</div>
      </button>
    </div>

    <!-- Secondary Strip -->
    <div class="kpi-strip">
      <div class="strip-item" data-metric="24h" title="Отчёты за последние 24 часа">
        <span>За 24 часа:</span> <strong id="kpi24h">${reports24h}</strong>
      </div>
      <div class="strip-item" data-metric="7d" title="Отчёты за последние 7 дней">
        <span>За 7 дней:</span> <strong id="kpi7d">${reports7d}</strong>
      </div>
      <div class="strip-item" data-metric="KNOWN_ISSUE" title="Известные проблемы">
        <span>Известные:</span> <strong id="kpiKnownIssue">${knownIssueCount}</strong>
      </div>
      <div class="strip-item" data-metric="IGNORED_TEST" title="Тестовые и проигнорированные">
        <span>Тест / игнор:</span> <strong id="kpiIgnoredTest">${ignoredTestCount}</strong>
      </div>
      <div class="strip-item" data-metric="all" title="Всего отчётов в базе">
        <span>Всего в базе:</span> <strong id="kpiTotal">${totalReports}</strong>
      </div>
    </div>
  </div>

  <!-- Navigation Tabs -->
  <div class="nav-tabs">
    <button class="nav-tab active" id="tabBtnReports" data-tab="reports">Все отчёты (${reports.length})</button>
    <button class="nav-tab" id="tabBtnIssues" data-tab="issues">Частые проблемы (${groupedIssues.length})</button>
    <button class="nav-tab" id="tabBtnStats" data-tab="metrics" data-tab-name="metrics">Сводка и метрики</button>
  </div>

  <!-- VIEW 1: ВСЕ ОТЧЁТЫ -->
  <div id="viewReports">
    <div class="toolbar-container">
      <div class="toolbar-main">
        <div class="search-wrapper">
          <input type="text" id="searchInput" class="search-input" placeholder="Поиск по ID, устройству, версии или сигнатуре (нажмите / для поиска)...">
          <button class="search-clear" id="searchClearBtn" title="Очистить поиск">✕</button>
        </div>
        
        <select id="filterPlatform" class="select-filter" aria-label="Платформа">
          <option value="">Все платформы</option>
          <option value="Android">Android TV / Google TV</option>
          <option value="Tizen">Samsung Tizen</option>
        </select>

        <select id="filterStatus" class="select-filter" aria-label="Статус">
          <option value="">Все статусы</option>
          <option value="NEW">Новый</option>
          <option value="IN_PROGRESS">В работе</option>
          <option value="NEEDS_INFO">Нужны данные</option>
          <option value="FIXED_PENDING_VERIFICATION">Исправлено — ждёт проверки</option>
          <option value="CLOSED">Закрыто</option>
          <option value="RESOLVED">Решено</option>
          <option value="KNOWN_ISSUE">Известная проблема</option>
          <option value="IGNORED_TEST">Тест / игнор</option>
        </select>

        <select id="filterCategory" class="select-filter" aria-label="Категория">
          <option value="">Все категории</option>
          <option value="DOWNLOAD">DOWNLOAD</option>
          <option value="PLAYBACK">PLAYBACK</option>
          <option value="TRANSLATION">TRANSLATION</option>
          <option value="CODEC">CODEC</option>
          <option value="NETWORK">NETWORK</option>
          <option value="SYSTEM">SYSTEM</option>
        </select>

        <select id="sortReports" class="select-filter" aria-label="Порядок сортировки">
          <option value="newest">Сначала новые</option>
          <option value="oldest">Сначала старые</option>
          <option value="severity">По важности</option>
          <option value="repeats">По повторам</option>
          <option value="status">По статусу</option>
          <option value="version">По версии</option>
        </select>

        <button class="btn" id="toggleMoreFiltersBtn" type="button">
          <span>Ещё фильтры</span>
          <span id="activeFiltersBadge" class="tag tag-status-new" style="display: none; padding: 1px 6px; font-size: 10px;">0</span>
        </button>

        <button class="btn btn-outline" id="resetFiltersBtn" type="button" style="display: none;">
          <span>Сбросить</span>
        </button>
      </div>

      <!-- Expandable Extra Filters Tray -->
      <div class="toolbar-extra" id="toolbarExtra">
        <select id="filterSeverity" class="select-filter" aria-label="Важность">
          <option value="">Любая важность</option>
          <option>CRITICAL</option>
          <option>HIGH</option>
          <option>MEDIUM</option>
          <option>LOW</option>
          <option>INFO</option>
        </select>
        <select id="filterPurpose" class="select-filter" aria-label="Цель отчёта">
          <option value="">Любая цель</option>
          <option value="USER">Пользовательские (USER)</option>
          <option value="TEST">Тестовые (TEST)</option>
        </select>
        <input id="filterVersion" class="search-input" aria-label="Версия" placeholder="Версия" style="min-width:110px;max-width:140px">
        <input id="filterDevice" class="search-input" aria-label="Устройство" placeholder="Устройство" style="min-width:110px;max-width:140px">
        <input id="filterSignature" class="search-input" aria-label="Сигнатура" placeholder="Сигнатура" style="min-width:140px;max-width:190px">
        <select id="filterSpecial" class="select-filter" aria-label="Специальный фильтр">
          <option value="">Все отчёты</option>
          <option value="attention">Требует внимания</option>
          <option value="notes">С заметками</option>
          <option value="duplicates">С повторами</option>
        </select>
      </div>
    </div>

    <!-- Reports Table -->
    <div class="table-container">
      <table id="reportsTable">
        <thead>
          <tr>
            <th>Код отчёта</th>
            <th>Время</th>
            <th>Платформа</th>
            <th>Версия</th>
            <th>Устройство</th>
            <th>Категория / Сбой</th>
            <th>Статус</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody id="reportsTableBody">
          ${reports.length === 0 ? `
          <tr id="reportsEmptyRow">
            <td colspan="8" style="text-align: center; color: var(--text-muted); padding: 40px;">
              Отчётов пока нет.
            </td>
          </tr>` : reports.map(r => {
            const normStatus = normalizeStatus(r.status);
            const statusLabel = STATUS_LABELS[normStatus] || normStatus;
            return `
          <tr data-report-id="${escapeHtml(r.report_id)}"
              data-created-at="${Number(r.created_at)}"
              data-platform="${escapeHtml(r.platform || '')}"
              data-version="${escapeHtml(r.app_version || '')}"
              data-device="${escapeHtml(r.device_family || '')}"
              data-category="${escapeHtml(r.error_category || '')}"
              data-status="${escapeHtml(normStatus)}"
              data-purpose="${escapeHtml(r.report_purpose || 'USER')}"
              data-signature="${escapeHtml(r.error_signature || '')}">
            <td>
              <span class="report-id" data-action="open-report" data-report-id="${escapeHtml(r.report_id)}">${escapeHtml(r.report_id)}</span>
              ${r.report_purpose === 'TEST' ? '<span class="tag tag-purpose-test">TEST</span>' : ''}
              ${Number(r.related_count || 0) > 0 ? `<button class="btn btn-sm" data-action="filter-signature" data-signature="${escapeHtml(r.error_signature || '')}" title="Показать похожие отчёты">Похожие: ${Number(r.related_count)}</button>` : ''}
              ${Number(r.related_count || 0) >= 2 ? '<span class="tag tag-status-in_progress">Повторы</span>' : ''}
            </td>
            <td>${new Date(r.created_at).toLocaleString('ru-RU')}</td>
            <td><span class="tag ${(r.platform || '').includes('Tizen') ? 'tag-tizen' : 'tag-android'}">${escapeHtml(r.platform || '-')}</span></td>
            <td>${escapeHtml(r.app_version || '-')}</td>
            <td>${escapeHtml(r.device_family || '-')}</td>
            <td>
              ${r.error_category ? `<span class="tag tag-error">${escapeHtml(r.error_category)}</span>` : '<span style="color: var(--text-muted);">-</span>'}
              ${r.error_signature ? `<small style="display:block;color:var(--text-dim);font-family:monospace;font-size:11px;">${escapeHtml(r.error_signature)}</small>` : ''}
            </td>
            <td><select class="status-select" data-status="${normStatus}" aria-label="Статус отчёта ${escapeHtml(r.report_id)}">${Object.entries(STATUS_LABELS).map(([key,label]) => `<option value="${key}" ${key === normStatus ? 'selected' : ''}>${escapeHtml(label)}</option>`).join('')}</select></td>
            <td style="white-space: nowrap;">
              <button class="btn btn-primary btn-open-report" data-action="open-report" data-report-id="${escapeHtml(r.report_id)}" style="margin-right: 4px;">Открыть</button>
              <button class="btn" data-action="quick-note" data-report-id="${escapeHtml(r.report_id)}">Заметка</button>
              <button class="btn btn-download-report" data-action="download-report" data-report-id="${escapeHtml(r.report_id)}" title="Скачать JSON">Скачать</button>
              ${r.developer_notes ? `<span class="note-preview" title="${escapeHtml(r.developer_notes)}">✎ ${escapeHtml(r.developer_notes)}</span>` : ''}
            </td>
          </tr>`;
          }).join('')}
        </tbody>
      </table>
    </div>
    <div class="table-controls">
      <span id="pageLabel" style="font-size: 13px; color: var(--text-muted);">Страница 1</span>
      <div style="display: flex; gap: 8px;">
        <button class="btn" id="pageBack" disabled>← Назад</button>
        <button class="btn" id="pageNext" ${reports.length < 50 ? 'disabled' : ''}>Вперёд →</button>
      </div>
    </div>
  </div>

  <!-- VIEW 2: ЧАСТЫЕ ПРОБЛЕМЫ (GROUPED ISSUES) -->
  <div id="viewIssues" style="display: none;">
    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>Проблема</th>
            <th>Категория</th>
            <th>Всего</th>
            <th>Новый</th>
            <th>В работе</th>
            <th>Решено</th>
            <th>Известная</th>
            <th>Последнее появление</th>
            <th>Действие</th>
            <th>Исправление</th>
          </tr>
        </thead>
        <tbody id="issuesTableBody">
          ${groupedIssues.length === 0 ? `
          <tr>
            <td colspan="10" style="text-align: center; color: var(--text-muted); padding: 40px;">
              Сгруппированных проблем пока нет.
            </td>
          </tr>` : groupedIssues.map(i => `
          <tr data-issue-signature="${escapeHtml(i.error_signature || '')}">
            <td style="font-family: monospace; font-size: 12px; color: var(--accent); font-weight: 600;">
              <input class="search-input issue-title" aria-label="Название проблемы" placeholder="Название проблемы" value="${escapeHtml(i.title || '')}" style="width:100%;min-width:0">
              <small style="display:block;color:var(--text-muted);margin-top:2px;">${escapeHtml(i.error_signature || '')}</small>
              <div style="margin-top:4px;display:flex;gap:6px;align-items:center;">
                <span class="tag ${i.severity === 'CRITICAL' || i.severity === 'HIGH' ? 'tag-error' : 'tag-status-test'}">${escapeHtml(i.severity || 'INFO')}</span>
                ${i.reopened_at ? '<span class="tag tag-status-in_progress">Повторно открыто</span>' : ''}
              </div>
              <small style="display:block;margin-top:4px;">Версии: ${escapeHtml((i.affected_versions || []).join(', '))}</small>
              <small style="display:block;color:var(--text-dim)">Первое: ${new Date(i.first_seen).toLocaleString('ru-RU')}</small>
            </td>
            <td><span class="tag tag-error">${escapeHtml(i.error_category || 'ERROR')}</span></td>
            <td><strong style="color: var(--text-bright); font-size: 15px;">${i.count || 1}</strong></td>
            <td><span class="tag tag-status-new">${i.new_count || 0}</span></td>
            <td><span class="tag tag-status-in_progress">${i.in_progress_count || 0}</span></td>
            <td><span class="tag tag-status-resolved">${i.resolved_count || 0}</span></td>
            <td><span class="tag tag-status-known_issue">${i.known_issue_count || 0}</span></td>
            <td>${new Date(i.last_seen).toLocaleString('ru-RU')}</td>
            <td style="white-space: nowrap;">
              <button class="btn btn-primary btn-open-sample" data-action="open-report" data-report-id="${escapeHtml(i.sample_report_id || '')}" style="margin-right: 6px;">Образец</button>
              <button class="btn" data-action="filter-signature" data-signature="${escapeHtml(i.error_signature || '')}">В отчёты</button>
            </td>
            <td>
              <select class="select-filter issue-status" aria-label="Статус проблемы">${Object.entries(STATUS_LABELS).map(([key,label]) => `<option value="${key}" ${key === i.issue_status ? 'selected' : ''}>${escapeHtml(label)}</option>`).join('')}</select>
              <input class="search-input issue-version" aria-label="Версия исправления" placeholder="Версия" value="${escapeHtml(i.fixed_in_version || '')}" style="min-width:100px;width:110px">
              <input class="search-input issue-patch" aria-label="Патч" placeholder="Patch" value="${escapeHtml(i.fix_patch || '')}" style="min-width:85px;width:90px">
              <input class="search-input issue-commit" aria-label="Коммит" placeholder="Commit" value="${escapeHtml(i.fix_commit || '')}" style="min-width:95px;width:100px">
              <button class="btn" data-action="save-issue">Сохранить</button>
            </td>
          </tr>`).join('')}
        </tbody>
      </table>
    </div>
  </div>

  <!-- VIEW 3: СВОДКА И МЕТРИКИ (STATS) -->
  <div id="viewStats" style="display: none;">
    <div class="grid-2">
      <div class="section-card">
        <div class="section-title">Распределение по статусам</div>
        <table>
          <thead><tr><th>Статус</th><th>Количество</th></tr></thead>
          <tbody id="statsStatusBody">
            ${(stats.statusBreakdown || []).map(s => `<tr><td><span class="tag tag-status-${(s.status || '').toLowerCase()}">${escapeHtml(s.label || s.status)}</span></td><td><strong>${s.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет данных</td></tr>'}
          </tbody>
        </table>
      </div>

      <div class="section-card">
        <div class="section-title">Распределение по платформам</div>
        <table>
          <thead><tr><th>Платформа</th><th>Количество</th></tr></thead>
          <tbody id="statsPlatformBody">
            ${(stats.platformBreakdown || []).map(p => `<tr><td>${escapeHtml(p.platform)}</td><td><strong>${p.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет данных</td></tr>'}
          </tbody>
        </table>
      </div>

      <div class="section-card">
        <div class="section-title">Распределение по версиям</div>
        <table>
          <thead><tr><th>Версия</th><th>Количество</th></tr></thead>
          <tbody id="statsVersionBody">
            ${(stats.versionDistribution || []).map(v => `<tr><td>${escapeHtml(v.app_version)}</td><td><strong>${v.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет данных</td></tr>'}
          </tbody>
        </table>
      </div>

      <div class="section-card">
        <div class="section-title">Топ категорий ошибок</div>
        <table>
          <thead><tr><th>Категория</th><th>Количество</th></tr></thead>
          <tbody id="statsCategoryBody">
            ${(stats.topCategories || []).map(c => `<tr><td><span class="tag tag-error">${escapeHtml(c.error_category)}</span></td><td><strong>${c.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет ошибок</td></tr>'}
          </tbody>
        </table>
      </div>

      <div class="section-card" style="grid-column: 1 / -1;">
        <div class="section-title">Топ сигнатур сбоев</div>
        <table>
          <thead><tr><th>Сигнатура</th><th>Количество</th></tr></thead>
          <tbody id="statsIssuesBody">
            ${(stats.topIssues || []).map(i => `<tr><td style="font-family: monospace; font-size: 11px;">${escapeHtml(i.error_signature)}</td><td><strong>${i.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет данных</td></tr>'}
          </tbody>
        </table>
      </div>
    </div>
  </div>

  <!-- MODAL: ДЕТАЛИ ОТЧЁТА (SIDE DRAWER) -->
  <div id="modalOverlay" class="modal-overlay" role="presentation">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="modalTitle">
      <div class="modal-header">
        <div>
          <h3 id="modalTitle" style="color: var(--text-bright); display: inline-block; margin-right: 12px; font-size: 16px;">Детали отчёта</h3>
          <span id="modalPurposeBadge"></span>
        </div>
        <button class="btn btn-sm" id="modalCloseBtn" data-action="close-modal" title="Закрыть (Esc)">✕ Закрыть (Esc)</button>
      </div>

      <div class="modal-body">
        <!-- TOP WORKFLOW CONTROL BAR -->
        <div class="section-card" id="detailWorkflowCard" style="margin-bottom: 16px; border-left: 3px solid var(--accent); background: var(--surface);">
          <div style="display: flex; gap: 14px; align-items: flex-start; flex-wrap: wrap;">
            <div style="flex: 1; min-width: 260px;">
              <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 8px;">
                <label for="detailStatusSelect" style="font-size: 12px; font-weight: 600; text-transform: uppercase; color: var(--text-muted);">Статус:</label>
                <select id="detailStatusSelect" class="select-filter" style="font-weight: 600;">
                  <option value="NEW">Новый</option>
                  <option value="IN_PROGRESS">В работе</option>
                  <option value="NEEDS_INFO">Нужны данные</option>
                  <option value="FIXED_PENDING_VERIFICATION">Исправлено — ждёт проверки</option>
                  <option value="CLOSED">Закрыто</option>
                  <option value="RESOLVED">Решено</option>
                  <option value="KNOWN_ISSUE">Известная проблема</option>
                  <option value="IGNORED_TEST">Тест / игнор</option>
                </select>
                <button class="btn btn-primary" id="detailSaveBtn" data-action="save-detail-ops">Сохранить</button>
              </div>
              <label for="detailNotesText" style="font-size: 11px; font-weight: 600; color: var(--text-muted); display: block; margin-bottom: 4px; text-transform: uppercase;">Заметка разработчика:</label>
              <textarea id="detailNotesText" class="notes-textarea" style="min-height: 52px;" placeholder="Причина сбоя, ссылка на PR, фикс или пояснение..."></textarea>
            </div>
            <div style="display: flex; flex-direction: column; gap: 6px; min-width: 170px;">
              <span style="font-size: 11px; text-transform: uppercase; color: var(--text-muted); font-weight: 600;">Действия:</span>
              <button class="btn btn-sm" id="detailDownloadJsonBtn" data-action="download-json">💾 Скачать JSON</button>
              <button class="btn btn-sm" id="detailCopyMdBtn" data-action="copy-markdown">📋 Скопировать MD</button>
              <button class="btn btn-sm btn-danger" id="detailDeleteBtn" data-action="delete-report">🗑️ Удалить отчёт</button>
            </div>
          </div>
        </div>

        <div class="modal-tabs">
          <button class="modal-tab-btn active" id="mTabBtnOverview" data-modal-tab="overview">Общее</button>
          <button class="modal-tab-btn" id="mTabBtnCodecs" data-modal-tab="codecs">Кодеки</button>
          <button class="modal-tab-btn" id="mTabBtnTimeline" data-modal-tab="timeline">События</button>
          <button class="modal-tab-btn" id="mTabBtnOps" data-modal-tab="ops">Разработчик</button>
          <button class="modal-tab-btn" id="mTabBtnRaw" data-modal-tab="raw">Тех. JSON</button>
        </div>

        <div id="modalContent">
          <p style="color: var(--text-muted); padding: 24px; text-align: center;">Выберите отчёт для просмотра деталей.</p>
        </div>
      </div>
    </div>
  </div>

  <div id="noteDialog" class="modal-overlay" role="presentation">
    <div class="modal" role="dialog" aria-modal="true" aria-label="Заметка разработчика" style="max-width:460px;height:auto;margin:auto;border-radius:var(--radius);">
      <div class="modal-header">
        <h3 style="font-size: 15px;">Заметка разработчика</h3>
        <button class="btn btn-sm" id="noteCancel">Закрыть</button>
      </div>
      <div class="modal-body">
        <label for="quickNoteText" style="font-size: 12px; color: var(--text-muted);">Текст заметки</label>
        <textarea id="quickNoteText" class="notes-textarea" maxlength="4000" placeholder="Введите заметку к инциденту..."></textarea>
        <button id="noteSave" class="btn btn-primary" style="margin-top:12px;width:100%;">Сохранить заметку</button>
      </div>
    </div>
  </div>

  <div id="toast" class="toast" role="status" aria-live="polite" hidden></div>

  <!-- MODAL: ОЧИСТКА ВСЕХ ЛОГОВ (SAFE PURGE) -->
  <div id="purgeModalOverlay" class="modal-overlay">
    <div class="modal" style="max-width: 520px; height: auto; margin: auto; border-radius: var(--radius);">
      <div class="modal-header">
        <h3 style="color: var(--danger); font-size: 16px;">Удалить все диагностические отчёты?</h3>
        <button class="btn btn-sm" id="purgeModalCloseBtn" data-action="close-purge-modal">✕</button>
      </div>
      <div class="modal-body">
        <p style="margin-bottom: 14px; font-size: 14px; color: var(--text); line-height: 1.6;">
          Будут безвозвратно удалены все диагностические отчёты SmartTube VOX.<br>
          <strong style="color: #fca5a5;">Это действие нельзя отменить.</strong>
        </p>
        <label for="purgeConfirmInput" style="display: block; font-size: 13px; margin-bottom: 8px; color: var(--text-muted);">
          Введите слово: <strong style="color: var(--danger); font-family: monospace; font-size: 15px;">УДАЛИТЬ</strong>
        </label>
        <input type="text" id="purgeConfirmInput" class="search-input" style="width: 100%; margin-bottom: 18px;" placeholder="УДАЛИТЬ" autocomplete="off">
        <div style="display: flex; justify-content: flex-end; gap: 10px;">
          <button class="btn" id="purgeCancelBtn" data-action="cancel-purge">Отмена</button>
          <button class="btn btn-danger" id="purgeConfirmBtn" data-action="confirm-purge" disabled>Удалить все</button>
        </div>
      </div>
    </div>
  </div>

  <script src="/admin/app.js" defer></script>
</body>
</html>`;
}
