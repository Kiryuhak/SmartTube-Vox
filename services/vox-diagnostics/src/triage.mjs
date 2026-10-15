export const STATUSES = [
  'NEW',
  'TRIAGED',
  'IN_PROGRESS',
  'NEEDS_INFO',
  'FIXED_PENDING_VERIFICATION',
  'FIXED_PENDING_VALIDATION',
  'CLOSED',
  'RESOLVED',
  'KNOWN_ISSUE',
  'IGNORED_TEST',
  'IGNORED',
  'WONT_FIX',
];
export const SEVERITIES = ['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
export const USER_FRIENDLY_ISSUE_LABELS_RU = {
  PLAYER_REBUFFER: 'Повторная буферизация видео',
  PLAYER_STARTUP_SLOW: 'Видео долго запускается',
  PLAYER_HTTP_ERROR: 'Сетевая ошибка при загрузке видеопотока (HTTP сбой)',
  HUD_STATE_INCONSISTENT: 'Панель управления плеером работает некорректно',
  HUD_SELF_HEAL_FAILED: 'Сбой автовосстановления панели управления',
  HUD_CONTROLS_DETACHED: 'Панель управления отключена',
  OTA_FAILED: 'Не удалось проверить обновление',
  OTA_RATE_LIMIT: 'Превышен лимит запросов к серверу обновлений (активен период ожидания)',
  OTA_RATE_LIMIT_RECOVERED: 'Связь с сервером обновлений восстановлена',
  OTA_CHECK_SKIPPED_COOLDOWN: 'Проверка обновлений пропущена (действует период охлаждения)',
  OTA_NO_UPDATE: 'Установлена актуальная версия приложения',
  OTA_SIGNATURE_MISMATCH: 'Несовпадение цифровой подписи обновления',
  IMAGE_REQUEST_FAILED_FINAL: 'Не удалось загрузить изображение',
  IMAGE_REQUEST_FAILED: 'Ошибка загрузки обложки / миниатюры',
  DOWNLOAD_FAILED: 'Сбой скачивания видео',
  DOWNLOAD_STATE_INCONSISTENT: 'Несоответствие состояния загрузки',
  TRANSLATION_DESYNC_WARNING: 'Рассинхронизация звуковой дорожки перевода',
  TRANSLATION_AUDIO_DOWNLOAD_FAILED: 'Сбой загрузки аудиодорожки перевода',
  TRANSLATION_FAST_VOCALS_FAILED: 'Сбой перевода быстрых треков / хэви-метал в Yandex VOT',
  PLAYER_DOUBLE_BACK_PAUSE: 'Первое нажатие «Назад» ставит плеер на паузу при двойном выходе',
  BACKGROUND_PLAYBACK_PAUSED: 'Фоновое воспроизведение ставится на паузу при сворачивании',
  LOCAL_PLAYBACK_FAIL: 'Не удалось воспроизвести скачанное видео',
};

export function inferCategoryFromCode(code = '') {
  const c = String(code).toUpperCase();
  if (c.startsWith('OTA_')) return 'OTA';
  if (c.startsWith('PLAYER_') || c.startsWith('MEDIA3_') || c.startsWith('DECODER_') || c.startsWith('BUFFER_') || c.startsWith('LIVE_')) return 'PLAYER';
  if (c.startsWith('HUD_') || c.startsWith('SIDEBAR_') || c.startsWith('CARD_')) return 'UI';
  if (c.startsWith('DOWNLOAD_') || c.startsWith('MUX_')) return 'DOWNLOAD';
  if (c.startsWith('TRANSLATION_') || c.startsWith('LIVE_TRANSLATION_')) return 'TRANSLATION';
  if (c.startsWith('NETWORK_') || c.includes('HTTP_') || c.includes('DNS_')) return 'NETWORK';
  if (c.startsWith('BACKGROUND_')) return 'BACKGROUND';
  if (c.startsWith('OFFLINE_')) return 'OFFLINE';
  if (c.startsWith('CHANNEL_GROUP_')) return 'CHANNEL_GROUPS';
  if (c.startsWith('AUTO_TUNING_') || c.startsWith('AUTO_SETUP_')) return 'AUTO_SETUP';
  return 'GENERAL';
}

export function inferSubsystemFromCode(code = '') {
  const cat = inferCategoryFromCode(code);
  if (cat === 'UI') return 'UI';
  return cat;
}

export function getFriendlyIssueLabel(code) {
  if (!code) return null;
  return USER_FRIENDLY_ISSUE_LABELS_RU[code] || null;
}
const SUBSYSTEMS = ['DOWNLOAD', 'PLAYER', 'BACKGROUND', 'OTA', 'OFFLINE', 'CHANNEL_GROUPS', 'AUTO_SETUP', 'TRANSLATION', 'NETWORK', 'UI', 'SYSTEM'];
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
  'errorCategory', 'liveOffsetMs',
  'bufferAheadMs', 'playbackSessionId', 'bitrate', 'networkEngine', 'reason',
  'sourceLang', 'targetLang', 'finalPath', 'reuseSource', 'isReused', 'lookupElapsedMs',
  'requestElapsedMs', 'providerWaitElapsedMs', 'processingElapsedMs', 'audioAttachElapsedMs',
  'totalTranslationStartupMs', 'firstSeen', 'lastSeen', 'engine', 'candidateEngine',
  'cooldownMs', 'budgetRemaining', 'switchCount', 'failureClass', 'nearestPositionMs',
  'requestedPositionMs', 'windowStartMs', 'windowEndMs', 'holdTimeMs', 'averageMbps',
  'peakMbps', 'videoElapsedMs', 'audioElapsedMs', 'translationElapsedMs', 'packagingElapsedMs',
  'finalizeElapsedMs', 'diskReadMs', 'diskWriteMs', 'downloadId', 'sessionId', 'mode',
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
    ['DOWNLOAD', 'DOWNLOAD'], ['HUD', 'UI'],
  ];
  return rules.find(([needle]) => code.includes(needle))?.[1] || '';
}

function severityForEvent(code, rawLevel, repeats = 1) {
  if (rawLevel === 'CRITICAL' || /CRASH_LOOP|CORRUPT|DATA_LOSS/.test(code)) return 'CRITICAL';
  if (/MUX_FAILED|PACKAGING_FAILED|FINALIZE_FAILED|BACKGROUND.*FAILED|OTA_SIGNATURE_MISMATCH|HUD_SELF_HEAL_FAILED|PLAYER_HUD_MISSING/.test(code)) return 'HIGH';
  if (/HUD_STATE_INCONSISTENT/.test(code)) return 'MEDIUM';
  if (/HUD_SELF_HEAL_SUCCESS/.test(code)) return 'INFO';
  if (/REBUFFER|STARTUP_SLOW|NETWORK_TIMEOUT|HTTP_5XX|OTA_FAILED/.test(code)) return 'MEDIUM';
  if (rawLevel === 'ERROR') return repeats > 2 ? 'HIGH' : 'MEDIUM';
  if (rawLevel === 'WARNING') return 'LOW';
  return SEVERITIES.includes(rawLevel) ? rawLevel : 'INFO';
}

export function technicalSummary(report = {}) {
  const download = report.downloadDiagnostics || report.downloadState || {};
  const perf = download.downloadPerformance || {};
  const player = report.livePlayback || report.playbackStats || report.playerPlayback || {};
  const recentEvents = Array.isArray(report.safeRecentEvents) ? report.safeRecentEvents : [];
  const eventRebuffers = recentEvents.filter(e => (e.event || e.code) === 'PLAYER_REBUFFER').length;
  const rebufferCount = player.rebufferCount !== undefined ? player.rebufferCount : (eventRebuffers > 0 ? eventRebuffers : undefined);

  return {
    download: safeContext({
      queueLength: download.queueLength ?? download.activeTasks,
      activeJobs: download.activeJobs ?? download.activeTasks,
      pausedJobs: download.pausedJobs,
      failedJobs: download.failedJobs,
      stage: download.lastStage,
      retryCount: download.retryCount,
      bytes: perf.totalBytes,
      speed: perf.averageBytesPerSecond,
      stallCount: perf.stallCount,
      resumeCount: perf.resumeCount,
      packagingState: download.packagingCompleted ? 'COMPLETED' : download.packagingStarted ? 'STARTED' : undefined,
      finalizeState: download.finalizeCompleted ? 'COMPLETED' : undefined,
      freeStorageMb: download.freeStorageMb ?? download.storageSpaceAvailableMb,
      errorCategory: download.lastErrorCategory,
    }),
    player: safeContext({
      sourceType: report.sourceType || (report.livePlayback ? 'LIVE' : (report.playbackStats ? 'VOD' : undefined)),
      isLive: report.livePlayback ? true : (report.sourceType === 'LIVE' ? true : false),
      selectedCodec: player.selectedCodec,
      selectedResolution: player.selectedHeight ?? player.selectedResolution,
      liveOffsetMs: player.liveOffsetMs,
      rebufferCount: rebufferCount,
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

export function normalizePlatformTag(report = {}) {
  let rawPlat = String(report.platform || 'UNKNOWN_PLATFORM').trim();
  if (!rawPlat || /неизвестн/i.test(rawPlat) || /^unknown$/i.test(rawPlat) || /[^\x00-\x7F]/.test(rawPlat)) {
    rawPlat = 'UNKNOWN_PLATFORM';
  }
  const platform = rawPlat.toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'UNKNOWN_PLATFORM';
  const sdk = report.sdkInt ? `API${report.sdkInt}` : (report.osVersion ? String(report.osVersion).replace(/[^A-Za-z0-9.]/g, '') : '');
  return `${platform}${sdk ? `_${sdk}` : ''}`;
}

export function sanitizeMachineSignature(sig = '') {
  if (!sig || typeof sig !== 'string') return '';
  return sig.replace(/НЕИЗВЕСТНАЯ_ПЛАТФОРМА/gi, 'UNKNOWN_PLATFORM');
}

export function isSyntheticTestReport(report = {}) {
  const purpose = String(report.reportPurpose || report.purpose || '').toUpperCase();
  if (['TEST', 'UNIT_TEST', 'EMULATOR_TEST'].includes(purpose)) return true;
  if (report.isSynthetic === true || report.incidentSummary?.isSynthetic === true) return true;
  const model = String(report.model || '');
  const manufacturer = String(report.manufacturer || '');
  const family = String(report.deviceFamily || report.device_family || '');
  if (/robolectric/i.test(model) || /robolectric/i.test(manufacturer) || /robolectric/i.test(family)) return true;
  if (report.reportId && String(report.reportId).includes('TEST')) return true;
  return false;
}

export function structuredEvents(report = {}) {
  const raw = Array.isArray(report.safeRecentEvents) ? report.safeRecentEvents : [];
  const mapped = raw.map(event => {
    const code = enumValue(event.event || event.code, 'EVENT');
    const category = enumValue(event.category || inferCategoryFromCode(code), 'GENERAL');
    const inferredSubsystem = category === 'CHANNEL_GROUP' ? 'CHANNEL_GROUPS' :
      ['MEDIA3', 'CODEC', 'PLAYBACK'].includes(category) ? 'PLAYER' :
      category === 'COMPATIBILITY' ? 'AUTO_SETUP' :
      category === 'STORAGE' ? 'OFFLINE' :
      SUBSYSTEMS.find(name => category.includes(name) || code.includes(name)) || 'SYSTEM';
    const severity = enumValue(event.severity || event.level, 'INFO');
    const level = enumValue(event.level || event.severity, 'INFO');
    const isSyntheticFixture = code === 'DOWNLOAD_STATE_INCONSISTENT' && (
      /p37-|test-fixture-|test-dl-/i.test(String(event.message || '')) ||
      /p37-|test-fixture-|test-dl-/i.test(String(event.context?.downloadId || event.safeContext?.downloadId || ''))
    );
    let eventErrorCategory = null;
    if (event.errorCategory) {
      eventErrorCategory = enumValue(event.errorCategory);
    } else if (level === 'ERROR') {
      eventErrorCategory = enumValue(code);
    } else if (level === 'WARN' || level === 'WARNING') {
      eventErrorCategory = enumValue(inferCategoryFromCode(code) || code);
    }
    return {
      timestamp: Number(event.timestamp || event.ts || report.timestamp) || 0,
      category,
      subsystem: enumValue(event.subsystem, inferredSubsystem),
      stage: enumValue(event.stage || report.stage, inferStage(code)),
      event: code,
      severity: SEVERITIES.includes(severity) ? severity : severityForEvent(code, level, event.repeatCount || 1),
      level,
      result: enumValue(event.result),
      errorCategory: eventErrorCategory,
      safeContext: safeContext(event.safeContext || event.context),
      repeatCount: Number.isInteger(event.repeatCount) ? Math.min(Math.max(event.repeatCount, 1), 100000) : 1,
      ...(Number.isFinite(event.durationMs) && event.durationMs >= 0 ? { durationMs: event.durationMs } : {}),
      ...(isSyntheticFixture ? { isSyntheticFixture: true } : {}),
    };
  });

  const aggregated = [];
  for (const ev of mapped) {
    const last = aggregated[aggregated.length - 1];
    if (last && last.event === ev.event) {
      if (ev.event === 'DOWNLOAD_STATE_INCONSISTENT') {
        const lastReason = last.safeContext?.reason || last.safeContext?.state;
        const curReason = ev.safeContext?.reason || ev.safeContext?.state;
        if (!lastReason || lastReason === curReason || !curReason) {
          last.repeatCount = (last.repeatCount || 1) + (ev.repeatCount || 1);
          last.timestamp = Math.max(last.timestamp, ev.timestamp);
          continue;
        }
      } else if (ev.event === 'OTA_FAILED' || ev.event === 'OTA_RATE_LIMIT') {
        const lastOta = last.safeContext?.sessionId || last.safeContext?.otaSessionId;
        const curOta = ev.safeContext?.sessionId || ev.safeContext?.otaSessionId;
        if (!curOta || lastOta === curOta) {
          last.repeatCount = (last.repeatCount || 1) + (ev.repeatCount || 1);
          last.timestamp = Math.max(last.timestamp, ev.timestamp);
          continue;
        }
      } else if (ev.event === 'IMAGE_REQUEST_FAILED') {
        last.repeatCount = (last.repeatCount || 1) + (ev.repeatCount || 1);
        last.timestamp = Math.max(last.timestamp, ev.timestamp);
        continue;
      }
    }
    aggregated.push(ev);
  }
  return aggregated;
}

export function classifyReport(report = {}) {
  const events = structuredEvents(report);
  const now = Number(report.timestamp) || Date.now();
  const currentSessionId = report.playbackSessionId || report.livePlayback?.playbackSessionId || report.playbackStats?.playbackSessionId;

  // Annotate events with lifecycle: CURRENT_SESSION, RECENT, HISTORICAL
  for (const ev of events) {
    const evSessionId = ev.safeContext?.playbackSessionId || ev.safeContext?.sessionId;
    const isOldVersion = Boolean(
      report.appVersion && (
        (ev.safeContext?.appVersion && ev.safeContext.appVersion !== report.appVersion) ||
        (ev.safeContext?.currentVersion && ev.safeContext.currentVersion !== report.appVersion)
      )
    );
    const isStaleTime = Math.abs(now - ev.timestamp) > 3600000; // > 1 hour
    if (ev.isHistorical || isOldVersion || isStaleTime) {
      ev.lifecycle = 'HISTORICAL';
      ev.isHistorical = true;
    } else if (currentSessionId && evSessionId && evSessionId === currentSessionId) {
      ev.lifecycle = 'CURRENT_SESSION';
    } else if (Math.abs(now - ev.timestamp) <= 180000) { // within last 3 minutes
      ev.lifecycle = 'RECENT';
    } else {
      ev.lifecycle = 'HISTORICAL';
      ev.isHistorical = true;
    }
  }

  // Ensure chronological order (oldest -> newest) for recovery and precedence checks
  const isNewestFirst = events.length >= 2 && events[0].timestamp > events[events.length - 1].timestamp;
  const chronoEvents = isNewestFirst ? [...events].reverse() : events;

  // Recovery detection: Check if earlier errors were followed by newer success events
  for (let i = 0; i < chronoEvents.length; i++) {
    const ev = chronoEvents[i];
    const isError = ev.level === 'ERROR' || ev.level === 'WARNING' || ['HIGH', 'CRITICAL', 'MEDIUM'].includes(ev.severity);
    if (!isError) continue;

    for (let j = i + 1; j < chronoEvents.length; j++) {
      const later = chronoEvents[j];
      // OTA recovery
      if ((ev.category === 'OTA' || ev.event.startsWith('OTA_')) &&
          ['OTA_RATE_LIMIT', 'OTA_FAILED', 'OTA_CHECK_FAILED', 'OTA_HTTP_ERROR', 'OTA_TIMEOUT', 'OTA_DNS_ERROR'].includes(ev.event)) {
        if (['OTA_NO_UPDATE', 'OTA_CHECK_COMPLETED', 'OTA_RELEASE_PARSED', 'OTA_UPDATE_AVAILABLE', 'OTA_RATE_LIMIT_RECOVERED', 'OTA_VERSION_COMPARED'].includes(later.event)) {
          ev.recovered = true;
          ev.recoveryEvent = later.event;
          break;
        }
      }
      // HUD recovery
      if ((ev.category === 'UI' || ev.event.startsWith('HUD_') || ev.event.startsWith('PLAYER_HUD_')) &&
          ['HUD_STATE_INCONSISTENT', 'HUD_CONTROLS_DETACHED', 'PLAYER_HUD_MISSING'].includes(ev.event)) {
        if (['HUD_SELF_HEAL_SUCCESS', 'HUD_CONTROLS_REATTACHED', 'HUD_VISIBILITY_RESTORED', 'HUD_CONTROLS_ATTACHED'].includes(later.event)) {
          ev.recovered = true;
          ev.recoveryEvent = later.event;
          break;
        }
      }
      // Image request recovery
      if (ev.event === 'IMAGE_REQUEST_FAILED' && (later.event === 'IMAGE_REQUEST_RECOVERED' || later.event === 'IMAGE_REQUEST_RETRY')) {
        ev.recovered = true;
        ev.recoveryEvent = later.event;
        break;
      }
      // Network recovery
      if ((ev.event === 'NETWORK_ENGINE_FALLBACK' || ev.event === 'NETWORK_TIMEOUT') &&
          ['NETWORK_RECOVERED', 'PLAYER_PLAYBACK_PROGRESS', 'DOWNLOAD_COMPLETED'].includes(later.event)) {
        ev.recovered = true;
        ev.recoveryEvent = later.event;
        break;
      }
      // Player error recovery
      if ((ev.category === 'PLAYER' || ev.event.startsWith('PLAYER_')) &&
          ['PLAYER_HTTP_ERROR', 'PLAYER_REBUFFER', 'PLAYER_ERROR', 'PLAYER_SOURCE_FAILED'].includes(ev.event)) {
        if (['PLAYER_PLAYBACK_PROGRESS', 'PLAYER_SOURCE_RECOVERED', 'PLAYER_RECOVERED', 'PLAYER_STATE_READY'].includes(later.event)) {
          ev.recovered = true;
          ev.recoveryEvent = later.event;
          break;
        }
      }
    }
  }

  const rank = { INFO: 0, LOW: 1, MEDIUM: 2, HIGH: 3, CRITICAL: 4 };

  // Explicit Trigger has highest precedence unless the trigger event itself was recovered by a newer event
  const trigger = report.triggerEvent || report.trigger_event || report.trigger || report.incidentSummary?.triggerCode;
  let primary = null;

  if (trigger) {
    const triggerCode = String(trigger).toUpperCase();
    const trigEv = [...chronoEvents].reverse().find(e => (e.event || e.code) === triggerCode);
    if (trigEv && trigEv.recovered) {
      primary = null;
    } else if (trigEv) {
      primary = trigEv;
    } else {
      primary = {
        event: triggerCode,
        category: inferCategoryFromCode(triggerCode),
        subsystem: inferSubsystemFromCode(triggerCode),
        stage: inferStage(triggerCode),
        severity: severityForEvent(triggerCode, 'ERROR'),
        level: 'ERROR',
        lifecycle: 'TRIGGER',
        errorCategory: inferCategoryFromCode(triggerCode),
      };
    }
  }

  const unrecovered = chronoEvents.filter(e => !e.recovered && !e.isSyntheticFixture);
  const currentError = [...unrecovered].reverse().find(e => e.lifecycle === 'CURRENT_SESSION' && (e.level === 'ERROR' || ['HIGH', 'CRITICAL'].includes(e.severity)));
  const currentWarn = [...unrecovered].reverse().find(e => e.lifecycle === 'CURRENT_SESSION' && (e.level === 'WARNING' || ['MEDIUM', 'LOW'].includes(e.severity)));
  const recentError = [...unrecovered].reverse().find(e => e.lifecycle === 'RECENT' && (e.level === 'ERROR' || ['HIGH', 'CRITICAL'].includes(e.severity)));
  const recentWarn = [...unrecovered].reverse().find(e => e.lifecycle === 'RECENT' && (e.level === 'WARNING' || ['MEDIUM', 'LOW'].includes(e.severity)));
  const freshPrimary = currentError || currentWarn || recentError || recentWarn;

  if (!primary) {
    const histError = [...unrecovered].reverse().find(e => e.lifecycle === 'HISTORICAL' && (e.level === 'ERROR' || ['HIGH', 'CRITICAL'].includes(e.severity)));
    const histWarn = [...unrecovered].reverse().find(e => e.lifecycle === 'HISTORICAL' && (e.level === 'WARNING' || ['MEDIUM', 'LOW'].includes(e.severity)));
    primary = freshPrimary || histError || histWarn;
  } else if (primary.lifecycle === 'HISTORICAL' && freshPrimary) {
    primary = freshPrimary;
  }

  let last = primary;
  if (!last) {
    if (report.errorCategory || report.reportPurpose === 'TEST') {
      last = [...chronoEvents].reverse().find(e => !e.recovered && !e.isSyntheticFixture && (e.level === 'ERROR' || ['HIGH', 'CRITICAL'].includes(e.severity))) || chronoEvents.at(-1);
    } else {
      last = chronoEvents.at(-1);
    }
  }

  const errorCategory = enumValue(primary?.category || primary?.errorCategory || report.errorCategory || (last && !last.recovered && !last.isSyntheticFixture && last.level === 'ERROR' ? (last.category || last.errorCategory) : ''));
  const subsystem = enumValue(report.subsystem || primary?.subsystem || last?.subsystem, 'SYSTEM');
  const stage = enumValue(report.stage || primary?.stage || last?.stage);
  const explicit = enumValue(report.severity);

  // Active vs historical counts
  const hasEvents = chronoEvents.length > 0;
  const activeEvents = chronoEvents.filter(e => !e.recovered && !e.isSyntheticFixture && !e.isHistorical && e.lifecycle !== 'HISTORICAL' && (e.level === 'ERROR' || e.level === 'WARNING' || ['HIGH', 'CRITICAL', 'MEDIUM'].includes(e.severity)));
  const historicalEvents = chronoEvents.filter(e => e.isHistorical || e.lifecycle === 'HISTORICAL');
  const activeEventCount = hasEvents ? activeEvents.length : (errorCategory ? 1 : 0);
  const historicalEventCount = historicalEvents.length;
  const reportActiveIncident = hasEvents
    ? (activeEvents.length > 0 || (primary != null && primary.lifecycle === 'TRIGGER'))
    : Boolean(errorCategory || explicit === 'HIGH' || explicit === 'CRITICAL');

  let computedSeverity = 'INFO';
  if (!reportActiveIncident && chronoEvents.length > 0) {
    computedSeverity = chronoEvents.some(e => e.recovered) ? 'LOW' : 'INFO';
  } else if (primary && primary.lifecycle === 'TRIGGER') {
    computedSeverity = primary.severity || 'MEDIUM';
  } else if (activeEvents.length > 0) {
    computedSeverity = activeEvents.reduce((best, event) => rank[event.severity] > rank[best] ? event.severity : best, errorCategory ? 'MEDIUM' : 'INFO');
  } else if (chronoEvents.some(e => e.recovered)) {
    computedSeverity = 'LOW';
  } else if (errorCategory) {
    computedSeverity = 'MEDIUM';
  }

  const severity = SEVERITIES.includes(explicit) ? explicit : computedSeverity;
  const signature = buildClassificationSignature(report, { events, errorCategory, subsystem, stage, severity, primaryEvent: primary });
  return {
    events,
    errorCategory,
    subsystem,
    stage,
    severity,
    primaryEvent: primary || null,
    signature,
    reportActiveIncident,
    activeEventCount,
    historicalEventCount,
    isHistorical: !reportActiveIncident
  };
}

export function buildClassificationSignature(report = {}, classified = null) {
  const sanitizedIncomingSig = sanitizeMachineSignature(report.errorSignature);
  if (sanitizedIncomingSig && /^[A-Za-z0-9_|:.-]{1,160}$/.test(sanitizedIncomingSig)) {
    return sanitizedIncomingSig;
  }

  const platformTag = normalizePlatformTag(report);
  const isStructured = Array.isArray(report.safeRecentEvents) && report.safeRecentEvents.some(ev => ev.stage || ev.subsystem || (ev.event && !ev.code));

  const c = classified || classifyReport(report);
  const primary = c.primaryEvent;
  const hasExplicitTrigger = Boolean(report.triggerEvent || report.trigger_event || report.trigger || report.incidentSummary?.triggerCode);

  if (primary) {
    const cat = primary.category || c.errorCategory || 'GENERAL';
    const sub = c.subsystem || primary.subsystem || 'SYSTEM';
    const stg = primary.stage || c.stage || 'GENERAL';
    const code = primary.event || primary.code || 'UNKNOWN';
    if (isStructured) {
      const fourth = hasExplicitTrigger ? code : (primary.errorCategory || c.errorCategory || code);
      return [cat, sub, stg, fourth, primary.safeContext?.exceptionClassSafe || 'NONE', primary.safeContext?.sourceType || report.sourceType || 'UNKNOWN'].join('|');
    }
    return `${cat}|${code}|${platformTag}`;
  }

  if (report.errorCategory || c.errorCategory) {
    const cat = report.errorCategory || c.errorCategory;
    if (isStructured) {
      return [cat, c.subsystem || 'SYSTEM', c.stage || 'GENERAL', cat, 'NONE', report.sourceType || 'UNKNOWN'].join('|');
    }
    return `${cat}|UNKNOWN_CODE|${platformTag}`;
  }

  if (report.reportPurpose === 'USER' || report.reportPurpose === 'USER_REPORT' || report.userFacingReport) {
    return `GENERAL|USER_REPORT|${platformTag}`;
  }

  if (report.reportPurpose === 'AUTO_BETA') {
    return `SYSTEM|AUTO_BETA_ROUTINE|${platformTag}`;
  }

  return '';
}

export function generateDeterministicSummary(report = {}) {
  const classified = classifyReport(report);
  const dl = report.downloadDiagnostics || report.downloadState || {};
  const perf = dl.downloadPerformance || {};
  const lp = report.livePlayback || {};
  const primary = classified.primaryEvent;

  // 1. Download packaging bottleneck
  const packMs = Number(perf.packagingElapsedMs || 0);
  const dlMs = Number(perf.totalElapsedMs || 0);
  if (packMs > 30000 || (packMs > 5000 && dlMs > 0 && packMs > dlMs * 2)) {
    return {
      primary: 'DOWNLOAD_PACKAGING_SLOW',
      evidence: [
        `download network: ${Math.round(dlMs / 1000)}s`,
        `packaging: ${Math.round(packMs / 1000)}s`,
        `finalize: ${Math.round(Number(perf.finalizeElapsedMs || 0) / 1000)}s`,
      ],
      conclusion: 'bottleneck = packaging',
      confidence: 'HIGH',
      descriptionRu: 'Скачивание завершилось быстро, но этап упаковки контейнера занял основное время.'
    };
  }

  // 2. Live seek recovery
  const liveOffset = Number(lp.liveOffsetMs || 0);
  const seekClampEv = classified.events.find(e => e.event === 'LIVE_SEEK_CLAMPED');
  const liveRecoveryEv = classified.events.find(e => e.event === 'LIVE_RECOVERY_STARTED' || e.event === 'LIVE_RECOVERY_SUCCEEDED');
  if (seekClampEv || liveRecoveryEv || (lp.isLive && liveOffset > 300000)) {
    return {
      primary: 'LIVE_SEEK_RECOVERY',
      evidence: [
        `live window offset: ${Math.round(liveOffset / 1000)}s`,
        `seek clamped: ${Boolean(seekClampEv)}`,
        `recovery attempted: ${Boolean(liveRecoveryEv)}`,
      ],
      conclusion: 'live position adjusted to safe window',
      confidence: 'HIGH',
      descriptionRu: 'Позиция прямого эфира была скорректирована в доступное окно воспроизведения.'
    };
  }

  // 3. Translation startup slow or reused
  const transEv = classified.events.find(e => e.category === 'TRANSLATION' && (e.event === 'TRANSLATION_REUSED' || e.event === 'TRANSLATION_READY' || e.event === 'TRANSLATION_STARTUP_SLOW'));
  if (transEv) {
    const isReused = transEv.event === 'TRANSLATION_REUSED' || transEv.safeContext?.isReused === 'true';
    const totalMs = Number(transEv.safeContext?.totalTranslationStartupMs || transEv.safeContext?.elapsedMs || 0);
    return {
      primary: isReused ? 'TRANSLATION_REUSED' : (totalMs > 30000 ? 'TRANSLATION_STARTUP_SLOW' : 'TRANSLATION_READY'),
      evidence: [
        `reused: ${isReused}`,
        `total startup: ${Math.round(totalMs / 1000)}s`,
        `source: ${transEv.safeContext?.source || 'api'}`,
      ],
      conclusion: isReused ? 'audio track reused from cache' : (totalMs > 30000 ? 'provider processing delay' : 'normal startup'),
      confidence: 'HIGH',
      descriptionRu: isReused ? 'Готовый перевод был найден в кэше и воспроизведён без повторного запроса.' : 'Перевод был успешно получен от внешнего сервиса.'
    };
  }

  // 4. Network engine fallback
  const fallbackEv = classified.events.find(e => e.event === 'NETWORK_ENGINE_FALLBACK');
  if (fallbackEv) {
    return {
      primary: 'NETWORK_ENGINE_FALLBACK',
      evidence: [
        `engine: ${fallbackEv.safeContext?.engine || 'UNKNOWN'}`,
        `reason: ${fallbackEv.safeContext?.reason || 'consecutive timeouts'}`,
      ],
      conclusion: 'controlled engine switch with cooldown active',
      confidence: 'HIGH',
      descriptionRu: 'Сетевой движок был переключён на резервный с соблюдением периода охлаждения.'
    };
  }

  // 5. Primary event from current session
  if (primary) {
    return {
      primary: primary.event,
      evidence: [
        `category: ${primary.category}`,
        `subsystem: ${primary.subsystem}`,
        `stage: ${primary.stage}`,
        `severity: ${primary.severity}`,
      ],
      conclusion: `issue detected in ${primary.subsystem}`,
      confidence: primary.lifecycle === 'CURRENT_SESSION' ? 'HIGH' : 'MEDIUM',
      descriptionRu: `Зафиксировано событие ${primary.event} на этапе ${primary.stage}.`
    };
  }

  return {
    primary: 'NO_ACTIVE_ISSUES',
    evidence: ['all current session checks normal'],
    conclusion: 'stable operation',
    confidence: 'HIGH',
    descriptionRu: 'В текущей сессии активных сбоев не зафиксировано.'
  };
}

function parseStageTag(tagStr = '') {
  const tag = String(tagStr).toLowerCase().replace(/^[-.]/, '');
  if (!tag) return 1000000; // STABLE / FINAL
  if (tag === 'dev') return -2;
  if (tag.startsWith('alpha')) return -1;
  if (tag.startsWith('beta')) {
    const num = Number(tag.match(/\d+/)?.[0]) || 1;
    return 100 + num;
  }
  if (tag.startsWith('rc')) {
    const num = Number(tag.match(/\d+/)?.[0]) || 1;
    return 1000 + num;
  }
  return 1000000;
}

export function parseVoxVersion(value) {
  if (!value) return null;
  const str = String(value).trim().replace(/^v/i, '');
  const match = str.match(/^(\d+)\.(\d+)-(?:vox|vot)\.(\d+)(?:\.(\d+))?(?:-([a-zA-Z0-9.]+))?$/i);
  if (match) {
    const major = Number(match[1]) || 0;
    const minor = Number(match[2]) || 0;
    const voxMajor = Number(match[3]) || 0;
    const voxMinor = Number(match[4]) || 0;
    const stage = parseStageTag(match[5]);
    return [major, minor, voxMajor, voxMinor, stage];
  }
  const simpleMatch = str.match(/^(\d+)(?:\.(\d+))?(?:-([a-zA-Z0-9.]+))?$/i);
  if (simpleMatch) {
    const voxMajor = Number(simpleMatch[1]) || 0;
    const voxMinor = Number(simpleMatch[2]) || 0;
    const stage = parseStageTag(simpleMatch[3]);
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
  if (sig.includes('LIVE_SEEK') || (subsystem === 'PLAYER' && stage === 'LIVE_SEEK')) {
    return 'Истекло окно прямого эфира при перемотке';
  }
  if (sig.includes('FINALIZE_INCONSISTENT') || (subsystem === 'DOWNLOAD' && stage === 'FINALIZE_INCONSISTENT')) {
    return 'Несогласованное состояние завершения скачивания';
  }
  if (sig.includes('DNS') || subsystem === 'NETWORK') {
    return sig.includes('OTA') ? 'Сбой DNS при проверке обновлений' : 'Сбой сетевого соединения или DNS';
  }
  if (subsystem === 'CHANNEL_GROUPS' || sig.includes('CHANNEL_GROUP')) {
    return 'Ошибка управления локальными группами каналов';
  }
  if (subsystem === 'AUTO_SETUP' || sig.includes('AUTO_SETUP')) {
    return 'Сбой модуля автонастройки кодеков';
  }
  if (sig.includes('PLAYER_HUD_MISSING') || errorCategory.includes('PLAYER_HUD_MISSING') || sig.includes('HUD_MISSING')) {
    return 'Исчезновение панели управления (HUD) после смены видео';
  }
  if (sig.includes('HUD_SELF_HEAL_FAILED') || errorCategory.includes('HUD_SELF_HEAL_FAILED')) {
    return 'Сбой автоматического восстановления HUD';
  }
  if (sig.includes('HUD_STATE_INCONSISTENT') || errorCategory.includes('HUD_STATE_INCONSISTENT')) {
    return 'Рассинхронизация элементов управления HUD';
  }
  if (subsystem) {
    return `Сбой подсистемы ${subsystem}${stage ? ` на этапе ${stage}` : ''}`;
  }
  return `Инцидент ${errorCategory || signature || 'системного сбоя'}`;
}

export function extractReportIssues(report = {}) {
  const payload = typeof report.payload_json === 'string'
    ? JSON.parse(report.payload_json || '{}')
    : (report.payload_json || report);

  const classified = classifyReport(payload);
  const events = classified.events || [];

  const issueMap = new Map();
  for (const ev of events) {
    if (ev.level === 'ERROR' || ev.level === 'WARNING' || ['HIGH', 'CRITICAL', 'MEDIUM'].includes(ev.severity)) {
      const code = ev.event;
      if (!issueMap.has(code)) {
        issueMap.set(code, {
          code,
          subsystem: ev.subsystem,
          category: ev.category,
          severity: ev.severity,
          lifecycle: ev.lifecycle,
          recovered: Boolean(ev.recovered),
          repeatCount: ev.repeatCount || 1,
          timestamp: ev.timestamp
        });
      } else {
        const item = issueMap.get(code);
        item.repeatCount += (ev.repeatCount || 1);
        item.timestamp = Math.max(item.timestamp, ev.timestamp);
        if (ev.recovered) item.recovered = true;
      }
    }
  }

  const rank = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1, INFO: 0 };
  const sorted = [...issueMap.values()].sort((a, b) => {
    if (a.recovered !== b.recovered) {
      return a.recovered ? 1 : -1;
    }
    if (a.lifecycle !== b.lifecycle) {
      if (a.lifecycle === 'TRIGGER') return -1;
      if (b.lifecycle === 'TRIGGER') return 1;
      if (a.lifecycle === 'CURRENT_SESSION') return -1;
      if (b.lifecycle === 'CURRENT_SESSION') return 1;
      if (a.lifecycle === 'RECENT') return -1;
      if (b.lifecycle === 'RECENT') return 1;
    }
    return (rank[b.severity] || 0) - (rank[a.severity] || 0);
  });

  const formatConcise = (issue) => {
    if (!issue) return 'USER REPORT';
    const sub = issue.subsystem || 'SYSTEM';
    const code = issue.code.replace(/^(PLAYER_|OTA_|DOWNLOAD_|IMAGE_)/, '');
    const recStr = issue.recovered ? ' (Восстановлено)' : '';
    return `${sub} · ${code}${recStr}`;
  };

  const primaryIssue = sorted.length > 0
    ? formatConcise(sorted[0])
    : (report.errorCategory ? `${report.subsystem || 'APP'} · ${report.errorCategory}` : 'USER REPORT');

  const secondaryIssues = sorted.slice(1).map(i => formatConcise(i));
  const primaryCode = sorted.length > 0 ? sorted[0].code : (report.errorCategory || null);

  const enrichedIssues = sorted.map(i => ({
    ...i,
    labelRu: getFriendlyIssueLabel(i.code),
  }));

  return {
    primaryIssue,
    primaryIssueLabelRu: getFriendlyIssueLabel(primaryCode),
    secondaryIssues,
    allIssues: enrichedIssues
  };
}

export function groupOtaReports(reports = []) {
  const groups = new Map();
  for (const r of reports) {
    const payload = typeof r.payload_json === 'string' ? JSON.parse(r.payload_json || '{}') : (r.payload_json || r);
    const channel = payload.channel || r.channel || 'stable';
    const version = payload.appVersion || r.app_version || 'unknown';
    const devFamily = payload.deviceFamily || r.device_family || 'unknown';
    const reason = r.error_signature || 'OTA_FAILED';
    const key = `${channel}:${version}:${devFamily}:${reason}`;

    if (!groups.has(key)) {
      groups.set(key, {
        channel,
        version,
        deviceFamily: devFamily,
        reason,
        count: 1,
        firstSeen: r.created_at || r.timestamp || Date.now(),
        lastSeen: r.created_at || r.timestamp || Date.now(),
        reportIds: [r.report_id || r.id]
      });
    } else {
      const g = groups.get(key);
      g.count++;
      g.firstSeen = Math.min(g.firstSeen, r.created_at || r.timestamp || Date.now());
      g.lastSeen = Math.max(g.lastSeen, r.created_at || r.timestamp || Date.now());
      g.reportIds.push(r.report_id || r.id);
    }
  }
  return [...groups.values()];
}

export function groupRebufferReports(reports = []) {
  const groups = new Map();
  for (const r of reports) {
    const payload = typeof r.payload_json === 'string' ? JSON.parse(r.payload_json || '{}') : (r.payload_json || r);
    const events = payload.safeRecentEvents || payload.events || [];
    for (const ev of events) {
      if ((ev.code || ev.event) === 'PLAYER_REBUFFER') {
        const ctx = ev.context || ev.safeContext || {};
        const sessionId = ctx.playbackSessionId || 'default';
        const resolution = ctx.selectedHeight || 'unknown';
        const codec = ctx.selectedCodec || 'unknown';
        const key = `${sessionId}:${resolution}:${codec}`;

        if (!groups.has(key)) {
          groups.set(key, {
            sessionId,
            resolution,
            codec,
            count: Number(ctx.rebufferCount) || 1,
            reportId: r.report_id || r.id
          });
        } else {
          const g = groups.get(key);
          g.count += (Number(ctx.rebufferCount) || 1);
        }
      }
    }
  }
  return [...groups.values()];
}

export function getNeedsAttentionSummary(reports = []) {
  const attentionStatuses = new Set(['IN_PROGRESS', 'KNOWN_ISSUE', 'FIXED_PENDING_VERIFICATION']);
  const filtered = reports.filter(r => attentionStatuses.has(r.status));

  const issueCounts = new Map();
  for (const r of filtered) {
    const { primaryIssue } = extractReportIssues(r);
    issueCounts.set(primaryIssue, (issueCounts.get(primaryIssue) || 0) + 1);
  }

  return [...issueCounts.entries()]
    .map(([issue, count]) => ({ issue, count }))
    .sort((a, b) => b.count - a.count);
}
