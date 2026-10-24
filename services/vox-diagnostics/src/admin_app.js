/**
 * SmartTube VOX Diagnostics — Client-side Admin Panel Application
 * Clean, modern, vanilla JavaScript (zero external dependencies).
 * Fully CSP-compliant (script-src 'self' without unsafe-inline or unsafe-eval).
 */

(function () {
  'use strict';

  // Canonical Statuses & UI Labels
  const CANONICAL_STATUSES = [
    'NEW',
    'INVESTIGATING',
    'ROOT_CAUSE_FOUND',
    'FIX_IMPLEMENTED',
    'TESTING',
    'FIXED_AWAITING_VERIFICATION',
    'VERIFIED',
    'REGRESSION',
    'KNOWN_LIMITATION',
    'IGNORED',
    // Legacy & Alias Statuses
    'FIXED_PENDING_VERIFICATION',
    'RESOLVED',
    'IGNORED_TEST',
    'IN_PROGRESS',
    'TRIAGED',
    'NEEDS_INFO',
    'CLOSED',
    'KNOWN_ISSUE',
    'WONT_FIX',
  ];

  const STATUS_LABELS = {
    NEW: 'Новый',
    INVESTIGATING: 'В работе',
    ROOT_CAUSE_FOUND: 'Причина найдена',
    FIX_IMPLEMENTED: 'Исправлено в коде',
    TESTING: 'На проверке',
    FIXED_AWAITING_VERIFICATION: 'Исправлено — ждёт проверки',
    VERIFIED: 'Исправлено — подтверждено',
    REGRESSION: 'Повторная ошибка',
    KNOWN_LIMITATION: 'Известное ограничение',
    IGNORED: 'Игнорируется',
    // Legacy / historical statuses
    FIXED_PENDING_VERIFICATION: 'Исправлено — ждёт проверки',
    RESOLVED: 'Решено',
    IGNORED_TEST: 'Тест / игнор',
    IN_PROGRESS: 'В работе',
    TRIAGED: 'Разобран',
    NEEDS_INFO: 'Нужны данные',
    CLOSED: 'Закрыто',
    KNOWN_ISSUE: 'Известная проблема',
    WONT_FIX: 'Не будет исправлено',
  };

  /**
   * Нормализует статус к одному из канонических значений:
   * NEW, TRIAGED, IN_PROGRESS, NEEDS_INFO, FIXED_PENDING_VERIFICATION, CLOSED, RESOLVED, KNOWN_ISSUE, IGNORED_TEST, WONT_FIX
   */
  function normalizeStatus(rawStatus) {
    if (!rawStatus) return 'NEW';
    const s = String(rawStatus).toUpperCase().trim();
    if (s === 'FIXED_AWAITING_VERIFICATION') return 'FIXED_AWAITING_VERIFICATION';
    if (s === 'KNOWN_LIMITATION') return 'KNOWN_LIMITATION';
    if (s === 'INVESTIGATING') return 'INVESTIGATING';
    if (s === 'ROOT_CAUSE_FOUND') return 'ROOT_CAUSE_FOUND';
    if (s === 'FIX_IMPLEMENTED') return 'FIX_IMPLEMENTED';
    if (s === 'TESTING') return 'TESTING';
    if (s === 'VERIFIED') return 'VERIFIED';
    if (s === 'REGRESSION') return 'REGRESSION';
    if (s === 'IGNORED') return 'IGNORED';
    if (s === 'TRIAGED') return 'TRIAGED';
    if (s === 'REVIEWED' || s === 'IN_PROGRESS') return 'IN_PROGRESS';
    if (s === 'NEEDS_INFO' || s === 'INFO_NEEDED') return 'NEEDS_INFO';
    if (s === 'FIXED' || s === 'FIXED_PENDING_VERIFICATION' || s === 'PENDING_VERIFICATION') return 'FIXED_PENDING_VERIFICATION';
    if (s === 'CLOSED') return 'CLOSED';
    if (s === 'RESOLVED') return 'RESOLVED';
    if (s === 'KNOWN' || s === 'KNOWN_ISSUE') return 'KNOWN_ISSUE';
    if (s === 'IGNORED_TEST' || s === 'TEST') return 'IGNORED_TEST';
    if (s === 'WONT_FIX' || s === 'WONTFIX') return 'WONT_FIX';
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
    const norm = normalizeStatus(status);
    const statuses = CANONICAL_STATUSES.includes(norm)
      ? CANONICAL_STATUSES
      : [norm, ...CANONICAL_STATUSES];
    return '<select class="status-select" data-status="' + escapeHtml(norm) + '" aria-label="Статус отчёта ' + escapeHtml(id) + '">' +
      statuses.map(key => '<option value="' + escapeHtml(key) + '"' + (key === norm ? ' selected' : '') + '>' + escapeHtml(STATUS_LABELS[key] || key) + '</option>').join('') + '</select>';
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
  let batchInvestigateBtn = null;
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
  let kpiFixedAwaiting = null;
  let kpiTriaged = null;
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
    batchInvestigateBtn = document.getElementById('batchInvestigateBtn');
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
    kpiFixedAwaiting = document.getElementById('kpiFixedAwaiting');
    kpiTriaged = document.getElementById('kpiTriaged');
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
    const viewDevices = document.getElementById('viewDevices');
    const viewAnalytics = document.getElementById('viewAnalytics');

    if (viewReports) viewReports.style.display = (normalizedTab === 'reports') ? 'block' : 'none';
    if (viewIssues) viewIssues.style.display = (normalizedTab === 'issues') ? 'block' : 'none';
    if (viewStats) viewStats.style.display = (normalizedTab === 'stats') ? 'block' : 'none';
    if (viewDevices) viewDevices.style.display = (normalizedTab === 'devices') ? 'block' : 'none';
    if (viewAnalytics) viewAnalytics.style.display = (normalizedTab === 'analytics') ? 'block' : 'none';

    document.querySelectorAll('.nav-tab').forEach(tab => {
      const t = tab.getAttribute('data-tab');
      const isMatch = (t === normalizedTab) || (normalizedTab === 'stats' && (t === 'stats' || t === 'metrics'));
      tab.classList.toggle('active', isMatch);
    });

    if (normalizedTab === 'issues') {
      loadIssues();
    } else if (normalizedTab === 'stats') {
      loadStats();
    } else if (normalizedTab === 'devices') {
      loadDevices();
    } else if (normalizedTab === 'analytics') {
      loadAnalytics(currentAnalyticsPeriod || '7d');
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
      if (stat === 'ACTIVE') {
        if (['RESOLVED', 'VERIFIED', 'CLOSED', 'IGNORED', 'IGNORED_TEST'].includes(rStat)) match = false;
      } else if (stat && rStat !== stat) {
        match = false;
      }
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

    const activeCat = (filterCategory ? filterCategory.value : '').toUpperCase();
    document.querySelectorAll('.category-chip').forEach(c => {
      c.classList.toggle('active', (c.getAttribute('data-chip-category') || '').toUpperCase() === activeCat);
    });
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
          <td style="text-align: center;"><input type="checkbox" class="report-select-cb" data-report-id="${escapeHtml(r.report_id)}" style="cursor: pointer;" /></td>
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
    if (kpiFixedAwaiting) kpiFixedAwaiting.textContent = stats.fixedAwaitingCount ?? 0;
    if (kpiTriaged) kpiTriaged.textContent = stats.triagedCount ?? 0;
    if (kpiResolved) kpiResolved.textContent = stats.resolvedCount ?? 0;
    if (kpiKnownIssue) kpiKnownIssue.textContent = stats.knownIssueCount ?? 0;
    if (kpiIgnoredTest) kpiIgnoredTest.textContent = stats.ignoredTestCount ?? 0;
    if (kpiTotal) kpiTotal.textContent = stats.totalReports ?? 0;
    const kpiAtt = document.getElementById('kpiAttention');
    if (kpiAtt) kpiAtt.textContent = stats.attentionCount ?? 0;
    const kpiLive = document.getElementById('kpiLive');
    if (kpiLive) kpiLive.textContent = stats.liveCount ?? 0;
    const kpiTrans = document.getElementById('kpiTranslation');
    if (kpiTrans) kpiTrans.textContent = stats.translationCount ?? 0;
    const kpiDl = document.getElementById('kpiDownload');
    if (kpiDl) kpiDl.textContent = stats.downloadCount ?? 0;
    const kpiNet = document.getElementById('kpiNetwork');
    if (kpiNet) kpiNet.textContent = stats.networkCount ?? 0;
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

    if (window.location.hash !== '#report=' + encodeURIComponent(reportId)) {
      history.replaceState(null, '', '#report=' + encodeURIComponent(reportId));
    }

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
    if (window.location.hash.startsWith('#report=')) {
      history.replaceState(null, '', window.location.pathname + window.location.search);
    }
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
      const player = p.playbackStats || p.livePlayback || {};
      const download = p.downloadDiagnostics || p.downloadState || {};
      const network = p.networkState || {};

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
            ${errorEvents.length > 0 ? `<div style="margin-top:6px;color:#fca5a5;">Последнее сообщение: <strong>${escapeHtml(errorEvents[errorEvents.length - 1].message || errorEvents[errorEvents.length - 1].code || '')}</strong></div>` : ''}
          </div>
        </div>

        ${(p.telegramDelivery || report.telegramDelivery) ? `
        <div class="section-card" style="border-left: 3px solid #38bdf8;">
          <div class="section-title" style="color:#38bdf8;">Уведомление в Telegram</div>
          <div style="font-size:13px;display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px;">
            <div>Статус: <strong>${escapeHtml((p.telegramDelivery || report.telegramDelivery).decision || 'UNKNOWN')}</strong></div>
            <div>Время: <strong>${formatDateTime((p.telegramDelivery || report.telegramDelivery).notifiedAt)}</strong></div>
            <div>Повторов: <strong>${escapeHtml((p.telegramDelivery || report.telegramDelivery).repeatCount ?? 0)}</strong></div>
            ${(p.telegramDelivery || report.telegramDelivery).messageId ? `<div>Message ID: <code>${escapeHtml((p.telegramDelivery || report.telegramDelivery).messageId)}</code></div>` : ''}
          </div>
        </div>` : ''}

        ${(player.rebufferCount !== undefined || player.selectedCodec || player.selectedHeight) ? `
        <div class="section-card">
          <div class="section-title">Воспроизведение (Playback)</div>
          <div style="font-size:13px;display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px;">
            <div>Тип источника: <strong>${escapeHtml(report.sourceType || (p.livePlayback ? 'LIVE' : 'VOD'))}</strong></div>
            <div>Кодек: <strong>${escapeHtml(player.selectedCodec || '-')}</strong></div>
            <div>Разрешение: <strong>${escapeHtml(player.selectedHeight || player.selectedResolution || '-')}p</strong></div>
            <div>Буферизаций (rebuffer): <strong>${escapeHtml(player.rebufferCount ?? 0)}</strong></div>
            <div>Длительность сталла: <strong>${player.totalRebufferMs ? (player.totalRebufferMs + ' мс') : '-'}</strong></div>
            <div>Битрейт сети: <strong>${player.averageBandwidthKbps ? (player.averageBandwidthKbps + ' kbps') : '-'}</strong></div>
          </div>
        </div>` : ''}

        ${(download.activeTasks !== undefined || download.activeJobs !== undefined || download.engine) ? `
        <div class="section-card">
          <div class="section-title">Состояние загрузок (Downloads)</div>
          <div style="font-size:13px;display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px;">
            <div>Активные задачи: <strong>${escapeHtml(download.activeJobs ?? download.activeTasks ?? 0)}</strong></div>
            <div>Движок: <strong>${escapeHtml(download.engine || 'MKV_MUXER')}</strong></div>
            <div>Свободно памяти: <strong>${escapeHtml(download.storageSpaceAvailableMb ?? download.freeStorageMb ?? '-')} МБ</strong></div>
            <div>Упаковка завершена: <strong>${download.packagingCompleted ? 'Да' : (download.packagingStarted ? 'В процессе' : 'Нет')}</strong></div>
            <div>Финализация: <strong>${download.finalizeCompleted ? 'Да' : 'Нет'}</strong></div>
          </div>
        </div>` : ''}

        ${p.translationState || p.translatedTrackPresent !== undefined ? `
        <div class="section-card">
          <div class="section-title">Закадровый перевод (Translation)</div>
          <div style="font-size:13px;display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px;">
            <div>Переведённая дорожка: <strong>${p.translatedTrackPresent ? 'Присутствует' : 'Отсутствует'}</strong></div>
            <div>Статус сессии: <strong>${escapeHtml(p.translationState?.status || '-')}</strong></div>
          </div>
        </div>` : ''}

        <div class="mobile-sticky-actions">
          <button class="btn btn-sm btn-primary" data-quick-status="IN_PROGRESS">В работу</button>
          <button class="btn btn-sm" data-quick-status="NEEDS_INFO">Нужны данные</button>
          <button class="btn btn-sm" data-quick-status="FIXED_PENDING_VERIFICATION">Исправлено</button>
          <button class="btn btn-sm" data-quick-status="CLOSED">Закрыто</button>
        </div>
      `;
    } else if (activeModalTab === 'timeline') {
      const events = p.safeRecentEvents || [];
      const sp = p.startupProfile || report.startupProfile;
      modalContent.innerHTML = `
        ${sp ? `
        <div class="section-card" style="border-left: 3px solid var(--accent);">
          <div class="section-title">Startup Profiler Timeline (Cold Start)</div>
          <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px;font-size:13px;margin-bottom:8px;">
            <div>Cold Start: <strong>${escapeHtml(sp.stages?.coldStartMs ?? sp.coldStartMs ?? '-')} мс</strong></div>
            <div>Network Init: <strong>${escapeHtml(sp.stages?.networkInitMs ?? sp.networkInitMs ?? '-')} мс</strong></div>
            <div>First Frame: <strong>${escapeHtml(sp.stages?.firstFrameMs ?? sp.firstFrameMs ?? '-')} мс</strong></div>
            <div>Total Startup: <strong style="color:var(--accent);">${escapeHtml(sp.totalStartupMs ?? '-')} мс</strong></div>
          </div>
        </div>` : ''}

        <div class="section-card">
          <div class="section-title">Хронология событий (Safe Timeline)</div>
          ${events.length === 0 ? '<p style="color:var(--text-muted);">Событий не зафиксировано.</p>' : `
          <div style="margin-top:10px;">
            ${events.map(e => {
              const lvl = (e.level || 'INFO').toLowerCase();
              const lifecycle = e.lifecycle || (e.safeContext?.lifecycle) || '';
              return `
              <div class="timeline-item ${lvl}">
                <div style="display:flex;justify-content:space-between;align-items:center;font-size:11px;color:var(--text-muted);margin-bottom:2px;">
                  <span>${formatDateTime(e.timestamp)}</span>
                  <div style="display:flex;gap:4px;">
                    ${lifecycle ? `<span class="tag ${lifecycle === 'CURRENT_SESSION' ? 'tag-status-new' : 'tag-status-test'}">${escapeHtml(lifecycle)}</span>` : ''}
                    <span class="tag ${lvl === 'error' ? 'tag-error' : (lvl === 'warn' ? 'tag-status-in_progress' : 'tag-status-new')}">${escapeHtml(e.level || 'INFO')}</span>
                  </div>
                </div>
                <div style="font-size:13px;color:var(--text-bright);"><strong>${escapeHtml(e.category || '')}</strong>: ${escapeHtml(e.code || e.event || '')}</div>
                ${e.message ? `<div style="font-size:12px;color:var(--text-muted);margin-top:2px;">${escapeHtml(e.message)}</div>` : ''}
                ${e.safeContext && Object.keys(e.safeContext).length > 0 ? `<div style="font-size:11px;color:var(--text-dim);margin-top:2px;font-family:monospace;">${escapeHtml(JSON.stringify(e.safeContext))}</div>` : ''}
              </div>`;
            }).join('')}
          </div>`}
        </div>
      `;
    } else if (activeModalTab === 'network') {
      const net = p.networkState || {};
      const pol = p.currentPolicy || {};
      modalContent.innerHTML = `
        <div class="section-card">
          <div class="section-title">Сетевой движок (Network Engine 2.0)</div>
          <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;font-size:13px;">
            <div>Активный движок: <strong>${escapeHtml(net.engine || net.networkEngine || pol.networkEngine || 'AUTO (OkHttp / Cronet)')}</strong></div>
            <div>Статус охлаждения: <strong>${net.cooldownActive ? 'Активно (Anti-ping-pong)' : 'Готов к переключению'}</strong></div>
            <div>Счётчик переключений: <strong>${escapeHtml(net.switchCount ?? 0)} (бюджет: ${escapeHtml(net.budgetRemaining ?? 2)})</strong></div>
            <div>Время удержания (Hold time): <strong>${net.holdTimeMs ? (net.holdTimeMs + ' мс') : '-'}</strong></div>
            <div>Health score: <strong>${escapeHtml(net.healthScore ?? 100)} / 100</strong></div>
            <div>Probe Latency: <strong>${net.probeLatencyMs ? (net.probeLatencyMs + ' мс') : (net.latencyMs ? (net.latencyMs + ' мс') : '-')}</strong></div>
          </div>
        </div>
      `;
    } else if (activeModalTab === 'player') {
      const pl = p.playbackStats || p.livePlayback || {};
      modalContent.innerHTML = `
        <div class="section-card">
          <div class="section-title">Воспроизведение и плеер (Playback)</div>
          <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;font-size:13px;">
            <div>Тип источника: <strong>${escapeHtml(report.sourceType || (p.livePlayback ? 'LIVE' : (p.playbackStats ? 'VOD' : '-')))}</strong></div>
            <div>Выбранный кодек: <strong>${escapeHtml(pl.selectedCodec || '-')}</strong></div>
            <div>Разрешение: <strong>${escapeHtml(pl.selectedHeight || pl.selectedResolution || '-')}p</strong></div>
            <div>Смещение Live (DVR offset): <strong>${pl.liveOffsetMs ? (Math.round(pl.liveOffsetMs / 1000) + ' сек') : '-'}</strong></div>
            <div>Буферизаций (Rebuffer count): <strong>${escapeHtml(pl.rebufferCount ?? 0)}</strong></div>
            <div>Общее время сталла: <strong>${pl.totalRebufferMs ? (pl.totalRebufferMs + ' мс') : '-'}</strong></div>
            <div>Средний буфер вперед: <strong>${pl.averageBufferedMs ? (pl.averageBufferedMs + ' мс') : '-'}</strong></div>
            <div>Битрейт: <strong>${pl.averageBandwidthKbps ? (pl.averageBandwidthKbps + ' kbps') : '-'}</strong></div>
          </div>
        </div>
      `;
    } else if (activeModalTab === 'translation') {
      const tr = p.translationState || {};
      const tc = p.translationCache || {};
      modalContent.innerHTML = `
        <div class="section-card">
          <div class="section-title">Закадровый перевод (Translation Reuse 2.0)</div>
          <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;font-size:13px;">
            <div>Кэш перевода: <strong>${tc.isReused || tr.isReused ? 'HIT (переиспользован)' : 'MISS (запрос к backend)'}</strong></div>
            <div>Языковая пара: <strong>${escapeHtml(tr.sourceLang || 'auto')} → ${escapeHtml(tr.targetLang || 'ru')}</strong></div>
            <div>Текущий этап (State): <strong>${escapeHtml(tr.status || tr.stage || 'IDLE')}</strong></div>
            <div>Монотонный ETA отсчёт: <strong>${tr.etaRemainingSeconds ? (tr.etaRemainingSeconds + ' сек') : '-'}</strong></div>
            <div>Общее время запуска: <strong>${tr.totalStartupMs ? (tr.totalStartupMs + ' мс') : '-'}</strong></div>
            <div>Ожидание провайдера: <strong>${tr.providerWaitMs ? (tr.providerWaitMs + ' мс') : '-'}</strong></div>
            <div>Скачивание аудиодорожки: <strong>${tr.audioDownloadMs ? (tr.audioDownloadMs + ' мс') : '-'}</strong></div>
            <div>Дорожка подключена: <strong>${p.translatedTrackPresent ? 'Да' : 'Нет'}</strong></div>
          </div>
        </div>
      `;
    } else if (activeModalTab === 'downloads') {
      const dl = p.downloadDiagnostics || p.downloadState || {};
      const perf = dl.downloadPerformance || {};
      modalContent.innerHTML = `
        <div class="section-card">
          <div class="section-title">Производительность загрузок (Downloads 3.0)</div>
          <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;font-size:13px;">
            <div>Активные задачи: <strong>${escapeHtml(dl.activeJobs ?? dl.activeTasks ?? 0)}</strong></div>
            <div>Движок: <strong>${escapeHtml(dl.engine || 'MKV_MUXER')}</strong></div>
            <div>Свободно на диске: <strong>${escapeHtml(dl.storageSpaceAvailableMb ?? dl.freeStorageMb ?? '-')} МБ</strong></div>
            <div>Сетевая загрузка (Всего): <strong>${perf.totalElapsedMs ? (Math.round(perf.totalElapsedMs / 1000) + ' сек') : '-'}</strong></div>
            <div>Видеопоток: <strong>${perf.videoElapsedMs ? (Math.round(perf.videoElapsedMs / 1000) + ' сек') : '-'}</strong></div>
            <div>Аудиопоток: <strong>${perf.audioElapsedMs ? (Math.round(perf.audioElapsedMs / 1000) + ' сек') : '-'}</strong></div>
            <div>Дорожка перевода: <strong>${perf.translationElapsedMs ? (Math.round(perf.translationElapsedMs / 1000) + ' сек') : '-'}</strong></div>
            <div>Этап упаковки (Packaging): <strong style="${Number(perf.packagingElapsedMs || 0) > 30000 ? 'color:var(--danger);' : ''}">${perf.packagingElapsedMs ? (Math.round(perf.packagingElapsedMs / 1000) + ' сек') : '-'}</strong></div>
            <div>Финализация: <strong>${perf.finalizeElapsedMs ? (Math.round(perf.finalizeElapsedMs / 1000) + ' сек') : '-'}</strong></div>
            <div>Чтение / Запись на диск: <strong>${perf.diskReadMs ? (perf.diskReadMs + ' мс') : '-'} / ${perf.diskWriteMs ? (perf.diskWriteMs + ' мс') : '-'}</strong></div>
            <div>Средняя скорость: <strong>${perf.averageMbps ? (perf.averageMbps + ' Mbps') : '-'}</strong></div>
            <div>Пиковая скорость: <strong>${perf.peakMbps ? (perf.peakMbps + ' Mbps') : '-'}</strong></div>
          </div>
        </div>
      `;
    } else if (activeModalTab === 'device') {
      const vCodecs = p.videoCodecs || {};
      const aCodecs = p.audioCodecs || {};
      modalContent.innerHTML = `
        <div class="section-card">
          <div class="section-title">Характеристики устройства</div>
          <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;font-size:13px;margin-bottom:14px;">
            <div>Производитель: <strong>${escapeHtml(p.manufacturer || '-')}</strong></div>
            <div>Модель: <strong>${escapeHtml(p.model || report.device_family || '-')}</strong></div>
            <div>Класс устройства: <strong>${escapeHtml(p.deviceTier || '-')}</strong></div>
            <div>ОС / SDK: <strong>${escapeHtml(p.osName || 'Android')} ${escapeHtml(p.osVersion || '')} (API ${escapeHtml(p.sdkInt || '-')})</strong></div>
            <div>Экран: <strong>${escapeHtml(p.display?.resolution || '-')} @ ${escapeHtml(p.display?.refreshRateHz || '-')}Hz</strong></div>
            <div>HDR10: <strong>${escapeHtml(p.display?.hdr10 || '-')}</strong></div>
          </div>
        </div>
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
    } else if (activeModalTab === 'ops') {
      const statusHist = report.statusHistory || report.history || [];
      const auditHist = report.auditHistory || [];
      const notesList = report.notes || [];
      modalContent.innerHTML = `
        <div class="section-card">
          <div class="section-title">Переходы статусов</div>
          ${statusHist.length === 0 ? '<p style="color:var(--text-muted);font-size:13px;">История статусов пуста.</p>' : `
          <table>
            <thead><tr><th>Время</th><th>Откуда</th><th>Куда</th><th>Автор</th><th>Причина</th></tr></thead>
            <tbody>
              ${statusHist.map(h => `<tr>
                <td>${formatDateTime(h.changedAt || h.timestamp)}</td>
                <td><span class="tag tag-status-${(h.fromStatus || '').toLowerCase()}">${escapeHtml(STATUS_LABELS[h.fromStatus] || h.fromStatus || '-')}</span></td>
                <td><span class="tag tag-status-${(h.toStatus || '').toLowerCase()}">${escapeHtml(STATUS_LABELS[h.toStatus] || h.toStatus || '-')}</span></td>
                <td><strong style="color:var(--accent);font-size:11px;">${escapeHtml(h.actor || 'ADMIN')}</strong></td>
                <td>${escapeHtml(h.reason || '-')}</td>
              </tr>`).join('')}
            </tbody>
          </table>`}
        </div>

        <div class="section-card">
          <div class="section-title">Заметки разработчиков</div>
          ${notesList.length === 0 ? '<p style="color:var(--text-muted);font-size:13px;">Заметок пока нет.</p>' : `
          <table>
            <thead><tr><th>Время</th><th>Автор</th><th>Заметка</th></tr></thead>
            <tbody>
              ${notesList.map(n => `<tr>
                <td>${formatDateTime(n.createdAt || n.timestamp)}</td>
                <td><strong style="color:var(--accent);font-size:11px;">${escapeHtml(n.actor || 'ADMIN')}</strong></td>
                <td>${escapeHtml(n.note || '')}</td>
              </tr>`).join('')}
            </tbody>
          </table>`}
        </div>

        <div class="section-card">
          <div class="section-title">Журнал аудита действий</div>
          ${auditHist.length === 0 ? '<p style="color:var(--text-muted);font-size:13px;">Журнал аудита пуст.</p>' : `
          <table>
            <thead><tr><th>Время</th><th>Действие</th><th>Автор</th><th>Цель</th></tr></thead>
            <tbody>
              ${auditHist.map(a => `<tr>
                <td>${formatDateTime(a.createdAt || a.created_at)}</td>
                <td><span class="tag tag-status-${(a.action || '').toLowerCase()}">${escapeHtml(a.action || '')}</span></td>
                <td><strong style="color:var(--accent);font-size:11px;">${escapeHtml(a.actor || 'ADMIN')}</strong></td>
                <td>${escapeHtml(a.targetId || a.target_id || '-')}</td>
              </tr>`).join('')}
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

  async function quickSetStatus(newStatus, defaultNote = '') {
    if (!currentReport) return;
    if (detailStatusSelect) detailStatusSelect.value = newStatus;
    if (defaultNote && detailNotesText && !detailNotesText.value.trim()) {
      detailNotesText.value = defaultNote;
    }
    await saveReportOperations();
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

  function copyTechnicalSummary(report) {
    if (!report) return;
    const p = report.payload || {};
    const text = [
      `[VOX TECHNICAL SUMMARY]`,
      `ID: ${report.report_id || report.reportId}`,
      `Status: ${normalizeStatus(report.status)}`,
      `Platform: ${report.platform || p.platform || 'Android TV'}`,
      `Version: ${report.app_version || p.appVersion || '-'} (Code: ${p.appVersionCode || '-'})`,
      `Device: ${p.manufacturer || ''} ${p.model || report.device_family || ''} (Tier: ${p.deviceTier || '-'}, API: ${p.sdkInt || '-'})`,
      `Category: ${report.error_category || report.errorCategory || '-'}`,
      `Signature: ${report.error_signature || report.errorSignature || '-'}`,
      report.developer_notes ? `Notes: ${report.developer_notes}` : '',
      `Events Count: ${(p.safeRecentEvents || []).length}`,
    ].filter(Boolean).join('\n');

    navigator.clipboard.writeText(text).then(() => {
      showToast('Техническое резюме скопировано');
    }).catch(() => {
      showToast('Не удалось скопировать', true);
    });
  }

  function copyAntigravitySummary(report) {
    if (!report) return;
    const p = report.payload || {};
    const text = [
      `### VOX Diagnostic Incident: ${report.report_id || report.reportId}`,
      `- **Device Family**: ${p.manufacturer || ''} ${p.model || report.device_family || ''} (Tier: ${p.deviceTier || '-'})`,
      `- **Platform / OS**: ${report.platform || p.platform || 'Android TV'} Android ${p.osVersion || ''} (API ${p.sdkInt || '-'})`,
      `- **Version**: ${report.app_version || p.appVersion || '-'} (${p.appVersionCode || '-'})`,
      `- **Error Category**: \`${report.error_category || report.errorCategory || '-'}\``,
      `- **Error Signature**: \`${report.error_signature || report.errorSignature || '-'}\``,
      `- **Status**: ${normalizeStatus(report.status)}`,
      `- **Recent Events Summary**: ${(p.safeRecentEvents || []).slice(-5).map(e => `${e.level || 'INFO'}: ${e.code || e.event || ''} - ${e.message || ''}`).join('; ')}`,
      report.developer_notes ? `- **Developer Notes**: ${report.developer_notes}` : '',
    ].filter(Boolean).join('\n');

    navigator.clipboard.writeText(text).then(() => {
      showToast('Antigravity резюме скопировано');
    }).catch(() => {
      showToast('Не удалось скопировать', true);
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

  const TARGET_INVESTIGATING_REPORTS = [
    'VOX-FC763605', 'VOX-319BD42A', 'VOX-BAEAAEB6', 'VOX-EF0F761F',
    'VOX-0BF9F24D', 'VOX-C04586D3', 'VOX-D5F0C645', 'VOX-C6A533DD',
    'VOX-6B514E8A', 'VOX-2BC4753A', 'VOX-EC363B97', 'VOX-6D00AEA9',
    'VOX-3345BBFD', 'VOX-BD27BAD9', 'VOX-7AE27244', 'VOX-9121247C',
    'VOX-78151084', 'VOX-87B9400C', 'VOX-28070F7A'
  ];

  async function batchTransitionToInvestigating() {
    if (!confirm('Перевести ' + TARGET_INVESTIGATING_REPORTS.length + ' утверждённых отчётов в статус «В работе» (INVESTIGATING)?')) {
      return;
    }
    const btn = document.getElementById('batchInvestigateBtn');
    if (btn) btn.disabled = true;
    let successCount = 0;
    let failCount = 0;
    let authBlocked = false;

    for (const reportId of TARGET_INVESTIGATING_REPORTS) {
      try {
        const response = await fetch('/v1/admin/reports/' + encodeURIComponent(reportId), {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            status: 'INVESTIGATING',
            developer_notes: 'Переведено в работу по результатам аудита (Section 24 Security Hardening).'
          })
        });
        if (response.status === 401 || response.status === 403) {
          authBlocked = true;
          break;
        }
        if (response.ok) {
          successCount++;
        } else {
          failCount++;
        }
      } catch (err) {
        failCount++;
      }
    }

    if (btn) btn.disabled = false;

    if (authBlocked) {
      showToast('Ошибка аутентификации (BLOCKED_AUTH): требуется вход администратора', true);
    } else if (successCount > 0) {
      showToast('Успешно обновлено ' + successCount + ' отчётов' + (failCount > 0 ? (' (ошибок: ' + failCount + ')') : ''));
      refreshDashboard();
    } else {
      showToast('Не удалось обновить отчёты (ошибок: ' + failCount + ')', true);
    }
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

  let currentAnalyticsPeriod = '7d';

  // --- COMPLETE ACTIVE REPORTS ZIP EXPORT (NEW + INVESTIGATING) ---
  async function downloadAllActiveZip() {
    const exportSnapshotUntil = Date.now();
    let currentCursor = null;
    let part = 1;
    let totalDownloaded = 0;
    let hasMoreParts = true;

    showToast('Подготовка выгрузки всех активных отчётов (NEW + В работе)...');

    try {
      while (hasMoreParts) {
        const params = new URLSearchParams({
          statuses: 'NEW,INVESTIGATING',
          until: String(exportSnapshotUntil),
          part: String(part),
        });
        if (currentCursor) {
          params.set('cursor', currentCursor);
        }

        const url = '/v1/admin/export/zip?' + params.toString();
        if (part > 1) {
          showToast('Загрузка части ' + part + ' архива...');
        }

        const res = await fetch(url, { credentials: 'same-origin' });
        if (res.status === 401 || res.status === 403) {
          showToast('Нет доступа к выгрузке (401/403): требуется авторизация администратора', true);
          return;
        }
        if (!res.ok) {
          const errJson = await res.json().catch(() => ({}));
          const errMsg = errJson.message || res.statusText || String(res.status);
          showToast('Ошибка формирования части ' + part + ': ' + errMsg, true);
          return;
        }

        const blob = await res.blob();
        const disposition = res.headers.get('Content-Disposition') || '';
        const match = disposition.match(/filename="?([^";]+)"?/i);
        const defaultName = part > 1 ? ('VOX_Diagnostics_Active_part' + part + '.zip') : 'VOX_Diagnostics_Active.zip';
        const filename = match ? match[1] : defaultName;

        const blobUrl = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = blobUrl;
        link.download = filename;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        setTimeout(() => URL.revokeObjectURL(blobUrl), 30000);

        const partReports = parseInt(res.headers.get('X-Total-Reports') || '0', 10);
        totalDownloaded += partReports;
        const isTruncated = res.headers.get('X-Export-Truncated') === 'true';
        const nextCursor = res.headers.get('X-Export-Next-Cursor');

        if (isTruncated && nextCursor) {
          showToast('Часть ' + part + ' скачана (' + partReports + ' отчётов). Загрузка следующей части...');
          currentCursor = nextCursor;
          part++;
          await new Promise((r) => setTimeout(r, 600));
        } else {
          hasMoreParts = false;
        }
      }

      if (part > 1) {
        showToast('Выгрузка завершена: успешно скачано частей: ' + part + ' (всего ' + totalDownloaded + ' отчётов)');
      } else {
        showToast(totalDownloaded > 0 ? ('Архив успешно скачан (' + totalDownloaded + ' отчётов)') : 'Архив скачан (0 активных отчётов)');
      }
    } catch (err) {
      console.error('[AdminApp] Active ZIP export failed:', err);
      showToast('Ошибка при скачивании архива: ' + err.message, true);
    }
  }

  // --- ZIP EXPORT ---
  async function downloadZipArchive(params = {}) {
    const qs = new URLSearchParams(params).toString();
    const url = '/v1/admin/export/zip' + (qs ? '?' + qs : '');
    showToast('Подготовка ZIP-архива...');
    try {
      const res = await fetch(url, { credentials: 'same-origin' });
      if (res.status === 401 || res.status === 403) {
        showToast('Нет доступа к выгрузке (401/403): требуется авторизация', true);
        return;
      }
      if (!res.ok) {
        const errJson = await res.json().catch(() => ({}));
        showToast('Ошибка формирования ZIP: ' + (errJson.message || res.statusText || res.status), true);
        return;
      }
      const blob = await res.blob();
      const filenameHeader = res.headers.get('Content-Disposition') || '';
      const match = filenameHeader.match(/filename="?([^";]+)"?/i);
      const filename = match ? match[1] : 'VOX_Diagnostics_Active.zip';
      const blobUrl = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = blobUrl;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      setTimeout(() => URL.revokeObjectURL(blobUrl), 10000);
      const totalReports = res.headers.get('X-Total-Reports') || '';
      const isTruncated = res.headers.get('X-Export-Truncated') === 'true';
      if (isTruncated) {
        showToast(`Внимание: архив ограничен первыми ${totalReports} отчётами. Для остальных сузьте фильтры.`);
      } else {
        showToast(totalReports ? `ZIP-архив успешно скачан (${totalReports} отчётов)` : 'ZIP-архив успешно скачан');
      }
    } catch (err) {
      showToast('Ошибка при скачивании архива: ' + err.message, true);
    }
  }

  // --- BATCH SELECTION & OPERATIONS ---
  function getSelectedReportIds() {
    return Array.from(document.querySelectorAll('.report-select-cb:checked'))
      .map(cb => cb.dataset.reportId)
      .filter(Boolean);
  }

  function updateBatchActionBar() {
    const selected = getSelectedReportIds();
    const bar = document.getElementById('batchActionBar');
    const countEl = document.getElementById('batchSelectedCount');
    if (countEl) countEl.textContent = String(selected.length);
    if (bar) {
      bar.style.display = selected.length > 0 ? 'block' : 'none';
    }
    const selectAllCb = document.getElementById('selectAllReportsCb');
    const allCbs = document.querySelectorAll('.report-select-cb');
    if (selectAllCb && allCbs.length > 0) {
      selectAllCb.checked = selected.length === allCbs.length;
      selectAllCb.indeterminate = selected.length > 0 && selected.length < allCbs.length;
    }
  }

  async function applyBatchStatus() {
    const selected = getSelectedReportIds();
    const selectEl = document.getElementById('batchStatusSelect');
    const status = selectEl ? selectEl.value : '';
    if (!status) {
      showToast('Выберите статус для применения', true);
      return;
    }
    if (!confirm('Перевести ' + selected.length + ' отчётов в статус ' + status + '?')) {
      return;
    }
    let successCount = 0;
    let failCount = 0;
    for (const id of selected) {
      try {
        const res = await fetch('/v1/admin/reports/' + encodeURIComponent(id), {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ status })
        });
        if (res.ok) successCount++; else failCount++;
      } catch (_) { failCount++; }
    }
    showToast('Обновлено ' + successCount + ' отчётов' + (failCount > 0 ? ' (ошибок: ' + failCount + ')' : ''));
    refreshDashboard();
  }

  async function applyBatchNote() {
    const selected = getSelectedReportIds();
    const note = prompt('Введите текст заметки для ' + selected.length + ' выбранных отчётов:');
    if (!note || !note.trim()) return;
    let successCount = 0;
    for (const id of selected) {
      try {
        const res = await fetch('/v1/admin/reports/' + encodeURIComponent(id) + '/notes', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ note: note.trim() })
        });
        if (res.ok) successCount++;
      } catch (_) {}
    }
    showToast('Заметка добавлена к ' + successCount + ' отчётам');
    refreshDashboard();
  }

  function downloadSelectedZip() {
    const selected = getSelectedReportIds();
    if (selected.length === 0) {
      showToast('Нет выбранных отчётов', true);
      return;
    }
    downloadZipArchive({ search: selected.join(' ') });
  }

  function clearBatchSelection() {
    document.querySelectorAll('.report-select-cb').forEach(cb => { cb.checked = false; });
    const selectAllCb = document.getElementById('selectAllReportsCb');
    if (selectAllCb) { selectAllCb.checked = false; selectAllCb.indeterminate = false; }
    updateBatchActionBar();
  }

  // --- DEVICES VIEW ---
  async function loadDevices() {
    const container = document.getElementById('devicesListContainer');
    const kpiContainer = document.getElementById('devicesSummaryKpis');
    const searchInput = document.getElementById('deviceSearchInput');
    const q = searchInput ? searchInput.value.trim() : '';

    if (container) container.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-muted);">Загрузка списка устройств...</div>';

    try {
      const res = await fetch('/v1/admin/devices?search=' + encodeURIComponent(q));
      if (res.status === 401 || res.status === 403) {
        throw new Error('AUTH_ERROR: Нет доступа к разделу устройств (401/403). Требуется авторизация администратора.');
      }
      if (res.status >= 500) {
        throw new Error('SERVER_ERROR: Ошибка сервера (' + res.status + '). Сервер временно недоступен.');
      }
      if (!res.ok) {
        throw new Error('REQUEST_ERROR: Ошибка запроса (' + res.status + ')');
      }
      const data = await res.json();

      if (kpiContainer) {
        kpiContainer.innerHTML = `
          <div class="kpi-card">
            <div>
              <div class="kpi-title">Всего установок</div>
              <div class="kpi-val" style="color: #60a5fa;">${data.totalInstallations || 0}</div>
            </div>
            <div class="kpi-sub">Уникальных UUID установок</div>
          </div>
          <div class="kpi-card">
            <div>
              <div class="kpi-title">Моделей устройств</div>
              <div class="kpi-val" style="color: #34d399;">${data.modelsCount || 0}</div>
            </div>
            <div class="kpi-sub">Групп оборудования</div>
          </div>
          <div class="kpi-card">
            <div>
              <div class="kpi-title">Ранее поступившие</div>
              <div class="kpi-val" style="color: #fbbf24;">${data.unassignedReportsCount || 0}</div>
            </div>
            <div class="kpi-sub">Без Installation ID (сохранены)</div>
          </div>
        `;
      }

      if (!data.models || data.models.length === 0) {
        if (container) {
          container.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-muted);">Устройства не найдены.</div>';
        }
        return;
      }

      let html = '';
      for (const m of data.models) {
        html += `
          <div class="device-model-card">
            <div class="device-model-header" data-action="toggle-model">
              <div>
                <strong style="font-size: 15px; color: var(--text-bright);">${escapeHtml(m.model)}</strong>
                <span class="tag tag-android" style="margin-left: 8px;">${escapeHtml(m.manufacturer || 'OEM')}</span>
              </div>
              <div style="font-size: 13px; color: var(--text-muted);">
                Установок: <strong style="color:#60a5fa;">${m.installationsCount}</strong> &nbsp;|&nbsp;
                Отчётов: <strong>${m.reportsCount}</strong>
              </div>
            </div>
            <div class="device-model-body">
              <table class="installations-table">
                <thead>
                  <tr>
                    <th>Installation ID</th>
                    <th>Система / API</th>
                    <th>Текущая версия</th>
                    <th>История версий</th>
                    <th>Первый запуск</th>
                    <th>Посл. активность</th>
                    <th>Отчёты</th>
                    <th>Действия</th>
                  </tr>
                </thead>
                <tbody>
                  ${(m.installations || []).map(inst => `
                    <tr>
                      <td>
                        <span class="install-id-pill" title="Нажмите, чтобы скопировать" data-action="copy-id" data-id="${escapeHtml(inst.installationId)}">
                          📋 ${escapeHtml(inst.installationId)}
                        </span>
                      </td>
                      <td>API ${inst.sdkInt || '-'} ${inst.androidVersion ? ('(Android ' + escapeHtml(inst.androidVersion) + ')') : ''}</td>
                      <td><strong>${escapeHtml(inst.voxVersion || '-')}</strong></td>
                      <td>
                        ${(inst.appVersionHistory || []).map(v => `<span class="tag tag-android" style="font-size:10px;margin-right:2px;">${escapeHtml(v)}</span>`).join('')}
                      </td>
                      <td>${formatDateTime(inst.firstSeenAt)}</td>
                      <td>${formatDateTime(inst.lastSeenAt)}</td>
                      <td>
                        <strong style="color:${inst.activeInvestigationsCount > 0 ? '#60a5fa' : 'inherit'};">${inst.reportsCount}</strong>
                        ${inst.activeInvestigationsCount > 0 ? `<span class="tag tag-status-investigating" style="font-size:10px;margin-left:4px;">В работе: ${inst.activeInvestigationsCount}</span>` : ''}
                      </td>
                      <td>
                        <button class="btn btn-sm btn-primary" data-action="filter-installation" data-install-id="${escapeHtml(inst.installationId)}">
                          Показать отчёты
                        </button>
                      </td>
                    </tr>
                  `).join('')}
                </tbody>
              </table>
            </div>
          </div>
        `;
      }

      if (data.unassignedReports && data.unassignedReports.length > 0) {
        html += `
          <div class="device-model-card" style="border-color: rgba(251, 191, 36, 0.3);">
            <div class="device-model-header" style="background: rgba(251, 191, 36, 0.08);">
              <div>
                <strong style="color: #fbbf24;">Ранее поступившие отчёты (без Installation ID)</strong>
                <span style="font-size: 12px; color: var(--text-muted); margin-left: 8px;">Не объединены в вымышленное устройство</span>
              </div>
              <div>Отчётов: <strong>${data.unassignedReports.length}</strong></div>
            </div>
            <div style="padding: 14px 18px; display: flex; flex-wrap: wrap; gap: 8px;">
              ${data.unassignedReports.map(r => `
                <button class="btn btn-sm" data-action="open-report" data-report-id="${escapeHtml(r.reportId)}">
                  ${escapeHtml(r.reportId)} (${escapeHtml(r.model || 'Unknown')})
                </button>
              `).join('')}
            </div>
          </div>
        `;
      }

      if (container) container.innerHTML = html;
    } catch (err) {
      let msg = err.message || 'Ошибка сети';
      if (msg.startsWith('AUTH_ERROR:')) msg = msg.replace('AUTH_ERROR:', '').trim();
      else if (msg.startsWith('SERVER_ERROR:')) msg = msg.replace('SERVER_ERROR:', '').trim();
      else if (msg.startsWith('REQUEST_ERROR:')) msg = msg.replace('REQUEST_ERROR:', '').trim();
      else if (err.name === 'TypeError' || msg.includes('Failed to fetch')) msg = 'Сервер недоступен. Проверьте сетевое соединение.';

      if (kpiContainer) {
        kpiContainer.innerHTML = `
          <div class="kpi-card" style="border-left: 3px solid var(--danger); grid-column: 1 / -1;">
            <div>
              <div class="kpi-title" style="color: var(--danger);">Ошибка загрузки устройств</div>
              <div class="kpi-sub" style="color: var(--text-muted); margin-top: 6px;">${escapeHtml(msg)}</div>
            </div>
          </div>
        `;
      }
      if (container) {
        container.innerHTML = '<div style="text-align:center;padding:40px;color:var(--danger);font-size:14px;"><div style="font-size:24px;margin-bottom:8px;">⚠️</div>' + escapeHtml(msg) + '</div>';
      }
      showToast(msg, true);
    }
  }

  // --- ANALYTICS VIEW ---
  async function loadAnalytics(period = '7d') {
    currentAnalyticsPeriod = period;
    const kpiContainer = document.getElementById('analyticsKpisContainer');

    document.querySelectorAll('.analytics-period-btn').forEach(btn => {
      btn.classList.toggle('active', btn.getAttribute('data-period') === period);
    });

    try {
      const res = await fetch('/v1/admin/analytics?period=' + encodeURIComponent(period));
      if (res.status === 401 || res.status === 403) {
        throw new Error('AUTH_ERROR: Нет доступа к аналитике (401/403). Требуется авторизация администратора.');
      }
      if (res.status >= 500) {
        throw new Error('SERVER_ERROR: Ошибка сервера (' + res.status + '). Сервер временно недоступен.');
      }
      if (!res.ok) {
        throw new Error('REQUEST_ERROR: Ошибка запроса аналитики (' + res.status + ')');
      }
      const data = await res.json();

      if (kpiContainer) {
        kpiContainer.innerHTML = `
          <div class="kpi-card">
            <div>
              <div class="kpi-title">Сейчас активны</div>
              <div class="kpi-val" style="color: #34d399;">${data.activeNow || 0}</div>
            </div>
            <div class="kpi-sub">В окне 45 минут</div>
          </div>
          <div class="kpi-card">
            <div>
              <div class="kpi-title">Активны за период</div>
              <div class="kpi-val" style="color: #60a5fa;">${data.activeInPeriod || 0}</div>
            </div>
            <div class="kpi-sub">Всего уникальных за ${data.period}</div>
          </div>
          <div class="kpi-card">
            <div>
              <div class="kpi-title">Новые установки</div>
              <div class="kpi-val" style="color: #a78bfa;">${data.newInstallations || 0}</div>
            </div>
            <div class="kpi-sub">Впервые запущены в период</div>
          </div>
          <div class="kpi-card">
            <div>
              <div class="kpi-title">Обновились</div>
              <div class="kpi-val" style="color: #38bdf8;">${data.upgradedInstallations || 0}</div>
            </div>
            <div class="kpi-sub">Сменили версию приложения</div>
          </div>
          <div class="kpi-card">
            <div>
              <div class="kpi-title">Установки с ошибками</div>
              <div class="kpi-val" style="color: ${(data.errorRatePercent || 0) > 10 ? '#ef4444' : '#fbbf24'};">${data.errorRatePercent || 0}%</div>
            </div>
            <div class="kpi-sub">${data.installationsWithErrors || 0} устройств со сбоями</div>
          </div>
          <div class="kpi-card">
            <div>
              <div class="kpi-title">Частота инцидентов</div>
              <div class="kpi-val" style="color: #f472b6;">${data.incidentFrequency || 0}</div>
            </div>
            <div class="kpi-sub">Отчётов на активную установку</div>
          </div>
        `;
      }

      renderTimelineAreaChart('chartActiveTimeline', data.timeline, 'active', '#3b82f6');
      renderTimelineAreaChart('chartNewTimeline', data.timeline, 'new', '#a78bfa');
      renderDistributionBars('chartVersionDist', data.versionDistribution);
      renderDistributionBars('chartErrorDist', data.errorCategoryDistribution, '#ef4444');
      renderDistributionBars('chartModelDist', data.modelDistribution, '#34d399');
      renderDistributionBars('chartApiDist', data.apiLevelDistribution, '#fbbf24');

    } catch (err) {
      let msg = err.message || 'Ошибка сети';
      if (msg.startsWith('AUTH_ERROR:')) msg = msg.replace('AUTH_ERROR:', '').trim();
      else if (msg.startsWith('SERVER_ERROR:')) msg = msg.replace('SERVER_ERROR:', '').trim();
      else if (msg.startsWith('REQUEST_ERROR:')) msg = msg.replace('REQUEST_ERROR:', '').trim();
      else if (err.name === 'TypeError' || msg.includes('Failed to fetch')) msg = 'Сервер недоступен. Проверьте сетевое соединение.';

      if (kpiContainer) {
        kpiContainer.innerHTML = `
          <div class="kpi-card" style="border-left: 3px solid var(--danger); grid-column: 1 / -1;">
            <div>
              <div class="kpi-title" style="color: var(--danger);">Ошибка загрузки аналитики</div>
              <div class="kpi-sub" style="color: var(--text-muted); margin-top: 6px;">${escapeHtml(msg)}</div>
            </div>
          </div>
        `;
      }
      ['chartActiveTimeline', 'chartNewTimeline', 'chartVersionDist', 'chartErrorDist', 'chartModelDist', 'chartApiDist'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.innerHTML = '<div style="color:var(--danger);padding:30px;text-align:center;font-size:12px;">' + escapeHtml(msg) + '</div>';
      });
      showToast(msg, true);
    }
  }

  // Pure SVG Timeline Area Chart
  function renderTimelineAreaChart(containerId, timeline = [], field = 'active', strokeColor = '#3b82f6') {
    const el = document.getElementById(containerId);
    if (!el) return;
    if (!timeline || timeline.length === 0) {
      el.innerHTML = '<div style="color:var(--text-muted);padding:40px;text-align:center;">Нет данных за выбранный период</div>';
      return;
    }

    const W = 520;
    const H = 180;
    const padL = 35;
    const padR = 20;
    const padT = 20;
    const padB = 30;

    const values = timeline.map(t => Number(t[field] || 0));
    const maxVal = Math.max(5, ...values);
    const n = timeline.length;

    const points = values.map((val, i) => {
      const x = padL + (i / Math.max(1, n - 1)) * (W - padL - padR);
      const y = H - padB - (val / maxVal) * (H - padT - padB);
      return { x, y, val, date: timeline[i].date };
    });

    const pathData = points.map((p, i) => (i === 0 ? 'M' : 'L') + p.x.toFixed(1) + ',' + p.y.toFixed(1)).join(' ');
    const areaData = pathData + ' L' + points[points.length - 1].x.toFixed(1) + ',' + (H - padB) + ' L' + points[0].x.toFixed(1) + ',' + (H - padB) + ' Z';

    let svg = `<svg viewBox="0 0 ${W} ${H}" style="width:100%;height:100%;overflow:visible;">
      <defs>
        <linearGradient id="grad_${containerId}" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="${strokeColor}" stop-opacity="0.35" />
          <stop offset="100%" stop-color="${strokeColor}" stop-opacity="0.0" />
        </linearGradient>
      </defs>
      <!-- Grid Lines -->
      <line x1="${padL}" y1="${padT}" x2="${W - padR}" y2="${padT}" stroke="rgba(255,255,255,0.06)" />
      <line x1="${padL}" y1="${(padT + H - padB)/2}" x2="${W - padR}" y2="${(padT + H - padB)/2}" stroke="rgba(255,255,255,0.06)" />
      <line x1="${padL}" y1="${H - padB}" x2="${W - padR}" y2="${H - padB}" stroke="rgba(255,255,255,0.12)" />
      <!-- Y Axis Labels -->
      <text x="${padL - 6}" y="${padT + 4}" fill="var(--text-muted)" font-size="10" text-anchor="end">${maxVal}</text>
      <text x="${padL - 6}" y="${H - padB + 3}" fill="var(--text-muted)" font-size="10" text-anchor="end">0</text>
      <!-- Area & Line -->
      <path d="${areaData}" fill="url(#grad_${containerId})" />
      <path d="${pathData}" fill="none" stroke="${strokeColor}" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
      <!-- Dots -->
      ${points.map(p => `<circle cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}" r="3" fill="${strokeColor}" stroke="#111726" stroke-width="1.5"><title>${p.date}: ${p.val}</title></circle>`).join('')}
      <!-- Date Labels -->
      <text x="${padL}" y="${H - 8}" fill="var(--text-muted)" font-size="10">${timeline[0]?.date?.slice(5) || ''}</text>
      <text x="${W - padR}" y="${H - 8}" fill="var(--text-muted)" font-size="10" text-anchor="end">${timeline[timeline.length - 1]?.date?.slice(5) || ''}</text>
    </svg>`;

    el.innerHTML = svg;
  }

  function renderDistributionBars(containerId, distObj = {}, barColor = '#3b82f6') {
    const el = document.getElementById(containerId);
    if (!el) return;
    const entries = Object.entries(distObj || {}).sort((a, b) => b[1] - a[1]);
    if (entries.length === 0) {
      el.innerHTML = '<div style="color:var(--text-muted);padding:30px;text-align:center;">Нет данных</div>';
      return;
    }
    const total = entries.reduce((acc, e) => acc + e[1], 0);
    const max = entries[0][1] || 1;

    let html = '<div style="display:flex;flex-direction:column;gap:8px;padding:8px 0;">';
    for (const [key, count] of entries.slice(0, 8)) {
      const pct = Math.round((count / total) * 100);
      const widthPct = Math.max(4, Math.round((count / max) * 100));
      html += `
        <div class="chart-bar-row">
          <div class="chart-bar-label" title="${escapeHtml(key)}">${escapeHtml(key)}</div>
          <div class="chart-bar-track">
            <div class="chart-bar-fill" style="width:${widthPct}%;background:${barColor};"></div>
          </div>
          <div class="chart-bar-value">${count} <span style="font-size:10px;color:var(--text-muted);font-weight:normal;">(${pct}%)</span></div>
        </div>
      `;
    }
    html += '</div>';
    el.innerHTML = html;
  }

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
        } else if (['LIVE', 'TRANSLATION', 'DOWNLOAD', 'NETWORK'].includes(metric)) {
          resetAllFilters();
          if (filterCategory) filterCategory.value = metric;
          filterReportsTable();
        } else if (CANONICAL_STATUSES.includes(metric)) {
          resetAllFilters();
          if (filterStatus) filterStatus.value = metric;
          filterReportsTable();
        }
      });
    });

    // Category Chips
    document.querySelectorAll('.category-chip').forEach(chip => {
      chip.addEventListener('click', () => {
        const cat = chip.getAttribute('data-chip-category') || '';
        document.querySelectorAll('.category-chip').forEach(c => c.classList.toggle('active', c === chip));
        if (filterCategory) filterCategory.value = cat;
        filterReportsTable();
      });
    });

    // Attention Cards
    document.querySelectorAll('.attention-card').forEach(card => {
      card.addEventListener('click', () => {
        const att = card.getAttribute('data-attention');
        switchMainTab('reports');
        resetAllFilters();
        if (att === 'live-seek') {
          if (filterSignature) filterSignature.value = 'LIVE_SEEK';
        } else if (att === 'translation-slow') {
          if (filterCategory) filterCategory.value = 'TRANSLATION';
        } else if (att === 'download-packaging') {
          if (filterCategory) filterCategory.value = 'DOWNLOAD';
          if (filterSignature) filterSignature.value = 'PACKAGING';
        } else if (att === 'network-fallback') {
          if (filterCategory) filterCategory.value = 'NETWORK';
          if (filterSignature) filterSignature.value = 'FALLBACK';
        } else if (att === 'thumbnail-failures') {
          if (filterSignature) filterSignature.value = 'IMAGE';
        }
        filterReportsTable();
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

    // Complete Active Reports ZIP Export (NEW + INVESTIGATING)
    const downloadAllActiveZipBtn = document.getElementById('downloadAllActiveZipBtn');
    if (downloadAllActiveZipBtn) {
      downloadAllActiveZipBtn.addEventListener('click', () => {
        downloadAllActiveZip();
      });
    }

    const headerDownloadAllZipBtn = document.getElementById('headerDownloadAllZipBtn');
    if (headerDownloadAllZipBtn) {
      headerDownloadAllZipBtn.addEventListener('click', () => {
        downloadAllActiveZip();
      });
    }

    // Bulk ZIP from Action Menu
    const downloadAllPendingZipBtn = document.getElementById('downloadAllPendingZipBtn');
    if (downloadAllPendingZipBtn) {
      downloadAllPendingZipBtn.addEventListener('click', () => {
        if (headerMorePopup) headerMorePopup.classList.remove('open');
        downloadAllActiveZip();
      });
    }

    if (batchInvestigateBtn) {
      batchInvestigateBtn.addEventListener('click', () => {
        if (headerMorePopup) headerMorePopup.classList.remove('open');
        batchTransitionToInvestigating();
      });
    }

    // Batch Actions Floating Toolbar
    const batchDownloadSelectedZipBtn = document.getElementById('batchDownloadSelectedZipBtn');
    if (batchDownloadSelectedZipBtn) {
      batchDownloadSelectedZipBtn.addEventListener('click', downloadSelectedZip);
    }

    const batchApplyStatusBtn = document.getElementById('batchApplyStatusBtn');
    if (batchApplyStatusBtn) {
      batchApplyStatusBtn.addEventListener('click', applyBatchStatus);
    }

    const batchAddNoteBtn = document.getElementById('batchAddNoteBtn');
    if (batchAddNoteBtn) {
      batchAddNoteBtn.addEventListener('click', applyBatchNote);
    }

    const batchClearSelectionBtn = document.getElementById('batchClearSelectionBtn');
    if (batchClearSelectionBtn) {
      batchClearSelectionBtn.addEventListener('click', clearBatchSelection);
    }

    const selectAllReportsCb = document.getElementById('selectAllReportsCb');
    if (selectAllReportsCb) {
      selectAllReportsCb.addEventListener('change', () => {
        const checked = selectAllReportsCb.checked;
        document.querySelectorAll('.report-select-cb').forEach(cb => { cb.checked = checked; });
        updateBatchActionBar();
      });
    }

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

    // Devices view listeners
    const refreshDevicesBtn = document.getElementById('refreshDevicesBtn');
    if (refreshDevicesBtn) {
      refreshDevicesBtn.addEventListener('click', () => loadDevices());
    }
    const deviceSearchInput = document.getElementById('deviceSearchInput');
    if (deviceSearchInput) {
      let searchTimeout = null;
      deviceSearchInput.addEventListener('input', () => {
        clearTimeout(searchTimeout);
        searchTimeout = setTimeout(() => loadDevices(), 300);
      });
    }

    // Analytics period buttons listeners
    document.querySelectorAll('.analytics-period-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const p = btn.getAttribute('data-period');
        if (p) loadAnalytics(p);
      });
    });

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

    document.addEventListener('click', (e) => {
      const quickBtn = e.target.closest('[data-quick-status]');
      if (quickBtn && currentReport) {
        const nextStatus = quickBtn.getAttribute('data-quick-status');
        if (detailStatusSelect) detailStatusSelect.value = nextStatus;
        saveReportOperations();
      }
    });

    const detailDownloadBtn = document.getElementById('detailDownloadJsonBtn');
    if (detailDownloadBtn) {
      detailDownloadBtn.addEventListener('click', () => {
        if (currentReport) downloadJsonFile(currentReport.report_id || currentReport.reportId);
      });
    }

    const detailCopyTechBtn = document.getElementById('detailCopyTechBtn');
    if (detailCopyTechBtn) {
      detailCopyTechBtn.addEventListener('click', () => {
        if (currentReport) copyTechnicalSummary(currentReport);
      });
    }

    const detailCopyAgBtn = document.getElementById('detailCopyAgBtn');
    if (detailCopyAgBtn) {
      detailCopyAgBtn.addEventListener('click', () => {
        if (currentReport) copyAntigravitySummary(currentReport);
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
      if (e.target && e.target.classList.contains('report-select-cb')) {
        updateBatchActionBar();
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
      } else if (action === 'quick-in-progress') {
        quickSetStatus('IN_PROGRESS', 'Взято в работу разработчиком');
      } else if (action === 'quick-fixed') {
        quickSetStatus('FIXED_PENDING_VERIFICATION', 'Исправлено в коде, ожидает верификации');
      } else if (action === 'quick-resolved') {
        quickSetStatus('RESOLVED', 'Проблема проверена и решена');
      } else if (action === 'copy-technical-summary') {
        copyTechnicalSummary(currentReport);
      } else if (action === 'copy-antigravity-summary') {
        copyAntigravitySummary(currentReport);
      } else if (action === 'copy-markdown') {
        copyGitHubMarkdown(currentReport);
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

  function handleDeepLink() {
    let reportId = null;
    const hash = window.location.hash;
    if (hash) {
      const match = hash.match(/report=([A-Za-z0-9_-]+)/i) || hash.match(/^#([A-Za-z0-9_-]+)$/);
      if (match && match[1].toUpperCase().startsWith('VOX-')) {
        reportId = match[1].toUpperCase();
      }
    }
    if (!reportId && window.location.search) {
      const params = new URLSearchParams(window.location.search);
      const q = params.get('report') || params.get('reportId');
      if (q && q.toUpperCase().startsWith('VOX-')) {
        reportId = q.toUpperCase();
      }
    }
    if (reportId) {
      openReport(reportId);
    }
  }

  function initAdminApp() {
    cacheDomElements();
    setupEventListeners();
    filterReportsTable();
    handleDeepLink();
    window.addEventListener('hashchange', handleDeepLink);
  }

  // Initialize on DOM Ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initAdminApp);
  } else {
    initAdminApp();
  }
})();
