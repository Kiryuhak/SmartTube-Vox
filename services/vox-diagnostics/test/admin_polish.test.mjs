import assert from 'node:assert/strict';
import test from 'node:test';
import { renderAdminHtml } from '../src/admin.mjs';
import { ADMIN_APP_JS } from '../src/admin_client.mjs';

test('Diagnostics Polish: HTML template contains design system tokens and new controls', () => {
  const html = renderAdminHtml([], {
    totalReports: 12,
    reports24h: 3,
    reports7d: 8,
    newCount: 2,
    inProgressCount: 1,
    resolvedCount: 7,
    knownIssueCount: 2,
    ignoredTestCount: 0,
    attentionCount: 3,
  });

  // Check top bar and live indicator
  assert.match(html, /class="live-dot"/);
  assert.match(html, /id="refreshButton"/);
  assert.match(html, /id="headerMoreBtn"/);
  assert.match(html, /id="purgeAllBtn"/);

  // Check 4 primary KPI cards + secondary strip
  assert.match(html, /data-metric="NEW"/);
  assert.match(html, /data-metric="IN_PROGRESS"/);
  assert.match(html, /data-metric="attention"/);
  assert.match(html, /data-metric="RESOLVED"/);
  assert.match(html, /class="kpi-strip"/);
  assert.match(html, /data-metric="24h"/);
  assert.match(html, /data-metric="7d"/);
  assert.match(html, /data-metric="all"/);

  // Check search with clear button & extra filters toggle
  assert.match(html, /id="searchClearBtn"/);
  assert.match(html, /id="toggleMoreFiltersBtn"/);
  assert.match(html, /id="toolbarExtra"/);
  assert.match(html, /id="resetFiltersBtn"/);
  assert.match(html, /id="activeFiltersBadge"/);

  // Check side drawer modal & tabs
  assert.match(html, /id="modalOverlay"/);
  assert.match(html, /data-modal-tab="overview"/);
  assert.match(html, /data-modal-tab="codecs"/);
  assert.match(html, /data-modal-tab="timeline"/);
  assert.match(html, /data-modal-tab="ops"/);
  assert.match(html, /data-modal-tab="raw"/);
});

test('Diagnostics Polish: Client JS supports keyboard navigation and filtering helpers', () => {
  assert.match(ADMIN_APP_JS, /initAdminApp/);
  assert.match(ADMIN_APP_JS, /filterReportsTable/);
  assert.match(ADMIN_APP_JS, /resetAllFilters/);
  assert.match(ADMIN_APP_JS, /switchMainTab/);
  assert.match(ADMIN_APP_JS, /switchModalTab/);
  assert.match(ADMIN_APP_JS, /openReport/);
  assert.match(ADMIN_APP_JS, /copyGitHubMarkdown/);
  assert.match(ADMIN_APP_JS, /downloadJsonFile/);
  assert.match(ADMIN_APP_JS, /copyRawJson/);
});
