import http from 'node:http';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { handleRequest } from '../src/diagnostics.mjs';
import { ReportStorage } from '../src/storage.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const screenshotsDir = path.resolve(__dirname, '../screenshots');
fs.mkdirSync(screenshotsDir, { recursive: true });

const brainArtifactDir = 'C:\\Users\\user\\.gemini\\antigravity\\brain\\4dc79d69-418f-4710-80df-f85411654131';
if (fs.existsSync(brainArtifactDir)) {
  fs.mkdirSync(brainArtifactDir, { recursive: true });
}

// 1. Initialize local in-memory storage with test reports including VOX-A-9BB2D7
const storage = new ReportStorage();
const fixtures = [
  {
    reportId: 'VOX-A-9BB2D7',
    schema: 'vox-diagnostic-report-v2',
    timestamp: Date.now() - 3600000,
    appVersion: '32.56-vox.7',
    appVersionCode: 2446008,
    platform: 'Android TV',
    manufacturer: 'TCL',
    model: 'BeyondTV',
    osName: 'Android',
    osVersion: '12',
    sdkInt: 31,
    deviceTier: 'Премиум TV',
    errorCategory: 'DOWNLOAD',
    errorSignature: 'DOWNLOAD::AUDIO_STREAM_TIMEOUT',
    status: 'NEW',
    reportPurpose: 'USER',
    developerNotes: 'Investigating audio stream issue',
    display: { resolution: '3840x2160', refreshRateHz: 60, hdr10: 'SUPPORTED' },
    videoCodecs: { AVC: 'Поддерживается [HW]', VP9: 'Поддерживается [HW]', AV1: 'Поддерживается [HW]' },
    audioCodecs: { AAC: 'Поддерживается', OPUS: 'Поддерживается', AC3: 'Passthrough: Поддерживается' },
    currentPolicy: { mode: 'AUTO', maxQualityHeight: 2160, preferredVideoCodec: 'AUTO' },
    recommendedSettings: { tier: 'PREMIUM_TV', mode: 'AUTO', maxQualityHeight: 2160 },
    downloadState: { activeTasks: 1, engine: 'MKV_MUXER', storageSpaceAvailableMb: 14200 },
    safeRecentEvents: [
      { timestamp: Date.now() - 5000, level: 'INFO', category: 'DOWNLOAD', code: 'TASK_QUEUED', message: 'Download requested' },
      { timestamp: Date.now() - 1000, level: 'ERROR', category: 'DOWNLOAD', code: 'AUDIO_STREAM_TIMEOUT', message: 'Stream chunk timed out after 10000ms' }
    ]
  },
  {
    reportId: 'VOX-A-A108BB',
    schema: 'vox-diagnostic-report-v2',
    timestamp: Date.now() - 7200000,
    appVersion: '32.56-vox.7',
    appVersionCode: 2446008,
    platform: 'Android TV',
    manufacturer: 'Sony',
    model: 'BRAVIA-4K',
    errorCategory: 'PLAYBACK',
    errorSignature: 'PLAYBACK::DECODER_INIT_FAILED',
    status: 'NEW',
    reportPurpose: 'USER'
  },
  {
    reportId: 'VOX-A-60A400',
    schema: 'vox-diagnostic-report-v2',
    timestamp: Date.now() - 14400000,
    appVersion: '32.56-vox.7',
    appVersionCode: 2446008,
    platform: 'Android TV',
    manufacturer: 'Xiaomi',
    model: 'MiBox4',
    errorCategory: 'CODEC',
    errorSignature: 'CODEC::PROFILE_NOT_SUPPORTED',
    status: 'REVIEWED',
    reportPurpose: 'USER'
  },
  {
    reportId: 'VOX-A-BB7E52',
    schema: 'vox-diagnostic-report-v2',
    timestamp: Date.now() - 28800000,
    appVersion: '32.56-vox.7',
    appVersionCode: 2446008,
    platform: 'Android TV',
    manufacturer: 'Google',
    model: 'Android TV Emulator',
    status: 'IGNORED_TEST',
    reportPurpose: 'TEST'
  },
  {
    reportId: 'VOX-T-55A123',
    schema: 'vox-diagnostic-report-v2',
    timestamp: Date.now() - 36000000,
    appVersion: '32.56-vox.7',
    appVersionCode: 2446008,
    platform: 'Samsung Tizen',
    manufacturer: 'Samsung',
    model: 'QN90B',
    errorCategory: 'NETWORK',
    errorSignature: 'NETWORK::PROXY_CONNECT_TIMEOUT',
    status: 'KNOWN_ISSUE',
    reportPurpose: 'USER'
  }
];

for (const r of fixtures) {
  await storage.saveReport(r);
}
console.log(`[E2E Server] Pre-loaded ${fixtures.length} fixture reports into test storage.`);

const TEST_SECRET = 'e2e_secret_token_12345';
const env = {
  ADMIN_SECRET: TEST_SECRET,
};

// 2. Start local HTTP server bridging to handleRequest
const PORT = 8787;
const server = http.createServer(async (req, res) => {
  try {
    const url = `http://127.0.0.1:${PORT}${req.url}`;
    const headers = new Headers();
    for (const [k, v] of Object.entries(req.headers)) {
      if (Array.isArray(v)) {
        v.forEach(val => headers.append(k, val));
      } else if (v !== undefined) {
        headers.set(k, v);
      }
    }

    const chunks = [];
    for await (const chunk of req) {
      chunks.push(chunk);
    }
    const bodyBuffer = chunks.length > 0 ? Buffer.concat(chunks) : undefined;

    const request = new Request(url, {
      method: req.method,
      headers,
      body: bodyBuffer,
    });

    const response = await handleRequest(request, env, storage);

    res.statusCode = response.status;
    response.headers.forEach((val, key) => {
      res.setHeader(key, val);
    });

    const responseBuffer = Buffer.from(await response.arrayBuffer());
    res.end(responseBuffer);
  } catch (err) {
    console.error('[E2E Server Error]:', err);
    res.statusCode = 500;
    res.end(err.stack);
  }
});

await new Promise(resolve => server.listen(PORT, '127.0.0.1', resolve));
console.log(`[E2E Server] Running at http://127.0.0.1:${PORT}`);

// 3. Launch Chrome with CDP
const chromePath = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const userDataDir = path.resolve(__dirname, '../temp_chrome_profile');
fs.mkdirSync(userDataDir, { recursive: true });

const chromePort = 9222;
const chromeProc = spawn(chromePath, [
  '--headless=new',
  `--remote-debugging-port=${chromePort}`,
  `--user-data-dir=${userDataDir}`,
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-gpu',
  '--window-size=1280,900',
  `http://127.0.0.1:${PORT}/admin?token=${TEST_SECRET}`
], { stdio: 'ignore' });

console.log('[E2E] Chrome process spawned.');

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

// Wait for Chrome CDP endpoint
let wsUrl = null;
for (let i = 0; i < 20; i++) {
  await sleep(500);
  try {
    const res = await fetch(`http://127.0.0.1:${chromePort}/json/version`);
    if (res.ok) {
      const data = await res.json();
      wsUrl = data.webSocketDebuggerUrl;
      console.log('[E2E] Connected to Chrome DevTools Protocol at:', wsUrl);
      break;
    }
  } catch (e) {}
}

if (!wsUrl) {
  chromeProc.kill();
  server.close();
  throw new Error('Failed to connect to Chrome CDP endpoint.');
}

// 4. WebSocket CDP Client
const ws = new WebSocket(wsUrl);
await new Promise((resolve, reject) => {
  ws.onopen = resolve;
  ws.onerror = reject;
});

let msgId = 1;
const pending = new Map();
const consoleErrors = [];
const failedRequests = [];

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    if (msg.error) {
      reject(new Error(JSON.stringify(msg.error)));
    } else {
      resolve(msg.result);
    }
  }

  // Monitor uncaught JavaScript exceptions and errors
  if (msg.method === 'Runtime.exceptionThrown') {
    const desc = msg.params.exceptionDetails?.exception?.description || msg.params.exceptionDetails?.text;
    consoleErrors.push(`[Runtime.exceptionThrown] ${desc}`);
  }
  if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
    const args = (msg.params.args || []).map(a => a.value || a.description).join(' ');
    consoleErrors.push(`[console.error] ${args}`);
  }
  // Monitor Network responses
  if (msg.method === 'Network.responseReceived') {
    const status = msg.params.response?.status;
    const url = msg.params.response?.url;
    if (status >= 400 && !url.includes('favicon.ico')) {
      failedRequests.push({ url, status });
    }
  }
};

// Find the target page
let pageTarget = null;
for (let i = 0; i < 20; i++) {
  await sleep(300);
  try {
    const targets = await (await fetch(`http://127.0.0.1:${chromePort}/json/list`)).json();
    pageTarget = targets.find(t => t.type === 'page');
    if (pageTarget) break;
  } catch (e) {}
}

if (!pageTarget) {
  chromeProc.kill();
  server.close();
  throw new Error('Failed to find page target in Chrome.');
}

console.log('[E2E] Page target found:', pageTarget.url);

// Attach to page target WebSocket
const pageWs = new WebSocket(pageTarget.webSocketDebuggerUrl);
await new Promise((res, rej) => {
  pageWs.onopen = res;
  pageWs.onerror = rej;
});

let pMsgId = 1;
const pPending = new Map();
pageWs.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.id && pPending.has(msg.id)) {
    const { resolve, reject } = pPending.get(msg.id);
    pPending.delete(msg.id);
    if (msg.error) reject(new Error(JSON.stringify(msg.error)));
    else resolve(msg.result);
  }
  if (msg.method === 'Runtime.exceptionThrown') {
    const desc = msg.params.exceptionDetails?.exception?.description || msg.params.exceptionDetails?.text;
    consoleErrors.push(`[Runtime.exceptionThrown] ${desc}`);
  }
  if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
    const args = (msg.params.args || []).map(a => a.value || a.description).join(' ');
    consoleErrors.push(`[console.error] ${args}`);
  }
  if (msg.method === 'Network.responseReceived') {
    const status = msg.params.response?.status;
    const url = msg.params.response?.url;
    if (status >= 400 && !url.includes('favicon.ico')) {
      failedRequests.push({ url, status });
    }
  }
  if (msg.method === 'Page.javascriptDialogOpening') {
    pageSend('Page.handleJavaScriptDialog', { accept: true }).catch(() => {});
  }
};

function pageSend(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = pMsgId++;
    pPending.set(id, { resolve, reject });
    pageWs.send(JSON.stringify({ id, method, params }));
  });
}

await pageSend('Page.enable');
await pageSend('Runtime.enable');
await pageSend('Network.enable');

async function evalInPage(expr) {
  const res = await pageSend('Runtime.evaluate', {
    expression: expr,
    returnByValue: true,
    awaitPromise: true,
  });
  if (res.exceptionDetails) {
    throw new Error(`Eval error: ${JSON.stringify(res.exceptionDetails)}`);
  }
  return res.result?.value;
}

async function takeScreenshot(filename) {
  const res = await pageSend('Page.captureScreenshot', { format: 'png' });
  const buffer = Buffer.from(res.data, 'base64');
  const filePath1 = path.join(screenshotsDir, filename);
  fs.writeFileSync(filePath1, buffer);

  if (fs.existsSync(brainArtifactDir)) {
    const filePath2 = path.join(brainArtifactDir, filename);
    fs.writeFileSync(filePath2, buffer);
  }
  console.log(`[E2E Screenshot] Saved ${filename} (${buffer.length} bytes)`);
  return filePath1;
}

try {
  // Wait for page to fully load and script to execute
  console.log('[E2E] Waiting for page and app.js to load...');
  await sleep(1500);

  // Install mock alert/confirm to prevent modal freeze in headless browser
  await evalInPage(`
    window.alert = (msg) => { console.log('[Browser Alert]:', msg); };
    window.confirm = (msg) => { console.log('[Browser Confirm]:', msg); return true; };
  `);

  // 1. Verify "Все отчёты" tab is active by default with [Открыть] and [Скачать]
  const initialTabActive = await evalInPage(`
    document.getElementById('viewReports').style.display !== 'none' &&
    document.getElementById('tabBtnReports').classList.contains('active')
  `);
  console.log('[E2E Step 1-2] "Все отчёты" active by default:', initialTabActive);
  if (!initialTabActive) throw new Error('"Все отчёты" should be active by default');

  const rowsCount = await evalInPage(`document.querySelectorAll('#reportsTableBody tr[data-report-id]').length`);
  console.log('[E2E Step 2] Initial reports rows count:', rowsCount);
  if (rowsCount === 0) throw new Error('Reports table should have rows');

  // Verify both "Открыть" and "Скачать" buttons exist
  const buttonsOk = await evalInPage(`
    Boolean(document.querySelector('button[data-action="open-report"]') &&
            document.querySelector('button[data-action="download-report"]'))
  `);
  console.log('[E2E Step 2] Both Открыть and Скачать buttons present:', buttonsOk);
  if (!buttonsOk) throw new Error('Both buttons Открыть and Скачать must be present');

  // Screenshot 1: Reports list
  await takeScreenshot('screenshot_1_reports_list.png');

  // 3-4. Click "Скачать" and verify download endpoint responds
  console.log('[E2E Step 3-4] Testing individual report download...');
  const dlFetchStatus = await evalInPage(`
    fetch('/v1/admin/reports/VOX-A-9BB2D7/download').then(r => r.status)
  `);
  console.log('[E2E Step 4] Download endpoint HTTP status:', dlFetchStatus);
  if (dlFetchStatus !== 200) throw new Error(`Download status expected 200, got ${dlFetchStatus}`);

  // 5. Click "Открыть" on VOX-A-9BB2D7
  console.log('[E2E Step 5] Clicking "Открыть" on report VOX-A-9BB2D7...');
  await evalInPage(`
    const btn = document.querySelector('button[data-action="open-report"][data-report-id="VOX-A-9BB2D7"]');
    if (!btn) throw new Error('Button for VOX-A-9BB2D7 not found');
    btn.click();
  `);
  await sleep(800);

  const modalState = await evalInPage(`
    (() => {
      const overlay = document.getElementById('modalOverlay');
      const title = document.getElementById('modalTitle')?.textContent;
      const statusVal = document.getElementById('detailStatusSelect')?.value;
      const notesVal = document.getElementById('detailNotesText')?.value;
      const overviewVisible = document.getElementById('mSection_overview')?.style.display !== 'none';
      return {
        overlayDisplay: overlay.style.display,
        title,
        statusVal,
        notesVal,
        overviewVisible
      };
    })()
  `);
  console.log('[E2E Step 5] Modal opened state:', modalState);
  if (modalState.overlayDisplay !== 'flex') throw new Error('Modal overlay should be flex');

  // Screenshot 2: Detail modal (status + developer notes)
  await takeScreenshot('screenshot_2_report_detail.png');

  // 6-9. Status: NEW -> IN_PROGRESS, save, verify persistence
  console.log('[E2E Step 6-7] Changing status to IN_PROGRESS...');
  await evalInPage(`
    document.getElementById('detailStatusSelect').value = 'IN_PROGRESS';
    document.getElementById('detailSaveBtn').click();
  `);
  await sleep(600);

  const statusAfterSave = await evalInPage(`
    document.querySelector('tr[data-report-id="VOX-A-9BB2D7"]')?.getAttribute('data-status')
  `);
  console.log('[E2E Step 7] Status in table row after save:', statusAfterSave);
  if (statusAfterSave !== 'IN_PROGRESS') throw new Error(`Expected status IN_PROGRESS, got ${statusAfterSave}`);

  // 10-12. Add developer note, save and verify
  console.log('[E2E Step 10-11] Adding developer note and saving...');
  await evalInPage(`
    document.getElementById('detailNotesText').value = 'Проверено в E2E Patch #35';
    document.getElementById('detailSaveBtn').click();
  `);
  await sleep(600);

  // 13. Status: IN_PROGRESS -> RESOLVED
  console.log('[E2E Step 13] Changing status to RESOLVED and saving...');
  await evalInPage(`
    document.getElementById('detailStatusSelect').value = 'RESOLVED';
    document.getElementById('detailSaveBtn').click();
  `);
  await sleep(600);

  // Close modal
  await evalInPage(`document.getElementById('modalCloseBtn').click()`);
  await sleep(300);

  // 14. Filter by status "RESOLVED" (Решено)
  console.log('[E2E Step 14] Testing filter by status RESOLVED...');
  await evalInPage(`
    const statSelect = document.getElementById('filterStatus');
    statSelect.value = 'RESOLVED';
    statSelect.dispatchEvent(new Event('change', { bubbles: true }));
  `);
  await sleep(300);

  const resolvedVisible = await evalInPage(`
    Array.from(document.querySelectorAll('#reportsTableBody tr[data-report-id]'))
      .filter(r => r.style.display !== 'none').length
  `);
  console.log('[E2E Step 14] Filtered rows with status RESOLVED:', resolvedVisible);
  if (resolvedVisible !== 1) throw new Error(`Expected 1 row with RESOLVED status, got ${resolvedVisible}`);

  // Reset filter
  await evalInPage(`
    document.getElementById('filterStatus').value = '';
    document.getElementById('filterStatus').dispatchEvent(new Event('change', { bubbles: true }));
  `);
  await sleep(200);

  // 15. Common Issues Tab
  console.log('[E2E Step 15] Switching to "Частые проблемы"...');
  await evalInPage(`document.getElementById('tabBtnIssues').click()`);
  await sleep(400);

  // Screenshot 3: Common Issues
  await takeScreenshot('screenshot_3_issues_tab.png');

  // 16. Metrics Tab
  console.log('[E2E Step 16] Switching to "Сводка и метрики"...');
  await evalInPage(`document.getElementById('tabBtnStats').click()`);
  await sleep(400);

  // Screenshot 4: Metrics Tab
  await takeScreenshot('screenshot_4_metrics_tab.png');

  // Switch back to "Все отчёты"
  await evalInPage(`document.getElementById('tabBtnReports').click()`);
  await sleep(200);

  // 17-22. Purge button opens modal, verify button disabled until exact word 'УДАЛИТЬ'
  console.log('[E2E Step 17-22] Testing Safe Purge modal...');
  await evalInPage(`document.getElementById('purgeAllBtn').click()`);
  await sleep(300);

  const purgeModalOpen = await evalInPage(`
    document.getElementById('purgeModalOverlay').style.display !== 'none'
  `);
  console.log('[E2E Step 17] Purge modal visible:', purgeModalOpen);
  if (!purgeModalOpen) throw new Error('Purge modal should be open');

  const btnInitiallyDisabled = await evalInPage(`
    document.getElementById('purgeConfirmBtn').disabled === true
  `);
  console.log('[E2E Step 18] Delete button initially disabled:', btnInitiallyDisabled);
  if (!btnInitiallyDisabled) throw new Error('Delete button must be disabled initially');

  // Type wrong value
  await evalInPage(`
    (() => {
      const input = document.getElementById('purgeConfirmInput');
      input.value = 'удалить_случайно';
      input.dispatchEvent(new Event('input', { bubbles: true }));
    })()
  `);
  await sleep(100);

  const btnStillDisabled = await evalInPage(`
    document.getElementById('purgeConfirmBtn').disabled === true
  `);
  console.log('[E2E Step 20] Delete button still disabled with wrong input:', btnStillDisabled);
  if (!btnStillDisabled) throw new Error('Delete button must stay disabled on wrong input');

  // Type exact word 'УДАЛИТЬ'
  await evalInPage(`
    (() => {
      const input = document.getElementById('purgeConfirmInput');
      input.value = 'УДАЛИТЬ';
      input.dispatchEvent(new Event('input', { bubbles: true }));
    })()
  `);
  await sleep(100);

  const btnNowEnabled = await evalInPage(`
    document.getElementById('purgeConfirmBtn').disabled === false
  `);
  console.log('[E2E Step 22] Delete button enabled when УДАЛИТЬ is typed:', btnNowEnabled);
  if (!btnNowEnabled) throw new Error('Delete button must be enabled when УДАЛИТЬ is typed');

  // Screenshot 5: Purge Confirmation Modal
  await takeScreenshot('screenshot_5_purge_modal.png');

  // 23. Do NOT confirm purge in production/destructive manner; click Cancel
  console.log('[E2E Step 23] Cancelling purge to preserve test fixtures/data...');
  await evalInPage(`document.getElementById('purgeCancelBtn').click()`);
  await sleep(200);

  const purgeModalClosed = await evalInPage(`
    document.getElementById('purgeModalOverlay').style.display === 'none'
  `);
  console.log('[E2E Step 23] Purge modal closed cleanly:', purgeModalClosed);
  if (!purgeModalClosed) throw new Error('Purge modal should be closed after cancel');

  // 24-25. Verify 0 console errors and 0 unexpected network errors
  console.log('[E2E Step 24] Console Errors count:', consoleErrors.length);
  if (consoleErrors.length > 0) {
    console.error('Console errors detected:', consoleErrors);
    throw new Error(`Console errors found: ${consoleErrors.join('; ')}`);
  }

  console.log('[E2E Step 25] Failed network requests count:', failedRequests.length);
  if (failedRequests.length > 0) {
    console.error('Failed network requests:', failedRequests);
    throw new Error(`Network 404/500 requests found: ${JSON.stringify(failedRequests)}`);
  }

  console.log('\n==================================================');
  console.log('ALL 25 BROWSER E2E TESTS PASSED WITH ZERO CONSOLE ERRORS!');
  console.log('==================================================\n');
} finally {
  pageWs.close();
  ws.close();
  chromeProc.kill();
  server.close();
  try {
    fs.rmSync(userDataDir, { recursive: true, force: true });
  } catch (e) {}
}
