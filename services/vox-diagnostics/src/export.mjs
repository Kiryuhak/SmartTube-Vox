import { ZipWriter } from './zip.mjs';
import { normalizeStatus } from './storage.mjs';
import { prepareReportDownloadJson } from './diagnostics.mjs';

/**
 * SmartTube VOX Diagnostics — Bulk Diagnostics ZIP Export Service.
 * Safely generates complete ZIP archives of active diagnostic reports with
 * manifest.json, summary.json, and structured status folders.
 */

export const ACTIVE_EXPORT_STATUSES = Object.freeze(['NEW', 'INVESTIGATING']);
export const FINAL_EXCLUDED_STATUSES = Object.freeze([
  'RESOLVED',
  'CLOSED',
  'IGNORED',
  'IGNORED_TEST',
  'WONT_FIX',
  'KNOWN_LIMITATION',
]);

/**
 * Builds a ZIP archive of diagnostic reports matching filters.
 * Uses bounded pagination across the DB to stream all records without exhausting memory.
 */
export async function buildDiagnosticsZip(storage, options = {}) {
  const now = options.now || Date.now();
  const dateStr = new Date(now).toISOString().slice(0, 10);
  const zip = new ZipWriter();

  // Target statuses
  let targetStatuses = ACTIVE_EXPORT_STATUSES;
  if (Array.isArray(options.statuses) && options.statuses.length > 0) {
    targetStatuses = options.statuses.map(s => normalizeStatus(s));
  } else if (typeof options.statuses === 'string' && options.statuses.trim()) {
    targetStatuses = options.statuses.split(',').map(s => normalizeStatus(s.trim())).filter(Boolean);
  }

  const reports = [];
  const seenIds = new Set();
  const PAGE_SIZE = 100;
  const MAX_EXPORT_REPORTS = options.maxReports || 5000;
  let offset = options.offset || 0;
  let currentCursor = options.cursor || null;
  let hasMore = true;
  let skippedCount = 0;
  const skippedReasons = [];
  let isTruncated = false;
  let nextCursor = null;

  // 1. Bounded, keyset/offset-consistent paginated retrieval across the entire database
  while (hasMore) {
    const listParams = {
      limit: PAGE_SIZE,
      offset,
      cursor: currentCursor || undefined,
      search: options.search,
      platform: options.platform,
      appVersion: options.appVersion,
      errorCategory: options.errorCategory,
      device: options.device,
      signature: options.signature,
      sort: 'newest',
    };

    const page = await storage.listReports(listParams);

    const items = Array.isArray(page) ? page : (page?.reports || []);
    if (items.length === 0) {
      break;
    }

    for (const raw of items) {
      const reportId = raw.report_id || raw.reportId;
      if (!reportId || seenIds.has(reportId)) {
        continue;
      }

      // Check status against allowed active statuses and exclude final statuses
      const rawStatus = raw.status || 'NEW';
      const normStatus = normalizeStatus(rawStatus);

      // Explicitly exclude completed/final statuses
      if (FINAL_EXCLUDED_STATUSES.includes(normStatus)) {
        skippedCount++;
        continue;
      }

      if (!targetStatuses.includes(normStatus)) {
        skippedCount++;
        continue;
      }

      // If we have already reached the maximum requested reports, and another matching report is found,
      // the archive is strictly truncated beyond the maximum limit.
      if (reports.length >= MAX_EXPORT_REPORTS) {
        isTruncated = true;
        const lastIncluded = reports[reports.length - 1];
        nextCursor = `${lastIncluded.created_at || lastIncluded.createdAt}:${lastIncluded.report_id || lastIncluded.reportId}`;
        hasMore = false;
        break;
      }

      seenIds.add(reportId);
      reports.push(raw);
    }

    if (isTruncated || !hasMore) {
      break;
    }

    const lastRaw = items[items.length - 1];
    if (lastRaw && (lastRaw.created_at || lastRaw.createdAt) && (lastRaw.report_id || lastRaw.reportId)) {
      currentCursor = `${lastRaw.created_at || lastRaw.createdAt}:${lastRaw.report_id || lastRaw.reportId}`;
    }

    offset += items.length;
    // If fewer items returned than PAGE_SIZE, we've reached the end
    if (items.length < PAGE_SIZE) {
      hasMore = false;
    }
    // Safety guard against infinite loops
    if (offset > 20000) {
      hasMore = false;
    }
  }

  // 2. Metrics aggregation for summary.json
  const distinctInstallationIds = new Set();
  let unknownInstallationCount = 0;
  const deviceModels = Object.create(null);
  const errorCategories = Object.create(null);
  const voxVersions = Object.create(null);
  const signatures = Object.create(null);
  let historicalEventsCount = 0;
  let newReportsCount = 0;
  let investigatingReportsCount = 0;

  for (const r of reports) {
    const reportId = r.report_id || r.reportId;
    const status = normalizeStatus(r.status || 'NEW');
    if (status === 'NEW') newReportsCount++;
    if (status === 'INVESTIGATING') investigatingReportsCount++;

    // Parse payload for detailed metrics
    let payload = null;
    if (r.payload_json && typeof r.payload_json === 'string') {
      try { payload = JSON.parse(r.payload_json); } catch (_) {}
    } else if (r.payload && typeof r.payload === 'object') {
      payload = r.payload;
    } else {
      payload = r;
    }

    const installId = r.installation_id || payload?.installationId || payload?.installation_id;
    if (installId && typeof installId === 'string' && installId.trim()) {
      distinctInstallationIds.add(installId.trim());
    } else {
      unknownInstallationCount++;
    }

    const model = payload?.model || r.device_family || 'Unknown Model';
    deviceModels[model] = (deviceModels[model] || 0) + 1;

    const cat = r.error_category || payload?.errorCategory || 'UNCATEGORIZED';
    errorCategories[cat] = (errorCategories[cat] || 0) + 1;

    const ver = r.app_version || payload?.appVersion || 'Unknown';
    voxVersions[ver] = (voxVersions[ver] || 0) + 1;

    const sig = r.error_signature || payload?.errorSignature || 'UNKNOWN_SIGNATURE';
    signatures[sig] = (signatures[sig] || 0) + 1;

    const events = payload?.safeRecentEvents || payload?.events || [];
    if (Array.isArray(events)) {
      for (const ev of events) {
        if (ev.isHistorical === true || ev.context?.isHistorical === true) {
          historicalEventsCount++;
        }
      }
    }

    // 3. Add report to status folder
    const folder = status === 'INVESTIGATING' ? 'INVESTIGATING' : 'NEW';
    const filename = `${folder}/${reportId}.json`;
    const sanitizedJson = prepareReportDownloadJson(payload || r);
    zip.addFile(filename, JSON.stringify(sanitizedJson, null, 2));
  }

  // 4. Repeated error signatures
  const repeatedSignatures = Object.entries(signatures)
    .filter(([_, count]) => count > 1)
    .sort((a, b) => b[1] - a[1])
    .map(([signature, count]) => ({ signature, count }));

  // 5. Create manifest.json
  const manifest = {
    schemaVersion: '1.0',
    exportedAt: new Date(now).toISOString(),
    totalReports: reports.length,
    newReports: newReportsCount,
    investigatingReports: investigatingReportsCount,
    reportIds: Array.from(seenIds),
    filters: {
      statuses: targetStatuses,
      search: options.search || '',
      category: options.errorCategory || '',
      cursor: options.cursor || null,
    },
    timezone: 'UTC',
    exportStatus: isTruncated ? 'TRUNCATED_MAX_LIMIT' : 'COMPLETE',
    isTruncated: Boolean(isTruncated),
    truncatedAt: isTruncated ? reports.length : null,
    nextCursor: isTruncated ? nextCursor : null,
    warning: isTruncated ? `Export was truncated at maximum limit of ${MAX_EXPORT_REPORTS} reports. To export the next batch, use ?cursor=${nextCursor}.` : undefined,
    skippedCount,
    skippedReasons,
  };
  zip.addFile('manifest.json', JSON.stringify(manifest, null, 2));

  // 6. Create summary.json
  const summary = {
    totalReports: reports.length,
    distinctInstallationIds: distinctInstallationIds.size,
    unknownInstallationCount,
    deviceModelsCount: Object.keys(deviceModels).length,
    deviceModels,
    errorCategories,
    voxVersions,
    repeatedSignatures,
    historicalEventsCount,
    uniqueErrorSignaturesCount: Object.keys(signatures).length,
  };
  zip.addFile('summary.json', JSON.stringify(summary, null, 2));

  const buffer = zip.build();
  const zipFilename = `SmartTube-VOX-Diagnostics-${dateStr}.zip`;

  return {
    buffer,
    filename: zipFilename,
    manifest,
    summary,
    totalReports: reports.length,
    isTruncated,
    nextCursor,
  };
}
