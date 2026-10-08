export const STATUSES = ['NEW', 'IN_PROGRESS', 'RESOLVED', 'KNOWN_ISSUE', 'IGNORED_TEST'];
export const SEVERITIES = ['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const SUBSYSTEMS = ['DOWNLOAD', 'PLAYER', 'BACKGROUND', 'OTA', 'OFFLINE', 'CHANNEL_GROUPS', 'AUTO_SETUP', 'TRANSLATION', 'NETWORK', 'SYSTEM'];
const SAFE_CONTEXT_KEYS = new Set([
  'queueState', 'stage', 'bytes', 'speed', 'stallCount', 'retryCount', 'resumeCount',
  'packagingState', 'finalizeState', 'freeStorage', 'sourceType', 'isLive',
  'selectedCodec', 'selectedResolution', 'startupLatency', 'buffering',
  'rebufferCount', 'rebufferDuration', 'decoderError', 'droppedFrames',
  'backgroundMode', 'audioOnly', 'playerState', 'audioFocus', 'serviceState',
  'mediaSessionState', 'videoRendererEnabled', 'returnForegroundResult',
  'checkStarted', 'releaseParsed', 'versionCompared', 'assetSelected',
  'downloadStarted', 'hashVerified', 'signerVerified', 'installerRequested',
  'localFileExists', 'localFileSize', 'isDownloadedItem', 'remoteFallback',
  'translatedTrackPresent', 'openResult', 'groupCount', 'membershipCount',
  'operation', 'profile', 'recommendedProfile', 'confidence', 'runtimeHealth',
  'networkLimited', 'decoderLimited', 'sampleCount', 'networkClassification',
  'storageClassification', 'exceptionClassSafe', 'durationMs',
  'queueLength', 'activeJobs', 'pausedJobs', 'failedJobs', 'freeStorageMb',
  'selectedHeight', 'totalRebufferMs', 'maxRebufferMs', 'averageBufferedMs',
  'averageBandwidthKbps', 'manifestErrorCount', 'segmentErrorCount',
  'errorCategory',
]);
const SAFE_ENUM = /^[A-Za-z][A-Za-z0-9_+.-]{0,63}$/;

export function safeContext(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {};
  const result = {};
  for (const [key, value] of Object.entries(raw)) {
    if (!SAFE_CONTEXT_KEYS.has(key)) continue;
    if (typeof value === 'boolean' || (typeof value === 'number' && Number.isFinite(value))) result[key] = value;
    else if (typeof value === 'string' && SAFE_ENUM.test(value)) result[key] = value;
  }
  return result;
}

function enumValue(value, fallback = '') {
  const normalized = String(value || '').toUpperCase().replace(/[^A-Z0-9_]/g, '_').slice(0, 64);
  return normalized || fallback;
}

function inferStage(code) {
  const rules = [
    ['VERIFY_SIGNER', 'VERIFY_SIGNER'], ['SIGNATURE_VERIFIED', 'VERIFY_SIGNER'],
    ['VERIFY_HASH', 'VERIFY_HASH'], ['HASH_VERIFIED', 'VERIFY_HASH'],
    ['ASSET_SELECT', 'ASSET_SELECT'], ['VERSION_COMPARE', 'VERSION_COMPARE'],
    ['RELEASE_PARSE', 'RELEASE_PARSE'], ['PACKAGING', 'PACKAGING'],
    ['FINALIZE', 'FINALIZE'], ['TRANSLATION', 'TRANSLATION'],
    ['AUDIO', 'AUDIO'], ['VIDEO', 'VIDEO'], ['INSTALL', 'INSTALL'],
    ['BUFFER', 'BUFFERING'], ['STARTUP', 'PREPARE'], ['DECODER', 'PREPARE'],
    ['CHECK', 'CHECK'], ['RESUME', 'RESOLVING'], ['QUEUE', 'RESOLVING'],
    ['DOWNLOAD', 'DOWNLOAD'],
  ];
  return rules.find(([needle]) => code.includes(needle))?.[1] || '';
}

function severityForEvent(code, rawLevel, repeats = 1) {
  if (rawLevel === 'CRITICAL' || /CRASH_LOOP|CORRUPT|DATA_LOSS/.test(code)) return 'CRITICAL';
  if (/MUX_FAILED|PACKAGING_FAILED|FINALIZE_FAILED|BACKGROUND.*FAILED|OTA_SIGNATURE_MISMATCH/.test(code)) return 'HIGH';
  if (/REBUFFER|STARTUP_SLOW|NETWORK_TIMEOUT|HTTP_5XX|OTA_FAILED/.test(code)) return 'MEDIUM';
  if (rawLevel === 'ERROR') return repeats > 2 ? 'HIGH' : 'MEDIUM';
  if (rawLevel === 'WARNING') return 'LOW';
  return SEVERITIES.includes(rawLevel) ? rawLevel : 'INFO';
}

export function technicalSummary(report = {}) {
  const download = report.downloadDiagnostics || {};
  const perf = download.downloadPerformance || {};
  const player = report.livePlayback || {};
  return {
    download: safeContext({
      queueLength: download.queueLength, activeJobs: download.activeJobs,
      pausedJobs: download.pausedJobs, failedJobs: download.failedJobs,
      stage: download.lastStage, retryCount: download.retryCount,
      bytes: perf.totalBytes, speed: perf.averageBytesPerSecond,
      stallCount: perf.stallCount, resumeCount: perf.resumeCount,
      packagingState: download.packagingCompleted ? 'COMPLETED' : download.packagingStarted ? 'STARTED' : undefined,
      finalizeState: download.finalizeCompleted ? 'COMPLETED' : undefined,
      freeStorageMb: download.freeStorageMb,
      errorCategory: download.lastErrorCategory,
    }),
    player: safeContext({
      sourceType: report.sourceType || (report.livePlayback ? 'LIVE' : undefined),
      isLive: report.livePlayback ? true : undefined, selectedCodec: player.selectedCodec,
      selectedResolution: player.selectedHeight,
      rebufferCount: player.rebufferCount,
      rebufferDuration: player.totalRebufferMs,
      averageBufferedMs: player.averageBufferedMs,
      averageBandwidthKbps: player.averageBandwidthKbps,
      decoderError: player.lastErrorCategory,
    }),
    background: safeContext(report.backgroundState),
    ota: safeContext(report.otaState),
    offline: safeContext(report.offlineState),
    channelGroups: safeContext(report.channelGroupState),
    autoSetup: safeContext(report.autoSetupState),
  };
}

export function structuredEvents(report = {}) {
  const raw = Array.isArray(report.safeRecentEvents) ? report.safeRecentEvents : [];
  return raw.map(event => {
    const category = enumValue(event.category || report.errorCategory, 'GENERAL');
    const code = enumValue(event.event || event.code, 'EVENT');
    const inferredSubsystem = category === 'CHANNEL_GROUP' ? 'CHANNEL_GROUPS' :
      ['MEDIA3', 'CODEC', 'PLAYBACK'].includes(category) ? 'PLAYER' :
      category === 'COMPATIBILITY' ? 'AUTO_SETUP' :
      category === 'STORAGE' ? 'OFFLINE' :
      SUBSYSTEMS.find(name => category.includes(name) || code.includes(name)) || 'SYSTEM';
    const severity = enumValue(event.severity || event.level, 'INFO');
    const level = enumValue(event.level || event.severity, 'INFO');
    return {
      timestamp: Number(event.timestamp || event.ts || report.timestamp) || 0,
      category,
      subsystem: enumValue(event.subsystem, inferredSubsystem),
      stage: enumValue(event.stage || report.stage, inferStage(code)),
      event: code,
      severity: SEVERITIES.includes(severity) ? severity : severityForEvent(code, level, event.repeatCount || 1),
      level,
      result: enumValue(event.result),
      errorCategory: enumValue(event.errorCategory || (level === 'ERROR' ? code : report.errorCategory)),
      safeContext: safeContext(event.safeContext || event.context),
      repeatCount: Number.isInteger(event.repeatCount) ? Math.min(Math.max(event.repeatCount, 1), 100000) : 1,
      ...(Number.isFinite(event.durationMs) && event.durationMs >= 0 ? { durationMs: event.durationMs } : {}),
    };
  });
}

export function classifyReport(report = {}) {
  const events = structuredEvents(report);
  const rank = { INFO: 0, LOW: 1, MEDIUM: 2, HIGH: 3, CRITICAL: 4 };
  const last = [...events].reverse().find(e => e.level === 'ERROR' || ['HIGH', 'CRITICAL'].includes(e.severity)) || events.at(-1);
  const errorCategory = enumValue(last?.errorCategory || report.errorCategory);
  const subsystem = enumValue(report.subsystem || last?.subsystem, 'SYSTEM');
  const stage = enumValue(report.stage || last?.stage);
  const explicit = enumValue(report.severity);
  const severity = SEVERITIES.includes(explicit) ? explicit :
    events.reduce((best, event) => rank[event.severity] > rank[best] ? event.severity : best, errorCategory ? 'MEDIUM' : 'INFO');
  return { events, errorCategory, subsystem, stage, severity };
}

export function parseVoxVersion(value) {
  if (!value) return null;
  const str = String(value).trim().replace(/^v/i, '');
  const match = str.match(/^(\d+)\.(\d+)-(?:vox|vot)\.(\d+)(?:\.(\d+))?(?:-(dev|rc\.?(\d+)))?$/i);
  if (match) {
    const major = Number(match[1]) || 0;
    const minor = Number(match[2]) || 0;
    const voxMajor = Number(match[3]) || 0;
    const voxMinor = Number(match[4]) || 0;
    const tag = (match[5] || '').toLowerCase();
    const isDev = tag === 'dev';
    const rcNum = tag.startsWith('rc') ? (Number(match[6]) || 1) : null;
    const stage = isDev ? -1 : rcNum !== null ? rcNum : 1000000;
    return [major, minor, voxMajor, voxMinor, stage];
  }
  const simpleMatch = str.match(/^(\d+)(?:\.(\d+))?(?:-(dev|rc\.?(\d+)))?$/i);
  if (simpleMatch) {
    const voxMajor = Number(simpleMatch[1]) || 0;
    const voxMinor = Number(simpleMatch[2]) || 0;
    const tag = (simpleMatch[3] || '').toLowerCase();
    const isDev = tag === 'dev';
    const rcNum = tag.startsWith('rc') ? (Number(simpleMatch[4]) || 1) : null;
    const stage = isDev ? -1 : rcNum !== null ? rcNum : 1000000;
    return [32, 56, voxMajor, voxMinor, stage];
  }
  return null;
}

export function compareVoxVersions(left, right) {
  const a = parseVoxVersion(left);
  const b = parseVoxVersion(right);
  if (!a || !b) return null;
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) return a[i] > b[i] ? 1 : -1;
  }
  return 0;
}

export function generateDeterministicIssueTitle(signature = '', report = {}) {
  const sig = String(signature).toUpperCase();
  const sigSubsystem = sig.split('|')[0] || '';
  const subsystem = String(report.subsystem || report.errorCategory || sigSubsystem).toUpperCase();
  const stage = String(report.stage || '').toUpperCase();
  const errorCategory = String(report.error_category || report.errorCategory || '').toUpperCase();

  if ((subsystem === 'DOWNLOAD' || sig.includes('DOWNLOAD')) && (stage === 'PACKAGING' || sig.includes('PACKAGING') || sig.includes('MUX'))) {
    return 'Ошибка упаковки скачанного видео';
  }
  if ((subsystem === 'DOWNLOAD' || sig.includes('DOWNLOAD')) && (stage === 'RESOLVING' || sig.includes('RESOLV'))) {
    return 'Ошибка разрешения потоков при скачивании';
  }
  if (subsystem === 'BACKGROUND' || sig.includes('BACKGROUND')) {
    return 'Фоновое воспроизведение останавливается';
  }
  if (subsystem === 'OTA' || sig.includes('OTA')) {
    return 'Ошибка OTA при проверке или выборе APK';
  }
  if (subsystem === 'OFFLINE' || sig.includes('OFFLINE')) {
    return 'Ошибка локального воспроизведения скачанного медиа';
  }
  if (subsystem === 'PLAYER' && (stage === 'BUFFERING' || sig.includes('BUFFER'))) {
    return 'Зависание буферизации видеопотока';
  }
  if (subsystem === 'PLAYER' && (stage === 'PREPARE' || sig.includes('DECODER'))) {
    return 'Сбой декодера при инициализации плеера';
  }
  if (subsystem === 'TRANSLATION' || sig.includes('TRANSLAT')) {
    return 'Сбой закадрового перевода звуковой дорожки';
  }
  if (subsystem === 'CHANNEL_GROUPS' || sig.includes('CHANNEL_GROUP')) {
    return 'Ошибка управления локальными группами каналов';
  }
  if (subsystem === 'AUTO_SETUP' || sig.includes('AUTO_SETUP')) {
    return 'Сбой модуля автонастройки кодеков';
  }
  if (subsystem) {
    return `Сбой подсистемы ${subsystem}${stage ? ` на этапе ${stage}` : ''}`;
  }
  return `Инцидент ${errorCategory || signature || 'системного сбоя'}`;
}
