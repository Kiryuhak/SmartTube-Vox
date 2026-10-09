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
  let searchDebounceTimer = null;

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
      if (currentReport?.report_id === id || currentReport?.reportId === id) currentReport.status = next;
      showToast('Статус сохранён');
      if (next === 'RESOLVED' && !row.querySelector('.note-preview')) showToast('Рекомендуется указать версию или заметку об исправлении.');
      filterReportsTable();
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
  let searchClearBtn = null;
  let filterPlatform = null;
  let filterStatus = null;
  let filterPurpose = null;
  let filterCategory = null;
  let filterSeverity = null;
  let filterVersion = null;
  let filterDevice = null;
  let filterSignature = null;
  let filterSpecial = null;
  let sortReports = null;
  let toggleMoreFiltersBtn = null;
  let activeFiltersBadge = null;
  let resetFiltersBtn = null;
  let toolbarExtra = null;
  let headerMoreBtn = null;
  let headerMorePopup = null;
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
   * Initialize DOM element references.
   */
  function cacheDomElements() {
    searchInput = document.getElementById('searchInput');
    searchClearBtn = document.getElementById('searchClearBtn');
    filterPlatform = document.getElementById('filterPlatform');
    filterStatus = document.getElementById('filterStatus');
    filterPurpose = document.getElementById('filterPurpose');
    filterCategory = document.getElementById('filterCategory');
    filterSeverity = document.getElementById('filterSeverity');
    filterVersion = document.getElementById('filterVersion');
    filterDevice = document.getElementById('filterDevice');
    filterSignature = document.getElementById('filterSignature');
    filterSpecial = document.getElementById('filterSpecial');
    sortReports = document.getElementById('sortReports');
    toggleMoreFiltersBtn = document.getElementById('toggleMoreFiltersBtn');
    activeFiltersBadge = document.getElementById('activeFiltersBadge');
    resetFiltersBtn = document.getElementById('resetFiltersBtn');
    toolbarExtra = document.getElementById('toolbarExtra');
    headerMoreBtn = document.getElementById('headerMoreBtn');
    headerMorePopup = document.getElementById('headerMorePopup');
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

    const viewReports = document.getElementById('viewReports');
    const viewIssues = document.getElementById('viewIssues');
    const viewStats = document.getElementById('viewStats');

    if (viewReports) viewReports.style.display = (normalizedTab === 'reports') ? 'block' : 'none';
    if (viewIssues) viewIssues.style.display = (normalizedTab === 'issues') ? 'block' : 'none';
    if (viewStats) viewStats.style.display = (normalizedTab === 'stats') ? 'block' : 'none';

    document.querySelectorAll('.nav-tab').forEach(tab => {
      const t = tab.getAttribute('data-tab');
      const isMatch = (t === normalizedTab) || (normalizedTab === 'stats' && (t === 'stats' || t === 'metrics'));
      tab.classList.toggle('active', isMatch);
    });

    if (normalizedTab === 'issues') {
      loadIssues();
    } else if (normalizedTab === 'stats') {
      loadStats();
    }
  }

  /**
   * Switch the active sub-tab inside the report detail modal.
   */
  function switchModalTab(tabName) {
    activeModalTab = tabName;
    document.querySelectorAll('.modal-tab-btn').forEach(btn => {
      btn.classList.toggle('active', btn.getAttribute('data-modal-tab') === tabName);
    });
    if (currentReport) {
      renderReportDetailContent(currentReport);
    }
  }

  /**
   * Apply client-side filters on the currently loaded reports table rows.
   */
  function filterReportsTable() {
    if (!reportsTableBody) return;

    const query = (searchInput?.value || '').trim().toLowerCase();
    const plat = (filterPlatform?.value || '').toLowerCase();
    const stat = (filterStatus?.value || '').toUpperCase();
    const purp = (filterPurpose?.value || '').toUpperCase();
    const cat = (filterCategory?.value || '').toUpperCase();
    const sev = (filterSeverity?.value || '').toUpperCase();
    const ver = (filterVersion?.value || '').trim().toLowerCase();
    const dev = (filterDevice?.value || '').trim().toLowerCase();
    const sig = (filterSignature?.value || '').trim().toLowerCase();
    const spec = (filterSpecial?.value || '');

    if (searchClearBtn) {
      searchClearBtn.style.display = query ? 'block' : 'none';
    }

    let extraCount = 0;
    if (sev) extraCount++;
    if (purp) extraCount++;
    if (ver) extraCount++;
    if (dev) extraCount++;
    if (sig) extraCount++;
    if (spec) extraCount++;

    if (activeFiltersBadge) {
      activeFiltersBadge.textContent = String(extraCount);
      activeFiltersBadge.style.display = extraCount > 0 ? 'inline-block' : 'none';
    }

    const hasAnyFilter = Boolean(query || plat || stat || cat || sev || purp || ver || dev || sig || spec || metricSince);
    if (resetFiltersBtn) {
      resetFiltersBtn.style.display = hasAnyFilter ? 'inline-flex' : 'none';
    }

    const rows = reportsTableBody.querySelectorAll('tr[data-report-id]');
    let visibleCount = 0;

    rows.forEach(row => {
      const id = (row.dataset.reportId || '').toLowerCase();
      const rPlat = (row.dataset.platform || '').toLowerCase();
      const rStat = normalizeStatus(row.dataset.status);
      const rPurp = (row.dataset.purpose || 'USER').toUpperCase();
      const rCat = (row.dataset.category || '').toUpperCase();
      const rVer = (row.dataset.version || '').toLowerCase();
      const rDev = (row.dataset.device || '').toLowerCase();
      const rSig = (row.dataset.signature || '').toLowerCase();
      const rCreated = Number(row.dataset.createdAt) || 0;
      const rNotes = row.querySelector('.note-preview')?.textContent || '';

      let match = true;

      if (metricSince && rCreated < metricSince) match = false;
      if (query && !id.includes(query) && !rDev.includes(query) && !rVer.includes(query) && !rSig.includes(query) && !rNotes.toLowerCase().includes(query)) match = false;
      if (plat && !rPlat.includes(plat)) match = false;
      if (stat && rStat !== stat) match = false;
      if (purp && rPurp !== purp) match = false;
      if (cat && rCat !== cat) match = false;
      if (ver && !rVer.includes(ver)) match = false;
      if (dev && !rDev.includes(dev)) match = false;
      if (sig && !rSig.includes(sig)) match = false;

      if (spec === 'attention') {
        const isRepeated = row.innerHTML.includes('Повторы') || row.innerHTML.includes('Повторяется') || row.innerHTML.includes('Похожие');
        const isCritical = row.dataset.severity === 'CRITICAL' || row.dataset.severity === 'HIGH';
        if (!isRepeated && !isCritical) match = false;
      } else if (spec === 'notes' && !rNotes) {
        match = false;
      } else if (spec === 'duplicates' && !row.innerHTML.includes('Повторы') && !row.innerHTML.includes('Похожие')) {
        match = false;
      }

      row.style.display = match ? '' : 'none';
      if (match) visibleCount++;
    });

    let emptyRow = document.getElementById('reportsEmptyRow');
    if (visibleCount === 0) {
      if (!emptyRow) {
        emptyRow = document.createElement('tr');
        emptyRow.id = 'reportsEmptyRow';
        emptyRow.innerHTML = '<td colspan="8" style="text-align:center;color:var(--text-muted);padding:32px;">Отчётов по выбранным фильтрам не найдено.</td>';
        reportsTableBody.appendChild(emptyRow);
      }
      emptyRow.style.display = '';
    } else if (emptyRow) {
      emptyRow.style.display = 'none';
    }

    const tabBtn = document.getElementById('tabBtnReports');
    if (tabBtn) tabBtn.textContent = 'Все отчёты (' + visibleCount + ')';
  }

  function resetAllFilters() {
    if (searchInput) searchInput.value = '';
    if (filterPlatform) filterPlatform.value = '';
    if (filterStatus) filterStatus.value = '';
    if (filterPurpose) filterPurpose.value = '';
    if (filterCategory) filterCategory.value = '';
    if (filterSeverity) filterSeverity.value = '';
    if (filterVersion) filterVersion.value = '';
    if (filterDevice) filterDevice.value = '';
    if (filterSignature) filterSignature.value = '';
    if (filterSpecial) filterSpecial.value = '';
    metricSince = null;
    filterReportsTable();
  }

  /**
   * Fetch latest report list and stats from backend API.
   */
  async function refreshDashboard(cursor = null) {
    if (isRefreshing) return;
    isRefreshing = true;

    const refreshState = document.getElementById('refreshState');
    if (refreshState) {
      refreshState.textContent = ' ↻';
      refreshState.className = 'spinning';
    }

    try {
      let url = '/v1/admin/reports?limit=50';
      if (cursor) url += '&cursor=' + encodeURIComponent(cursor);

      const [reportsRes, statsRes] = await Promise.all([
        fetch(url),
        fetch('/v1/admin/stats')
      ]);

      if (reportsRes.ok) {
        const data = await reportsRes.json();
        renderReportsList(data.reports || []);
        nextCursor = data.nextCursor || null;
        updatePaginationControls();
      }

      if (statsRes.ok) {
        const stats = await statsRes.json();
        updateKpiValues(stats);
      }
    } catch (err) {
      console.error('[AdminApp] Refresh failed:', err);
      showToast('Не удалось обновить данные', true);
    } finally {
      isRefreshing = false;
      if (refreshState) {
        refreshState.textContent = '';
        refreshState.className = '';
      }
    }
  }

  function updatePaginationControls() {
    const backBtn = document.getElementById('pageBack');
    const nextBtn = document.getElementById('pageNext');
    const pageLabel = document.getElementById('pageLabel');
    if (backBtn) backBtn.disabled = pageHistory.length === 0;
    if (nextBtn) nextBtn.disabled = !nextCursor;
    if (pageLabel) pageLabel.textContent = 'Страница ' + (pageHistory.length + 1);
  }

  function renderReportsList(reports) {
    if (!reportsTableBody) return;
    if (!reports || reports.length === 0) {
      reportsTableBody.innerHTML = '<tr id="reportsEmptyRow"><td colspan="8" style="text-align:center;color:var(--text-muted);padding:32px;">Отчётов пока нет.</td></tr>';
      return;
    }

    reportsTableBody.innerHTML = reports.map(r => {
      const normStatus = normalizeStatus(r.status);
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
            ${Number(r.related_count || 0) > 0 ? `<button class="btn btn-sm" data-action="filter-signature" data-signature="${escapeHtml(r.error_signature || '')}">Похожие: ${Number(r.related_count)}</button>` : ''}
            ${Number(r.related_count || 0) >= 2 ? '<span class="tag tag-status-in_progress">Повторы</span>' : ''}
          </td>
          <td>${formatDateTime(r.created_at)}</td>
          <td><span class="tag ${(r.platform || '').includes('Tizen') ? 'tag-tizen' : 'tag-android'}">${escapeHtml(r.platform || '-')}</span></td>
          <td>${escapeHtml(r.app_version || '-')}</td>
          <td>${escapeHtml(r.device_family || '-')}</td>
          <td>
            ${r.error_category ? `<span class="tag tag-error">${escapeHtml(r.error_category)}</span>` : '<span style="color:var(--text-muted);">-</span>'}
            ${r.error_signature ? `<small style="display:block;color:var(--text-dim);font-family:monospace;font-size:11px;">${escapeHtml(r.error_signature)}</small>` : ''}
          </td>
          <td>${statusSelectHtml(normStatus, r.report_id)}</td>
          <td style="white-space:nowrap;">
            <button class="btn btn-primary btn-open-report" data-action="open-report" data-report-id="${escapeHtml(r.report_id)}" style="margin-right:4px;">Открыть</button>
            <button class="btn" data-action="quick-note" data-report-id="${escapeHtml(r.report_id)}">Заметка</button>
            <button class="btn btn-download-report" data-action="download-report" data-report-id="${escapeHtml(r.report_id)}" title="Скачать JSON">Скачать</button>
            ${r.developer_notes ? `<span class="note-preview" title="${escapeHtml(r.developer_notes)}">✎ ${escapeHtml(r.developer_notes)}</span>` : ''}
          </td>
        </tr>`;
    }).join('');

    filterReportsTable();
  }

  function updateKpiValues(stats) {
    if (!stats) return;
    if (kpi24h) kpi24h.textContent = stats.reports24h ?? 0;
    if (kpi7d) kpi7d.textContent = stats.reports7d ?? 0;
    if (kpiNew) kpiNew.textContent = stats.newCount ?? 0;
    if (kpiInProgress) kpiInProgress.textContent = stats.inProgressCount ?? 0;
    if (kpiResolved) kpiResolved.textContent = stats.resolvedCount ?? 0;
    if (kpiKnownIssue) kpiKnownIssue.textContent = stats.knownIssueCount ?? 0;
    if (kpiIgnoredTest) kpiIgnoredTest.textContent = stats.ignoredTestCount ?? 0;
    if (kpiTotal) kpiTotal.textContent = stats.totalReports ?? 0;
    const kpiAtt = document.getElementById('kpiAttention');
    if (kpiAtt) kpiAtt.textContent = stats.attentionCount ?? 0;
  }

  /**
   * Fetch and open the slide-over detail drawer for a report.
   */
  async function openReport(reportId) {
    if (!reportId) return;
    lastFocus = document.activeElement;

    if (modalTitle) modalTitle.textContent = 'Отчёт ' + reportId;
    if (modalPurposeBadge) modalPurposeBadge.innerHTML = '';
    if (modalContent) modalContent.innerHTML = '<p style="color:var(--text-muted);padding:32px;text-align:center;">Загрузка данных отчёта...</p>';
    if (modalOverlay) modalOverlay.style.display = 'flex';

    try {
      const res = await fetch('/v1/admin/reports/' + encodeURIComponent(reportId));
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      currentReport = data.report || data;
      renderReportDetail(currentReport);
    } catch (err) {
      console.error('[AdminApp] Load report failed:', err);
      if (modalContent) {
        modalContent.innerHTML = '<div style="color:var(--danger);padding:24px;text-align:center;">Не удалось загрузить данные отчёта.<br><button class="btn btn-primary" style="margin-top:12px;" onclick="window.location.reload()">Повторить</button></div>';
      }
    }
  }

  function closeReportModal() {
    if (modalOverlay) modalOverlay.style.display = 'none';
    currentReport = null;
    if (lastFocus) lastFocus.focus();
  }

  function renderReportDetail(report) {
    if (!report) return;

    if (modalTitle) modalTitle.textContent = 'Отчёт ' + (report.report_id || report.reportId);
    if (modalPurposeBadge) {
      const purp = report.report_purpose || report.reportPurpose || 'USER';
      modalPurposeBadge.innerHTML = purp === 'TEST' ? '<span class="tag tag-purpose-test">TEST</span>' : '';
    }

    if (detailStatusSelect) {
      detailStatusSelect.value = normalizeStatus(report.status);
    }
    if (detailNotesText) {
      detailNotesText.value = report.developer_notes || report.developerNotes || '';
    }

    renderReportDetailContent(report);
  }

  function renderReportDetailContent(report) {
    if (!modalContent || !report) return;
    const p = report.payload || {};

    if (activeModalTab === 'overview') {
      const codecs = p.videoCodecs || {};
      const safeEvents = p.safeRecentEvents || [];
      const errorEvents = safeEvents.filter(e => e.level === 'ERROR');

      modalContent.innerHTML = `
        <div class="section-card">
          <div class="section-title">Сводка об инциденте</div>
          <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;font-size:13px;">
            <div><span style="color:var(--text-muted);">Код отчёта:</span> <strong style="font-family:monospace;color:var(--accent);">${escapeHtml(report.report_id || report.reportId)}</strong></div>
            <div><span style="color:var(--text-muted);">Время:</span> <strong>${formatDateTime(report.created_at || report.timestamp)}</strong></div>
            <div><span style="color:var(--text-muted);">Платформа:</span> <strong>${escapeHtml(report.platform || p.platform || '-')}</strong></div>
            <div><span style="color:var(--text-muted);">Версия приложения:</span> <strong>${escapeHtml(report.app_version || p.appVersion || '-')}</strong></div>
            <div><span style="color:var(--text-muted);">Устройство:</span> <strong>${escapeHtml(p.manufacturer || '')} ${escapeHtml(p.model || report.device_family || '')}</strong></div>
            <div><span style="color:var(--text-muted);">ОС / SDK:</span> <strong>${escapeHtml(p.osName || 'Android')} ${escapeHtml(p.osVersion || '')} (API ${escapeHtml(p.sdkInt || '-')})</strong></div>
            <div><span style="color:var(--text-muted);">Класс устройства:</span> <strong>${escapeHtml(p.deviceTier || '-')}</strong></div>
            <div><span style="color:var(--text-muted);">Экран:</span> <strong>${escapeHtml(p.display?.resolution || '-')} @ ${escapeHtml(p.display?.refreshRateHz || '-')}Hz</strong></div>
          </div>
        </div>

        <div class="section-card" style="border-left:3px solid var(--danger);">
          <div class="section-title" style="color:var(--danger);">Диагностированный сбой</div>
          <div style="font-size:13px;line-height:1.6;">
            <div><span style="color:var(--text-muted);">Категория:</span> <span class="tag tag-error">${escapeHtml(report.error_category || report.errorCategory || 'UNKNOWN')}</span></div>
            <div style="margin-top:4px;"><span style="color:var(--text-muted);">Сигнатура:</span> <code style="font-family:monospace;color:#93c5fd;">${escapeHtml(report.error_signature || report.errorSignature || '-')}</code></div>
            ${errorEvents.length > 0 ? `<div style="margin-top:6px;color:#fca5a5;">Последнее сообщение: <strong>${escapeHtml(errorEvents[errorEvents.length - 1].message || '')}</strong></div>` : ''}
          </div>
        </div>

        ${p.downloadState ? `
        <div class="section-card">
          <div class="section-title">Состояние загрузок (Downloads)</div>
          <div style="font-size:13px;display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px;">
            <div>Активные задачи: <strong>${escapeHtml(p.downloadState.activeTasks ?? 0)}</strong></div>
            <div>Движок: <strong>${escapeHtml(p.downloadState.engine || 'MKV_MUXER')}</strong></div>
            <div>Свободно памяти: <strong>${escapeHtml(p.downloadState.storageSpaceAvailableMb ?? '-')} МБ</strong></div>
          </div>
        </div>` : ''}
      `;
    } else if (activeModalTab === 'codecs') {
      const vCodecs = p.videoCodecs || {};
      const aCodecs = p.audioCodecs || {};

      modalContent.innerHTML = `
        <div class="section-card">
          <div class="section-title">Видеодекодеры устройства</div>
          <table>
            <thead><tr><th>Кодек</th><th>Поддержка</th></tr></thead>
            <tbody>
              ${Object.entries(vCodecs).map(([k, v]) => `<tr><td><strong>${escapeHtml(k)}</strong></td><td><span class="tag ${String(v).includes('HW') ? 'tag-android' : 'tag-status-test'}">${escapeHtml(v)}</span></td></tr>`).join('') || '<tr><td colspan="2">Информация отсутствует</td></tr>'}
            </tbody>
          </table>
        </div>
        <div class="section-card">
          <div class="section-title">Аудиодекодеры</div>
          <table>
            <thead><tr><th>Формат</th><th>Статус</th></tr></thead>
            <tbody>
              ${Object.entries(aCodecs).map(([k, v]) => `<tr><td><strong>${escapeHtml(k)}</strong></td><td>${escapeHtml(v)}</td></tr>`).join('') || '<tr><td colspan="2">Информация отсутствует</td></tr>'}
            </tbody>
          </table>
        </div>
      `;
    } else if (activeModalTab === 'timeline') {
      const events = p.safeRecentEvents || [];
      modalContent.innerHTML = `
        <div class="section-card">
          <div class="section-title">Хронология событий (Safe Timeline)</div>
          ${events.length === 0 ? '<p style="color:var(--text-muted);">Событий не зафиксировано.</p>' : `
          <div style="margin-top:10px;">
            ${events.map(e => {
              const lvl = (e.level || 'INFO').toLowerCase();
              return `
              <div class="timeline-item ${lvl}">
                <div style="display:flex;justify-content:space-between;align-items:center;font-size:11px;color:var(--text-muted);margin-bottom:2px;">
                  <span>${formatDateTime(e.timestamp)}</span>
                  <span class="tag ${lvl === 'error' ? 'tag-error' : (lvl === 'warn' ? 'tag-status-in_progress' : 'tag-status-new')}">${escapeHtml(e.level || 'INFO')}</span>
                </div>
                <div style="font-size:13px;color:var(--text-bright);"><strong>${escapeHtml(e.category || '')}</strong>: ${escapeHtml(e.code || '')}</div>
                ${e.message ? `<div style="font-size:12px;color:var(--text-muted);margin-top:2px;">${escapeHtml(e.message)}</div>` : ''}
              </div>`;
            }).join('')}
          </div>`}
        </div>
      `;
    } else if (activeModalTab === 'ops') {
      const history = report.history || [];
      modalContent.innerHTML = `
        <div class="section-card">
          <div class="section-title">История обработки инцидента</div>
          ${history.length === 0 ? '<p style="color:var(--text-muted);">История изменений пуста.</p>' : `
          <table>
            <thead><tr><th>Время</th><th>Действие</th><th>Детали</th></tr></thead>
            <tbody>
              ${history.map(h => `<tr><td>${formatDateTime(h.timestamp)}</td><td><span class="tag tag-status-${(h.action || '').toLowerCase()}">${escapeHtml(h.action || '')}</span></td><td>${escapeHtml(h.details || '-')}</td></tr>`).join('')}
            </tbody>
          </table>`}
        </div>
      `;
    } else if (activeModalTab === 'raw') {
      const sanitized = JSON.stringify(report, null, 2);
      modalContent.innerHTML = `
        <div class="section-card">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;">
            <div class="section-title" style="margin-bottom:0;">Санитизированный JSON</div>
            <button class="btn btn-sm" data-action="copy-raw-json">Скопировать JSON</button>
          </div>
          <pre style="max-height:500px;">${escapeHtml(sanitized)}</pre>
        </div>
      `;
    }
  }

  async function saveReportOperations() {
    if (!currentReport) return;
    const id = currentReport.report_id || currentReport.reportId;
    const nextStatus = detailStatusSelect ? detailStatusSelect.value : currentReport.status;
    const nextNotes = detailNotesText ? detailNotesText.value.trim() : '';

    if (detailSaveBtn) detailSaveBtn.disabled = true;

    try {
      const [statusRes, noteRes] = await Promise.all([
        fetch('/v1/admin/reports/' + encodeURIComponent(id), {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ status: nextStatus })
        }),
        fetch('/v1/admin/reports/' + encodeURIComponent(id) + '/notes', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ note: nextNotes })
        })
      ]);

      if (!statusRes.ok || !noteRes.ok) throw new Error('Save failed');

      currentReport.status = nextStatus;
      currentReport.developer_notes = nextNotes;

      showToast('Отчёт успешно сохранён');

      const row = reportsTableBody?.querySelector('tr[data-report-id="' + id + '"]');
      if (row) {
        row.dataset.status = nextStatus;
        const select = row.querySelector('.status-select');
        if (select) { select.value = nextStatus; select.dataset.status = nextStatus; }
        let preview = row.querySelector('.note-preview');
        if (nextNotes) {
          if (!preview) { preview = document.createElement('span'); preview.className = 'note-preview'; row.lastElementChild.append(preview); }
          preview.textContent = '✎ ' + nextNotes;
          preview.title = nextNotes;
        } else if (preview) {
          preview.remove();
        }
      }

      filterReportsTable();
    } catch (err) {
      console.error('[AdminApp] Save detail failed:', err);
      showToast('Ошибка при сохранении отчёта', true);
    } finally {
      if (detailSaveBtn) detailSaveBtn.disabled = false;
    }
  }

  async function deleteCurrentReport() {
    if (!currentReport) return;
    const id = currentReport.report_id || currentReport.reportId;
    if (!confirm('Удалить отчёт ' + id + '?')) return;

    try {
      const res = await fetch('/v1/admin/reports/' + encodeURIComponent(id), { method: 'DELETE' });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      showToast('Отчёт удалён');
      closeReportModal();
      const row = reportsTableBody?.querySelector('tr[data-report-id="' + id + '"]');
      if (row) row.remove();
      filterReportsTable();
    } catch (err) {
      console.error('[AdminApp] Delete failed:', err);
      showToast('Не удалось удалить отчёт', true);
    }
  }

  function downloadJsonFile(reportId) {
    if (!reportId) return;
    const a = document.createElement('a');
    a.href = '/v1/admin/reports/' + encodeURIComponent(reportId) + '/download';
    a.download = reportId + '-sanitized.json';
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  function copyGitHubMarkdown(report) {
    if (!report) return;
    const p = report.payload || {};
    const text = [
      `### Диагностический отчёт ${report.report_id || report.reportId}`,
      `- **Время**: ${formatDateTime(report.created_at || report.timestamp)}`,
      `- **Платформа**: ${report.platform || p.platform || 'Android TV'}`,
      `- **Версия**: ${report.app_version || p.appVersion || '-'}`,
      `- **Устройство**: ${p.manufacturer || ''} ${p.model || report.device_family || ''}`,
      `- **Категория**: ${report.error_category || report.errorCategory || '-'}`,
      `- **Сигнатура**: \`${report.error_signature || report.errorSignature || '-'}\``,
      `- **Статус**: ${normalizeStatus(report.status)}`,
      report.developer_notes ? `- **Заметки**: ${report.developer_notes}` : '',
    ].filter(Boolean).join('\n');

    navigator.clipboard.writeText(text).then(() => {
      showToast('Markdown скопирован в буфер');
    }).catch(() => {
      showToast('Не удалось скопировать Markdown', true);
    });
  }

  function copyRawJson() {
    if (currentReport) {
      navigator.clipboard.writeText(JSON.stringify(currentReport, null, 2)).then(() => {
        showToast('JSON скопирован');
      });
    }
  }

  /**
   * Load and render grouped frequent issues.
   */
  async function loadIssues() {
    if (!issuesTableBody) return;
    try {
      const res = await fetch('/v1/admin/issues');
      if (!res.ok) return;
      const data = await res.json();
      const issues = Array.isArray(data) ? data : (data.issues || []);
      if (!issues || issues.length === 0) {
        issuesTableBody.innerHTML = '<tr><td colspan="10" style="text-align:center;color:var(--text-muted);padding:32px;">Сгруппированных проблем пока нет.</td></tr>';
        return;
      }
      issuesTableBody.innerHTML = issues.map(i => `
        <tr data-issue-signature="${escapeHtml(i.error_signature || '')}">
          <td style="font-family:monospace;font-size:12px;color:var(--accent);font-weight:600;">
            <input class="search-input issue-title" aria-label="Название проблемы" placeholder="Название проблемы" value="${escapeHtml(i.title || '')}" style="width:100%;min-width:0">
            <small style="display:block;color:var(--text-muted);margin-top:2px;">${escapeHtml(i.error_signature || '')}</small>
            <div style="margin-top:4px;display:flex;gap:6px;align-items:center;">
              <span class="tag ${i.severity === 'CRITICAL' || i.severity === 'HIGH' ? 'tag-error' : 'tag-status-test'}">${escapeHtml(i.severity || 'INFO')}</span>
              ${i.reopened_at ? '<span class="tag tag-status-in_progress">Повторно открыто</span>' : ''}
            </div>
            <small style="display:block;margin-top:4px;">Версии: ${escapeHtml((i.affected_versions || []).join(', '))}</small>
            <small style="display:block;color:var(--text-dim)">Первое: ${formatDateTime(i.first_seen)}</small>
          </td>
          <td><span class="tag tag-error">${escapeHtml(i.error_category || 'ERROR')}</span></td>
          <td><strong style="color:var(--text-bright);font-size:15px;">${i.count || 1}</strong></td>
          <td><span class="tag tag-status-new">${i.new_count || 0}</span></td>
          <td><span class="tag tag-status-in_progress">${i.in_progress_count || 0}</span></td>
          <td><span class="tag tag-status-resolved">${i.resolved_count || 0}</span></td>
          <td><span class="tag tag-status-known_issue">${i.known_issue_count || 0}</span></td>
          <td>${formatDateTime(i.last_seen)}</td>
          <td style="white-space:nowrap;">
            <button class="btn btn-primary btn-open-sample" data-action="open-report" data-report-id="${escapeHtml(i.sample_report_id || '')}" style="margin-right:6px;">Образец</button>
            <button class="btn" data-action="filter-signature" data-signature="${escapeHtml(i.error_signature || '')}">В отчёты</button>
          </td>
          <td>
            <select class="select-filter issue-status" aria-label="Статус проблемы">${Object.entries(STATUS_LABELS).map(([k,l]) => `<option value="${k}" ${k === i.issue_status ? 'selected' : ''}>${escapeHtml(l)}</option>`).join('')}</select>
            <input class="search-input issue-version" aria-label="Версия исправления" placeholder="Версия" value="${escapeHtml(i.fixed_in_version || '')}" style="min-width:90px;width:100px">
            <input class="search-input issue-patch" aria-label="Патч" placeholder="Patch" value="${escapeHtml(i.fix_patch || '')}" style="min-width:80px;width:85px">
            <input class="search-input issue-commit" aria-label="Коммит" placeholder="Commit" value="${escapeHtml(i.fix_commit || '')}" style="min-width:90px;width:95px">
            <button class="btn" data-action="save-issue">Сохранить</button>
          </td>
        </tr>
      `).join('');

      const tabBtn = document.getElementById('tabBtnIssues');
      if (tabBtn) tabBtn.textContent = 'Частые проблемы (' + issues.length + ')';
    } catch (err) {
      console.error('[AdminApp] Load issues failed:', err);
    }
  }

  async function saveIssueItem(row) {
    if (!row) return;
    const signature = row.dataset.issueSignature;
    const title = row.querySelector('.issue-title')?.value.trim() || '';
    const status = row.querySelector('.issue-status')?.value || 'NEW';
    const version = row.querySelector('.issue-version')?.value.trim() || '';
    const patch = row.querySelector('.issue-patch')?.value.trim() || '';
    const commit = row.querySelector('.issue-commit')?.value.trim() || '';

    const btn = row.querySelector('button[data-action="save-issue"]');
    if (btn) btn.disabled = true;

    try {
      const res = await fetch('/v1/admin/issues', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          error_signature: signature,
          title,
          status,
          fixed_in_version: version,
          fix_patch: patch,
          fix_commit: commit
        })
      });
      if (!res.ok) throw new Error('Save failed');
      showToast('Группа проблем сохранена');
    } catch (err) {
      console.error('[AdminApp] Save issue failed:', err);
      showToast('Не удалось сохранить изменения', true);
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  /**
   * Load and render metrics & stats.
   */
  async function loadStats() {
    try {
      const res = await fetch('/v1/admin/stats');
      if (!res.ok) return;
      const stats = await res.json();
      updateKpiValues(stats);

      const statusBody = document.getElementById('statsStatusBody');
      if (statusBody && stats.statusBreakdown) {
        statusBody.innerHTML = stats.statusBreakdown.map(s => `<tr><td><span class="tag tag-status-${(s.status || '').toLowerCase()}">${escapeHtml(s.label || s.status)}</span></td><td><strong>${s.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет данных</td></tr>';
      }

      const platBody = document.getElementById('statsPlatformBody');
      if (platBody && stats.platformBreakdown) {
        platBody.innerHTML = stats.platformBreakdown.map(p => `<tr><td>${escapeHtml(p.platform)}</td><td><strong>${p.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет данных</td></tr>';
      }

      const verBody = document.getElementById('statsVersionBody');
      if (verBody && stats.versionDistribution) {
        verBody.innerHTML = stats.versionDistribution.map(v => `<tr><td>${escapeHtml(v.app_version)}</td><td><strong>${v.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет данных</td></tr>';
      }

      const catBody = document.getElementById('statsCategoryBody');
      if (catBody && stats.topCategories) {
        catBody.innerHTML = stats.topCategories.map(c => `<tr><td><span class="tag tag-error">${escapeHtml(c.error_category)}</span></td><td><strong>${c.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет ошибок</td></tr>';
      }

      const issBody = document.getElementById('statsIssuesBody');
      if (issBody && stats.topIssues) {
        issBody.innerHTML = stats.topIssues.map(i => `<tr><td style="font-family:monospace;font-size:11px;">${escapeHtml(i.error_signature)}</td><td><strong>${i.count}</strong></td></tr>`).join('') || '<tr><td colspan="2">Нет данных</td></tr>';
      }
    } catch (err) {
      console.error('[AdminApp] Load stats failed:', err);
    }
  }

  /**
   * Safe Purge modal logic.
   */
  function openPurgeModal() {
    if (purgeModalOverlay) {
      purgeModalOverlay.style.display = 'flex';
      if (purgeConfirmInput) {
        purgeConfirmInput.value = '';
        purgeConfirmInput.focus();
      }
      if (purgeConfirmBtn) purgeConfirmBtn.disabled = true;
    }
  }

  function closePurgeModal() {
    if (purgeModalOverlay) purgeModalOverlay.style.display = 'none';
  }

  async function executeSafePurge() {
    if (purgeConfirmInput?.value.trim() !== 'УДАЛИТЬ') return;
    if (purgeConfirmBtn) purgeConfirmBtn.disabled = true;

    try {
      const res = await fetch('/v1/admin/reports', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmation: 'УДАЛИТЬ' })
      });
      if (!res.ok) throw new Error('Purge failed');
      const data = await res.json();
      showToast('Удалено отчётов: ' + (data.deletedCount ?? 0));
      closePurgeModal();
      refreshDashboard();
    } catch (err) {
      console.error('[AdminApp] Purge failed:', err);
      showToast('Ошибка при удалении логов', true);
    } finally {
      if (purgeConfirmBtn) purgeConfirmBtn.disabled = false;
    }
  }

  /**
   * Global event listeners registration.
   */
  function setupEventListeners() {
    // Navigation Tabs
    document.querySelectorAll('.nav-tab').forEach(btn => {
      btn.addEventListener('click', () => {
        const tab = btn.getAttribute('data-tab');
        switchMainTab(tab);
      });
    });

    // KPI Cards click to filter
    document.querySelectorAll('.kpi-card, .strip-item').forEach(card => {
      card.addEventListener('click', () => {
        const metric = card.getAttribute('data-metric');
        switchMainTab('reports');
        if (metric === 'all') {
          resetAllFilters();
        } else if (metric === '24h') {
          resetAllFilters();
          metricSince = Date.now() - 24 * 3600 * 1000;
          filterReportsTable();
        } else if (metric === '7d') {
          resetAllFilters();
          metricSince = Date.now() - 7 * 24 * 3600 * 1000;
          filterReportsTable();
        } else if (metric === 'attention') {
          resetAllFilters();
          if (filterSpecial) filterSpecial.value = 'attention';
          filterReportsTable();
        } else if (CANONICAL_STATUSES.includes(metric)) {
          resetAllFilters();
          if (filterStatus) filterStatus.value = metric;
          filterReportsTable();
        }
      });
    });

    // Search and Filters
    if (searchInput) {
      searchInput.addEventListener('input', () => {
        clearTimeout(searchDebounceTimer);
        searchDebounceTimer = setTimeout(filterReportsTable, 150);
      });
    }

    if (searchClearBtn) {
      searchClearBtn.addEventListener('click', () => {
        if (searchInput) {
          searchInput.value = '';
          searchInput.focus();
          filterReportsTable();
        }
      });
    }

    [filterPlatform, filterStatus, filterPurpose, filterCategory, filterSeverity, filterVersion, filterDevice, filterSignature, filterSpecial].forEach(el => {
      if (el) el.addEventListener('change', filterReportsTable);
      if (el && el.tagName === 'INPUT') el.addEventListener('input', filterReportsTable);
    });

    if (resetFiltersBtn) {
      resetFiltersBtn.addEventListener('click', resetAllFilters);
    }

    if (toggleMoreFiltersBtn) {
      toggleMoreFiltersBtn.addEventListener('click', () => {
        if (toolbarExtra) toolbarExtra.classList.toggle('open');
      });
    }

    if (headerMoreBtn) {
      headerMoreBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        if (headerMorePopup) headerMorePopup.classList.toggle('open');
      });
    }

    document.addEventListener('click', () => {
      if (headerMorePopup) headerMorePopup.classList.remove('open');
    });

    // Refresh & Purge
    if (refreshButton) {
      refreshButton.addEventListener('click', () => refreshDashboard(pageCursor));
    }

    if (purgeAllBtn) {
      purgeAllBtn.addEventListener('click', openPurgeModal);
    }

    if (purgeModalCloseBtn) purgeModalCloseBtn.addEventListener('click', closePurgeModal);
    if (purgeCancelBtn) purgeCancelBtn.addEventListener('click', closePurgeModal);
    if (purgeConfirmBtn) purgeConfirmBtn.addEventListener('click', executeSafePurge);

    if (purgeConfirmInput) {
      purgeConfirmInput.addEventListener('input', () => {
        if (purgeConfirmBtn) {
          purgeConfirmBtn.disabled = (purgeConfirmInput.value.trim() !== 'УДАЛИТЬ');
        }
      });
    }

    // Modal tabs
    document.querySelectorAll('.modal-tab-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const tab = btn.getAttribute('data-modal-tab');
        switchModalTab(tab);
      });
    });

    // Modal Close
    const modalCloseBtn = document.getElementById('modalCloseBtn');
    if (modalCloseBtn) modalCloseBtn.addEventListener('click', closeReportModal);

    // Save Detail Ops
    if (detailSaveBtn) {
      detailSaveBtn.addEventListener('click', saveReportOperations);
    }

    const detailDownloadBtn = document.getElementById('detailDownloadJsonBtn');
    if (detailDownloadBtn) {
      detailDownloadBtn.addEventListener('click', () => {
        if (currentReport) downloadJsonFile(currentReport.report_id || currentReport.reportId);
      });
    }

    const detailCopyMdBtn = document.getElementById('detailCopyMdBtn');
    if (detailCopyMdBtn) {
      detailCopyMdBtn.addEventListener('click', () => {
        if (currentReport) copyGitHubMarkdown(currentReport);
      });
    }

    const detailDeleteBtn = document.getElementById('detailDeleteBtn');
    if (detailDeleteBtn) {
      detailDeleteBtn.addEventListener('click', deleteCurrentReport);
    }

    // Quick Note Modal
    const noteCancel = document.getElementById('noteCancel');
    if (noteCancel) noteCancel.addEventListener('click', closeQuickNote);
    const noteSave = document.getElementById('noteSave');
    if (noteSave) noteSave.addEventListener('click', saveQuickNote);

    // Pagination buttons
    const backBtn = document.getElementById('pageBack');
    if (backBtn) {
      backBtn.addEventListener('click', () => {
        if (pageHistory.length > 0) {
          const prev = pageHistory.pop();
          pageCursor = prev || null;
          refreshDashboard(pageCursor);
        }
      });
    }

    const nextBtn = document.getElementById('pageNext');
    if (nextBtn) {
      nextBtn.addEventListener('click', () => {
        if (nextCursor) {
          pageHistory.push(pageCursor);
          pageCursor = nextCursor;
          refreshDashboard(pageCursor);
        }
      });
    }

    // Global Delegated Click & Change Handlers
    document.addEventListener('change', (e) => {
      if (e.target && e.target.classList.contains('status-select') && !e.target.closest('#detailWorkflowCard')) {
        updateInlineStatus(e.target);
      }
    });

    document.addEventListener('click', (e) => {
      const target = e.target.closest('[data-action]');
      if (!target) return;

      const action = target.getAttribute('data-action');
      const reportId = target.getAttribute('data-report-id');

      if (action === 'open-report') {
        openReport(reportId);
      } else if (action === 'download-report') {
        downloadJsonFile(reportId);
      } else if (action === 'quick-note') {
        openQuickNote(reportId);
      } else if (action === 'filter-signature') {
        const sig = target.getAttribute('data-signature');
        switchMainTab('reports');
        resetAllFilters();
        if (filterSignature) filterSignature.value = sig;
        filterReportsTable();
      } else if (action === 'save-issue') {
        saveIssueItem(target.closest('tr'));
      } else if (action === 'copy-raw-json') {
        copyRawJson();
      }
    });

    // Keyboard Shortcuts
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        if (purgeModalOverlay && purgeModalOverlay.style.display === 'flex') {
          closePurgeModal();
        } else if (document.getElementById('noteDialog')?.style.display === 'flex') {
          closeQuickNote();
        } else if (modalOverlay && modalOverlay.style.display === 'flex') {
          closeReportModal();
        }
      } else if (e.key === '/' && document.activeElement !== searchInput && document.activeElement.tagName !== 'INPUT' && document.activeElement.tagName !== 'TEXTAREA') {
        e.preventDefault();
        if (searchInput) {
          switchMainTab('reports');
          searchInput.focus();
          searchInput.select();
        }
      }
    });
  }

  function initAdminApp() {
    cacheDomElements();
    setupEventListeners();
    filterReportsTable();
  }

  // Initialize on DOM Ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initAdminApp);
  } else {
    initAdminApp();
  }
})();
