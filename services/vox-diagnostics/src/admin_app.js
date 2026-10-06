/**
 * SmartTube VOX Diagnostics — Client-side Admin Panel Application
 * Clean, modern, vanilla JavaScript (zero external dependencies).
 * Fully CSP-compliant (script-src 'self' without unsafe-inline or unsafe-eval).
 */

(function () {
  'use strict';

  // Application State
  let currentReport = null;
  let activeMainTab = 'reports';
  let activeModalTab = 'overview';
  let isRefreshing = false;

  // DOM Elements Cache
  let searchInput = null;
  let filterPlatform = null;
  let filterStatus = null;
  let filterPurpose = null;
  let filterCategory = null;
  let refreshButton = null;
  let reportsTableBody = null;
  let issuesTableBody = null;
  let modalOverlay = null;
  let modalTitle = null;
  let modalPurposeBadge = null;
  let modalContent = null;
  let kpi24h = null;
  let kpi7d = null;
  let kpiActive = null;
  let kpiTotal = null;

  /**
   * Escape HTML special characters for safe insertion into innerHTML.
   */
  function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  /**
   * Format a numeric timestamp into localized date & time string.
   */
  function formatDateTime(ts) {
    if (!ts) return '-';
    try {
      const d = new Date(Number(ts));
      if (isNaN(d.getTime())) return '-';
      return d.toLocaleString('ru-RU');
    } catch {
      return '-';
    }
  }

  /**
   * Format a numeric timestamp into localized time string.
   */
  function formatTime(ts) {
    if (!ts) return '-';
    try {
      const d = new Date(Number(ts));
      if (isNaN(d.getTime())) return '-';
      return d.toLocaleTimeString('ru-RU');
    } catch {
      return '-';
    }
  }

  /**
   * Initialize DOM element references.
   */
  function cacheDomElements() {
    searchInput = document.getElementById('searchInput');
    filterPlatform = document.getElementById('filterPlatform');
    filterStatus = document.getElementById('filterStatus');
    filterPurpose = document.getElementById('filterPurpose');
    filterCategory = document.getElementById('filterCategory');
    refreshButton = document.getElementById('refreshButton');
    reportsTableBody = document.getElementById('reportsTableBody');
    issuesTableBody = document.getElementById('issuesTableBody');
    modalOverlay = document.getElementById('modalOverlay');
    modalTitle = document.getElementById('modalTitle');
    modalPurposeBadge = document.getElementById('modalPurposeBadge');
    modalContent = document.getElementById('modalContent');
    kpi24h = document.getElementById('kpi24h');
    kpi7d = document.getElementById('kpi7d');
    kpiActive = document.getElementById('kpiActiveIssues');
    kpiTotal = document.getElementById('kpiTotal');
  }

  /**
   * Switch the active top-level tab: 'reports', 'issues', or 'metrics'/'stats'.
   */
  function switchMainTab(tabName) {
    const normalizedTab = (tabName === 'stats' || tabName === 'metrics') ? 'stats' : tabName;
    activeMainTab = normalizedTab;

    // Views
    const viewReports = document.getElementById('viewReports');
    const viewIssues = document.getElementById('viewIssues');
    const viewStats = document.getElementById('viewStats');

    if (viewReports) viewReports.style.display = (normalizedTab === 'reports') ? 'block' : 'none';
    if (viewIssues) viewIssues.style.display = (normalizedTab === 'issues') ? 'block' : 'none';
    if (viewStats) viewStats.style.display = (normalizedTab === 'stats') ? 'block' : 'none';

    // Buttons
    document.querySelectorAll('.nav-tab').forEach(btn => {
      const btnTab = btn.getAttribute('data-tab');
      const isTarget = (btnTab === normalizedTab) ||
                       (normalizedTab === 'stats' && (btnTab === 'stats' || btnTab === 'metrics'));
      btn.classList.toggle('active', Boolean(isTarget));
    });
  }

  /**
   * Switch the active modal sub-tab: 'overview', 'codecs', 'timeline', 'ops', or 'raw'.
   */
  function switchModalTab(tabName) {
    activeModalTab = tabName;

    // Toggle active tab button
    document.querySelectorAll('.modal-tab-btn').forEach(btn => {
      const bTab = btn.getAttribute('data-modal-tab');
      btn.classList.toggle('active', bTab === tabName);
    });

    // Toggle active section view
    document.querySelectorAll('.modal-section-view').forEach(section => {
      const sId = section.getAttribute('id');
      const targetId = 'mSection_' + tabName;
      section.style.display = (sId === targetId) ? 'block' : 'none';
    });
  }

  /**
   * Filter the main reports table rows based on search input and dropdown filters.
   */
  function filterReportsTable() {
    if (!reportsTableBody) return;

    const query = (searchInput?.value || '').toLowerCase().trim();
    const platFilter = (filterPlatform?.value || '').toLowerCase().trim();
    const statFilter = (filterStatus?.value || '').trim();
    const purpFilter = (filterPurpose?.value || '').trim();
    const catFilter = (filterCategory?.value || '').toLowerCase().trim();

    const rows = reportsTableBody.querySelectorAll('tr[data-report-id]');
    let visibleCount = 0;

    rows.forEach(row => {
      const rId = (row.getAttribute('data-report-id') || '').toLowerCase();
      const rPlat = (row.getAttribute('data-platform') || '').toLowerCase();
      const rVer = (row.getAttribute('data-version') || '').toLowerCase();
      const rDev = (row.getAttribute('data-device') || '').toLowerCase();
      const rCat = (row.getAttribute('data-category') || '').toLowerCase();
      const rStat = row.getAttribute('data-status') || '';
      const rPurp = row.getAttribute('data-purpose') || '';
      const rSig = (row.getAttribute('data-signature') || '').toLowerCase();

      // Search match
      const textMatches = !query ||
        rId.includes(query) ||
        rDev.includes(query) ||
        rVer.includes(query) ||
        rCat.includes(query) ||
        rSig.includes(query);

      // Dropdown filter matches
      const platMatches = !platFilter || rPlat.includes(platFilter);
      const statMatches = !statFilter || rStat === statFilter;
      const purpMatches = !purpFilter || rPurp === purpFilter;
      const catMatches = !catFilter || rCat.includes(catFilter);

      const isVisible = textMatches && platMatches && statMatches && purpMatches && catMatches;
      row.style.display = isVisible ? '' : 'none';
      if (isVisible) visibleCount++;
    });

    // Update empty state if all filtered out
    const emptyRow = document.getElementById('reportsEmptyRow');
    if (emptyRow) {
      emptyRow.style.display = (visibleCount === 0 && rows.length > 0) ? '' : 'none';
    }
  }

  /**
   * Open report detail modal by reportId.
   */
  async function openReport(reportId) {
    if (!reportId || !modalOverlay) return;

    modalOverlay.style.display = 'flex';
    if (modalTitle) modalTitle.textContent = 'Отчёт ' + reportId;
    if (modalPurposeBadge) modalPurposeBadge.innerHTML = '';
    if (modalContent) {
      modalContent.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);"><span style="display:inline-block; animation: pulse 1.5s infinite;">⏳ Загрузка деталей отчёта...</span></div>';
    }

    try {
      const res = await fetch('/v1/admin/reports/' + encodeURIComponent(reportId), {
        headers: { 'Accept': 'application/json' },
      });

      if (res.status === 401) {
        alert('Сессия истекла или требуется повторная авторизация.');
        window.location.reload();
        return;
      }

      if (!res.ok) {
        throw new Error('HTTP ' + res.status + ': ' + res.statusText);
      }

      const reportData = await res.json();
      currentReport = reportData;
      renderModal(reportData);
      switchModalTab('overview');
    } catch (err) {
      if (modalContent) {
        modalContent.innerHTML = '<div style="padding: 24px; color: var(--danger);"><p><strong>Ошибка загрузки отчёта:</strong></p><p>' + escapeHtml(err.message) + '</p></div>';
      }
    }
  }

  /**
   * Render the contents of the detail modal.
   */
  function renderModal(data) {
    if (!modalContent) return;

    const p = data.payload || {};
    const rec = p.recommendedSettings || {};
    const cur = p.currentPolicy || {};
    const disp = p.display || {};
    const events = p.safeRecentEvents || [];
    const download = p.downloadState || p.backgroundDownloadState || p.downloadStorage || null;
    const translation = p.translationState || p.translationProvider || p.liveTranslationBuffer || null;

    if (modalPurposeBadge) {
      if (data.report_purpose === 'TEST') {
        modalPurposeBadge.innerHTML = '<span class="tag tag-purpose-test">TEST</span>';
      } else {
        modalPurposeBadge.innerHTML = '';
      }
    }

    let html = '';

    // ==========================================
    // SECTION 1: OVERVIEW
    // ==========================================
    html += '<div id="mSection_overview" class="modal-section-view">';
    html += '<div class="grid-2">';

    // Device & OS Card
    html += '<div class="section-card"><div class="section-title">Устройство и ОС</div><table style="font-size: 12px;"><tbody>';
    html += '<tr><td><strong>Производитель:</strong></td><td>' + escapeHtml(p.manufacturer || data.device_family || '-') + '</td></tr>';
    html += '<tr><td><strong>Модель:</strong></td><td>' + escapeHtml(p.model || '-') + '</td></tr>';
    html += '<tr><td><strong>Система:</strong></td><td>' + escapeHtml(p.osName || 'Android') + ' ' + escapeHtml(p.osVersion || '') + ' (API ' + (p.sdkInt || '-') + ')</td></tr>';
    html += '<tr><td><strong>Категория TV:</strong></td><td>' + escapeHtml(p.deviceTier || '-') + '</td></tr>';
    html += '<tr><td><strong>Платформа:</strong></td><td>' + escapeHtml(data.platform || '-') + '</td></tr>';
    html += '<tr><td><strong>Создан:</strong></td><td>' + formatDateTime(data.created_at) + '</td></tr>';
    html += '</tbody></table></div>';

    // Application & Screen Card
    html += '<div class="section-card"><div class="section-title">Приложение и Экран</div><table style="font-size: 12px;"><tbody>';
    html += '<tr><td><strong>Версия приложения:</strong></td><td>' + escapeHtml(data.app_version || '-') + ' (' + (p.appVersionCode || '-') + ')</td></tr>';
    html += '<tr><td><strong>Разрешение экрана:</strong></td><td>' + escapeHtml(disp.resolution || '-') + ' @ ' + (disp.refreshRateHz || '-') + 'Hz</td></tr>';
    html += '<tr><td><strong>HDR10 / HLG:</strong></td><td>' + escapeHtml(disp.hdr10 || '-') + ' / ' + escapeHtml(disp.hlg || '-') + '</td></tr>';
    html += '<tr><td><strong>Категория ошибки:</strong></td><td>' + (data.error_category ? '<span class="tag tag-error">' + escapeHtml(data.error_category) + '</span>' : '<span style="color: var(--text-muted);">-</span>') + '</td></tr>';
    html += '<tr><td><strong>Сигнатура сбоя:</strong></td><td><code>' + escapeHtml(data.error_signature || '-') + '</code></td></tr>';
    html += '<tr><td><strong>Статус:</strong></td><td><span class="tag tag-status-' + escapeHtml((data.status || 'new').toLowerCase()) + '">' + escapeHtml(data.status || 'NEW') + '</span></td></tr>';
    html += '</tbody></table></div>';

    html += '</div>'; // End grid-2

    // Risk Warning Alert
    if (p.riskWarning) {
      html += '<div class="section-card" style="border-color: var(--warning); background: rgba(245, 158, 11, 0.08); margin-top: 12px;">';
      html += '<div class="section-title" style="color: var(--warning);">Предупреждение о рисках совместимости</div>';
      html += '<p style="color: var(--warning); font-size: 13px;">⚠️ ' + escapeHtml(p.riskWarning) + '</p>';
      html += '</div>';
    }

    html += '</div>'; // End overview

    // ==========================================
    // SECTION 2: CODECS & POLICY
    // ==========================================
    html += '<div id="mSection_codecs" class="modal-section-view" style="display: none;">';
    html += '<div class="grid-2">';

    // Video Codecs
    html += '<div class="section-card"><div class="section-title">Видеодекодеры</div><table style="font-size: 11px;"><tbody>';
    const vCodecs = Object.entries(p.videoCodecs || {});
    if (vCodecs.length === 0) {
      html += '<tr><td style="color: var(--text-muted);">Нет информации</td></tr>';
    } else {
      vCodecs.forEach(([k, v]) => {
        html += '<tr><td><strong>' + escapeHtml(k) + '</strong></td><td>' + escapeHtml(v) + '</td></tr>';
      });
    }
    html += '</tbody></table></div>';

    // Audio Codecs
    html += '<div class="section-card"><div class="section-title">Аудиодекодеры</div><table style="font-size: 11px;"><tbody>';
    const aCodecs = Object.entries(p.audioCodecs || {});
    if (aCodecs.length === 0) {
      html += '<tr><td style="color: var(--text-muted);">Нет информации</td></tr>';
    } else {
      aCodecs.forEach(([k, v]) => {
        html += '<tr><td><strong>' + escapeHtml(k) + '</strong></td><td>' + escapeHtml(v) + '</td></tr>';
      });
    }
    html += '</tbody></table></div>';

    html += '</div>'; // End grid-2

    // Policy comparison
    html += '<div class="section-card"><div class="section-title">Политика качества: Текущая vs Рекомендуемая</div>';
    html += '<table style="font-size: 12px;">';
    html += '<thead><tr><th>Параметр</th><th>Текущая политика</th><th>Рекомендуемая политика</th></tr></thead><tbody>';
    html += '<tr><td>Режим</td><td>' + escapeHtml(cur.mode || '-') + '</td><td>' + escapeHtml(rec.mode || '-') + '</td></tr>';
    html += '<tr><td>Макс. качество</td><td>' + (cur.maxQualityHeight ? escapeHtml(cur.maxQualityHeight) + 'p' : '-') + '</td><td>' + (rec.maxQualityHeight ? escapeHtml(rec.maxQualityHeight) + 'p' : '-') + '</td></tr>';
    html += '<tr><td>Видеокодек</td><td>' + escapeHtml(cur.preferredVideoCodec || '-') + '</td><td>' + escapeHtml(rec.preferredVideoCodec || '-') + '</td></tr>';
    html += '<tr><td>Аудиокодек</td><td>' + escapeHtml(cur.preferredAudioCodec || '-') + '</td><td>' + escapeHtml(rec.preferredAudioCodec || '-') + '</td></tr>';
    html += '<tr><td>Passthrough</td><td>' + (cur.passthroughEnabled === true ? 'Да' : cur.passthroughEnabled === false ? 'Нет' : '-') + '</td><td>' + (rec.passthroughEnabled === true ? 'Да' : rec.passthroughEnabled === false ? 'Нет' : '-') + '</td></tr>';
    html += '</tbody></table></div>';

    // Download & Translation state if available
    if (download || translation) {
      html += '<div class="grid-2">';
      if (download) {
        html += '<div class="section-card"><div class="section-title">Состояние загрузки / Muxer</div>';
        html += '<pre style="font-size: 11px; max-height: 180px;">' + escapeHtml(JSON.stringify(download, null, 2)) + '</pre></div>';
      }
      if (translation) {
        html += '<div class="section-card"><div class="section-title">Состояние перевода / Buffer</div>';
        html += '<pre style="font-size: 11px; max-height: 180px;">' + escapeHtml(JSON.stringify(translation, null, 2)) + '</pre></div>';
      }
      html += '</div>';
    }

    html += '</div>'; // End codecs

    // ==========================================
    // SECTION 3: TIMELINE
    // ==========================================
    html += '<div id="mSection_timeline" class="modal-section-view" style="display: none;">';
    if (!events || events.length === 0) {
      html += '<div class="section-card" style="text-align: center; color: var(--text-muted); padding: 32px;">В отчёте нет сохранённых событий журнала.</div>';
    } else {
      html += '<div class="section-card"><div class="section-title">События перед инцидентом (' + events.length + ')</div>';
      html += '<div style="max-height: 420px; overflow-y: auto;"><table style="font-size: 11px;">';
      html += '<thead><tr><th>Время</th><th>Уровень</th><th>Категория</th><th>Код</th><th>Сообщение / Контекст</th></tr></thead><tbody>';
      events.forEach(e => {
        const lvlClass = e.level === 'ERROR' ? 'tag-error' : e.level === 'WARNING' ? 'tag-status-reviewed' : 'tag-status-test';
        let ctxStr = '';
        if (e.context && typeof e.context === 'object' && Object.keys(e.context).length > 0) {
          ctxStr = '<br><span style="color: var(--text-muted); font-family: monospace;">' + escapeHtml(JSON.stringify(e.context)) + '</span>';
        }
        html += '<tr>' +
          '<td>' + formatTime(e.timestamp) + '</td>' +
          '<td><span class="tag ' + lvlClass + '">' + escapeHtml(e.level || 'INFO') + '</span></td>' +
          '<td>' + escapeHtml(e.category || '-') + '</td>' +
          '<td><code>' + escapeHtml(e.code || '-') + '</code></td>' +
          '<td>' + escapeHtml(e.message || '') + ctxStr + '</td>' +
          '</tr>';
      });
      html += '</tbody></table></div></div>';
    }
    html += '</div>'; // End timeline

    // ==========================================
    // SECTION 4: OPERATIONS & STATUS
    // ==========================================
    html += '<div id="mSection_ops" class="modal-section-view" style="display: none;">';

    // Status & Notes Card
    html += '<div class="section-card"><div class="section-title">Управление статусом и заметки разработчика</div>';
    html += '<div style="display: flex; gap: 12px; align-items: center; margin-bottom: 12px;">';
    html += '<label for="opStatusSelect" style="font-size: 13px; font-weight: 600;">Статус отчёта:</label>';
    html += '<select id="opStatusSelect" class="select-filter">';
    const statuses = ['NEW', 'REVIEWED', 'KNOWN_ISSUE', 'RESOLVED', 'IGNORED_TEST'];
    statuses.forEach(s => {
      html += '<option value="' + s + '" ' + (data.status === s ? 'selected' : '') + '>' + s + '</option>';
    });
    html += '</select></div>';

    html += '<label for="opNotesText" style="font-size: 13px; font-weight: 600;">Заметки разработчика (Developer Notes):</label>';
    html += '<textarea id="opNotesText" class="notes-textarea" placeholder="Укажите issue #, причину сбоя или статус фикса...">' + escapeHtml(data.developer_notes || '') + '</textarea>';

    html += '<div style="margin-top: 14px; display: flex; gap: 10px;">';
    html += '<button class="btn btn-primary" id="btnSaveOps" data-action="save-ops" data-report-id="' + escapeHtml(data.report_id) + '">💾 Сохранить изменения</button>';
    html += '</div></div>';

    // Export & Delete Card
    html += '<div class="section-card"><div class="section-title">Экспорт и удаление</div>';
    html += '<div style="display: flex; gap: 10px; flex-wrap: wrap;">';
    html += '<button class="btn" id="btnCopyMarkdown" data-action="copy-markdown">📋 Скопировать сводку для GitHub Issue</button>';
    html += '<button class="btn" id="btnDownloadJson" data-action="download-json">💾 Скачать Sanitized JSON</button>';
    html += '<button class="btn btn-danger" id="btnDeleteReport" data-action="delete-report" data-report-id="' + escapeHtml(data.report_id) + '">🗑️ Удалить отчёт</button>';
    html += '</div></div>';

    html += '</div>'; // End ops

    // ==========================================
    // SECTION 5: RAW JSON
    // ==========================================
    html += '<div id="mSection_raw" class="modal-section-view" style="display: none;">';
    html += '<pre id="rawJsonPre">' + escapeHtml(JSON.stringify(p, null, 2)) + '</pre>';
    html += '<button class="btn btn-primary" style="margin-top: 12px;" id="btnCopyRawJson" data-action="copy-raw-json">Скопировать JSON</button>';
    html += '</div>';

    modalContent.innerHTML = html;
  }

  /**
   * Close report detail modal.
   */
  function closeModal() {
    if (modalOverlay) {
      modalOverlay.style.display = 'none';
    }
    currentReport = null;
  }

  /**
   * Save status & developer notes via PATCH /v1/admin/reports/:id.
   */
  async function saveReportOperations(reportId) {
    const statusSelect = document.getElementById('opStatusSelect');
    const notesText = document.getElementById('opNotesText');
    const saveBtn = document.getElementById('btnSaveOps');

    if (!reportId || !statusSelect || !notesText) return;

    const newStatus = statusSelect.value;
    const newNotes = notesText.value;

    if (saveBtn) {
      saveBtn.disabled = true;
      saveBtn.textContent = 'Сохранение...';
    }

    try {
      const res = await fetch('/v1/admin/reports/' + encodeURIComponent(reportId), {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json'
        },
        body: JSON.stringify({ status: newStatus, developerNotes: newNotes })
      });

      if (!res.ok) {
        throw new Error('HTTP ' + res.status + ': ' + res.statusText);
      }

      const resData = await res.json();
      if (currentReport) {
        currentReport.status = newStatus;
        currentReport.developer_notes = newNotes;
      }

      alert('Статус и заметки успешно сохранены!');

      // Update row in table immediately
      const row = document.querySelector('tr[data-report-id="' + reportId + '"]');
      if (row) {
        row.setAttribute('data-status', newStatus);
        const statusCell = row.children[6];
        if (statusCell) {
          statusCell.innerHTML = '<span class="tag tag-status-' + newStatus.toLowerCase() + '">' + escapeHtml(newStatus) + '</span>';
        }
      }

      if (saveBtn) {
        saveBtn.disabled = false;
        saveBtn.textContent = '💾 Сохранить изменения';
      }
    } catch (err) {
      alert('Не удалось сохранить изменения: ' + err.message);
      if (saveBtn) {
        saveBtn.disabled = false;
        saveBtn.textContent = '💾 Сохранить изменения';
      }
    }
  }

  /**
   * Delete report via DELETE /v1/admin/reports/:id.
   */
  async function deleteCurrentReport(reportId) {
    if (!reportId) return;
    if (!confirm('Вы действительно хотите удалить отчёт ' + reportId + '? Это действие необратимо.')) {
      return;
    }

    try {
      const res = await fetch('/v1/admin/reports/' + encodeURIComponent(reportId), {
        method: 'DELETE',
        headers: { 'Accept': 'application/json' }
      });

      if (!res.ok) {
        throw new Error('HTTP ' + res.status + ': ' + res.statusText);
      }

      alert('Отчёт ' + reportId + ' успешно удалён.');
      closeModal();

      // Remove row from table
      const row = document.querySelector('tr[data-report-id="' + reportId + '"]');
      if (row) {
        row.remove();
      }
      filterReportsTable();
    } catch (err) {
      alert('Не удалось удалить отчёт: ' + err.message);
    }
  }

  /**
   * Copy GitHub-formatted markdown summary to clipboard.
   */
  function copyGitHubMarkdown() {
    if (!currentReport) return;

    const r = currentReport;
    const p = r.payload || {};
    const events = p.safeRecentEvents || [];
    const b = '`';
    const tripleB = '```';

    const evText = events.map(e =>
      '[' + formatTime(e.timestamp) + '] [' + (e.level || 'INFO') + '] [' + (e.category || '-') + '] ' + (e.code || '-') + ': ' + (e.message || '')
    ).join('\n');

    const md = '### SmartTube VOX Diagnostic Report: ' + b + r.report_id + b + '\n\n' +
      '- **App Version:** ' + b + (r.app_version || '-') + b + ' (' + (p.appVersionCode || '-') + ')\n' +
      '- **Platform:** ' + (r.platform || '-') + '\n' +
      '- **Device:** ' + (p.manufacturer || r.device_family || '') + ' ' + (p.model || '') + ' (Android API ' + (p.sdkInt || '-') + ')\n' +
      '- **Error Category:** ' + (r.error_category || 'None') + '\n' +
      '- **Error Signature:** ' + b + (r.error_signature || 'N/A') + b + '\n' +
      '- **Report Status:** ' + (r.status || 'NEW') + '\n\n' +
      '#### Policy & Recommended Settings\n' +
      '- Current Policy: ' + b + JSON.stringify(p.currentPolicy || {}) + b + '\n' +
      '- Recommended: ' + b + JSON.stringify(p.recommendedSettings || {}) + b + '\n\n' +
      '#### Safe Recent Events (' + events.length + ')\n' +
      tripleB + 'text\n' + (evText || 'No events recorded') + '\n' + tripleB + '\n';

    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(md)
        .then(() => alert('Сводка для GitHub Issue скопирована в буфер обмена!'))
        .catch(e => prompt('Скопируйте текст вручную:', md));
    } else {
      prompt('Скопируйте текст вручную:', md);
    }
  }

  /**
   * Download sanitized JSON report file.
   */
  function downloadJsonFile() {
    if (!currentReport || !currentReport.payload) return;
    try {
      const dataStr = 'data:text/json;charset=utf-8,' + encodeURIComponent(JSON.stringify(currentReport.payload, null, 2));
      const downloadAnchor = document.createElement('a');
      downloadAnchor.setAttribute('href', dataStr);
      downloadAnchor.setAttribute('download', (currentReport.report_id || 'vox-report') + '.json');
      document.body.appendChild(downloadAnchor);
      downloadAnchor.click();
      downloadAnchor.remove();
    } catch (e) {
      alert('Не удалось скачать файл: ' + e.message);
    }
  }

  /**
   * Copy raw JSON payload to clipboard.
   */
  function copyRawJson() {
    if (!currentReport || !currentReport.payload) return;
    const jsonStr = JSON.stringify(currentReport.payload, null, 2);
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(jsonStr)
        .then(() => alert('Sanitized JSON скопирован!'))
        .catch(e => prompt('Скопируйте JSON вручную:', jsonStr));
    } else {
      prompt('Скопируйте JSON вручную:', jsonStr);
    }
  }

  /**
   * Refresh all dashboard data without losing auth or session.
   */
  async function refreshDashboard() {
    if (isRefreshing) return;
    isRefreshing = true;

    if (refreshButton) {
      refreshButton.disabled = true;
      refreshButton.textContent = '⏳ Обновление...';
    }

    try {
      const [reportsRes, statsRes, issuesRes] = await Promise.all([
        fetch('/v1/admin/reports?limit=100', { headers: { 'Accept': 'application/json' } }),
        fetch('/v1/admin/stats', { headers: { 'Accept': 'application/json' } }),
        fetch('/v1/admin/issues?limit=50', { headers: { 'Accept': 'application/json' } }),
      ]);

      if (reportsRes.status === 401 || statsRes.status === 401 || issuesRes.status === 401) {
        alert('Сессия истекла или требуется повторная авторизация.');
        window.location.reload();
        return;
      }

      if (!reportsRes.ok || !statsRes.ok || !issuesRes.ok) {
        throw new Error('Ошибка обновления данных через API');
      }

      const [reportsData, statsData, issuesData] = await Promise.all([
        reportsRes.json(),
        statsRes.json(),
        issuesRes.json(),
      ]);

      const reports = reportsData.reports || [];
      const stats = statsData || {};
      const issues = issuesData.issues || [];

      // Update KPI cards
      if (kpi24h) kpi24h.textContent = stats.reports24h || 0;
      if (kpi7d) kpi7d.textContent = stats.reports7d || 0;
      if (kpiActive) kpiActive.textContent = stats.activeIssuesCount || 0;
      if (kpiTotal) kpiTotal.textContent = stats.totalReports || reports.length;

      // Update Tab Badges
      const tabBtnReports = document.getElementById('tabBtnReports');
      const tabBtnIssues = document.getElementById('tabBtnIssues');
      if (tabBtnReports) tabBtnReports.textContent = 'Все отчёты (' + reports.length + ')';
      if (tabBtnIssues) tabBtnIssues.textContent = 'Частые проблемы (' + issues.length + ')';

      // Re-render Reports Table Body
      if (reportsTableBody) {
        if (reports.length === 0) {
          reportsTableBody.innerHTML = '<tr><td colspan="8" style="text-align: center; color: var(--text-muted); padding: 32px;">Отчётов пока нет.</td></tr>';
        } else {
          let rowsHtml = '';
          reports.forEach(r => {
            const isTizen = (r.platform || '').includes('Tizen');
            const platTag = isTizen ? 'tag-tizen' : 'tag-android';
            const statTag = 'tag-status-' + (r.status || 'new').toLowerCase();
            const errTag = r.error_category
              ? '<span class="tag tag-error">' + escapeHtml(r.error_category) + '</span>'
              : '<span style="color: var(--text-muted);">-</span>';
            const testBadge = r.report_purpose === 'TEST'
              ? '<span class="tag tag-purpose-test">TEST</span>'
              : '';

            rowsHtml += '<tr data-report-id="' + escapeHtml(r.report_id) + '" ' +
              'data-platform="' + escapeHtml(r.platform || '') + '" ' +
              'data-version="' + escapeHtml(r.app_version || '') + '" ' +
              'data-device="' + escapeHtml(r.device_family || '') + '" ' +
              'data-category="' + escapeHtml(r.error_category || '') + '" ' +
              'data-status="' + escapeHtml(r.status || 'NEW') + '" ' +
              'data-purpose="' + escapeHtml(r.report_purpose || 'USER') + '" ' +
              'data-signature="' + escapeHtml(r.error_signature || '') + '">' +
              '<td><span class="report-id" data-action="open-report" data-report-id="' + escapeHtml(r.report_id) + '">' + escapeHtml(r.report_id) + '</span> ' + testBadge + '</td>' +
              '<td>' + formatDateTime(r.created_at) + '</td>' +
              '<td><span class="tag ' + platTag + '">' + escapeHtml(r.platform || '-') + '</span></td>' +
              '<td>' + escapeHtml(r.app_version || '-') + '</td>' +
              '<td>' + escapeHtml(r.device_family || '-') + '</td>' +
              '<td>' + errTag + '</td>' +
              '<td><span class="tag ' + statTag + '">' + escapeHtml(r.status || 'NEW') + '</span></td>' +
              '<td><button class="btn btn-open-report" data-action="open-report" data-report-id="' + escapeHtml(r.report_id) + '">Открыть</button></td>' +
              '</tr>';
          });
          reportsTableBody.innerHTML = rowsHtml;
        }
      }

      // Re-render Issues Table Body
      if (issuesTableBody) {
        if (issues.length === 0) {
          issuesTableBody.innerHTML = '<tr><td colspan="7" style="text-align: center; color: var(--text-muted); padding: 32px;">Сгруппированных проблем пока нет.</td></tr>';
        } else {
          let issuesHtml = '';
          issues.forEach(i => {
            const statTag = 'tag-status-' + (i.status || 'new').toLowerCase();
            issuesHtml += '<tr>' +
              '<td style="font-family: monospace; font-size: 12px; color: var(--accent); font-weight: 600;">' + escapeHtml(i.error_signature || '-') + '</td>' +
              '<td><span class="tag tag-error">' + escapeHtml(i.error_category || 'ERROR') + '</span></td>' +
              '<td><strong style="color: var(--text-bright); font-size: 14px;">' + (i.count || 1) + '</strong></td>' +
              '<td>' + formatDateTime(i.first_seen) + '</td>' +
              '<td>' + formatDateTime(i.last_seen) + '</td>' +
              '<td><span class="tag ' + statTag + '">' + escapeHtml(i.status || 'NEW') + '</span></td>' +
              '<td><button class="btn btn-primary" data-action="open-report" data-report-id="' + escapeHtml(i.sample_report_id || '') + '">Открыть образец</button></td>' +
              '</tr>';
          });
          issuesTableBody.innerHTML = issuesHtml;
        }
      }

      // Re-apply filters
      filterReportsTable();
    } catch (err) {
      console.warn('[Admin] In-place API refresh failed, falling back to location.reload():', err);
      window.location.reload();
    } finally {
      isRefreshing = false;
      if (refreshButton) {
        refreshButton.disabled = false;
        refreshButton.textContent = 'Обновить';
      }
    }
  }

  /**
   * Main initialization function called on DOMContentLoaded.
   */
  function initAdminApp() {
    cacheDomElements();

    // 1. Navigation Tabs [data-tab]
    document.querySelectorAll('.nav-tab').forEach(btn => {
      btn.addEventListener('click', () => {
        const tab = btn.getAttribute('data-tab');
        if (tab) switchMainTab(tab);
      });
    });

    // 2. Search Input
    if (searchInput) {
      searchInput.addEventListener('input', filterReportsTable);
      searchInput.addEventListener('keyup', filterReportsTable);
    }

    // 3. Dropdown Filters
    if (filterPlatform) filterPlatform.addEventListener('change', filterReportsTable);
    if (filterStatus) filterStatus.addEventListener('change', filterReportsTable);
    if (filterPurpose) filterPurpose.addEventListener('change', filterReportsTable);
    if (filterCategory) filterCategory.addEventListener('change', filterReportsTable);

    // 4. Refresh Button
    if (refreshButton) {
      refreshButton.addEventListener('click', (e) => {
        e.preventDefault();
        refreshDashboard();
      });
    }

    // 5. Event Delegation for Reports Table
    if (reportsTableBody) {
      reportsTableBody.addEventListener('click', (e) => {
        const target = e.target;
        if (!target) return;

        const actionEl = target.closest('[data-action="open-report"]') || target.closest('.report-id') || target.closest('.btn-open-report');
        if (actionEl) {
          e.preventDefault();
          const reportId = actionEl.getAttribute('data-report-id') ||
                           actionEl.closest('tr')?.getAttribute('data-report-id');
          if (reportId) {
            openReport(reportId);
          }
        }
      });
    }

    // 6. Event Delegation for Issues Table
    const viewIssues = document.getElementById('viewIssues');
    if (viewIssues) {
      viewIssues.addEventListener('click', (e) => {
        const target = e.target;
        if (!target) return;

        const actionEl = target.closest('[data-action="open-report"]');
        if (actionEl) {
          e.preventDefault();
          const reportId = actionEl.getAttribute('data-report-id');
          if (reportId) {
            openReport(reportId);
          }
        }
      });
    }

    // 7. Modal Tabs [data-modal-tab]
    document.querySelectorAll('.modal-tab-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const tab = btn.getAttribute('data-modal-tab');
        if (tab) switchModalTab(tab);
      });
    });

    // 8. Modal Close Buttons and Backdrop Click
    if (modalOverlay) {
      modalOverlay.addEventListener('click', (e) => {
        if (e.target === modalOverlay) {
          closeModal();
        }
      });
    }

    const closeBtn = document.getElementById('modalCloseBtn');
    if (closeBtn) {
      closeBtn.addEventListener('click', closeModal);
    }

    // Keyboard ESC to close modal
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && modalOverlay && modalOverlay.style.display === 'flex') {
        closeModal();
      }
    });

    // 9. Event Delegation for Modal Content Actions (Save Ops, Delete, Export, Copy)
    if (modalContent) {
      modalContent.addEventListener('click', (e) => {
        const target = e.target;
        if (!target) return;

        const actionBtn = target.closest('[data-action]');
        if (!actionBtn) return;

        const action = actionBtn.getAttribute('data-action');
        const reportId = actionBtn.getAttribute('data-report-id') || (currentReport?.report_id);

        if (action === 'save-ops') {
          e.preventDefault();
          saveReportOperations(reportId);
        } else if (action === 'delete-report') {
          e.preventDefault();
          deleteCurrentReport(reportId);
        } else if (action === 'copy-markdown') {
          e.preventDefault();
          copyGitHubMarkdown();
        } else if (action === 'download-json') {
          e.preventDefault();
          downloadJsonFile();
        } else if (action === 'copy-raw-json') {
          e.preventDefault();
          copyRawJson();
        } else if (action === 'close-modal') {
          e.preventDefault();
          closeModal();
        }
      });
    }

    // Initial table filter check
    filterReportsTable();
  }

  // Self-register when DOM is ready or immediately if already loaded
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initAdminApp);
  } else {
    initAdminApp();
  }
})();
