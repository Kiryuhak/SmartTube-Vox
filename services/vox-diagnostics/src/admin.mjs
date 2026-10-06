/**
 * Модуль аутентификации и панели администратора SmartTube VOX Diagnostics.
 */

/**
 * Проверяет права администратора.
 * Защита по умолчанию: FAIL CLOSED.
 * Доступ разрешается только при наличии валидного ADMIN_SECRET, заголовка Cloudflare Access
 * или явного флага ALLOW_DEV_AUTH в среде тестирования.
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

  // 1. Cloudflare Access
  const cfUser = request.headers.get('cf-access-authenticated-user-email');
  if (cfUser) return true;

  // 2. Authorization: Bearer <ADMIN_SECRET>
  const authHeader = request.headers.get('authorization') || '';
  if (authHeader.startsWith('Bearer ') && authHeader.substring(7).trim() === secret) {
    return true;
  }

  // 3. X-Admin-Key / X-Admin-Secret
  const adminKey = request.headers.get('x-admin-key') || request.headers.get('x-admin-secret') || '';
  if (adminKey.trim() === secret) {
    return true;
  }

  // 4. Query param ?token=<ADMIN_SECRET>
  try {
    const url = new URL(request.url);
    const tokenParam = url.searchParams.get('token');
    if (tokenParam === secret) {
      return true;
    }
  } catch (e) {}

  // 5. Cookie: vox_admin_token=<ADMIN_SECRET>
  const cookieHeader = request.headers.get('cookie') || '';
  const match = cookieHeader.match(/vox_admin_token=([^;]+)/);
  if (match && match[1].trim() === secret) {
    return true;
  }

  return false;
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
    }
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
    .tag-status-new { background: rgba(59, 130, 246, 0.2); color: var(--accent); }
    .tag-status-reviewed { background: rgba(245, 158, 11, 0.2); color: var(--warning); }
    .tag-status-known, .tag-status-known_issue { background: rgba(139, 92, 246, 0.2); color: var(--purple); }
    .tag-status-resolved { background: rgba(16, 185, 129, 0.2); color: var(--success); }
    .tag-status-test, .tag-status-ignored_test { background: rgba(156, 163, 175, 0.2); color: var(--text-muted); }
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
      <span class="badge-vox">DIAGNOSTICS &amp; OPERATIONS</span>
    </div>
    <div style="display: flex; gap: 10px; align-items: center;">
      <span style="font-size: 13px; color: var(--text-muted);">Retention: 30 дней</span>
      <button class="btn btn-primary" id="refreshButton">Обновить</button>
    </div>
  </div>

  <div class="kpi-grid">
    <div class="kpi-card">
      <div class="kpi-title">Отчётов за 24ч</div>
      <div class="kpi-val" id="kpi24h">${reports24h}</div>
    </div>
    <div class="kpi-card">
      <div class="kpi-title">Отчётов за 7 дней</div>
      <div class="kpi-val" id="kpi7d">${reports7d}</div>
    </div>
    <div class="kpi-card">
      <div class="kpi-title">Активные проблемы</div>
      <div class="kpi-val" id="kpiActiveIssues" style="color: var(--warning);">${activeIssues}</div>
    </div>
    <div class="kpi-card">
      <div class="kpi-title">Всего в базе</div>
      <div class="kpi-val" id="kpiTotal">${totalReports}</div>
    </div>
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
        <option value="NEW">NEW (Новые)</option>
        <option value="REVIEWED">REVIEWED (Просмотренные)</option>
        <option value="KNOWN_ISSUE">KNOWN_ISSUE (Известные)</option>
        <option value="RESOLVED">RESOLVED (Исправленные)</option>
        <option value="IGNORED_TEST">IGNORED_TEST (Тестовые)</option>
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
          </tr>` : reports.map(r => `
          <tr data-report-id="${escapeHtml(r.report_id)}"
              data-platform="${escapeHtml(r.platform || '')}"
              data-version="${escapeHtml(r.app_version || '')}"
              data-device="${escapeHtml(r.device_family || '')}"
              data-category="${escapeHtml(r.error_category || '')}"
              data-status="${escapeHtml(r.status || 'NEW')}"
              data-purpose="${escapeHtml(r.report_purpose || 'USER')}"
              data-signature="${escapeHtml(r.error_signature || '')}">
            <td>
              <span class="report-id" data-action="open-report" data-report-id="${escapeHtml(r.report_id)}">${escapeHtml(r.report_id)}</span>
              ${r.report_purpose === 'TEST' ? '<span class="tag tag-purpose-test">TEST</span>' : ''}
            </td>
            <td>${new Date(r.created_at).toLocaleString('ru-RU')}</td>
            <td><span class="tag ${(r.platform || '').includes('Tizen') ? 'tag-tizen' : 'tag-android'}">${escapeHtml(r.platform || '-')}</span></td>
            <td>${escapeHtml(r.app_version || '-')}</td>
            <td>${escapeHtml(r.device_family || '-')}</td>
            <td>${r.error_category ? `<span class="tag tag-error">${escapeHtml(r.error_category)}</span>` : '<span style="color: var(--text-muted);">-</span>'}</td>
            <td><span class="tag tag-status-${(r.status || 'new').toLowerCase()}">${escapeHtml(r.status || 'NEW')}</span></td>
            <td><button class="btn btn-open-report" data-action="open-report" data-report-id="${escapeHtml(r.report_id)}">Открыть</button></td>
          </tr>`).join('')}
        </tbody>
      </table>
    </div>
  </div>

  <!-- VIEW 2: ЧАСТЫЕ ПРОБЛЕМЫ (GROUPED ISSUES) -->
  <div id="viewIssues" style="display: none;">
    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>Сигнатура проблемы</th>
            <th>Категория</th>
            <th>Количество случаев</th>
            <th>Первый случай</th>
            <th>Последний случай</th>
            <th>Статус</th>
            <th>Действие</th>
          </tr>
        </thead>
        <tbody id="issuesTableBody">
          ${groupedIssues.length === 0 ? `
          <tr>
            <td colspan="7" style="text-align: center; color: var(--text-muted); padding: 32px;">
              Сгруппированных проблем пока нет.
            </td>
          </tr>` : groupedIssues.map(i => `
          <tr>
            <td style="font-family: monospace; font-size: 12px; color: var(--accent); font-weight: 600;">${escapeHtml(i.error_signature || '-')}</td>
            <td><span class="tag tag-error">${escapeHtml(i.error_category || 'ERROR')}</span></td>
            <td><strong style="color: var(--text-bright); font-size: 14px;">${i.count || 1}</strong></td>
            <td>${new Date(i.first_seen).toLocaleString('ru-RU')}</td>
            <td>${new Date(i.last_seen).toLocaleString('ru-RU')}</td>
            <td><span class="tag tag-status-${(i.status || 'new').toLowerCase()}">${escapeHtml(i.status || 'NEW')}</span></td>
            <td><button class="btn btn-primary btn-open-sample" data-action="open-report" data-report-id="${escapeHtml(i.sample_report_id || '')}">Открыть образец</button></td>
          </tr>`).join('')}
        </tbody>
      </table>
    </div>
  </div>

  <!-- VIEW 3: СВОДКА И МЕТРИКИ (STATS) -->
  <div id="viewStats" style="display: none;">
    <div class="grid-2">
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
  <div id="modalOverlay" class="modal-overlay">
    <div class="modal">
      <div class="modal-header">
        <div>
          <h3 id="modalTitle" style="color: var(--text-bright); display: inline-block; margin-right: 12px;">Детали отчёта</h3>
          <span id="modalPurposeBadge"></span>
        </div>
        <button class="btn" id="modalCloseBtn" data-action="close-modal">Закрыть (Esc)</button>
      </div>

      <div class="modal-body">
        <div class="modal-tabs">
          <button class="modal-tab-btn active" id="mTabBtnOverview" data-modal-tab="overview">Сводка и устройство</button>
          <button class="modal-tab-btn" id="mTabBtnCodecs" data-modal-tab="codecs">Кодеки и политика</button>
          <button class="modal-tab-btn" id="mTabBtnTimeline" data-modal-tab="timeline">Журнал событий</button>
          <button class="modal-tab-btn" id="mTabBtnOps" data-modal-tab="ops">Операции и статус</button>
          <button class="modal-tab-btn" id="mTabBtnRaw" data-modal-tab="raw">Raw JSON</button>
        </div>

        <div id="modalContent">
          <p style="color: var(--text-muted); padding: 24px; text-align: center;">Выберите отчёт для просмотра деталей.</p>
        </div>
      </div>
    </div>
  </div>

  <!-- External Clean JavaScript Application -->
  <script src="/admin/app.js" defer></script>
</body>
</html>`;
}
