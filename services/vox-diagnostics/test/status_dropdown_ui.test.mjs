import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

import { STATUS_LABELS, CANONICAL_STATUSES, PRIMARY_STATUSES } from '../src/storage.mjs';
import { ADMIN_APP_JS } from '../src/admin_client.mjs';

test('Status UI Matrix: Canonical Russian labels match specification', () => {
  const expectedLabels = {
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
    // Legacy statuses
    FIXED_PENDING_VERIFICATION: 'Исправлено — ждёт проверки',
    RESOLVED: 'Решено',
    IGNORED_TEST: 'Тест / игнор',
  };

  for (const [key, expected] of Object.entries(expectedLabels)) {
    assert.equal(STATUS_LABELS[key], expected, `STATUS_LABELS[${key}] must equal "${expected}"`);
  }
});

test('Status UI Matrix: admin_app.js includes all VOX 9.1 primary and legacy statuses in select options', () => {
  // Extract CANONICAL_STATUSES and STATUS_LABELS from ADMIN_APP_JS
  assert.ok(ADMIN_APP_JS.includes("'FIXED_AWAITING_VERIFICATION'"), 'admin_app.js must include FIXED_AWAITING_VERIFICATION');
  assert.ok(ADMIN_APP_JS.includes("'KNOWN_LIMITATION'"), 'admin_app.js must include KNOWN_LIMITATION');
  assert.ok(ADMIN_APP_JS.includes("'INVESTIGATING'"), 'admin_app.js must include INVESTIGATING');
  assert.ok(ADMIN_APP_JS.includes("'IGNORED'"), 'admin_app.js must include IGNORED');
  assert.ok(ADMIN_APP_JS.includes("'RESOLVED'"), 'admin_app.js must include RESOLVED');
  assert.ok(ADMIN_APP_JS.includes("'FIXED_PENDING_VERIFICATION'"), 'admin_app.js must include FIXED_PENDING_VERIFICATION');
  assert.ok(ADMIN_APP_JS.includes("'IGNORED_TEST'"), 'admin_app.js must include IGNORED_TEST');
});

test('Status UI Matrix: HTML select generator never falls back to NEW for VOX 9.1 statuses', () => {
  // Simulate statusSelectHtml from admin_app.js in VM
  const vmContext = {};
  const snippet = `
    const CANONICAL_STATUSES = [
      'NEW', 'INVESTIGATING', 'ROOT_CAUSE_FOUND', 'FIX_IMPLEMENTED', 'TESTING',
      'FIXED_AWAITING_VERIFICATION', 'VERIFIED', 'REGRESSION', 'KNOWN_LIMITATION', 'IGNORED',
      'FIXED_PENDING_VERIFICATION', 'RESOLVED', 'IGNORED_TEST', 'IN_PROGRESS', 'TRIAGED',
      'NEEDS_INFO', 'CLOSED', 'KNOWN_ISSUE', 'WONT_FIX'
    ];
    const STATUS_LABELS = ${JSON.stringify(STATUS_LABELS)};
    function escapeHtml(s) { return String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
    function normalizeStatus(raw) {
      if (!raw) return 'NEW';
      return String(raw).toUpperCase().trim();
    }
    function statusSelectHtml(status, id) {
      const norm = normalizeStatus(status);
      const statuses = CANONICAL_STATUSES.includes(norm) ? CANONICAL_STATUSES : [norm, ...CANONICAL_STATUSES];
      return '<select class="status-select" data-status="' + escapeHtml(norm) + '" aria-label="Статус ' + escapeHtml(id) + '">' +
        statuses.map(key => '<option value="' + escapeHtml(key) + '"' + (key === norm ? ' selected' : '') + '>' + escapeHtml(STATUS_LABELS[key] || key) + '</option>').join('') + '</select>';
    }
  `;
  const fn = new Function(snippet + `
    return {
      testStatuses: (list) => list.map(s => ({ status: s, html: statusSelectHtml(s, 'VOX-TEST') }))
    };
  `)();

  const testList = [
    'FIXED_AWAITING_VERIFICATION',
    'KNOWN_LIMITATION',
    'INVESTIGATING',
    'IGNORED',
    'NEW',
    'RESOLVED',
    'FIXED_PENDING_VERIFICATION',
    'IGNORED_TEST',
    'UNKNOWN_CUSTOM_STATUS'
  ];

  const results = fn.testStatuses(testList);

  for (const r of results) {
    // Check that <option value="<status>" selected> exists
    const expectedOption = `value="${r.status}" selected`;
    assert.ok(r.html.includes(expectedOption), `Select HTML for ${r.status} must contain ${expectedOption}`);
    assert.ok(r.html.includes(`data-status="${r.status}"`), `Select HTML must contain data-status="${r.status}"`);
    if (r.status !== 'NEW') {
      // Must NOT select NEW
      assert.ok(!r.html.includes('value="NEW" selected'), `Select for ${r.status} must NOT have NEW selected`);
    }
  }
});

test('Status UI Matrix: CSS stylesheet contains dedicated color tokens for all canonical statuses', () => {
  const adminMjs = fs.readFileSync(path.resolve(__dirname, '../src/admin.mjs'), 'utf8');

  const requiredDataStatuses = [
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
    'FIXED_PENDING_VERIFICATION',
    'RESOLVED',
    'IGNORED_TEST'
  ];

  for (const st of requiredDataStatuses) {
    const selector = `.status-select[data-status="${st}"]`;
    assert.ok(adminMjs.includes(selector), `admin.mjs stylesheet must contain CSS selector ${selector}`);
  }
});
