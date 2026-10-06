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
    appVersion: '32.56-vox.7-dev',
    appVersionCode: 2446007,
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
    appVersion: '32.56-vox.7-dev',
    appVersionCode: 2446007,
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
    appVersion: '32.56-vox.7-dev',
    appVersionCode: 2446007,
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
    appVersion: '32.56-vox.7-dev',
    appVersionCode: 2446007,
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
    appVersion: '32.56-vox.7-dev',
    appVersionCode: 2446007,
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

  // 1. Verify "Все отчёты" tab is active by default
  const initialTabActive = await evalInPage(`
    document.getElementById('viewReports').style.display !== 'none' &&
    document.getElementById('tabBtnReports').classList.contains('active')
  `);
  console.log('[E2E Check 1] "Все отчёты" active by default:', initialTabActive);
  if (!initialTabActive) throw new Error('"Все отчёты" should be active by default');

  const rowsCount = await evalInPage(`document.querySelectorAll('#reportsTableBody tr[data-report-id]').length`);
  console.log('[E2E Check 1] Initial reports rows count:', rowsCount);
  if (rowsCount === 0) throw new Error('Reports table should have rows');

  await takeScreenshot('screenshot_1_reports_list.png');

  // 2. Click "Частые проблемы" tab
  console.log('[E2E] Switching to "Частые проблемы"...');
  await evalInPage(`document.getElementById('tabBtnIssues').click()`);
  await sleep(300);

  const issuesTabActive = await evalInPage(`
    document.getElementById('viewIssues').style.display !== 'none' &&
    document.getElementById('viewReports').style.display === 'none' &&
    document.getElementById('tabBtnIssues').classList.contains('active')
  `);
  console.log('[E2E Check 2] "Частые проблемы" tab switched:', issuesTabActive);
  if (!issuesTabActive) throw new Error('"Частые проблемы" tab should be active');

  await takeScreenshot('screenshot_2_issues_tab.png');

  // 3. Click "Сводка и метрики" tab
  console.log('[E2E] Switching to "Сводка и метрики"...');
  await evalInPage(`document.getElementById('tabBtnStats').click()`);
  await sleep(300);

  const statsTabActive = await evalInPage(`
    document.getElementById('viewStats').style.display !== 'none' &&
    document.getElementById('viewIssues').style.display === 'none' &&
    document.getElementById('tabBtnStats').classList.contains('active')
  `);
  console.log('[E2E Check 3] "Сводка и метрики" tab switched:', statsTabActive);
  if (!statsTabActive) throw new Error('"Сводка и метрики" tab should be active');

  await takeScreenshot('screenshot_3_metrics_tab.png');

  // 4. Switch back to "Все отчёты" and test Search for "VOX-A-9BB2D7"
  console.log('[E2E] Switching back to "Все отчёты" and testing search...');
  await evalInPage(`document.getElementById('tabBtnReports').click()`);
  await sleep(200);

  await evalInPage(`
    const input = document.getElementById('searchInput');
    input.value = 'VOX-A-9BB2D7';
    input.dispatchEvent(new Event('input', { bubbles: true }));
  `);
  await sleep(300);

  const searchResults = await evalInPage(`
    (() => {
      const rows = Array.from(document.querySelectorAll('#reportsTableBody tr[data-report-id]'));
      const visible = rows.filter(r => r.style.display !== 'none');
      return {
        total: rows.length,
        visibleCount: visible.length,
        visibleId: visible[0]?.getAttribute('data-report-id')
      };
    })()
  `);
  console.log('[E2E Check 4] Search results for VOX-A-9BB2D7:', searchResults);
  if (searchResults.visibleCount !== 1 || searchResults.visibleId !== 'VOX-A-9BB2D7') {
    throw new Error(`Search failed: expected 1 row with VOX-A-9BB2D7, got ${searchResults.visibleCount}`);
  }

  // 5. Test Filters: reset search, test platform filter
  console.log('[E2E] Testing platform and status filters...');
  await evalInPage(`
    document.getElementById('searchInput').value = '';
    const platSelect = document.getElementById('filterPlatform');
    platSelect.value = 'Android';
    platSelect.dispatchEvent(new Event('change', { bubbles: true }));
  `);
  await sleep(300);

  const platFilteredCount = await evalInPage(`
    Array.from(document.querySelectorAll('#reportsTableBody tr[data-report-id]'))
      .filter(r => r.style.display !== 'none').length
  `);
  console.log('[E2E Check 5] Filtered by platform Android TV count:', platFilteredCount);
  if (platFilteredCount === 0) throw new Error('Platform filter should return Android reports');

  // Reset filters
  await evalInPage(`
    document.getElementById('filterPlatform').value = '';
    document.getElementById('filterPlatform').dispatchEvent(new Event('change', { bubbles: true }));
  `);
  await sleep(200);

  // 6. Test "Открыть" on VOX-A-9BB2D7
  console.log('[E2E] Clicking "Открыть" on report VOX-A-9BB2D7...');
  await evalInPage(`
    const btn = document.querySelector('button[data-report-id="VOX-A-9BB2D7"]');
    if (!btn) throw new Error('Button for VOX-A-9BB2D7 not found');
    btn.click();
  `);
  await sleep(800);

  const modalState = await evalInPage(`
    (() => {
      const overlay = document.getElementById('modalOverlay');
      const title = document.getElementById('modalTitle')?.textContent;
      const overviewVisible = document.getElementById('mSection_overview')?.style.display !== 'none';
      const hasContent = document.getElementById('modalContent')?.innerHTML?.includes('VOX-A-9BB2D7') ||
                         document.getElementById('modalContent')?.innerHTML?.includes('TCL') ||
                         document.getElementById('modalContent')?.innerHTML?.includes('Android');
      return {
        overlayDisplay: overlay.style.display,
        title,
        overviewVisible,
        hasContent
      };
    })()
  `);
  console.log('[E2E Check 6] Modal opened state for VOX-A-9BB2D7:', modalState);
  if (modalState.overlayDisplay !== 'flex') throw new Error('Modal overlay should be flex');

  await takeScreenshot('screenshot_4_report_detail.png');

  // 7. Test Modal tabs switching: codecs, timeline, ops, raw
  console.log('[E2E] Testing modal sub-tabs...');
  await evalInPage(`document.getElementById('mTabBtnCodecs').click()`);
  await sleep(200);
  const codecsVisible = await evalInPage(`document.getElementById('mSection_codecs').style.display !== 'none'`);
  if (!codecsVisible) throw new Error('Codecs section should be visible');

  await evalInPage(`document.getElementById('mTabBtnTimeline').click()`);
  await sleep(200);
  const timelineVisible = await evalInPage(`document.getElementById('mSection_timeline').style.display !== 'none'`);
  if (!timelineVisible) throw new Error('Timeline section should be visible');

  await evalInPage(`document.getElementById('mTabBtnOps').click()`);
  await sleep(200);
  const opsVisible = await evalInPage(`document.getElementById('mSection_ops').style.display !== 'none'`);
  if (!opsVisible) throw new Error('Ops section should be visible');

  await evalInPage(`document.getElementById('mTabBtnRaw').click()`);
  await sleep(200);
  const rawVisible = await evalInPage(`document.getElementById('mSection_raw').style.display !== 'none'`);
  if (!rawVisible) throw new Error('Raw section should be visible');

  // 8. Close Modal via Escape or close button
  console.log('[E2E] Closing modal...');
  await evalInPage(`document.getElementById('modalCloseBtn').click()`);
  await sleep(300);
  const modalClosed = await evalInPage(`document.getElementById('modalOverlay').style.display === 'none'`);
  console.log('[E2E Check 8] Modal closed:', modalClosed);
  if (!modalClosed) throw new Error('Modal should be closed');

  // 9. Test "Обновить" (Refresh button)
  console.log('[E2E] Testing "Обновить" button...');
  await evalInPage(`document.getElementById('refreshButton').click()`);
  await sleep(800);
  const refreshWorking = await evalInPage(`
    document.getElementById('refreshButton').textContent.includes('Обновить') &&
    document.querySelectorAll('#reportsTableBody tr[data-report-id]').length > 0
  `);
  console.log('[E2E Check 9] Refresh completed:', refreshWorking);
  if (!refreshWorking) throw new Error('Refresh button failed');

  // 10. Check Console and Network errors
  console.log('[E2E Check 10] Console Errors count:', consoleErrors.length);
  if (consoleErrors.length > 0) {
    console.error('Console errors detected:', consoleErrors);
    throw new Error(`Console errors found: ${consoleErrors.join('; ')}`);
  }

  console.log('[E2E Check 10] Failed network requests count:', failedRequests.length);
  if (failedRequests.length > 0) {
    console.error('Failed network requests:', failedRequests);
    throw new Error(`Network 404/500 requests found: ${JSON.stringify(failedRequests)}`);
  }

  console.log('\n==================================================');
  console.log('ALL BROWSER E2E TESTS PASSED WITH ZERO CONSOLE ERRORS!');
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
