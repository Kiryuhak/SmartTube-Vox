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
export function checkAdminAuth(request, env = {}) {
  const secret = env.ADMIN_SECRET;

  // Разрешено без секрета ТОЛЬКО если явно включен режим локального тестирования
  if (!secret) {
    if (env.ALLOW_DEV_AUTH === true || env.ALLOW_DEV_AUTH === 'true') {
      return true;
    }
    return false; // Fail closed in production if secret is not configured
  }

  // API clients may use a bearer token. Browser sessions use a short-lived HMAC cookie.
  const authHeader = request.headers.get('authorization') || '';
  if (authHeader.startsWith('Bearer ') && secureEqual(authHeader.substring(7).trim(), secret)) {
    return true;
  }
  const cookieHeader = request.headers.get('cookie') || '';
  const match = cookieHeader.match(/(?:^|;\s*)vox_admin_session=([^;]+)/);
  if (match) return verifyAdminSession(match[1], secret);

  return false;
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
      --bg: #0b0f19;
      --card-bg: #111827;
      --card-alt: #1f2937;
      --border: #374151;
      --border-focus: #3b82f6;
      --text: #e5e7eb;
      --text-bright: #ffffff;
      --text-muted: #9ca3af;
      --accent: #3b82f6;
      --accent-hover: #2563eb;
      --accent-glow: rgba(59, 130, 246, 0.2);
      --success: #10b981;
      --warning: #f59e0b;
      --danger: #ef4444;
      --purple: #8b5cf6;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      background: var(--bg);
      color: var(--text);
      line-height: 1.5;
      padding: 24px;
    }
    .header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 24px;
      padding-bottom: 16px;
      border-bottom: 1px solid var(--border);
    }
    .logo {
      display: flex;
      align-items: center;
      gap: 12px;
      font-size: 20px;
      font-weight: 700;
      color: var(--text-bright);
    }
    .badge-vox {
      background: var(--accent);
      color: #fff;
      font-weight: 700;
      font-size: 11px;
      padding: 2px 8px;
      border-radius: 4px;
      letter-spacing: 0.5px;
    }
    .kpi-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 16px;
      margin-bottom: 24px;
    }
    .kpi-card {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 16px;
      box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
      text-align: left;
      color: inherit;
      cursor: pointer;
    }
    .kpi-card:hover { border-color: var(--accent); }
    :focus-visible { outline: 2px solid #93c5fd !important; outline-offset: 2px; }
    .kpi-title {
      font-size: 12px;
      color: var(--text-muted);
      text-transform: uppercase;
      letter-spacing: 0.5px;
      margin-bottom: 6px;
    }
    .kpi-val {
      font-size: 28px;
      font-weight: 700;
      color: var(--text-bright);
    }
    .nav-tabs {
      display: flex;
      gap: 8px;
      margin-bottom: 20px;
      border-bottom: 1px solid var(--border);
      padding-bottom: 8px;
    }
    .nav-tab {
      background: none;
      border: none;
      color: var(--text-muted);
      font-size: 14px;
      font-weight: 600;
      padding: 8px 16px;
      cursor: pointer;
      border-radius: 6px;
      transition: all 0.2s;
    }
    .nav-tab:hover {
      color: var(--text-bright);
      background: var(--card-alt);
    }
    .nav-tab.active {
      color: #fff;
      background: var(--accent);
    }
    .toolbar {
      display: flex;
      gap: 12px;
      margin-bottom: 16px;
      flex-wrap: wrap;
    }
    .search-input, .select-filter {
      background: var(--card-bg);
      border: 1px solid var(--border);
      color: var(--text-bright);
      padding: 8px 12px;
      border-radius: 6px;
      font-size: 13px;
      outline: none;
      transition: border-color 0.2s;
    }
    .search-input {
      flex: 1;
      min-width: 260px;
    }
    .search-input:focus, .select-filter:focus {
      border-color: var(--border-focus);
      box-shadow: 0 0 0 3px var(--accent-glow);
    }
    .table-container {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 8px;
      overflow-x: auto;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      text-align: left;
      font-size: 13px;
    }
    th, td {
      padding: 12px 16px;
      border-bottom: 1px solid var(--border);
      vertical-align: middle;
    }
    th {
      background: #0d131f;
      color: var(--text-muted);
      font-weight: 600;
      text-transform: uppercase;
      font-size: 11px;
      letter-spacing: 0.5px;
    }
    tr:hover td {
      background: rgba(255, 255, 255, 0.02);
    }
    .report-id {
      font-family: monospace;
      color: var(--accent);
      font-weight: 600;
      cursor: pointer;
    }
    .report-id:hover {
      text-decoration: underline;
    }
    .btn {
      background: var(--card-alt);
      border: 1px solid var(--border);
      color: var(--text-bright);
      padding: 6px 12px;
      border-radius: 6px;
      cursor: pointer;
      font-size: 12px;
      font-weight: 500;
      transition: all 0.2s;
    }
    .btn:hover {
      background: var(--border);
    }
    .btn-primary {
      background: var(--accent);
      border-color: var(--accent);
      color: #fff;
      font-weight: 600;
    }
    .btn-primary:hover {
      background: var(--accent-hover);
    }
    .btn-danger {
      background: rgba(239, 68, 68, 0.15);
      border-color: var(--danger);
      color: var(--danger);
    }
    .btn-danger:hover {
      background: var(--danger);
      color: #fff;
    }
    .tag {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 12px;
      font-size: 11px;
      font-weight: 600;
    }
    .tag-android { background: rgba(16, 185, 129, 0.15); color: var(--success); }
    .tag-tizen { background: rgba(59, 130, 246, 0.15); color: var(--accent); }
    .tag-error { background: rgba(239, 68, 68, 0.15); color: var(--danger); }
    .tag-status-new { background: rgba(59, 130, 246, 0.2); color: #60a5fa; }
    .tag-status-in_progress, .tag-status-reviewed { background: rgba(245, 158, 11, 0.2); color: #f59e0b; }
    .tag-status-known, .tag-status-known_issue { background: rgba(139, 92, 246, 0.2); color: #a78bfa; }
    .tag-status-resolved { background: rgba(16, 185, 129, 0.2); color: #10b981; }
    .tag-status-test, .tag-status-ignored_test { background: rgba(156, 163, 175, 0.2); color: #9ca3af; }
    .tag-purpose-test { background: rgba(156, 163, 175, 0.2); color: var(--text-muted); font-size: 10px; }

    /* Modal / Drawer */
    .modal-overlay {
      display: none;
      position: fixed;
      top: 0; left: 0; right: 0; bottom: 0;
      background: rgba(0, 0, 0, 0.75);
      backdrop-filter: blur(4px);
      justify-content: center;
      align-items: center;
      z-index: 1000;
    }
    .modal {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 12px;
      width: 92%;
      max-width: 960px;
      max-height: 88vh;
      display: flex;
      flex-direction: column;
      box-shadow: 0 20px 40px rgba(0, 0, 0, 0.6);
    }
    #modalOverlay { justify-content: flex-end; align-items: stretch; }
    #modalOverlay .modal { width: min(43vw, 760px); max-width: none; max-height: 100vh; height: 100vh; border-radius: 14px 0 0 14px; }
    #modalOverlay .modal-body { padding: 20px; }
    #detailWorkflowCard > div { gap: 10px !important; }
    #detailWorkflowCard > div > div:first-child { min-width: 0 !important; }
    #detailWorkflowCard > div > div:last-child { display: flex !important; flex: 1 1 100%; flex-direction: row !important; flex-wrap: wrap; min-width: 0 !important; }
    #detailWorkflowCard > div > div:last-child > span { width: 100%; }
    #detailWorkflowCard > div > div:last-child > button { flex: 1 1 auto; }
    #detailNotesText { min-height: 42px !important; }
    #modalOverlay .grid-2 { grid-template-columns: 1fr; }
    #modalOverlay .modal-tabs { overflow-x: auto; }
    #modalOverlay .modal-tab-btn { white-space: nowrap; }
    @media (max-width: 900px) { #modalOverlay .modal { width: 100vw; border-radius: 0; } }
    @media (max-width: 600px) { #modalOverlay .modal-tab-btn { padding: 8px 5px; font-size: 11px; } #modalOverlay .modal-body { padding: 16px; } }
    .status-select { min-width: 120px; border: 1px solid var(--border); border-radius: 16px; padding: 4px 24px 4px 9px; background: #1b2638; color: var(--text); font-size: 12px; }
    .status-select[data-status="NEW"] { color: #93c5fd; }
    .status-select[data-status="IN_PROGRESS"] { color: #fbbf24; }
    .status-select[data-status="RESOLVED"] { color: #6ee7b7; }
    .status-select[data-status="KNOWN_ISSUE"] { color: #c4b5fd; }
    .status-select[data-status="IGNORED_TEST"] { color: #d1d5db; }
    .note-preview { display: block; max-width: 170px; color: var(--text-muted); font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .table-controls { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 12px; }
    #viewIssues .table-container { background: transparent; border: 0; overflow: visible; }
    #viewIssues table, #viewIssues tbody { display: block; width: 100%; }
    #viewIssues thead { display: none; }
    #viewIssues tbody { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 520px), 1fr)); gap: 16px; }
    #viewIssues tbody tr { display: flex; align-content: flex-start; flex-wrap: wrap; gap: 8px 15px; min-width: 0; padding: 16px; border: 1px solid var(--border); border-radius: 12px; background: var(--card-bg); }
    #viewIssues tbody tr:hover { border-color: #536786; }
    #viewIssues tbody td { display: block; border: 0; padding: 0; min-width: 0; }
    #viewIssues tbody td:first-child { flex: 1 1 100%; min-height: 55px; overflow-wrap: anywhere; }
    #viewIssues tbody td:nth-child(2) { flex: 1 1 100%; }
    #viewIssues tbody td:nth-child(n+3):nth-child(-n+7) { min-width: 52px; }
    #viewIssues tbody td:nth-child(8) { flex: 1 1 100%; color: var(--text-muted); font-size: 11px; }
    #viewIssues tbody td:nth-child(9) { flex: 1 1 100%; }
    #viewIssues tbody td:last-child { flex: 1 1 100%; display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px; }
    #viewIssues tbody td:last-child input, #viewIssues tbody td:last-child select { flex: 1 1 95px; min-width: 95px !important; width: auto !important; }
    #viewIssues tbody td:last-child button { flex: 1 1 100%; }
    #viewIssues tbody td:nth-child(3)::before { content: 'Всего'; }
    #viewIssues tbody td:nth-child(4)::before { content: 'Новых'; }
    #viewIssues tbody td:nth-child(5)::before { content: 'В работе'; }
    #viewIssues tbody td:nth-child(6)::before { content: 'Решено'; }
    #viewIssues tbody td:nth-child(7)::before { content: 'Известно'; }
    #viewIssues tbody td:nth-child(n+3):nth-child(-n+7)::before { display: block; color: var(--text-muted); font-size: 10px; text-transform: uppercase; margin-bottom: 3px; }
    .toast { position: fixed; bottom: 24px; right: 24px; padding: 10px 16px; border-radius: 9px; background: #173a33; color: #d1fae5; box-shadow: 0 10px 30px #0008; z-index: 3000; }
    .toast.error { background: #512129; color: #fee2e2; }
    #refreshState.spinning { display: inline-block; animation: rotate 0.8s linear infinite; color: var(--accent); }
    @keyframes rotate { to { transform: rotate(360deg); } }
    .timeline-item { border-left: 2px solid var(--border); padding: 7px 0 11px 14px; margin-left: 5px; }
    .timeline-item.error { border-color: var(--danger); }
    .timeline-item.warning { border-color: var(--warning); }
    .timeline-item.info { border-color: var(--accent); }
    .modal-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 16px 24px;
      border-bottom: 1px solid var(--border);
    }
    .modal-body {
      padding: 24px;
      overflow-y: auto;
      flex: 1;
    }
    .modal-tabs {
      display: flex;
      gap: 8px;
      margin-bottom: 16px;
      border-bottom: 1px solid var(--border);
    }
    .modal-tab-btn {
      background: none;
      border: none;
      color: var(--text-muted);
      padding: 8px 12px;
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
      border-bottom: 2px solid transparent;
    }
    .modal-tab-btn:hover {
      color: var(--text-bright);
    }
    .modal-tab-btn.active {
      color: var(--accent);
      border-bottom-color: var(--accent);
    }
    .grid-2 {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
    }
    @media (max-width: 768px) {
      .grid-2 { grid-template-columns: 1fr; }
    }
    .section-card {
      background: #0d131f;
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 14px;
      margin-bottom: 16px;
    }
    .section-title {
      font-size: 13px;
      font-weight: 700;
      color: var(--accent);
      margin-bottom: 10px;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    pre {
      background: #080c14;
      border: 1px solid var(--border);
      padding: 14px;
      border-radius: 6px;
      font-family: monospace;
      font-size: 12px;
      overflow-x: auto;
      color: #93c5fd;
    }
    .notes-textarea {
      width: 100%;
      min-height: 80px;
      background: #080c14;
      border: 1px solid var(--border);
      color: var(--text-bright);
      padding: 10px;
      border-radius: 6px;
      font-size: 13px;
      margin-top: 6px;
      resize: vertical;
    }
    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.4; }
    }
  </style>
</head>
<body>
  <div class="header">
    <div class="logo">
      <span>SmartTube VOX</span>
      <span class="badge-vox">ДИАГНОСТИКА</span>
    </div>
    <div style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap;">
      <span style="font-size: 13px; color: var(--text-muted);">Хранение: 30 дней</span>
      <button class="btn btn-primary" id="refreshButton">Обновить</button><span id="refreshState" aria-live="polite"></span>
      <button class="btn btn-danger" id="purgeAllBtn" style="font-size: 12px; padding: 6px 12px;">Очистить все логи</button>
    </div>
  </div>

  <div class="kpi-grid">
    <button class="kpi-card" type="button" data-metric="24h">
      <div class="kpi-title">Отчётов за 24ч</div>
      <div class="kpi-val" id="kpi24h">${reports24h}</div>
    </button>
    <button class="kpi-card" type="button" data-metric="7d">
      <div class="kpi-title">Отчётов за 7 дней</div>
      <div class="kpi-val" id="kpi7d">${reports7d}</div>
    </button>
    <button class="kpi-card" type="button" data-metric="NEW">
      <div class="kpi-title">Новый</div>
      <div class="kpi-val" id="kpiNew" style="color: #60a5fa;">${stats.newCount ?? 0}</div>
    </button>
    <button class="kpi-card" type="button" data-metric="IN_PROGRESS">
      <div class="kpi-title">В работе</div>
      <div class="kpi-val" id="kpiInProgress" style="color: var(--warning);">${stats.inProgressCount ?? 0}</div>
    </button>
    <button class="kpi-card" type="button" data-metric="RESOLVED">
      <div class="kpi-title">Решено</div>
      <div class="kpi-val" id="kpiResolved" style="color: var(--success);">${stats.resolvedCount ?? 0}</div>
    </button>
    <button class="kpi-card" type="button" data-metric="KNOWN_ISSUE">
      <div class="kpi-title">Известная проблема</div>
      <div class="kpi-val" id="kpiKnownIssue" style="color: var(--purple);">${stats.knownIssueCount ?? 0}</div>
    </button>
    <button class="kpi-card" type="button" data-metric="IGNORED_TEST">
      <div class="kpi-title">Тест / игнор</div>
      <div class="kpi-val" id="kpiIgnoredTest" style="color: var(--text-muted);">${stats.ignoredTestCount ?? 0}</div>
    </button>
    <button class="kpi-card" type="button" data-metric="all">
      <div class="kpi-title">Всего в базе</div>
      <div class="kpi-val" id="kpiTotal">${totalReports}</div>
    </button>
  </div>

  <div class="nav-tabs">
    <button class="nav-tab active" id="tabBtnReports" data-tab="reports">Все отчёты (${reports.length})</button>
    <button class="nav-tab" id="tabBtnIssues" data-tab="issues">Частые проблемы (${groupedIssues.length})</button>
    <button class="nav-tab" id="tabBtnStats" data-tab="metrics" data-tab-name="metrics">Сводка и метрики</button>
  </div>

  <!-- VIEW 1: ВСЕ ОТЧЁТЫ -->
  <div id="viewReports">
    <div class="toolbar">
      <input type="text" id="searchInput" class="search-input" placeholder="Поиск по ID отчёта (VOX-A-...), устройству, версии или сигнатуре...">
      
      <select id="filterPlatform" class="select-filter">
        <option value="">Все платформы</option>
        <option value="Android">Android TV / Google TV</option>
        <option value="Tizen">Samsung Tizen</option>
      </select>

      <select id="filterStatus" class="select-filter">
        <option value="">Все статусы</option>
        <option value="NEW">Новый</option>
        <option value="IN_PROGRESS">В работе</option>
        <option value="RESOLVED">Решено</option>
        <option value="KNOWN_ISSUE">Известная проблема</option>
        <option value="IGNORED_TEST">Тест / игнор</option>
      </select>

      <select id="filterPurpose" class="select-filter">
        <option value="">Любая цель</option>
        <option value="USER">Пользовательские (USER)</option>
        <option value="TEST">Тестовые (TEST)</option>
      </select>

      <select id="filterCategory" class="select-filter">
        <option value="">Все категории</option>
        <option value="DOWNLOAD">DOWNLOAD</option>
        <option value="PLAYBACK">PLAYBACK</option>
        <option value="TRANSLATION">TRANSLATION</option>
        <option value="CODEC">CODEC</option>
        <option value="NETWORK">NETWORK</option>
        <option value="SYSTEM">SYSTEM</option>
      </select>
      <select id="filterSeverity" class="select-filter" aria-label="Важность"><option value="">Любая важность</option><option>CRITICAL</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option><option>INFO</option></select>
      <input id="filterVersion" class="search-input" aria-label="Версия" placeholder="Версия" style="min-width:110px;max-width:150px">
      <input id="filterDevice" class="search-input" aria-label="Устройство" placeholder="Устройство" style="min-width:110px;max-width:150px">
      <input id="filterSignature" class="search-input" aria-label="Сигнатура" placeholder="Сигнатура" style="min-width:140px;max-width:190px">
      <select id="filterSpecial" class="select-filter" aria-label="Дополнительный фильтр"><option value="">Все отчёты</option><option value="attention">Требует внимания</option><option value="notes">С заметками</option><option value="duplicates">С повторами</option></select>
      <select id="sortReports" class="select-filter" aria-label="Порядок"><option value="newest">Сначала новые</option><option value="oldest">Сначала старые</option><option value="severity">По важности</option><option value="repeats">По повторам</option><option value="status">По статусу</option><option value="version">По версии</option></select>
    </div>

    <div class="table-container">
      <table id="reportsTable">
        <thead>
          <tr>
            <th>Код отчёта</th>
            <th>Время</th>
            <th>Платформа</th>
            <th>Версия</th>
            <th>Устройство</th>
            <th>Категория ошибки</th>
            <th>Статус</th>
            <th>Действие</th>
          </tr>
        </thead>
        <tbody id="reportsTableBody">
          ${reports.length === 0 ? `
          <tr id="reportsEmptyRow">
            <td colspan="8" style="text-align: center; color: var(--text-muted); padding: 32px;">
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
              ${Number(r.related_count || 0) > 0 ? `<button class="btn" data-action="filter-signature" data-signature="${escapeHtml(r.error_signature || '')}" title="Показать похожие отчёты">Похожие: ${Number(r.related_count)}</button>` : ''}
              ${Number(r.related_count || 0) >= 2 ? '<span class="tag tag-status-in_progress">Повторяется</span>' : ''}
            </td>
            <td>${new Date(r.created_at).toLocaleString('ru-RU')}</td>
            <td><span class="tag ${(r.platform || '').includes('Tizen') ? 'tag-tizen' : 'tag-android'}">${escapeHtml(r.platform || '-')}</span></td>
            <td>${escapeHtml(r.app_version || '-')}</td>
            <td>${escapeHtml(r.device_family || '-')}</td>
            <td>${r.error_category ? `<span class="tag tag-error">${escapeHtml(r.error_category)}</span>` : '<span style="color: var(--text-muted);">-</span>'}</td>
            <td><select class="status-select" data-status="${normStatus}" aria-label="Статус отчёта ${escapeHtml(r.report_id)}">${Object.entries(STATUS_LABELS).map(([key,label]) => `<option value="${key}" ${key === normStatus ? 'selected' : ''}>${escapeHtml(label)}</option>`).join('')}</select></td>
            <td style="white-space: nowrap;">
              <button class="btn btn-open-report" data-action="open-report" data-report-id="${escapeHtml(r.report_id)}" style="margin-right: 6px;">Открыть</button>
              <button class="btn" data-action="quick-note" data-report-id="${escapeHtml(r.report_id)}">Заметка</button>
              <button class="btn btn-download-report" data-action="download-report" data-report-id="${escapeHtml(r.report_id)}" title="Скачать JSON">Скачать</button>
              ${r.developer_notes ? `<span class="note-preview" title="${escapeHtml(r.developer_notes)}">✎ ${escapeHtml(r.developer_notes)}</span>` : ''}
            </td>
          </tr>`;
          }).join('')}
        </tbody>
      </table>
    </div>
    <div class="table-controls"><span id="pageLabel">Страница 1</span><div><button class="btn" id="pageBack" disabled>Назад</button> <button class="btn" id="pageNext" ${reports.length < 50 ? 'disabled' : ''}>Вперёд</button></div></div>
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
            <td colspan="10" style="text-align: center; color: var(--text-muted); padding: 32px;">
              Сгруппированных проблем пока нет.
            </td>
          </tr>` : groupedIssues.map(i => `
          <tr data-issue-signature="${escapeHtml(i.error_signature || '')}">
            <td style="font-family: monospace; font-size: 12px; color: var(--accent); font-weight: 600;"><input class="search-input issue-title" aria-label="Название проблемы" placeholder="Название проблемы" value="${escapeHtml(i.title || '')}" style="width:100%;min-width:0"><small style="display:block;color:var(--text-muted)">${escapeHtml(i.error_signature || '')}</small><span class="tag ${i.severity === 'CRITICAL' || i.severity === 'HIGH' ? 'tag-error' : 'tag-status-test'}">${escapeHtml(i.severity || 'INFO')}</span>${i.reopened_at ? '<span class="tag tag-status-in_progress">Повторно открыто</span>' : ''}<small style="display:block">Версии: ${escapeHtml((i.affected_versions || []).join(', '))}</small><small style="display:block">Первое появление: ${new Date(i.first_seen).toLocaleString('ru-RU')}</small></td>
            <td><span class="tag tag-error">${escapeHtml(i.error_category || 'ERROR')}</span></td>
            <td><strong style="color: var(--text-bright); font-size: 14px;">${i.count || 1}</strong></td>
            <td><span class="tag tag-status-new">${i.new_count || 0}</span></td>
            <td><span class="tag tag-status-in_progress">${i.in_progress_count || 0}</span></td>
            <td><span class="tag tag-status-resolved">${i.resolved_count || 0}</span></td>
            <td><span class="tag tag-status-known_issue">${i.known_issue_count || 0}</span></td>
            <td>${new Date(i.last_seen).toLocaleString('ru-RU')}</td>
            <td style="white-space: nowrap;">
              <button class="btn btn-primary btn-open-sample" data-action="open-report" data-report-id="${escapeHtml(i.sample_report_id || '')}" style="margin-right: 6px;">Открыть образец</button>
              <button class="btn" data-action="filter-signature" data-signature="${escapeHtml(i.error_signature || '')}">В отчёты</button>
            </td>
            <td><select class="select-filter issue-status" aria-label="Статус проблемы">${Object.entries(STATUS_LABELS).map(([key,label]) => `<option value="${key}" ${key === i.issue_status ? 'selected' : ''}>${escapeHtml(label)}</option>`).join('')}</select><input class="search-input issue-version" aria-label="Версия исправления" placeholder="Версия" value="${escapeHtml(i.fixed_in_version || '')}" style="min-width:100px;width:110px"><input class="search-input issue-patch" aria-label="Патч" placeholder="Patch" value="${escapeHtml(i.fix_patch || '')}" style="min-width:85px;width:90px"><input class="search-input issue-commit" aria-label="Коммит" placeholder="Commit" value="${escapeHtml(i.fix_commit || '')}" style="min-width:95px;width:100px"><button class="btn" data-action="save-issue">Сохранить</button></td>
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

      <div class="section-card">
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

  <!-- MODAL: ДЕТАЛИ ОТЧЁТА -->
  <div id="modalOverlay" class="modal-overlay" role="presentation">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="modalTitle">
      <div class="modal-header">
        <div>
          <h3 id="modalTitle" style="color: var(--text-bright); display: inline-block; margin-right: 12px;">Детали отчёта</h3>
          <span id="modalPurposeBadge"></span>
        </div>
        <button class="btn" id="modalCloseBtn" data-action="close-modal">Закрыть (Esc)</button>
      </div>

      <div class="modal-body">
        <!-- TOP WORKFLOW CONTROL BAR -->
        <div class="section-card" id="detailWorkflowCard" style="margin-bottom: 16px; border-left: 3px solid var(--accent); background: #0c121e;">
          <div style="display: flex; gap: 16px; align-items: flex-start; flex-wrap: wrap;">
            <div style="flex: 1; min-width: 260px;">
              <div style="display: flex; gap: 12px; align-items: center; margin-bottom: 8px;">
                <label for="detailStatusSelect" style="font-size: 13px; font-weight: 600;">Статус:</label>
                <select id="detailStatusSelect" class="select-filter" style="font-weight: 600;">
                  <option value="NEW">Новый</option>
                  <option value="IN_PROGRESS">В работе</option>
                  <option value="RESOLVED">Решено</option>
                  <option value="KNOWN_ISSUE">Известная проблема</option>
                  <option value="IGNORED_TEST">Тест / игнор</option>
                </select>
                <button class="btn btn-primary" id="detailSaveBtn" data-action="save-detail-ops">Сохранить</button>
              </div>
              <label for="detailNotesText" style="font-size: 12px; font-weight: 600; color: var(--text-muted); display: block; margin-bottom: 4px;">Заметка разработчика:</label>
              <textarea id="detailNotesText" class="notes-textarea" style="min-height: 56px;" placeholder="Заметка к инциденту, причина сбоя, ссылка на PR или номер фикса..."></textarea>
            </div>
            <div style="display: flex; flex-direction: column; gap: 8px; min-width: 190px;">
              <span style="font-size: 11px; text-transform: uppercase; color: var(--text-muted); font-weight: 600;">Действия:</span>
              <button class="btn" id="detailDownloadJsonBtn" data-action="download-json">💾 Скачать JSON</button>
              <button class="btn" id="detailCopyMdBtn" data-action="copy-markdown">📋 Скопировать Markdown</button>
              <button class="btn btn-danger" id="detailDeleteBtn" data-action="delete-report">🗑️ Удалить отчёт</button>
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

  <div id="noteDialog" class="modal-overlay" role="presentation"><div class="modal" role="dialog" aria-modal="true" aria-label="Заметка разработчика" style="max-width:460px;height:auto;margin:auto"><div class="modal-header"><h3>Заметка разработчика</h3><button class="btn" id="noteCancel">Закрыть</button></div><div class="modal-body"><label for="quickNoteText">Текст заметки</label><textarea id="quickNoteText" class="notes-textarea" maxlength="4000"></textarea><button id="noteSave" class="btn btn-primary" style="margin-top:12px">Сохранить</button></div></div></div>
  <div id="toast" class="toast" role="status" aria-live="polite" hidden></div>

  <!-- MODAL: ОЧИСТКА ВСЕХ ЛОГОВ (SAFE PURGE) -->
  <div id="purgeModalOverlay" class="modal-overlay">
    <div class="modal" style="max-width: 520px;">
      <div class="modal-header">
        <h3 style="color: var(--danger);">Удалить все диагностические отчёты?</h3>
        <button class="btn" id="purgeModalCloseBtn" data-action="close-purge-modal">Закрыть</button>
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

  <!-- External Clean JavaScript Application -->
  <script src="/admin/app.js" defer></script>
</body>
</html>`;
}
