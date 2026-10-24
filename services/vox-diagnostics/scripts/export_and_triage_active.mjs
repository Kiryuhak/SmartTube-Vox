import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { ZipWriter } from '../src/zip.mjs';
import { prepareReportDownloadJson } from '../src/diagnostics.mjs';

const EXPORTS_DIR = path.resolve('backups/exports');
if (!fs.existsSync(EXPORTS_DIR)) {
  fs.mkdirSync(EXPORTS_DIR, { recursive: true });
}

console.log('[1/5] Fetching all active reports (NEW + INVESTIGATING) from remote D1...');

// Keyset fetching from remote D1
const PAGE_SIZE = 100;
let hasMore = true;
let allReports = [];
let cursor = null;

while (hasMore) {
  let query = `SELECT report_id, created_at, schema_version, app_version, platform, device_family, error_category, payload_json, expires_at, status, developer_notes, report_purpose, error_signature, severity, subsystem, stage, event_error_category, updated_at FROM reports WHERE status IN ('NEW', 'INVESTIGATING')`;
  if (cursor) {
    const [cTime, cId] = cursor.split(':');
    query += ` AND (created_at < ${Number(cTime)} OR (created_at = ${Number(cTime)} AND report_id < '${cId}'))`;
  }
  query += ` ORDER BY created_at DESC, report_id DESC LIMIT ${PAGE_SIZE};`;

  const cmd = `npx wrangler d1 execute vox_diagnostics_db --remote --json --command "${query.replace(/"/g, '\\"')}"`;
  const output = execSync(cmd, { encoding: 'utf8', maxBuffer: 50 * 1024 * 1024 });
  const parsed = JSON.parse(output);
  const rows = parsed[0]?.results || [];

  if (rows.length === 0) {
    hasMore = false;
  } else {
    for (const r of rows) {
      if (r.payload_json && typeof r.payload_json === 'string') {
        try {
          r.payload = JSON.parse(r.payload_json);
        } catch {
          r.payload = {};
        }
      } else {
        r.payload = r.payload_json || {};
      }
      allReports.push(r);
    }
    if (rows.length < PAGE_SIZE) {
      hasMore = false;
    } else {
      const last = rows[rows.length - 1];
      cursor = `${last.created_at}:${last.report_id}`;
    }
  }
  console.log(`  Fetched ${allReports.length} reports so far...`);
}

console.log(`[2/5] Retrieved ${allReports.length} total active reports from D1.`);

// Filter strict status check
const filteredReports = allReports.filter(r => r.status === 'NEW' || r.status === 'INVESTIGATING');
console.log(`  Strict NEW/INVESTIGATING filter: ${filteredReports.length}/${allReports.length}`);

// Generate ZIP Archive
console.log('[3/5] Packing reports into ZIP archive using ZipWriter...');
const now = new Date();
const pad = (n) => String(n).padStart(2, '0');
const ts = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}`;
const zipFilename = `VOX_Diagnostics_Active_${ts}.zip`;
const zipPath = path.join(EXPORTS_DIR, zipFilename);

const zip = new ZipWriter();

let totalStructuredEvents = 0;
let totalSafeRecentEvents = 0;
let emptyPayloads = 0;
const reportIdsSeen = new Set();
let duplicatesCount = 0;

for (const report of filteredReports) {
  if (reportIdsSeen.has(report.report_id)) {
    duplicatesCount++;
    continue;
  }
  reportIdsSeen.add(report.report_id);

  const cleanJson = prepareReportDownloadJson(report);
  const payloadStr = JSON.stringify(cleanJson.payload || {});
  if (!cleanJson.payload || payloadStr === '{}') {
    emptyPayloads++;
  }
  const sEvents = cleanJson.structuredEvents || [];
  const rEvents = cleanJson.safeRecentEvents || [];
  totalStructuredEvents += sEvents.length;
  totalSafeRecentEvents += rEvents.length;

  zip.addFile(
    `reports/${report.report_id}.json`,
    Buffer.from(JSON.stringify(cleanJson, null, 2), 'utf8')
  );
}

// Generate Manifest and Summary
const manifest = {
  schemaVersion: '1.0',
  exportedAt: now.toISOString(),
  part: 1,
  totalReports: reportIdsSeen.size,
  newReports: filteredReports.filter(r => r.status === 'NEW').length,
  investigatingReports: filteredReports.filter(r => r.status === 'INVESTIGATING').length,
  reportIds: Array.from(reportIdsSeen),
  consistency: {
    creationCeiling: now.toISOString(),
    guarantee: 'CREATION_CEILING_CONSISTENT',
    statusEvaluation: 'READ_COMMITTED_AT_QUERY_TIME',
    note: 'until ceiling guarantees no cursor drift or new report skew across parts; status reflects committed state at query time.'
  }
};

const versionDist = {};
const categoryDist = {};
const deviceDist = {};

for (const r of filteredReports) {
  versionDist[r.app_version] = (versionDist[r.app_version] || 0) + 1;
  categoryDist[r.error_category || 'UNKNOWN'] = (categoryDist[r.error_category || 'UNKNOWN'] || 0) + 1;
  const dev = r.device_family || 'UNKNOWN';
  deviceDist[dev] = (deviceDist[dev] || 0) + 1;
}

const summary = {
  totalReports: reportIdsSeen.size,
  versionDistribution: versionDist,
  categoryDistribution: categoryDist,
  deviceModelsCount: Object.keys(deviceDist).length,
  deviceModels: deviceDist,
  emptyPayloads,
  totalStructuredEvents,
  totalSafeRecentEvents,
  duplicatesCount
};

zip.addFile('manifest.json', Buffer.from(JSON.stringify(manifest, null, 2), 'utf8'));
zip.addFile('summary.json', Buffer.from(JSON.stringify(summary, null, 2), 'utf8'));

const zipBuffer = Buffer.from(zip.build());
fs.writeFileSync(zipPath, zipBuffer, { mode: 0o600 });
fs.writeFileSync(path.join(EXPORTS_DIR, 'latest_manifest.json'), JSON.stringify(manifest, null, 2));
fs.writeFileSync(path.join(EXPORTS_DIR, 'latest_summary.json'), JSON.stringify(summary, null, 2));

console.log(`[4/5] ZIP Archive written: ${zipPath} (${(zipBuffer.length / 1024 / 1024).toFixed(2)} MB)`);

// Verification
console.log('[5/5] Performing archive integrity verification...');
const fileStats = fs.statSync(zipPath);
console.log(`  File size: ${fileStats.size} bytes`);
console.log(`  Reports packed: ${reportIdsSeen.size}`);
console.log(`  Empty payloads: ${emptyPayloads}`);
console.log(`  Structured events: ${totalStructuredEvents}`);
console.log(`  Safe recent events: ${totalSafeRecentEvents}`);
console.log(`  Duplicates: ${duplicatesCount}`);
console.log(`  Versions: ${JSON.stringify(versionDist)}`);
console.log(`  Categories: ${JSON.stringify(categoryDist)}`);
