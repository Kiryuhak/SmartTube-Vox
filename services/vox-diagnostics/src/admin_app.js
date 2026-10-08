/**
 * SmartTube VOX Diagnostics — Client-side Admin Panel Application
 * Clean, modern, vanilla JavaScript (zero external dependencies).
 * Fully CSP-compliant (script-src 'self' without unsafe-inline or unsafe-eval).
 */

(function () {
  'use strict';

  // Canonical Statuses & UI Labels
  const CANONICAL_STATUSES = ['NEW', 'IN_PROGRESS', 'RESOLVED', 'KNOWN_ISSUE', 'IGNORED_TEST'];

  const STATUS_LABELS = {
    NEW: 'Новый',
    IN_PROGRESS: 'В работе',
    RESOLVED: 'Решено',
    KNOWN_ISSUE: 'Известная проблема',
    IGNORED_TEST: 'Тест / игнор',
  };

  /**
   * Нормализует статус к одному из канонических значений:
   * NEW, IN_PROGRESS, RESOLVED, KNOWN_ISSUE, IGNORED_TEST
   */
  function normalizeStatus(rawStatus) {
    if (!rawStatus) return 'NEW';
    const s = String(rawStatus).toUpperCase().trim();
    if (s === 'REVIEWED' || s === 'TRIAGED' || s === 'IN_PROGRESS') return 'IN_PROGRESS';
    if (s === 'KNOWN' || s === 'KNOWN_ISSUE') return 'KNOWN_ISSUE';
    if (s === 'RESOLVED') return 'RESOLVED';
    if (s === 'IGNORED_TEST' || s === 'TEST' || s === 'IGNORED') return 'IGNORED_TEST';
    if (s === 'NEW') return 'NEW';
    return s;
  }

  // Application State
  let currentReport = null;
  let activeMainTab = 'reports';
  let activeModalTab = 'overview';
  let isRefreshing = false;
  let nextCursor = null;
  let pageCursor = null;
  let pageHistory = [];
  let selectedNoteId = null;
  let lastFocus = null;
  let metricSince = null;

  function showToast(message, error = false) {
    const el = document.getElementById('toast');
    if (!el) return;
    el.textContent = message;
    el.classList.toggle('error', error);
    el.hidden = false;
    clearTimeout(showToast.timer);
    showToast.timer = setTimeout(() => { el.hidden = true; }, 3500);
  }

  function statusSelectHtml(status, id) {
    return '<select class="status-select" data-status="' + escapeHtml(status) + '" aria-label="Статус отчёта ' + escapeHtml(id) + '">' +
      CANONICAL_STATUSES.map(key => '<option value="' + key + '"' + (key === status ? ' selected' : '') + '>' + escapeHtml(STATUS_LABELS[key]) + '</option>').join('') + '</select>';
  }

  async function updateInlineStatus(select) {
    const row = select.closest('tr[data-report-id]');
    const id = row?.dataset.reportId;
    if (!id) return;
    const before = select.dataset.status;
    const next = select.value;
    if (before === next) return;
    select.disabled = true;
    try {
      const response = await fetch('/v1/admin/reports/' + encodeURIComponent(id), {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status: next }),
      });
      if (!response.ok) throw new Error('save failed');
      select.dataset.status = next;
      row.dataset.status = next;
      if (currentReport?.report_id === id) currentReport.status = next;
      showToast('Статус сохранён');
      if (next === 'RESOLVED' && !row.querySelector('.note-preview')) showToast('Рекомендуется указать версию или заметку об исправлении.');
    } catch {
      select.value = before;
      showToast('Не удалось изменить статус', true);
    } finally {
      select.disabled = false;
    }
  }

  function openQuickNote(id) {
    selectedNoteId = id;
    lastFocus = document.activeElement;
    const dialog = document.getElementById('noteDialog');
    const input = document.getElementById('quickNoteText');
    const preview = [...(reportsTableBody?.querySelectorAll('tr[data-report-id]') || [])].find(row => row.dataset.reportId === id)?.querySelector('.note-preview');
    input.value = preview?.getAttribute('title') || '';
    dialog.style.display = 'flex';
    input.focus();
  }

  function closeQuickNote() {
    document.getElementById('noteDialog').style.display = 'none';
    selectedNoteId = null;
    lastFocus?.focus();
  }

  async function saveQuickNote() {
    const id = selectedNoteId;
    const note = document.getElementById('quickNoteText').value.trim();
    if (!id || !note) return;
    const save = document.getElementById('noteSave');
    save.disabled = true;
    try {
      const response = await fetch('/v1/admin/reports/' + encodeURIComponent(id) + '/notes', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ note }),
      });
      if (!response.ok) throw new Error('save failed');
      const row = [...reportsTableBody.querySelectorAll('tr[data-report-id]')].find(item => item.dataset.reportId === id);
      if (row) {
        let preview = row.querySelector('.note-preview');
        if (!preview) { preview = document.createElement('span'); preview.className = 'note-preview'; row.lastElementChild.append(preview); }
        preview.textContent = '✎ ' + note;
        preview.title = note;
      }
      closeQuickNote();
      showToast('Заметка сохранена');
    } catch { showToast('Не удалось сохранить заметку', true); }
    finally { save.disabled = false; }
  }

  // DOM Elements Cache
  let searchInput = null;
  let filterPlatform = null;
  let filterStatus = null;
  let filterPurpose = null;
  let filterCategory = null;
  let refreshButton = null;
  let purgeAllBtn = null;
  let reportsTableBody = null;
  let issuesTableBody = null;
  let modalOverlay = null;
  let modalTitle = null;
  let modalPurposeBadge = null;
  let modalContent = null;
  let detailStatusSelect = null;
  let detailNotesText = null;
  let detailSaveBtn = null;
  let purgeModalOverlay = null;
  let purgeConfirmInput = null;
  let purgeConfirmBtn = null;
  let purgeCancelBtn = null;
  let purgeModalCloseBtn = null;
  let kpi24h = null;
  let kpi7d = null;
  let kpiNew = null;
  let kpiInProgress = null;
  let kpiResolved = null;
  let kpiKnownIssue = null;
  let kpiIgnoredTest = null;
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
    purgeAllBtn = document.getElementById('purgeAllBtn');
    reportsTableBody = document.getElementById('reportsTableBody');
    issuesTableBody = document.getElementById('issuesTableBody');
    modalOverlay = document.getElementById('modalOverlay');
    modalTitle = document.getElementById('modalTitle');
    modalPurposeBadge = document.getElementById('modalPurposeBadge');
    modalContent = document.getElementById('modalContent');
    detailStatusSelect = document.getElementById('detailStatusSelect');
    detailNotesText = document.getElementById('detailNotesText');
    detailSaveBtn = document.getElementById('detailSaveBtn');
    purgeModalOverlay = document.getElementById('purgeModalOverlay');
    purgeConfirmInput = document.getElementById('purgeConfirmInput');
    purgeConfirmBtn = document.getElementById('purgeConfirmBtn');
    purgeCancelBtn = document.getElementById('purgeCancelBtn');
    purgeModalCloseBtn = document.getElementById('purgeModalCloseBtn');
    kpi24h = document.getElementById('kpi24h');
    kpi7d = document.getElementById('kpi7d');
    kpiNew = document.getElementById('kpiNew');
    kpiInProgress = document.getElementById('kpiInProgress');
    kpiResolved = document.getElementById('kpiResolved');
    kpiKnownIssue = document.getElementById('kpiKnownIssue');
    kpiIgnoredTest = document.getElementById('kpiIgnoredTest');
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
    const rawStatFilter = (filterStatus?.value || '').trim();
    const statFilter = rawStatFilter ? normalizeStatus(rawStatFilter) : '';
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
      const rStat = normalizeStatus(row.getAttribute('data-status') || '');
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

    lastFocus = document.activeElement;
    modalOverlay.style.display = 'flex';
    document.getElementById('modalCloseBtn')?.focus();
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
        modalContent.textContent = 'Не удалось загрузить подробности';
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
    const structured = p.structuredEvents || [];
    const download = p.downloadState || p.backgroundDownloadState || p.downloadStorage || null;
    const translation = p.translationState || p.translationProvider || p.liveTranslationBuffer || null;
    const normStatus = normalizeStatus(data.status);
    const statusLabel = STATUS_LABELS[normStatus] || normStatus;

    // Sync Top Workflow Bar controls
    if (detailStatusSelect) {
      detailStatusSelect.value = normStatus;
    }
    if (detailNotesText) {
      detailNotesText.value = data.developer_notes || '';
    }

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
    const lastError = [...structured].reverse().find(e => e.level === 'ERROR' || e.severity === 'CRITICAL' || e.severity === 'HIGH');
    html += '<div class="section-card"><div class="section-title">Краткий технический вывод</div>' +
      '<p><strong>Категория:</strong> ' + escapeHtml(data.error_category || '-') + ' · <strong>Подсистема:</strong> ' + escapeHtml(data.subsystem || '-') +
      ' · <strong>Этап:</strong> ' + escapeHtml(data.stage || '-') + '</p><p><strong>Ошибка:</strong> ' + escapeHtml(lastError?.event || data.event_error_category || '-') +
      ' · <strong>Важность:</strong> ' + escapeHtml(data.severity || 'INFO') + ' · <strong>Похожие:</strong> ' + Number(data.relatedReportsCount || 0) + '</p>' +
      '<p><strong>Сигнатура:</strong> <code>' + escapeHtml(data.error_signature || '-') + '</code></p>' +
      '<div style="display:flex;gap:8px;margin-top:12px"><button class="btn" data-action="copy-summary">Копировать краткий отчёт</button><button class="btn" data-action="copy-analysis">Копировать для анализа</button></div></div>';
    const technical = p.technicalSummary || {};
    html += '<div class="section-card"><div class="section-title">Технические состояния</div>';
    Object.entries({ download: 'Загрузка', player: 'Плеер', background: 'Фон', ota: 'Обновление', offline: 'Офлайн', channelGroups: 'Группы каналов', autoSetup: 'Автонастройка' }).forEach(([key, label]) => {
      if (technical[key] && Object.keys(technical[key]).length) html += '<p><strong>' + label + ':</strong> <code>' + escapeHtml(JSON.stringify(technical[key])) + '</code></p>';
    });
    html += '</div>';
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
    html += '<tr><td><strong>Статус:</strong></td><td><span class="tag tag-status-' + normStatus.toLowerCase() + '">' + escapeHtml(statusLabel) + '</span></td></tr>';
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
      html += '<label for="timelineFilter">Фильтр событий</label><select id="timelineFilter" class="select-filter"><option value="">Все</option><option value="ERROR">Ошибки</option><option value="PLAYER">Player</option><option value="DOWNLOAD">Download</option><option value="OTA">OTA</option><option value="BACKGROUND">Background</option></select>';
      html += '<div class="timeline" style="max-height:420px;overflow-y:auto;margin-top:12px">';
      const timelineEntries = [];
      events.forEach((event, index) => {
        const meta = structured[index] || {};
        const level = meta.severity || event.level || 'INFO';
        const previous = timelineEntries.at(-1);
        if (previous && !['ERROR', 'CRITICAL'].includes(meta.level || event.level) && previous.level === level &&
            previous.event.code === event.code && previous.event.category === event.category &&
            Math.abs(Number(event.timestamp) - Number(previous.event.timestamp)) <= 10000) {
          previous.count += Number(event.repeatCount || meta.repeatCount || 1);
        } else timelineEntries.push({ event, meta, level, count: Number(event.repeatCount || meta.repeatCount || 1) });
      });
      timelineEntries.forEach(({ event: e, meta: eventMeta, level, count }) => {
        const lvlClass = level === 'HIGH' || level === 'CRITICAL' ? 'tag-error' : level === 'MEDIUM' || level === 'LOW' ? 'tag-status-in_progress' : 'tag-status-test';
        let ctxStr = '';
        const context = eventMeta.safeContext || {};
        if (Object.keys(context).length > 0) {
          ctxStr = '<div style="color: var(--text-muted);font-family:monospace;font-size:11px">' + escapeHtml(JSON.stringify(context)) + '</div>';
        }
        html += '<div class="timeline-item ' + (level === 'HIGH' || level === 'CRITICAL' ? 'error' : level === 'MEDIUM' || level === 'LOW' ? 'warning' : 'info') + '" data-timeline-severity="' + escapeHtml(level) + '" data-timeline-level="' + escapeHtml(eventMeta.level || e.level || '') + '" data-timeline-subsystem="' + escapeHtml(eventMeta.subsystem || e.category || '') + '">' +
          '<div><strong>' + formatTime(e.timestamp) + '</strong> <span class="tag ' + lvlClass + '">' + escapeHtml(level) + '</span> ' + escapeHtml(e.category || '-') + '</div>' +
          '<div><code>' + escapeHtml(e.code || '-') + '</code>' + (count > 1 ? ' × ' + count : '') + '</div>' +
          '<div>' + escapeHtml(e.message || '') + '</div>' + ctxStr + '</div>';
      });
      html += '</div></div>';
    }
    html += '</div>'; // End timeline

    // ==========================================
    // SECTION 4: OPERATIONS & STATUS
    // ==========================================
    html += '<div id="mSection_ops" class="modal-section-view" style="display: none;">';
    html += '<div class="section-card"><div class="section-title">Связанная проблема</div><p><code>' + escapeHtml(data.error_signature || '-') + '</code></p><p>Статус: ' + escapeHtml(STATUS_LABELS[data.relatedIssue?.issue_status] || 'Новый') +
      ' · Исправлено в: ' + escapeHtml(data.relatedIssue?.fixed_in_version || '-') +
      ' · Патч: ' + escapeHtml(data.relatedIssue?.fix_patch || '-') +
      ' · Коммит: ' + escapeHtml(data.relatedIssue?.fix_commit || '-') + '</p></div>';
    html += '<div class="section-card"><div class="section-title">История статусов</div>' +
      ((data.statusHistory || []).map(item => '<div class="timeline-item info">' + formatDateTime(item.changedAt) + ' · ' + escapeHtml(item.fromStatus) + ' → ' + escapeHtml(item.toStatus) + ' · ' + escapeHtml(item.actor) + (item.reason ? ' · ' + escapeHtml(item.reason) : '') + '</div>').join('') || '<p>Истории изменений пока нет.</p>') + '</div>';
    html += '<div class="section-card"><div class="section-title">Заметки разработчика</div>' +
      ((data.notes || []).map(item => '<div class="timeline-item info">' + formatDateTime(item.createdAt) + ' · ' + escapeHtml(item.note) + '</div>').join('') || '<p>Заметок пока нет.</p>') + '</div>';

    // Status & Notes Card
    html += '<div class="section-card"><div class="section-title">Управление статусом и заметки разработчика</div>';
    html += '<div style="display: flex; gap: 12px; align-items: center; margin-bottom: 12px;">';
    html += '<label for="opStatusSelect" style="font-size: 13px; font-weight: 600;">Статус отчёта:</label>';
    html += '<select id="opStatusSelect" class="select-filter">';
    CANONICAL_STATUSES.forEach(s => {
      html += '<option value="' + s + '" ' + (normStatus === s ? 'selected' : '') + '>' + (STATUS_LABELS[s] || s) + '</option>';
    });
    html += '</select></div>';

    html += '<label for="opNotesText" style="font-size: 13px; font-weight: 600;">Заметки разработчика:</label>';
    html += '<textarea id="opNotesText" class="notes-textarea" placeholder="Укажите issue #, причину сбоя или статус фикса...">' + escapeHtml(data.developer_notes || '') + '</textarea>';

    html += '<div style="margin-top: 14px; display: flex; gap: 10px;">';
    html += '<button class="btn btn-primary" id="btnSaveOps" data-action="save-ops" data-report-id="' + escapeHtml(data.report_id) + '">💾 Сохранить изменения</button>';
    html += '</div></div>';

    // Export & Delete Card
    html += '<div class="section-card"><div class="section-title">Экспорт и удаление</div>';
    html += '<div style="display: flex; gap: 10px; flex-wrap: wrap;">';
    html += '<button class="btn" id="btnDownloadJson" data-action="download-json">💾 Скачать безопасный JSON</button>';
    html += '<button class="btn" id="btnCopyMarkdown" data-action="copy-markdown">📋 Скопировать сводку для GitHub Issue</button>';
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
      lastFocus?.focus();
    }
    currentReport = null;
  }

  /**
   * Download individual sanitized report JSON file via backend endpoint.
   */
  function downloadReportJson(reportId) {
    const id = reportId || currentReport?.report_id;
    if (!id) return;
    const downloadUrl = '/v1/admin/reports/' + encodeURIComponent(id) + '/download';
    const link = document.createElement('a');
    link.href = downloadUrl;
    link.download = 'vox-diagnostic-' + id + '.json';
    document.body.appendChild(link);
    link.click();
    link.remove();
  }

  function downloadJsonFile(reportId) {
    downloadReportJson(reportId);
  }

  /**
   * Save status & developer notes via PATCH /v1/admin/reports/:id.
   */
  async function saveReportOperations(reportId) {
    const targetId = reportId || currentReport?.report_id;
    if (!targetId) return;

    const topSelect = document.getElementById('detailStatusSelect');
    const topNotes = document.getElementById('detailNotesText');
    const opsSelect = document.getElementById('opStatusSelect');
    const opsNotes = document.getElementById('opNotesText');

    const newStatus = normalizeStatus((topSelect ? topSelect.value : (opsSelect ? opsSelect.value : 'NEW')));
    const newNotes = topNotes ? topNotes.value : (opsNotes ? opsNotes.value : '');

    const saveBtns = [
      document.getElementById('detailSaveBtn'),
      document.getElementById('btnSaveOps')
    ].filter(Boolean);

    saveBtns.forEach(btn => {
      btn.disabled = true;
      btn.textContent = 'Сохранение...';
    });

    try {
      const res = await fetch('/v1/admin/reports/' + encodeURIComponent(targetId), {
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

      if (currentReport) {
        currentReport.status = newStatus;
        currentReport.developer_notes = newNotes;
      }

      // Sync both controls
      if (topSelect) topSelect.value = newStatus;
      if (topNotes) topNotes.value = newNotes;
      if (opsSelect) opsSelect.value = newStatus;
      if (opsNotes) opsNotes.value = newNotes;

      // Update row in table immediately
      const row = document.querySelector('tr[data-report-id="' + targetId + '"]');
      if (row) {
        row.setAttribute('data-status', newStatus);
        const statusCell = row.children[6];
        if (statusCell) {
          statusCell.innerHTML = statusSelectHtml(newStatus, targetId);
        }
      }
      showToast('Изменения сохранены');
    } catch (err) {
      showToast('Не удалось сохранить изменения', true);
    } finally {
      saveBtns.forEach(btn => {
        btn.disabled = false;
        btn.textContent = btn.id === 'detailSaveBtn' ? 'Сохранить' : '💾 Сохранить изменения';
      });
    }
  }

  /**
   * Delete report via DELETE /v1/admin/reports/:id with explicit confirmation.
   */
  async function deleteCurrentReport(reportId) {
    const targetId = reportId || currentReport?.report_id;
    if (!targetId) return;

    if (!confirm('Вы действительно хотите удалить отчёт ' + targetId + '? Это действие нельзя отменить.')) {
      return;
    }

    try {
      const res = await fetch('/v1/admin/reports/' + encodeURIComponent(targetId), {
        method: 'DELETE',
        headers: { 'Accept': 'application/json' }
      });

      if (!res.ok) {
        throw new Error('HTTP ' + res.status + ': ' + res.statusText);
      }

      alert('Отчёт ' + targetId + ' успешно удалён.');
      closeModal();

      // Remove row from table
      const row = document.querySelector('tr[data-report-id="' + targetId + '"]');
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
    const normStatus = normalizeStatus(r.status);
    const statusLabel = STATUS_LABELS[normStatus] || normStatus;

    const evText = events.map(e =>
      '[' + formatTime(e.timestamp) + '] [' + (e.level || 'INFO') + '] [' + (e.category || '-') + '] ' + (e.code || '-') + ': ' + (e.message || '')
    ).join('\n');

    const md = '### SmartTube VOX Diagnostic Report: ' + b + r.report_id + b + '\n\n' +
      '- **App Version:** ' + b + (r.app_version || '-') + b + ' (' + (p.appVersionCode || '-') + ')\n' +
      '- **Platform:** ' + (r.platform || '-') + '\n' +
      '- **Device:** ' + (p.manufacturer || r.device_family || '') + ' ' + (p.model || '') + ' (Android API ' + (p.sdkInt || '-') + ')\n' +
      '- **Error Category:** ' + (r.error_category || 'None') + '\n' +
      '- **Error Signature:** ' + b + (r.error_signature || 'N/A') + b + '\n' +
      '- **Report Status:** ' + statusLabel + ' (' + normStatus + ')\n' +
      '- **Developer Notes:** ' + (r.developer_notes || 'None') + '\n\n' +
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

  async function copyStructuredReport(forAnalysis = false) {
    if (!currentReport) return;
    const r = currentReport;
    const p = r.payload || {};
    const events = (p.structuredEvents || []).slice(-10);
    const lines = forAnalysis ? [
      'REPORT_ID: ' + r.report_id, 'VERSION: ' + r.app_version,
      'DEVICE: ' + r.device_family, 'PLATFORM: ' + r.platform,
      'CATEGORY: ' + (r.error_category || '-'), 'SUBSYSTEM: ' + (r.subsystem || '-'),
      'STAGE: ' + (r.stage || '-'), 'ERROR_CATEGORY: ' + (r.event_error_category || '-'),
      'SEVERITY: ' + (r.severity || 'INFO'), 'SIGNATURE: ' + (r.error_signature || '-'),
      'RECENT_EVENTS: ' + JSON.stringify(events),
      'SAFE_CONTEXT: ' + JSON.stringify({ event: events.at(-1)?.safeContext || {}, technical: p.technicalSummary || {} }),
    ] : [
      r.report_id, 'Устройство: ' + r.device_family, 'Версия: ' + r.app_version,
      'Категория: ' + (r.error_category || '-'), 'Ошибка: ' + (r.event_error_category || '-'),
      'Статус: ' + (STATUS_LABELS[normalizeStatus(r.status)] || '-'),
      'Повторов: ' + Number(r.relatedReportsCount || 0),
      'Последнее событие: ' + (events.at(-1)?.event || '-'),
    ];
    try { await navigator.clipboard.writeText(lines.join('\n')); showToast('Скопировано'); }
    catch { showToast('Не удалось скопировать', true); }
  }

  /**
   * Open safe purge modal.
   */
  function openPurgeModal() {
    if (!purgeModalOverlay) return;
    purgeModalOverlay.style.display = 'flex';
    if (purgeConfirmInput) {
      purgeConfirmInput.value = '';
      purgeConfirmInput.focus();
    }
    if (purgeConfirmBtn) {
      purgeConfirmBtn.disabled = true;
    }
  }

  /**
   * Close safe purge modal.
   */
  function closePurgeModal() {
    if (purgeModalOverlay) {
      purgeModalOverlay.style.display = 'none';
    }
    if (purgeConfirmInput) {
      purgeConfirmInput.value = '';
    }
    if (purgeConfirmBtn) {
      purgeConfirmBtn.disabled = true;
    }
  }

  /**
   * Execute backend purge of all reports (DELETE /v1/admin/reports).
   */
  async function executePurgeAllReports() {
    if (!purgeConfirmInput || purgeConfirmInput.value.trim() !== 'УДАЛИТЬ') {
      alert('Для подтверждения необходимо ввести точное слово: УДАЛИТЬ');
      return;
    }

    if (purgeConfirmBtn) {
      purgeConfirmBtn.disabled = true;
      purgeConfirmBtn.textContent = 'Удаление...';
    }

    try {
      const res = await fetch('/v1/admin/reports', {
        method: 'DELETE',
        headers: { 'Accept': 'application/json' }
      });

      if (!res.ok) {
        throw new Error('HTTP ' + res.status + ': ' + res.statusText);
      }

      const resData = await res.json();
      const count = resData.deleted || 0;

      closePurgeModal();

      // Reset UI state
      if (reportsTableBody) {
        reportsTableBody.innerHTML = '<tr id="reportsEmptyRow"><td colspan="8" style="text-align: center; color: var(--text-muted); padding: 32px;">Отчётов пока нет.</td></tr>';
      }
      if (issuesTableBody) {
        issuesTableBody.innerHTML = '<tr><td colspan="9" style="text-align: center; color: var(--text-muted); padding: 32px;">Сгруппированных проблем пока нет.</td></tr>';
      }

      // Reset KPIs
      if (kpi24h) kpi24h.textContent = '0';
      if (kpi7d) kpi7d.textContent = '0';
      if (kpiNew) kpiNew.textContent = '0';
      if (kpiInProgress) kpiInProgress.textContent = '0';
      if (kpiResolved) kpiResolved.textContent = '0';
      if (kpiKnownIssue) kpiKnownIssue.textContent = '0';
      if (kpiIgnoredTest) kpiIgnoredTest.textContent = '0';
      if (kpiTotal) kpiTotal.textContent = '0';

      const tabBtnReports = document.getElementById('tabBtnReports');
      const tabBtnIssues = document.getElementById('tabBtnIssues');
      if (tabBtnReports) tabBtnReports.textContent = 'Все отчёты (0)';
      if (tabBtnIssues) tabBtnIssues.textContent = 'Частые проблемы (0)';

      alert('Удалено отчётов: ' + count);
    } catch (err) {
      alert('Ошибка при очистке логов: ' + err.message);
    } finally {
      if (purgeConfirmBtn) {
        purgeConfirmBtn.disabled = false;
        purgeConfirmBtn.textContent = 'Удалить все';
      }
    }
  }

  /**
   * Refresh all dashboard data without losing auth or session.
   */
  async function refreshDashboard() {
    if (isRefreshing) return;
    isRefreshing = true;
    const refreshState = document.getElementById('refreshState');
    if (refreshState) { refreshState.textContent = '◌'; refreshState.classList.add('spinning'); }

    if (refreshButton) {
      refreshButton.disabled = true;
      refreshButton.textContent = '⏳ Обновление...';
    }

    try {
      const params = new URLSearchParams({ limit: '50', sort: document.getElementById('sortReports')?.value || 'newest' });
      if (pageCursor) params.set('cursor', pageCursor);
      if (metricSince) params.set('since', String(metricSince));
      const filters = {
        platform: filterPlatform?.value, status: filterStatus?.value,
        reportPurpose: filterPurpose?.value, errorCategory: filterCategory?.value,
        severity: document.getElementById('filterSeverity')?.value,
        appVersion: document.getElementById('filterVersion')?.value,
        device: document.getElementById('filterDevice')?.value,
        signature: document.getElementById('filterSignature')?.value,
        search: searchInput?.value,
      };
      Object.entries(filters).forEach(([key, value]) => { if (value) params.set(key, value); });
      const special = document.getElementById('filterSpecial')?.value;
      if (special === 'attention') params.set('attention', 'true');
      if (special === 'notes') params.set('hasNotes', 'true');
      if (special === 'duplicates') params.set('hasDuplicates', 'true');
      const [reportsRes, statsRes, issuesRes] = await Promise.all([
        fetch('/v1/admin/reports?' + params, { headers: { 'Accept': 'application/json' } }),
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
      nextCursor = reportsData.nextCursor || null;
      document.getElementById('pageBack').disabled = pageHistory.length === 0;
      document.getElementById('pageNext').disabled = !nextCursor;
      document.getElementById('pageLabel').textContent = 'Страница ' + (pageHistory.length + 1);
      const stats = statsData || {};
      const issues = issuesData.issues || [];

      // Update KPI cards
      if (kpi24h) kpi24h.textContent = stats.reports24h || 0;
      if (kpi7d) kpi7d.textContent = stats.reports7d || 0;
      if (kpiNew) kpiNew.textContent = stats.newCount || 0;
      if (kpiInProgress) kpiInProgress.textContent = stats.inProgressCount || 0;
      if (kpiResolved) kpiResolved.textContent = stats.resolvedCount || 0;
      if (kpiKnownIssue) kpiKnownIssue.textContent = stats.knownIssueCount || 0;
      if (kpiIgnoredTest) kpiIgnoredTest.textContent = stats.ignoredTestCount || 0;
      if (kpiTotal) kpiTotal.textContent = stats.totalReports || reports.length;

      // Update Tab Badges
      const tabBtnReports = document.getElementById('tabBtnReports');
      const tabBtnIssues = document.getElementById('tabBtnIssues');
      if (tabBtnReports) tabBtnReports.textContent = 'Все отчёты (' + reports.length + ')';
      if (tabBtnIssues) tabBtnIssues.textContent = 'Частые проблемы (' + issues.length + ')';

      // Re-render Reports Table Body
      if (reportsTableBody) {
        if (reports.length === 0) {
          reportsTableBody.innerHTML = '<tr id="reportsEmptyRow"><td colspan="8" style="text-align: center; color: var(--text-muted); padding: 32px;">Отчётов пока нет.</td></tr>';
        } else {
          let rowsHtml = '';
          reports.forEach(r => {
            const isTizen = (r.platform || '').includes('Tizen');
            const platTag = isTizen ? 'tag-tizen' : 'tag-android';
            const normStatus = normalizeStatus(r.status);
            const statusLabel = STATUS_LABELS[normStatus] || normStatus;
            const statTag = 'tag-status-' + normStatus.toLowerCase();
            const errTag = r.error_category
              ? '<span class="tag tag-error">' + escapeHtml(r.error_category) + '</span>'
              : '<span style="color: var(--text-muted);">-</span>';
            const testBadge = r.report_purpose === 'TEST'
              ? '<span class="tag tag-purpose-test">TEST</span>'
              : '';

            rowsHtml += '<tr data-report-id="' + escapeHtml(r.report_id) + '" ' +
              'data-created-at="' + Number(r.created_at) + '" ' +
              'data-platform="' + escapeHtml(r.platform || '') + '" ' +
              'data-version="' + escapeHtml(r.app_version || '') + '" ' +
              'data-device="' + escapeHtml(r.device_family || '') + '" ' +
              'data-category="' + escapeHtml(r.error_category || '') + '" ' +
              'data-status="' + escapeHtml(normStatus) + '" ' +
              'data-purpose="' + escapeHtml(r.report_purpose || 'USER') + '" ' +
              'data-signature="' + escapeHtml(r.error_signature || '') + '">' +
              '<td><span class="report-id" data-action="open-report" data-report-id="' + escapeHtml(r.report_id) + '">' + escapeHtml(r.report_id) + '</span> ' + testBadge +
              (Number(r.related_count || 0) > 0 ? '<button class="btn" data-action="filter-signature" data-signature="' + escapeHtml(r.error_signature || '') + '" title="Показать похожие отчёты">Похожие: ' + Number(r.related_count) + '</button>' : '') +
              (Number(r.related_count || 0) >= 2 ? '<span class="tag tag-status-in_progress">Повторяется</span>' : '') + '</td>' +
              '<td>' + formatDateTime(r.created_at) + '</td>' +
              '<td><span class="tag ' + platTag + '">' + escapeHtml(r.platform || '-') + '</span></td>' +
              '<td>' + escapeHtml(r.app_version || '-') + '</td>' +
              '<td>' + escapeHtml(r.device_family || '-') + '</td>' +
              '<td>' + errTag + '</td>' +
              '<td>' + statusSelectHtml(normStatus, r.report_id) + '</td>' +
              '<td style="white-space: nowrap;">' +
              '<button class="btn btn-open-report" data-action="open-report" data-report-id="' + escapeHtml(r.report_id) + '" style="margin-right: 6px;">Открыть</button>' +
              '<button class="btn" data-action="quick-note" data-report-id="' + escapeHtml(r.report_id) + '">Заметка</button>' +
              '<button class="btn btn-download-report" data-action="download-report" data-report-id="' + escapeHtml(r.report_id) + '" title="Скачать JSON">Скачать</button>' +
              (r.developer_notes ? '<span class="note-preview" title="' + escapeHtml(r.developer_notes) + '">✎ ' + escapeHtml(r.developer_notes) + '</span>' : '') +
              '</td>' +
              '</tr>';
          });
          reportsTableBody.innerHTML = rowsHtml;
        }
      }

      // Re-render Issues Table Body (9 columns)
      if (issuesTableBody) {
        if (issues.length === 0) {
          issuesTableBody.innerHTML = '<tr><td colspan="10" style="text-align: center; color: var(--text-muted); padding: 32px;">Сгруппированных проблем пока нет.</td></tr>';
        } else {
          let issuesHtml = '';
          issues.forEach(i => {
            issuesHtml += '<tr data-issue-signature="' + escapeHtml(i.error_signature || '') + '">' +
              '<td style="font-family: monospace; font-size: 12px; color: var(--accent); font-weight: 600;"><input class="search-input issue-title" aria-label="Название проблемы" placeholder="Название проблемы" value="' + escapeHtml(i.title || '') + '" style="width:100%;min-width:0"><small style="display:block;color:var(--text-muted)">' + escapeHtml(i.error_signature || '') + '</small><span class="tag ' + (i.severity === 'CRITICAL' || i.severity === 'HIGH' ? 'tag-error' : 'tag-status-test') + '">' + escapeHtml(i.severity || 'INFO') + '</span>' + (i.reopened_at ? '<span class="tag tag-status-in_progress">Повторно открыто</span>' : '') + '<small style="display:block">Версии: ' + escapeHtml((i.affected_versions || []).join(', ')) + '</small><small style="display:block">Первое появление: ' + formatDateTime(i.first_seen) + '</small></td>' +
              '<td><span class="tag tag-error">' + escapeHtml(i.error_category || 'ERROR') + '</span></td>' +
              '<td><strong style="color: var(--text-bright); font-size: 14px;">' + (i.count || 1) + '</strong></td>' +
              '<td><span class="tag tag-status-new">' + (i.new_count || 0) + '</span></td>' +
              '<td><span class="tag tag-status-in_progress">' + (i.in_progress_count || 0) + '</span></td>' +
              '<td><span class="tag tag-status-resolved">' + (i.resolved_count || 0) + '</span></td>' +
              '<td><span class="tag tag-status-known_issue">' + (i.known_issue_count || 0) + '</span></td>' +
              '<td>' + formatDateTime(i.last_seen) + '</td>' +
              '<td style="white-space: nowrap;">' +
              '<button class="btn btn-primary btn-open-sample" data-action="open-report" data-report-id="' + escapeHtml(i.sample_report_id || '') + '" style="margin-right: 6px;">Открыть образец</button>' +
              '<button class="btn" data-action="filter-signature" data-signature="' + escapeHtml(i.error_signature || '') + '">В отчёты</button>' +
              '</td>' +
              '<td><select class="select-filter issue-status" aria-label="Статус проблемы">' + CANONICAL_STATUSES.map(key => '<option value="' + key + '"' + (key === i.issue_status ? ' selected' : '') + '>' + escapeHtml(STATUS_LABELS[key]) + '</option>').join('') + '</select>' +
              '<input class="search-input issue-version" aria-label="Версия исправления" placeholder="Версия" value="' + escapeHtml(i.fixed_in_version || '') + '" style="min-width:100px;width:110px">' +
              '<input class="search-input issue-patch" aria-label="Патч" placeholder="Patch" value="' + escapeHtml(i.fix_patch || '') + '" style="min-width:85px;width:90px">' +
              '<input class="search-input issue-commit" aria-label="Коммит" placeholder="Commit" value="' + escapeHtml(i.fix_commit || '') + '" style="min-width:95px;width:100px">' +
              '<button class="btn" data-action="save-issue">Сохранить</button></td>' +
              '</tr>';
          });
          issuesTableBody.innerHTML = issuesHtml;
        }
      }

      // Re-render Stats Bodies
      const statsStatusBody = document.getElementById('statsStatusBody');
      if (statsStatusBody && stats.statusBreakdown) {
        statsStatusBody.innerHTML = stats.statusBreakdown.map(s =>
          '<tr><td><span class="tag tag-status-' + (s.status || '').toLowerCase() + '">' + escapeHtml(s.label || s.status) + '</span></td><td><strong>' + s.count + '</strong></td></tr>'
        ).join('') || '<tr><td colspan="2">Нет данных</td></tr>';
      }

      const statsPlatformBody = document.getElementById('statsPlatformBody');
      if (statsPlatformBody && stats.platformBreakdown) {
        statsPlatformBody.innerHTML = stats.platformBreakdown.map(p =>
          '<tr><td>' + escapeHtml(p.platform) + '</td><td><strong>' + p.count + '</strong></td></tr>'
        ).join('') || '<tr><td colspan="2">Нет данных</td></tr>';
      }

      const statsVersionBody = document.getElementById('statsVersionBody');
      if (statsVersionBody && stats.versionDistribution) {
        statsVersionBody.innerHTML = stats.versionDistribution.map(v =>
          '<tr><td>' + escapeHtml(v.app_version) + '</td><td><strong>' + v.count + '</strong></td></tr>'
        ).join('') || '<tr><td colspan="2">Нет данных</td></tr>';
      }

      const statsCategoryBody = document.getElementById('statsCategoryBody');
      if (statsCategoryBody && stats.topCategories) {
        statsCategoryBody.innerHTML = stats.topCategories.map(c =>
          '<tr><td><span class="tag tag-error">' + escapeHtml(c.error_category) + '</span></td><td><strong>' + c.count + '</strong></td></tr>'
        ).join('') || '<tr><td colspan="2">Нет ошибок</td></tr>';
      }

      const statsIssuesBody = document.getElementById('statsIssuesBody');
      if (statsIssuesBody && stats.topIssues) {
        statsIssuesBody.innerHTML = stats.topIssues.map(i =>
          '<tr><td style="font-family: monospace; font-size: 11px;">' + escapeHtml(i.error_signature) + '</td><td><strong>' + i.count + '</strong></td></tr>'
        ).join('') || '<tr><td colspan="2">Нет данных</td></tr>';
      }

      document.getElementById('refreshState').textContent = 'Обновлено';
    } catch (err) {
      showToast('Не удалось загрузить отчёты', true);
      document.getElementById('refreshState').textContent = 'Ошибка обновления';
    } finally {
      isRefreshing = false;
      if (refreshState) refreshState.classList.remove('spinning');
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

    let searchTimer;
    const reloadFiltered = () => { pageCursor = null; pageHistory = []; refreshDashboard(); };
    searchInput?.addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(reloadFiltered, 350); });
    ['filterPlatform', 'filterStatus', 'filterPurpose', 'filterCategory', 'filterSeverity', 'filterSpecial', 'sortReports'].forEach(id =>
      document.getElementById(id)?.addEventListener('change', reloadFiltered));
    ['filterVersion', 'filterDevice', 'filterSignature'].forEach(id => document.getElementById(id)?.addEventListener('input', () => {
      clearTimeout(searchTimer); searchTimer = setTimeout(reloadFiltered, 350);
    }));
    document.getElementById('pageNext')?.addEventListener('click', () => {
      if (!nextCursor) return;
      pageHistory.push(pageCursor);
      pageCursor = nextCursor;
      refreshDashboard();
    });
    document.getElementById('pageBack')?.addEventListener('click', () => {
      if (!pageHistory.length) return;
      pageCursor = pageHistory.pop();
      refreshDashboard();
    });
    document.querySelectorAll('[data-metric]').forEach(card => card.addEventListener('click', () => {
      const metric = card.dataset.metric;
      switchMainTab('reports');
      filterStatus.value = CANONICAL_STATUSES.includes(metric) ? metric : '';
      document.getElementById('filterSpecial').value = '';
      metricSince = metric === '24h' ? Date.now() - 86400000 : metric === '7d' ? Date.now() - 7 * 86400000 : null;
      reloadFiltered();
    }));
    document.getElementById('noteCancel')?.addEventListener('click', closeQuickNote);
    document.getElementById('noteSave')?.addEventListener('click', saveQuickNote);
    document.getElementById('noteDialog')?.addEventListener('click', event => {
      if (event.target.id === 'noteDialog') closeQuickNote();
    });
    document.getElementById('quickNoteText')?.addEventListener('keydown', event => {
      if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) saveQuickNote();
    });

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

    // 5. Purge All Button & Purge Modal Actions
    if (purgeAllBtn) {
      purgeAllBtn.addEventListener('click', (e) => {
        e.preventDefault();
        openPurgeModal();
      });
    }

    if (purgeModalOverlay) {
      purgeModalOverlay.addEventListener('click', (e) => {
        if (e.target === purgeModalOverlay) {
          closePurgeModal();
        }
      });
    }

    if (purgeModalCloseBtn) {
      purgeModalCloseBtn.addEventListener('click', (e) => {
        e.preventDefault();
        closePurgeModal();
      });
    }

    if (purgeCancelBtn) {
      purgeCancelBtn.addEventListener('click', (e) => {
        e.preventDefault();
        closePurgeModal();
      });
    }

    if (purgeConfirmInput) {
      purgeConfirmInput.addEventListener('input', () => {
        const val = purgeConfirmInput.value.trim();
        if (purgeConfirmBtn) {
          purgeConfirmBtn.disabled = (val !== 'УДАЛИТЬ');
        }
      });
    }

    if (purgeConfirmBtn) {
      purgeConfirmBtn.addEventListener('click', (e) => {
        e.preventDefault();
        executePurgeAllReports();
      });
    }

    // 6. Event Delegation for Reports Table
    if (reportsTableBody) {
      reportsTableBody.addEventListener('change', event => {
        if (event.target.matches('.status-select')) updateInlineStatus(event.target);
      });
      reportsTableBody.addEventListener('click', (e) => {
        const target = e.target;
        if (!target) return;

        const noteEl = target.closest('[data-action="quick-note"]');
        if (noteEl) { e.preventDefault(); openQuickNote(noteEl.dataset.reportId); return; }
        const similarEl = target.closest('[data-action="filter-signature"]');
        if (similarEl) { e.preventDefault(); document.getElementById('filterSignature').value = similarEl.dataset.signature || ''; reloadFiltered(); return; }

        // Open Report
        const openEl = target.closest('[data-action="open-report"]') || target.closest('.report-id') || target.closest('.btn-open-report');
        if (openEl) {
          e.preventDefault();
          const reportId = openEl.getAttribute('data-report-id') ||
                           openEl.closest('tr')?.getAttribute('data-report-id');
          if (reportId) {
            openReport(reportId);
          }
          return;
        }

        // Download Report
        const dlEl = target.closest('[data-action="download-report"]') || target.closest('.btn-download-report');
        if (dlEl) {
          e.preventDefault();
          const reportId = dlEl.getAttribute('data-report-id') ||
                           dlEl.closest('tr')?.getAttribute('data-report-id');
          if (reportId) {
            downloadReportJson(reportId);
          }
          return;
        }
      });
    }

    // 7. Event Delegation for Issues Table
    const viewIssues = document.getElementById('viewIssues');
    if (viewIssues) {
      viewIssues.addEventListener('click', (e) => {
        const target = e.target;
        if (!target) return;

        const saveIssue = target.closest('[data-action="save-issue"]');
        if (saveIssue) {
          const row = saveIssue.closest('tr[data-issue-signature]');
          if (!row) return;
          const body = {
            status: row.querySelector('.issue-status').value,
            fixedInVersion: row.querySelector('.issue-version').value.trim(),
            fixPatch: row.querySelector('.issue-patch').value.trim(),
            fixCommit: row.querySelector('.issue-commit').value.trim(),
            title: row.querySelector('.issue-title').value.trim(),
          };
          if (body.status === 'RESOLVED' && !body.fixedInVersion) showToast('Рекомендуется указать версию или заметку об исправлении.');
          saveIssue.disabled = true;
          fetch('/v1/admin/issues/' + encodeURIComponent(row.dataset.issueSignature), {
            method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
          }).then(res => { if (!res.ok) throw new Error('save failed'); showToast('Проблема сохранена'); })
            .catch(() => showToast('Не удалось сохранить проблему', true))
            .finally(() => { saveIssue.disabled = false; });
          return;
        }

        // Open sample report
        const openEl = target.closest('[data-action="open-report"]');
        if (openEl) {
          e.preventDefault();
          const reportId = openEl.getAttribute('data-report-id');
          if (reportId) {
            openReport(reportId);
          }
          return;
        }

        // Filter by signature in reports tab
        const filterSigEl = target.closest('[data-action="filter-signature"]');
        if (filterSigEl) {
          e.preventDefault();
          const sig = filterSigEl.getAttribute('data-signature') || '';
          switchMainTab('reports');
          document.getElementById('filterSignature').value = sig;
          reloadFiltered();
          return;
        }
      });
    }

    // 8. Modal Tabs [data-modal-tab]
    document.querySelectorAll('.modal-tab-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const tab = btn.getAttribute('data-modal-tab');
        if (tab) switchModalTab(tab);
      });
    });

    // 9. Modal Close Buttons and Backdrop Click
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

    // Keyboard ESC to close modal or purge modal
    document.addEventListener('keydown', (e) => {
      const activeDialog = document.getElementById('noteDialog')?.style.display === 'flex'
        ? document.querySelector('#noteDialog .modal')
        : modalOverlay?.style.display === 'flex' ? modalOverlay.querySelector('.modal')
        : purgeModalOverlay?.style.display === 'flex' ? purgeModalOverlay.querySelector('.modal') : null;
      if (e.key === 'Tab' && activeDialog) {
        const controls = [...activeDialog.querySelectorAll('button, input, select, textarea, [tabindex]')]
          .filter(el => !el.disabled && el.getClientRects().length > 0);
        if (controls.length) {
          const first = controls[0], last = controls.at(-1);
          if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
          else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
        }
      }
      if (e.key === 'Escape') {
        if (document.getElementById('noteDialog')?.style.display === 'flex') closeQuickNote();
        else if (purgeModalOverlay && purgeModalOverlay.style.display === 'flex') {
          closePurgeModal();
        } else if (modalOverlay && modalOverlay.style.display === 'flex') {
          closeModal();
        }
      }
    });

    // 10. Event Delegation for Top Detail Workflow Bar
    const detailWorkflowCard = document.getElementById('detailWorkflowCard');
    if (detailWorkflowCard) {
      detailWorkflowCard.addEventListener('click', (e) => {
        const target = e.target;
        if (!target) return;

        const actionBtn = target.closest('[data-action]');
        if (!actionBtn) return;

        const action = actionBtn.getAttribute('data-action');
        const reportId = actionBtn.getAttribute('data-report-id') || (currentReport?.report_id);

        if (action === 'save-detail-ops') {
          e.preventDefault();
          saveReportOperations(reportId);
        } else if (action === 'download-json') {
          e.preventDefault();
          downloadReportJson(reportId);
        } else if (action === 'copy-markdown') {
          e.preventDefault();
          copyGitHubMarkdown();
        } else if (action === 'delete-report') {
          e.preventDefault();
          deleteCurrentReport(reportId);
        }
      });
    }

    // 11. Event Delegation for Modal Content Actions (Save Ops, Delete, Export, Copy)
    if (modalContent) {
      modalContent.addEventListener('change', event => {
        if (event.target.id !== 'timelineFilter') return;
        const value = event.target.value;
        modalContent.querySelectorAll('[data-timeline-severity]').forEach(row => {
          row.hidden = Boolean(value) && (value === 'ERROR'
            ? row.dataset.timelineLevel !== 'ERROR' && !['HIGH', 'CRITICAL'].includes(row.dataset.timelineSeverity)
            : row.dataset.timelineSubsystem !== value);
        });
      });
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
          downloadReportJson(reportId);
        } else if (action === 'copy-raw-json') {
          e.preventDefault();
          copyRawJson();
        } else if (action === 'copy-summary') {
          e.preventDefault();
          copyStructuredReport(false);
        } else if (action === 'copy-analysis') {
          e.preventDefault();
          copyStructuredReport(true);
        } else if (action === 'close-modal') {
          e.preventDefault();
          closeModal();
        }
      });
    }

    // Initial table filter check
    filterReportsTable();
    const firstPageRows = reportsTableBody.querySelectorAll('tr[data-report-id]');
    nextCursor = firstPageRows.length === 50 ? firstPageRows[49].dataset.createdAt + ':' + firstPageRows[49].dataset.reportId : null;
    document.getElementById('pageNext').disabled = !nextCursor;
    setInterval(() => {
      if (document.visibilityState === 'visible' && !isRefreshing && !selectedNoteId && modalOverlay.style.display !== 'flex') refreshDashboard();
    }, 45000);
  }

  // Self-register when DOM is ready or immediately if already loaded
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initAdminApp);
  } else {
    initAdminApp();
  }
})();
