/**
 * Проверяет права администратора.
 * Поддерживает:
 * 1. Cloudflare Access (заголовок cf-access-authenticated-user-email)
 * 2. Заголовок Authorization: Bearer <ADMIN_SECRET>
 * 3. Cookie или Query param ?token=<ADMIN_SECRET>
 * 4. Если ADMIN_SECRET не задан в окружении (локальная разработка) — доступ разрешён.
 */
export function checkAdminAuth(request, env = {}) {
  const secret = env.ADMIN_SECRET;
  if (!secret) return true; // Локальный / dev режим

  const cfUser = request.headers.get('cf-access-authenticated-user-email');
  if (cfUser) return true;

  const authHeader = request.headers.get('authorization') || '';
  if (authHeader.startsWith('Bearer ') && authHeader.substring(7).trim() === secret) {
    return true;
  }

  const adminKey = request.headers.get('x-admin-key') || request.headers.get('x-admin-secret') || '';
  if (adminKey.trim() === secret) {
    return true;
  }

  const url = new URL(request.url);
  const tokenParam = url.searchParams.get('token');
  if (tokenParam === secret) {
    return true;
  }

  const cookieHeader = request.headers.get('cookie') || '';
  const match = cookieHeader.match(/vox_admin_token=([^;]+)/);
  if (match && match[1] === secret) {
    return true;
  }

  return false;
}

/**
 * Генерирует HTML страницу панели администратора.
 */
export function renderAdminHtml(reports = []) {
  return `<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>SmartTube VOX — Консоль диагностики</title>
  <style>
    :root {
      --bg: #0d1117;
      --card-bg: #161b22;
      --border: #30363d;
      --text: #c9d1d9;
      --text-bright: #f0f6fc;
      --text-muted: #8b949e;
      --accent: #58a6ff;
      --accent-glow: rgba(88, 166, 255, 0.15);
      --success: #3fb950;
      --warning: #d29922;
      --danger: #f85149;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
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
      font-weight: 600;
      color: var(--text-bright);
    }
    .badge-vox {
      background: var(--accent);
      color: #000;
      font-weight: 700;
      font-size: 11px;
      padding: 2px 6px;
      border-radius: 4px;
    }
    .toolbar {
      display: flex;
      gap: 12px;
      margin-bottom: 20px;
      flex-wrap: wrap;
    }
    .search-input {
      flex: 1;
      min-width: 260px;
      background: var(--card-bg);
      border: 1px solid var(--border);
      color: var(--text-bright);
      padding: 8px 14px;
      border-radius: 6px;
      font-size: 14px;
      outline: none;
    }
    .search-input:focus {
      border-color: var(--accent);
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
    }
    th {
      background: #11151c;
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
      background: var(--card-bg);
      border: 1px solid var(--border);
      color: var(--text-bright);
      padding: 6px 12px;
      border-radius: 6px;
      cursor: pointer;
      font-size: 12px;
      transition: all 0.2s;
    }
    .btn:hover {
      background: var(--border);
    }
    .btn-primary {
      background: var(--accent);
      color: #000;
      border-color: var(--accent);
      font-weight: 600;
    }
    .btn-primary:hover {
      opacity: 0.9;
    }
    .tag {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 12px;
      font-size: 11px;
      font-weight: 500;
    }
    .tag-android { background: rgba(63, 185, 80, 0.15); color: var(--success); }
    .tag-tizen { background: rgba(88, 166, 255, 0.15); color: var(--accent); }
    .tag-error { background: rgba(248, 81, 73, 0.15); color: var(--danger); }
    
    /* Modal / Drawer */
    .modal-overlay {
      display: none;
      position: fixed;
      top: 0; left: 0; right: 0; bottom: 0;
      background: rgba(0, 0, 0, 0.7);
      backdrop-filter: blur(4px);
      justify-content: center;
      align-items: center;
      z-index: 1000;
    }
    .modal {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 12px;
      width: 90%;
      max-width: 800px;
      max-height: 85vh;
      overflow-y: auto;
      padding: 24px;
      box-shadow: 0 16px 32px rgba(0, 0, 0, 0.5);
    }
    .modal-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 16px;
      border-bottom: 1px solid var(--border);
      padding-bottom: 12px;
    }
    pre {
      background: #090d13;
      border: 1px solid var(--border);
      padding: 14px;
      border-radius: 6px;
      font-family: monospace;
      font-size: 12px;
      overflow-x: auto;
      color: #79c0ff;
      margin-top: 12px;
    }
  </style>
</head>
<body>
  <div class="header">
    <div class="logo">
      <span>SmartTube VOX</span>
      <span class="badge-vox">DIAGNOSTICS</span>
    </div>
    <div>
      <span style="font-size: 13px; color: var(--text-muted); margin-right: 12px;">30-дневное хранение</span>
      <button class="btn" onclick="location.reload()">Обновить</button>
    </div>
  </div>

  <div class="toolbar">
    <input type="text" id="searchInput" class="search-input" placeholder="Поиск по ID отчёта (VOX-A-...), модели или ошибке..." onkeyup="filterReports()">
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
          <th>Действие</th>
        </tr>
      </thead>
      <tbody>
        ${reports.length === 0 ? `
        <tr>
          <td colspan="7" style="text-align: center; color: var(--text-muted); padding: 32px;">
            Отчётов пока нет. Отправьте отчёт из SmartTube (Настройки → Совместимость устройства → Диагностика и логи).
          </td>
        </tr>` : reports.map(r => `
        <tr>
          <td><span class="report-id" onclick="viewReport('${r.report_id}')">${r.report_id}</span></td>
          <td>${new Date(r.created_at).toLocaleString('ru-RU')}</td>
          <td><span class="tag ${r.platform.includes('Tizen') ? 'tag-tizen' : 'tag-android'}">${r.platform}</span></td>
          <td>${r.app_version}</td>
          <td>${r.device_family}</td>
          <td>${r.error_category ? `<span class="tag tag-error">${r.error_category}</span>` : '<span style="color: var(--text-muted);">-</span>'}</td>
          <td><button class="btn" onclick="viewReport('${r.report_id}')">Открыть</button></td>
        </tr>`).join('')}
      </tbody>
    </table>
  </div>

  <div id="modalOverlay" class="modal-overlay" onclick="closeModal(event)">
    <div class="modal" onclick="event.stopPropagation()">
      <div class="modal-header">
        <h3 id="modalTitle" style="color: var(--text-bright);">Детали отчёта</h3>
        <button class="btn" onclick="closeModal()">Закрыть</button>
      </div>
      <div id="modalBody">
        <p style="color: var(--text-muted);">Загрузка деталей...</p>
      </div>
    </div>
  </div>

  <script>
    async function viewReport(id) {
      document.getElementById('modalOverlay').style.display = 'flex';
      document.getElementById('modalTitle').innerText = 'Отчёт ' + id;
      document.getElementById('modalBody').innerHTML = '<p style="color: var(--text-muted);">Загрузка...</p>';
      try {
        const res = await fetch('/v1/admin/reports/' + encodeURIComponent(id));
        if (!res.ok) throw new Error('Ошибка загрузки: ' + res.status);
        const data = await res.json();
        
        let eventsHtml = '';
        if (data.payload && data.payload.safeRecentEvents && data.payload.safeRecentEvents.length > 0) {
          eventsHtml = '<h4>Последние события (' + data.payload.safeRecentEvents.length + '):</h4><div style="max-height: 200px; overflow-y: auto; margin-top: 8px;"><table style="font-size: 11px;"><thead><tr><th>Время</th><th>Уровень</th><th>Код</th><th>Сообщение</th></tr></thead><tbody>' +
            data.payload.safeRecentEvents.map(e => '<tr><td>' + new Date(e.timestamp).toLocaleTimeString() + '</td><td>' + e.level + '</td><td>' + e.code + '</td><td>' + (e.message || '') + '</td></tr>').join('') +
            '</tbody></table></div>';
        }

        document.getElementById('modalBody').innerHTML = 
          '<div style="margin-bottom: 12px; font-size: 13px;">' +
          '<strong>Платформа:</strong> ' + data.platform + ' | <strong>Версия:</strong> ' + data.app_version + '<br>' +
          '<strong>Устройство:</strong> ' + data.device_family + '<br>' +
          '<strong>Получен:</strong> ' + new Date(data.created_at).toLocaleString('ru-RU') +
          '</div>' +
          eventsHtml +
          '<h4 style="margin-top: 16px;">Полный JSON (Sanitized):</h4>' +
          '<pre id="jsonPre">' + JSON.stringify(data.payload, null, 2) + '</pre>' +
          '<button class="btn btn-primary" style="margin-top: 12px;" onclick="copyJson()">Скопировать JSON</button>';
      } catch (err) {
        document.getElementById('modalBody').innerHTML = '<p style="color: var(--danger);">Не удалось загрузить отчёт: ' + err.message + '</p>';
      }
    }

    function closeModal(e) {
      document.getElementById('modalOverlay').style.display = 'none';
    }

    function copyJson() {
      const pre = document.getElementById('jsonPre');
      if (pre) {
        navigator.clipboard.writeText(pre.innerText);
        alert('JSON скопирован в буфер обмена!');
      }
    }

    function filterReports() {
      const q = document.getElementById('searchInput').value.toLowerCase();
      const rows = document.querySelectorAll('#reportsTable tbody tr');
      rows.forEach(r => {
        const text = r.innerText.toLowerCase();
        r.style.display = text.includes(q) ? '' : 'none';
      });
    }
  </script>
</body>
</html>`;
}
