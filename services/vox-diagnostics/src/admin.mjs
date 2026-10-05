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
  <meta http-equiv="Content-Security-Policy" content="default-src 'self' 'unsafe-inline' data:; frame-ancestors 'none';">
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
    }
    .kpi-title {
      font-size: 12px;
      color: var(--text-muted);
      text-transform: uppercase;
      font-weight: 600;
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
      border-bottom: 1px solid var(--border);
      margin-bottom: 20px;
    }
    .nav-tab {
      background: none;
      border: none;
      color: var(--text-muted);
      padding: 10px 16px;
      font-size: 14px;
      font-weight: 600;
      cursor: pointer;
      border-bottom: 2px solid transparent;
      transition: all 0.2s;
    }
    .nav-tab:hover {
      color: var(--text-bright);
    }
    .nav-tab.active {
      color: var(--accent);
      border-bottom-color: var(--accent);
    }
    .toolbar {
      display: flex;
      gap: 12px;
      margin-bottom: 20px;
      flex-wrap: wrap;
      align-items: center;
    }
    .search-input, .select-filter {
      background: var(--card-bg);
      border: 1px solid var(--border);
      color: var(--text-bright);
      padding: 8px 14px;
      border-radius: 6px;
      font-size: 13px;
      outline: none;
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
    .tag-status-known { background: rgba(139, 92, 246, 0.2); color: var(--purple); }
    .tag-status-resolved { background: rgba(16, 185, 129, 0.2); color: var(--success); }
    .tag-status-test { background: rgba(156, 163, 175, 0.2); color: var(--text-muted); }
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
    .modal-tab-btn.active {
      color: var(--accent);
      border-bottom-color: var(--accent);
    }
    .grid-2 {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
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
      <button class="btn btn-primary" onclick="location.reload()">Обновить</button>
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
      <div class="kpi-val" style="color: var(--warning);">${activeIssues}</div>
    </div>
    <div class="kpi-card">
      <div class="kpi-title">Всего в базе</div>
      <div class="kpi-val">${totalReports}</div>
    </div>
  </div>

  <div class="nav-tabs">
    <button class="nav-tab active" id="tabBtnReports" onclick="switchMainTab('reports')">Все отчёты (${reports.length})</button>
    <button class="nav-tab" id="tabBtnIssues" onclick="switchMainTab('issues')">Частые проблемы (${groupedIssues.length})</button>
    <button class="nav-tab" id="tabBtnStats" onclick="switchMainTab('stats')">Сводка и метрики</button>
  </div>

  <!-- VIEW 1: ВСЕ ОТЧЁТЫ -->
  <div id="viewReports">
    <div class="toolbar">
      <input type="text" id="searchInput" class="search-input" placeholder="Поиск по ID отчёта (VOX-A-...), устройству, версии или сигнатуре..." onkeyup="filterReportsTable()">
      
      <select id="filterPlatform" class="select-filter" onchange="filterReportsTable()">
        <option value="">Все платформы</option>
        <option value="Android">Android TV / Google TV</option>
        <option value="Tizen">Samsung Tizen</option>
      </select>

      <select id="filterStatus" class="select-filter" onchange="filterReportsTable()">
        <option value="">Все статусы</option>
        <option value="NEW">NEW (Новые)</option>
        <option value="REVIEWED">REVIEWED (Просмотренные)</option>
        <option value="KNOWN_ISSUE">KNOWN_ISSUE (Известные)</option>
        <option value="RESOLVED">RESOLVED (Исправленные)</option>
        <option value="IGNORED_TEST">IGNORED_TEST (Тестовые)</option>
      </select>

      <select id="filterPurpose" class="select-filter" onchange="filterReportsTable()">
        <option value="">Любая цель</option>
        <option value="USER">Пользовательские (USER)</option>
        <option value="TEST">Тестовые (TEST)</option>
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
          <tr>
            <td colspan="8" style="text-align: center; color: var(--text-muted); padding: 32px;">
              Отчётов пока нет.
            </td>
          </tr>` : reports.map(r => `
          <tr data-report-id="${escapeHtml(r.report_id)}"
              data-platform="${escapeHtml(r.platform)}"
              data-version="${escapeHtml(r.app_version)}"
              data-device="${escapeHtml(r.device_family)}"
              data-category="${escapeHtml(r.error_category || '')}"
              data-status="${escapeHtml(r.status || 'NEW')}"
              data-purpose="${escapeHtml(r.report_purpose || 'USER')}">
            <td>
              <span class="report-id" onclick="viewReport('${escapeHtml(r.report_id)}')">${escapeHtml(r.report_id)}</span>
              ${r.report_purpose === 'TEST' ? '<span class="tag tag-purpose-test">TEST</span>' : ''}
            </td>
            <td>${new Date(r.created_at).toLocaleString('ru-RU')}</td>
            <td><span class="tag ${(r.platform || '').includes('Tizen') ? 'tag-tizen' : 'tag-android'}">${escapeHtml(r.platform)}</span></td>
            <td>${escapeHtml(r.app_version)}</td>
            <td>${escapeHtml(r.device_family)}</td>
            <td>${r.error_category ? `<span class="tag tag-error">${escapeHtml(r.error_category)}</span>` : '<span style="color: var(--text-muted);">-</span>'}</td>
            <td><span class="tag tag-status-${(r.status || 'new').toLowerCase()}">${escapeHtml(r.status || 'NEW')}</span></td>
            <td><button class="btn" onclick="viewReport('${escapeHtml(r.report_id)}')">Открыть</button></td>
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
        <tbody>
          ${groupedIssues.length === 0 ? `
          <tr>
            <td colspan="7" style="text-align: center; color: var(--text-muted); padding: 32px;">
              Сгруппированных проблем пока нет.
            </td>
          </tr>` : groupedIssues.map(i => `
          <tr>
            <td style="font-family: monospace; font-size: 12px; color: var(--accent); font-weight: 600;">${escapeHtml(i.error_signature)}</td>
            <td><span class="tag tag-error">${escapeHtml(i.error_category || 'ERROR')}</span></td>
            <td><strong style="color: var(--text-bright); font-size: 14px;">${i.count}</strong></td>
            <td>${new Date(i.first_seen).toLocaleString('ru-RU')}</td>
            <td>${new Date(i.last_seen).toLocaleString('ru-RU')}</td>
            <td><span class="tag tag-status-${(i.status || 'new').toLowerCase()}">${escapeHtml(i.status || 'NEW')}</span></td>
            <td><button class="btn btn-primary" onclick="viewReport('${escapeHtml(i.sample_report_id)}')">Открыть образец</button></td>
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
          <tbody>
            ${(stats.platformBreakdown || []).map(p => `<tr><td>${escapeHtml(p.platform)}</td><td><strong>${p.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет данных</td></tr>'}
          </tbody>
        </table>
      </div>

      <div class="section-card">
        <div class="section-title">Распределение по версиям</div>
        <table>
          <thead><tr><th>Версия</th><th>Количество</th></tr></thead>
          <tbody>
            ${(stats.versionDistribution || []).map(v => `<tr><td>${escapeHtml(v.app_version)}</td><td><strong>${v.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет данных</td></tr>'}
          </tbody>
        </table>
      </div>

      <div class="section-card">
        <div class="section-title">Топ категорий ошибок</div>
        <table>
          <thead><tr><th>Категория</th><th>Количество</th></tr></thead>
          <tbody>
            ${(stats.topCategories || []).map(c => `<tr><td><span class="tag tag-error">${escapeHtml(c.error_category)}</span></td><td><strong>${c.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет ошибок</td></tr>'}
          </tbody>
        </table>
      </div>

      <div class="section-card">
        <div class="section-title">Топ сигнатур сбоев</div>
        <table>
          <thead><tr><th>Сигнатура</th><th>Количество</th></tr></thead>
          <tbody>
            ${(stats.topIssues || []).map(i => `<tr><td style="font-family: monospace; font-size: 11px;">${escapeHtml(i.error_signature)}</td><td><strong>${i.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет данных</td></tr>'}
          </tbody>
        </table>
      </div>
    </div>
  </div>

  <!-- MODAL: ДЕТАЛИ ОТЧЁТА -->
  <div id="modalOverlay" class="modal-overlay" onclick="closeModal(event)">
    <div class="modal" onclick="event.stopPropagation()">
      <div class="modal-header">
        <div>
          <h3 id="modalTitle" style="color: var(--text-bright); display: inline-block; margin-right: 12px;">Детали отчёта</h3>
          <span id="modalPurposeBadge"></span>
        </div>
        <button class="btn" onclick="closeModal()">Закрыть (Esc)</button>
      </div>

      <div class="modal-body">
        <div class="modal-tabs">
          <button class="modal-tab-btn active" id="mTabBtnOverview" onclick="switchModalTab('overview')">Сводка и устройство</button>
          <button class="modal-tab-btn" id="mTabBtnCodecs" onclick="switchModalTab('codecs')">Кодеки и политика</button>
          <button class="modal-tab-btn" id="mTabBtnTimeline" onclick="switchModalTab('timeline')">Журнал событий</button>
          <button class="modal-tab-btn" id="mTabBtnOps" onclick="switchModalTab('ops')">Операции и статус</button>
          <button class="modal-tab-btn" id="mTabBtnRaw" onclick="switchModalTab('raw')">Raw JSON</button>
        </div>

        <div id="modalContent">
          <p style="color: var(--text-muted);">Загрузка...</p>
        </div>
      </div>
    </div>
  </div>

  <script>
    let currentReport = null;

    function switchMainTab(tab) {
      document.getElementById('viewReports').style.display = tab === 'reports' ? 'block' : 'none';
      document.getElementById('viewIssues').style.display = tab === 'issues' ? 'block' : 'none';
      document.getElementById('viewStats').style.display = tab === 'stats' ? 'block' : 'none';

      document.getElementById('tabBtnReports').classList.toggle('active', tab === 'reports');
      document.getElementById('tabBtnIssues').classList.toggle('active', tab === 'issues');
      document.getElementById('tabBtnStats').classList.toggle('active', tab === 'stats');
    }

    function switchModalTab(tab) {
      document.querySelectorAll('.modal-tab-btn').forEach(b => b.classList.remove('active'));
      const activeBtn = document.getElementById('mTabBtn' + tab.charAt(0).toUpperCase() + tab.slice(1));
      if (activeBtn) activeBtn.classList.add('active');

      document.querySelectorAll('.modal-section-view').forEach(s => s.style.display = 'none');
      const activeSection = document.getElementById('mSection_' + tab);
      if (activeSection) activeSection.style.display = 'block';
    }

    function filterReportsTable() {
      const q = document.getElementById('searchInput').value.toLowerCase().trim();
      const plat = document.getElementById('filterPlatform').value.toLowerCase();
      const stat = document.getElementById('filterStatus').value;
      const purp = document.getElementById('filterPurpose').value;

      const rows = document.querySelectorAll('#reportsTableBody tr');
      rows.forEach(r => {
        const id = r.getAttribute('data-report-id') || '';
        const p = (r.getAttribute('data-platform') || '').toLowerCase();
        const v = (r.getAttribute('data-version') || '').toLowerCase();
        const d = (r.getAttribute('data-device') || '').toLowerCase();
        const c = (r.getAttribute('data-category') || '').toLowerCase();
        const s = r.getAttribute('data-status') || '';
        const pr = r.getAttribute('data-purpose') || '';

        const textMatches = !q || id.toLowerCase().includes(q) || d.includes(q) || v.includes(q) || c.includes(q);
        const platMatches = !plat || p.includes(plat);
        const statMatches = !stat || s === stat;
        const purpMatches = !purp || pr === purp;

        r.style.display = (textMatches && platMatches && statMatches && purpMatches) ? '' : 'none';
      });
    }

    async function viewReport(id) {
      document.getElementById('modalOverlay').style.display = 'flex';
      document.getElementById('modalTitle').innerText = 'Отчёт ' + id;
      document.getElementById('modalContent').innerHTML = '<p style="color: var(--text-muted);">Загрузка деталей...</p>';
      
      try {
        const res = await fetch('/v1/admin/reports/' + encodeURIComponent(id));
        if (!res.ok) throw new Error('Ошибка HTTP: ' + res.status);
        const data = await res.json();
        currentReport = data;
        renderModalSections(data);
        switchModalTab('overview');
      } catch (err) {
        document.getElementById('modalContent').innerHTML = '<p style="color: var(--danger);">Не удалось загрузить отчёт: ' + err.message + '</p>';
      }
    }

    function renderModalSections(data) {
      const p = data.payload || {};
      const rec = p.recommendedSettings || {};
      const cur = p.currentPolicy || {};
      const disp = p.display || {};
      const events = p.safeRecentEvents || [];

      let html = '';

      // SECTION 1: OVERVIEW
      html += '<div id="mSection_overview" class="modal-section-view">';
      html += '<div class="grid-2">';
      html += '<div class="section-card"><div class="section-title">Устройство и ОС</div>';
      html += '<table style="font-size: 12px;">';
      html += '<tr><td><strong>Производитель:</strong></td><td>' + escapeHtml(p.manufacturer || data.device_family) + '</td></tr>';
      html += '<tr><td><strong>Модель:</strong></td><td>' + escapeHtml(p.model || '') + '</td></tr>';
      html += '<tr><td><strong>Система:</strong></td><td>' + escapeHtml(p.osName || '') + ' ' + escapeHtml(p.osVersion || '') + ' (API ' + (p.sdkInt || '-') + ')</td></tr>';
      html += '<tr><td><strong>Уровень TV:</strong></td><td>' + escapeHtml(p.deviceTier || '-') + '</td></tr>';
      html += '<tr><td><strong>Платформа:</strong></td><td>' + escapeHtml(data.platform) + '</td></tr>';
      html += '</table></div>';

      html += '<div class="section-card"><div class="section-title">Приложение и Экран</div>';
      html += '<table style="font-size: 12px;">';
      html += '<tr><td><strong>Версия приложения:</strong></td><td>' + escapeHtml(data.app_version) + ' (' + (p.appVersionCode || '-') + ')</td></tr>';
      html += '<tr><td><strong>Разрешение:</strong></td><td>' + escapeHtml(disp.resolution || '-') + ' @ ' + (disp.refreshRateHz || '-') + 'Hz</td></tr>';
      html += '<tr><td><strong>HDR10 / HLG:</strong></td><td>' + escapeHtml(disp.hdr10 || '-') + ' / ' + escapeHtml(disp.hlg || '-') + '</td></tr>';
      html += '<tr><td><strong>Категория ошибки:</strong></td><td>' + (data.error_category ? '<span class="tag tag-error">' + escapeHtml(data.error_category) + '</span>' : 'Отсутствует') + '</td></tr>';
      html += '<tr><td><strong>Сигнатура:</strong></td><td><code>' + escapeHtml(data.error_signature || '-') + '</code></td></tr>';
      html += '</table></div>';
      html += '</div>';

      if (p.riskWarning) {
        html += '<div class="section-card" style="border-color: var(--warning);"><div class="section-title" style="color: var(--warning);">Предупреждение о рисках</div><p style="color: var(--warning); font-size: 13px;">⚠️ ' + escapeHtml(p.riskWarning) + '</p></div>';
      }
      html += '</div>';

      // SECTION 2: CODECS & POLICY
      html += '<div id="mSection_codecs" class="modal-section-view" style="display: none;">';
      html += '<div class="grid-2">';
      html += '<div class="section-card"><div class="section-title">Видеодекодеры</div><table style="font-size: 11px;">';
      for (const [k, v] of Object.entries(p.videoCodecs || {})) {
        html += '<tr><td><strong>' + escapeHtml(k) + '</strong></td><td>' + escapeHtml(v) + '</td></tr>';
      }
      html += '</table></div>';

      html += '<div class="section-card"><div class="section-title">Аудиодекодеры</div><table style="font-size: 11px;">';
      for (const [k, v] of Object.entries(p.audioCodecs || {})) {
        html += '<tr><td><strong>' + escapeHtml(k) + '</strong></td><td>' + escapeHtml(v) + '</td></tr>';
      }
      html += '</table></div>';
      html += '</div>';

      html += '<div class="section-card"><div class="section-title">Политика качества: Текущая vs Рекомендуемая</div>';
      html += '<table style="font-size: 12px;">';
      html += '<thead><tr><th>Параметр</th><th>Текущая</th><th>Рекомендуемая</th></tr></thead><tbody>';
      html += '<tr><td>Режим</td><td>' + escapeHtml(cur.mode || '-') + '</td><td>' + escapeHtml(rec.mode || '-') + '</td></tr>';
      html += '<tr><td>Макс. качество</td><td>' + escapeHtml(cur.maxQualityHeight || '-') + 'p</td><td>' + escapeHtml(rec.maxQualityHeight || '-') + 'p</td></tr>';
      html += '<tr><td>Видеокодек</td><td>' + escapeHtml(cur.preferredVideoCodec || '-') + '</td><td>' + escapeHtml(rec.preferredVideoCodec || '-') + '</td></tr>';
      html += '<tr><td>Аудиокодек</td><td>' + escapeHtml(cur.preferredAudioCodec || '-') + '</td><td>' + escapeHtml(rec.preferredAudioCodec || '-') + '</td></tr>';
      html += '<tr><td>Passthrough</td><td>' + (cur.passthroughEnabled ? 'Да' : 'Нет') + '</td><td>' + (rec.passthroughEnabled ? 'Да' : 'Нет') + '</td></tr>';
      html += '</tbody></table></div>';
      html += '</div>';

      // SECTION 3: SAFE EVENT TIMELINE
      html += '<div id="mSection_timeline" class="modal-section-view" style="display: none;">';
      if (events.length === 0) {
        html += '<p style="color: var(--text-muted); padding: 20px; text-align: center;">В отчёте нет сохранённых событий журнала.</p>';
      } else {
        html += '<div class="section-card"><div class="section-title">События перед инцидентом (' + events.length + ')</div>';
        html += '<div style="max-height: 420px; overflow-y: auto;"><table style="font-size: 11px;">';
        html += '<thead><tr><th>Время</th><th>Уровень</th><th>Категория</th><th>Код</th><th>Сообщение / Контекст</th></tr></thead><tbody>';
        events.forEach(e => {
          const lvlClass = e.level === 'ERROR' ? 'tag-error' : e.level === 'WARNING' ? 'tag-status-reviewed' : 'tag-status-test';
          let ctxStr = '';
          if (e.context && Object.keys(e.context).length > 0) {
            ctxStr = '<br><span style="color: var(--text-muted); font-family: monospace;">' + escapeHtml(JSON.stringify(e.context)) + '</span>';
          }
          html += '<tr>' +
            '<td>' + new Date(e.timestamp).toLocaleTimeString('ru-RU') + '</td>' +
            '<td><span class="tag ' + lvlClass + '">' + escapeHtml(e.level) + '</span></td>' +
            '<td>' + escapeHtml(e.category) + '</td>' +
            '<td><code>' + escapeHtml(e.code) + '</code></td>' +
            '<td>' + escapeHtml(e.message || '') + ctxStr + '</td>' +
            '</tr>';
        });
        html += '</tbody></table></div></div>';
      }
      html += '</div>';

      // SECTION 4: DEVELOPER OPERATIONS
      html += '<div id="mSection_ops" class="modal-section-view" style="display: none;">';
      html += '<div class="section-card"><div class="section-title">Управление статусом и заметки разработчика</div>';
      html += '<div style="display: flex; gap: 12px; align-items: center; margin-bottom: 12px;">';
      html += '<label style="font-size: 13px; font-weight: 600;">Статус:</label>';
      html += '<select id="opStatusSelect" class="select-filter">';
      const statuses = ['NEW', 'REVIEWED', 'KNOWN_ISSUE', 'RESOLVED', 'IGNORED_TEST'];
      statuses.forEach(s => {
        html += '<option value="' + s + '" ' + (data.status === s ? 'selected' : '') + '>' + s + '</option>';
      });
      html += '</select>';
      html += '</div>';

      html += '<label style="font-size: 13px; font-weight: 600;">Заметки разработчика (Developer Notes):</label>';
      html += '<textarea id="opNotesText" class="notes-textarea" placeholder="Укажите issue #, причину сбоя или статус фикса...">' + escapeHtml(data.developer_notes || '') + '</textarea>';
      html += '<div style="margin-top: 12px; display: flex; gap: 10px;">';
      html += '<button class="btn btn-primary" onclick="saveReportOperations(\'' + escapeHtml(data.report_id) + '\')">Сохранить изменения</button>';
      html += '</div></div>';

      html += '<div class="section-card"><div class="section-title">Экспорт и удаление</div>';
      html += '<div style="display: flex; gap: 10px; flex-wrap: wrap;">';
      html += '<button class="btn" onclick="copyGitHubMarkdown()">📋 Скопировать сводку для GitHub Issue</button>';
      html += '<button class="btn" onclick="downloadJsonFile()">💾 Скачать Sanitized JSON</button>';
      html += '<button class="btn btn-danger" onclick="deleteCurrentReport(\'' + escapeHtml(data.report_id) + '\')">🗑️ Удалить отчёт</button>';
      html += '</div></div>';
      html += '</div>';

      // SECTION 5: RAW JSON
      html += '<div id="mSection_raw" class="modal-section-view" style="display: none;">';
      html += '<pre id="rawJsonPre">' + escapeHtml(JSON.stringify(p, null, 2)) + '</pre>';
      html += '<button class="btn btn-primary" style="margin-top: 12px;" onclick="copyRawJson()">Скопировать JSON</button>';
      html += '</div>';

      document.getElementById('modalContent').innerHTML = html;
    }

    async function saveReportOperations(id) {
      const status = document.getElementById('opStatusSelect').value;
      const notes = document.getElementById('opNotesText').value;

      try {
        const res = await fetch('/v1/admin/reports/' + encodeURIComponent(id), {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ status, developerNotes: notes })
        });
        if (!res.ok) throw new Error('Ошибка сохранения: ' + res.status);
        alert('Статус и заметки успешно сохранены!');
        location.reload();
      } catch (e) {
        alert('Не удалось сохранить изменения: ' + e.message);
      }
    }

    async function deleteCurrentReport(id) {
      if (!confirm('Вы действительно хотите удалить отчёт ' + id + '?')) return;
      try {
        const res = await fetch('/v1/admin/reports/' + encodeURIComponent(id), { method: 'DELETE' });
        if (!res.ok) throw new Error('Ошибка удаления: ' + res.status);
        alert('Отчёт ' + id + ' удалён.');
        closeModal();
        location.reload();
      } catch (e) {
        alert('Не удалось удалить: ' + e.message);
      }
    }

    function copyRawJson() {
      if (currentReport && currentReport.payload) {
        navigator.clipboard.writeText(JSON.stringify(currentReport.payload, null, 2));
        alert('Sanitized JSON скопирован!');
      }
    }

    function downloadJsonFile() {
      if (!currentReport) return;
      const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(currentReport.payload, null, 2));
      const downloadAnchor = document.createElement('a');
      downloadAnchor.setAttribute("href", dataStr);
      downloadAnchor.setAttribute("download", currentReport.report_id + ".json");
      document.body.appendChild(downloadAnchor);
      downloadAnchor.click();
      downloadAnchor.remove();
    }

    function copyGitHubMarkdown() {
      if (!currentReport) return;
      const r = currentReport;
      const p = r.payload || {};
      const events = p.safeRecentEvents || [];
      const b = String.fromCharCode(96);
      const tripleB = b + b + b;
      const evText = events.map(e => '[' + new Date(e.timestamp).toLocaleTimeString() + '] [' + e.level + '] [' + e.category + '] ' + e.code + ': ' + e.message).join('\n');
      const md = '### SmartTube VOX Diagnostic Report: ' + b + r.report_id + b + '\n\n' +
        '- **App Version:** ' + b + r.app_version + b + ' (' + (p.appVersionCode || '-') + ')\n' +
        '- **Platform:** ' + r.platform + '\n' +
        '- **Device:** ' + (p.manufacturer || '') + ' ' + (p.model || '') + ' (Android API ' + (p.sdkInt || '-') + ')\n' +
        '- **Error Category:** ' + (r.error_category || 'None') + '\n' +
        '- **Error Signature:** ' + b + (r.error_signature || 'N/A') + b + '\n\n' +
        '#### Policy & Recommended\n' +
        '- Current Policy: ' + b + JSON.stringify(p.currentPolicy || {}) + b + '\n' +
        '- Recommended: ' + b + JSON.stringify(p.recommendedSettings || {}) + b + '\n\n' +
        '#### Safe Recent Events (' + events.length + ')\n' +
        tripleB + 'text\n' + evText + '\n' + tripleB + '\n';
      navigator.clipboard.writeText(md);
      alert('Markdown для GitHub скопирован в буфер обмена!');
    }

    function closeModal() {
      document.getElementById('modalOverlay').style.display = 'none';
      currentReport = null;
    }

    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') closeModal();
    });

    function escapeHtml(str) {
      if (str === null || str === undefined) return '';
      return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
    }
  </script>
</body>
</html>`;
}
