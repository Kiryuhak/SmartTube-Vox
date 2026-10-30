import { ZipWriter } from './zip.mjs';
import { normalizeStatus } from './storage.mjs';
import { prepareReportDownloadJson } from './diagnostics.mjs';

/**
 * Normalizes active statuses and their aliases (e.g. IN_PROGRESS, TRIAGED -> INVESTIGATING)
 * while preserving strict rejection of unknown or non-active statuses.
 */
export function normalizeActiveStatus(rawStatus) {
  const norm = normalizeStatus(rawStatus);
  if (norm === 'NEW') return 'NEW';
  if (['INVESTIGATING', 'IN_PROGRESS', 'TRIAGED', 'REVIEWED', 'NEEDS_INFO'].includes(norm)) return 'INVESTIGATING';
  return norm;
}

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
  'KNOWN_LIMITATION',
  'WONT_FIX',
  'FIXED_AWAITING_VERIFICATION',
  'FIXED_PENDING_VERIFICATION',
]);

/**
 * Builds a ZIP archive of diagnostic reports matching filters.
 * Uses bounded pagination across the DB to stream all records without exhausting memory.
 */
export async function buildDiagnosticsZip(storage, options = {}) {
  const now = options.now || Date.now();
  const d = new Date(now);
  const pad = (n) => String(n).padStart(2, '0');
  const dateHoursStr = `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}_${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}`;
  const zip = new ZipWriter();

  // Target statuses: defaults strictly to ACTIVE_EXPORT_STATUSES ('NEW', 'INVESTIGATING')
  let targetStatuses = ACTIVE_EXPORT_STATUSES;
  if (Array.isArray(options.statuses) && options.statuses.length > 0) {
    targetStatuses = options.statuses.map(s => normalizeActiveStatus(s));
  } else if (typeof options.statuses === 'string' && options.statuses.trim()) {
    targetStatuses = options.statuses.split(',').map(s => normalizeActiveStatus(s.trim())).filter(Boolean);
  }

  const reports = [];
  const reportsList = [];
  const seenIds = new Set();
  const DEFAULT_MAX_EXPORT_REPORTS = 5000;
  const MAX_EXPORT_REPORTS = options.maxReports !== undefined
    ? Math.max(1, Number(options.maxReports))
    : DEFAULT_MAX_EXPORT_REPORTS;
  const DEFAULT_MAX_EXPORT_BYTES = options.format === 'json' ? 650 * 1024 : 15 * 1024 * 1024;
  const MAX_EXPORT_BYTES = options.maxBytes !== undefined
    ? Math.max(10 * 1024, Number(options.maxBytes))
    : DEFAULT_MAX_EXPORT_BYTES;
  let accumulatedBytes = 0;
  const PAGE_SIZE = Math.min(options.pageSize || 50, MAX_EXPORT_REPORTS);
  let offset = options.offset || 0;
  let currentCursor = options.cursor || null;
  let hasMore = true;
  let skippedCount = 0;
  const skippedReasons = [];
  let isTruncated = false;
  let nextCursor = null;
  const partNum = options.part || 1;

  // 1. Bounded, keyset-consistent paginated retrieval across the database
  let pageIndex = 0;
  const MAX_PAGES = 1000; // Safe limit allowing up to 100,000 reports

  while (hasMore && pageIndex < MAX_PAGES) {
    pageIndex++;
    const remainingToFetch = MAX_EXPORT_REPORTS - reports.length;
    const fetchLimit = remainingToFetch > 0 ? Math.min(PAGE_SIZE, remainingToFetch) : 1;
    const needPayload = remainingToFetch > 0;

    const listParams = {
      limit: fetchLimit,
      statuses: targetStatuses,
      offset: offset,
      cursor: currentCursor || undefined,
      until: options.until || undefined,
      includePayload: needPayload,
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

      // Check status: strictly allow only active statuses, reject any missing or unknown status
      const rawStatus = raw.status;
      if (!rawStatus || typeof rawStatus !== 'string' || !rawStatus.trim()) {
        skippedCount++;
        skippedReasons.push(`Report ${reportId}: status is missing or empty`);
        continue;
      }
      const normStatus = normalizeActiveStatus(rawStatus);

      // Explicitly exclude completed/final/other excluded statuses
      if (FINAL_EXCLUDED_STATUSES.includes(normStatus) || FINAL_EXCLUDED_STATUSES.includes(normalizeStatus(rawStatus))) {
        skippedCount++;
        skippedReasons.push(`Report ${reportId}: status ${rawStatus} is excluded`);
        continue;
      }

      // Exclude any status not in target active statuses (including unknown statuses)
      if (!targetStatuses.includes(normStatus)) {
        skippedCount++;
        skippedReasons.push(`Report ${reportId}: status ${rawStatus} is not in target active statuses`);
        continue;
      }

      // Check byte budget: if we already have at least 1 report, and adding another would exceed byte budget
      const reportBytes = (raw.payload_json && typeof raw.payload_json === 'string')
        ? (typeof Buffer !== 'undefined' ? Buffer.byteLength(raw.payload_json, 'utf8') : (typeof TextEncoder !== 'undefined' ? new TextEncoder().encode(raw.payload_json).length : raw.payload_json.length))
        : (typeof raw.payload === 'object' ? (typeof Buffer !== 'undefined' ? Buffer.byteLength(JSON.stringify(raw.payload), 'utf8') : (typeof TextEncoder !== 'undefined' ? new TextEncoder().encode(JSON.stringify(raw.payload)).length : 2000)) : 2000);

      if (reports.length >= 1 && (accumulatedBytes + reportBytes > MAX_EXPORT_BYTES)) {
        isTruncated = true;
        const lastIncluded = reports[reports.length - 1];
        nextCursor = `${lastIncluded.created_at || lastIncluded.createdAt}:${lastIncluded.report_id || lastIncluded.reportId}`;
        hasMore = false;
        break;
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
      accumulatedBytes += reportBytes;
    }

    if (isTruncated || !hasMore) {
      break;
    }

    const lastRaw = items[items.length - 1];
    if (lastRaw && (lastRaw.created_at || lastRaw.createdAt) && (lastRaw.report_id || lastRaw.reportId)) {
      currentCursor = `${lastRaw.created_at || lastRaw.createdAt}:${lastRaw.report_id || lastRaw.reportId}`;
    }

    offset += items.length;
    // If fewer items returned than fetchLimit, we've reached the end
    if (items.length < fetchLimit) {
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
    const status = normalizeActiveStatus(r.status || 'NEW');
    if (status === 'NEW') newReportsCount++;
    if (status === 'INVESTIGATING') investigatingReportsCount++;

    // Parse payload for detailed metrics and complete export
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

    const model = payload?.model || payload?.device?.model || r.device_family || 'Unknown Model';
    deviceModels[model] = (deviceModels[model] || 0) + 1;

    const cat = r.error_category || payload?.errorCategory || 'UNCATEGORIZED';
    errorCategories[cat] = (errorCategories[cat] || 0) + 1;

    const ver = r.app_version || payload?.appVersion || 'Unknown';
    voxVersions[ver] = (voxVersions[ver] || 0) + 1;

    const sig = r.error_signature || payload?.errorSignature || 'UNKNOWN_SIGNATURE';
    signatures[sig] = (signatures[sig] || 0) + 1;

    const events = payload?.safeRecentEvents || payload?.structuredEvents || payload?.events || [];
    if (Array.isArray(events)) {
      for (const ev of events) {
        if (ev.isHistorical === true || ev.context?.isHistorical === true || ev.lifecycle === 'HISTORICAL') {
          historicalEventsCount++;
        }
      }
    }

    // 3. Add report to status folder (NEW or INVESTIGATING)
    const folder = status === 'INVESTIGATING' ? 'INVESTIGATING' : 'NEW';
    const filename = `${folder}/${reportId}.json`;
    const fullReportData = {
      ...r,
      payload: (payload && payload !== r) ? payload : (r.payload || payload),
      payload_json: r.payload_json || (typeof payload === 'object' ? JSON.stringify(payload) : undefined),
    };
    const sanitizedJson = prepareReportDownloadJson(fullReportData);
    if (options.format === 'json') {
      reportsList.push({
        reportId,
        status,
        folder,
        filename,
        report: sanitizedJson,
      });
    } else {
      zip.addFile(filename, JSON.stringify(sanitizedJson));
    }
  }

  // 4. Repeated error signatures
  const repeatedSignatures = Object.entries(signatures)
    .filter(([_, count]) => count > 1)
    .sort((a, b) => b[1] - a[1])
    .map(([signature, count]) => ({ signature, count }));

  // 5. Create manifest.json
  const manifest = {
    schemaVersion: '1.0',
    exportSessionId: options.exportSessionId || options.sessionId || null,
    exportedAt: new Date(now).toISOString(),
    part: partNum,
    totalReports: reports.length,
    newReports: newReportsCount,
    investigatingReports: investigatingReportsCount,
    reportIds: Array.from(seenIds),
    filters: {
      statuses: targetStatuses,
      search: options.search || '',
      category: options.errorCategory || '',
      cursor: options.cursor || null,
      until: options.until || null,
    },
    timezone: 'UTC',
    cursorStart: reports.length > 0 ? `${reports[0].created_at || reports[0].createdAt}:${reports[0].report_id || reports[0].reportId}` : null,
    cursorEnd: reports.length > 0 ? `${reports[reports.length - 1].created_at || reports[reports.length - 1].createdAt}:${reports[reports.length - 1].report_id || reports[reports.length - 1].reportId}` : null,
    consistency: {
      creationCeiling: options.until ? new Date(Number(options.until)).toISOString() : null,
      guarantee: 'CREATION_CEILING_CONSISTENT',
      statusEvaluation: 'READ_COMMITTED_AT_QUERY_TIME',
      note: 'until ceiling guarantees no cursor drift or new report skew across parts; status reflects committed state at query time.',
    },
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

  const cursorStart = (reports.length > 0)
    ? `${reports[0].created_at || reports[0].createdAt}:${reports[0].report_id || reports[0].reportId}`
    : null;
  const cursorEnd = (reports.length > 0)
    ? `${reports[reports.length - 1].created_at || reports[reports.length - 1].createdAt}:${reports[reports.length - 1].report_id || reports[reports.length - 1].reportId}`
    : null;

  if (options.format === 'json') {
    return {
      format: 'json',
      filename: `VOX_Diagnostics_Active_${dateHoursStr}_part${partNum}.json`,
      totalReports: reports.length,
      isTruncated,
      nextCursor,
      cursorStart,
      cursorEnd,
      part: partNum,
      reportsList,
      manifest,
      summary,
      reportIds: Array.from(seenIds),
    };
  }

  zip.addFile('summary.json', JSON.stringify(summary, null, 2));

  const buffer = zip.build();
  let sha256 = null;
  try {
    const hash = await crypto.subtle.digest('SHA-256', buffer);
    sha256 = Array.from(new Uint8Array(hash)).map(b => b.toString(16).padStart(2, '0')).join('');
  } catch (_) {}
  
  // Naming: VOX_Diagnostics_Active_YYYYMMDD_HHMM.zip (with _partN for multipart)
  const partSuffix = (partNum > 1) ? `_part${partNum}` : (isTruncated ? '_part1' : '');
  let zipFilename;
  if (options.filename) {
    zipFilename = options.filename;
  } else if (options.namePrefix) {
    zipFilename = `${options.namePrefix}_${dateHoursStr}${partSuffix}.zip`;
  } else {
    zipFilename = `VOX_Diagnostics_Active_${dateHoursStr}${partSuffix}.zip`;
  }

  return {
    buffer,
    filename: zipFilename,
    sha256,
    cursorStart,
    cursorEnd,
    manifest,
    summary,
    totalReports: reports.length,
    isTruncated,
    nextCursor,
    part: partNum,
    reportIds: Array.from(seenIds),
  };
}
