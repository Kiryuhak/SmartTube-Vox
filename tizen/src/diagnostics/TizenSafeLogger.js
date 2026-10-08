/**
 * TizenSafeLogger - Безопасный логгер событий SmartTube VOX для Samsung Tizen.
 *
 * Особенности:
 * - Структурированные события по строгим категориям и кодам;
 * - Гарантированная санитизация сообщений и контекста ДО сохранения;
 * - Ограниченное кольцевое хранилище (до 500 событий / 256 КБ);
 * - Удаление устаревших событий старше 7 дней;
 * - Исключение токенов, cookies, паролей, IP-адресов и персональных данных (PII).
 */

const VoxLogLevel = Object.freeze({
  DEBUG: 'DEBUG',
  INFO: 'INFO',
  WARNING: 'WARNING',
  ERROR: 'ERROR',
});

const VoxLogCategory = Object.freeze({
  APP: 'APP',
  PLAYER: 'PLAYER',
  MEDIA3: 'MEDIA3',
  CODEC: 'CODEC',
  DOWNLOAD: 'DOWNLOAD',
  TRANSLATION: 'TRANSLATION',
  YANDEX_AUTH: 'YANDEX_AUTH',
  NETWORK: 'NETWORK',
  PROXY: 'PROXY',
  COMPATIBILITY: 'COMPATIBILITY',
  STORAGE: 'STORAGE',
  TIZEN: 'TIZEN',
  DIAGNOSTICS: 'DIAGNOSTICS',
  BACKGROUND: 'BACKGROUND',
  OTA: 'OTA',
});

const VoxLogCode = Object.freeze({
  APP_START: 'APP_START',
  PLAYER_INIT_FAILED: 'PLAYER_INIT_FAILED',
  PLAYER_RENDERER_ERROR: 'PLAYER_RENDERER_ERROR',
  PLAYER_DECODER_ERROR: 'PLAYER_DECODER_ERROR',
  PLAYER_DECODER_FALLBACK: 'PLAYER_DECODER_FALLBACK',
  PLAYER_STARTUP_SLOW: 'PLAYER_STARTUP_SLOW',
  PLAYER_REBUFFER: 'PLAYER_REBUFFER',
  PLAYER_LOCAL_SOURCE_ERROR: 'PLAYER_LOCAL_SOURCE_ERROR',
  PLAYER_HTTP_ERROR: 'PLAYER_HTTP_ERROR',
  PLAYER_SOURCE_ERROR: 'PLAYER_SOURCE_ERROR',
  PLAYER_TRACK_FALLBACK: 'PLAYER_TRACK_FALLBACK',
  AVPLAY_INIT_FAILED: 'AVPLAY_INIT_FAILED',
  AVPLAY_ERROR: 'AVPLAY_ERROR',
  HTML5_FALLBACK_USED: 'HTML5_FALLBACK_USED',
  CODEC_UNKNOWN: 'CODEC_UNKNOWN',
  CODEC_FALLBACK_USED: 'CODEC_FALLBACK_USED',
  DOWNLOAD_PACKAGING_FAILED: 'DOWNLOAD_PACKAGING_FAILED',
  DOWNLOAD_FINALIZE_FAILED: 'DOWNLOAD_FINALIZE_FAILED',
  BACKGROUND_PLAYBACK_REQUESTED: 'BACKGROUND_PLAYBACK_REQUESTED',
  BACKGROUND_PLAYBACK_ALLOWED: 'BACKGROUND_PLAYBACK_ALLOWED',
  BACKGROUND_PLAYBACK_BLOCKED: 'BACKGROUND_PLAYBACK_BLOCKED',
  BACKGROUND_AUDIO_ONLY_ENTER: 'BACKGROUND_AUDIO_ONLY_ENTER',
  BACKGROUND_AUDIO_ONLY_EXIT: 'BACKGROUND_AUDIO_ONLY_EXIT',
  BACKGROUND_PLAYER_CONTINUED: 'BACKGROUND_PLAYER_CONTINUED',
  BACKGROUND_PLAYER_PAUSED: 'BACKGROUND_PLAYER_PAUSED',
  BACKGROUND_SERVICE_STARTED: 'BACKGROUND_SERVICE_STARTED',
  BACKGROUND_SERVICE_STOPPED: 'BACKGROUND_SERVICE_STOPPED',
  BACKGROUND_AUDIO_FOCUS_GAIN: 'BACKGROUND_AUDIO_FOCUS_GAIN',
  BACKGROUND_AUDIO_FOCUS_LOSS: 'BACKGROUND_AUDIO_FOCUS_LOSS',
  BACKGROUND_MEDIASESSION_ACTIVE: 'BACKGROUND_MEDIASESSION_ACTIVE',
  BACKGROUND_MEDIASESSION_RELEASED: 'BACKGROUND_MEDIASESSION_RELEASED',
  OTA_CHECK_STARTED: 'OTA_CHECK_STARTED',
  OTA_RELEASE_FOUND: 'OTA_RELEASE_FOUND',
  OTA_VERSION_COMPARED: 'OTA_VERSION_COMPARED',
  OTA_ASSET_SELECTED: 'OTA_ASSET_SELECTED',
  OTA_DOWNLOAD_STARTED: 'OTA_DOWNLOAD_STARTED',
  OTA_DOWNLOAD_COMPLETED: 'OTA_DOWNLOAD_COMPLETED',
  OTA_HASH_VERIFIED: 'OTA_HASH_VERIFIED',
  OTA_SIGNATURE_VERIFIED: 'OTA_SIGNATURE_VERIFIED',
  OTA_INSTALL_REQUESTED: 'OTA_INSTALL_REQUESTED',
  OTA_FAILED: 'OTA_FAILED',
  TRANSLATED_AUDIO_SYNC_WARNING: 'TRANSLATED_AUDIO_SYNC_WARNING',
  TRANSLATION_TIMEOUT: 'TRANSLATION_TIMEOUT',
  TRANSLATION_BACKEND_ERROR: 'TRANSLATION_BACKEND_ERROR',
  LIVE_TRANSLATION_START: 'LIVE_TRANSLATION_START',
  LIVE_TRANSLATION_BUFFER_READY: 'LIVE_TRANSLATION_BUFFER_READY',
  LIVE_TRANSLATION_BUFFER_LOW: 'LIVE_TRANSLATION_BUFFER_LOW',
  LIVE_TRANSLATION_DELAY_INCREASED: 'LIVE_TRANSLATION_DELAY_INCREASED',
  LIVE_TRANSLATION_DELAY_DECREASED: 'LIVE_TRANSLATION_DELAY_DECREASED',
  LIVE_TRANSLATION_REBUFFER: 'LIVE_TRANSLATION_REBUFFER',
  LIVE_TRANSLATION_BACKEND_TIMEOUT: 'LIVE_TRANSLATION_BACKEND_TIMEOUT',
  LIVE_TRANSLATION_FALLBACK_ORIGINAL: 'LIVE_TRANSLATION_FALLBACK_ORIGINAL',
  LIVE_TRANSLATION_STOPPED: 'LIVE_TRANSLATION_STOPPED',
  AUTH_STARTED: 'AUTH_STARTED',
  DEVICE_CODE_RECEIVED: 'DEVICE_CODE_RECEIVED',
  AUTH_SUCCESS: 'AUTH_SUCCESS',
  AUTH_TIMEOUT: 'AUTH_TIMEOUT',
  AUTH_FAILED: 'AUTH_FAILED',
  TOKEN_SAVE_SUCCESS: 'TOKEN_SAVE_SUCCESS',
  TOKEN_SAVE_FAILED: 'TOKEN_SAVE_FAILED',
  PROXY_CONNECTED: 'PROXY_CONNECTED',
  PROXY_FAILED: 'PROXY_FAILED',
  DEVICE_PROFILE_UNKNOWN: 'DEVICE_PROFILE_UNKNOWN',
  DIAGNOSTIC_SEND_FAILED: 'DIAGNOSTIC_SEND_FAILED',
  DIAGNOSTIC_SEND_SUCCESS: 'DIAGNOSTIC_SEND_SUCCESS',
  STORAGE_CLEARED: 'STORAGE_CLEARED',
});

const MAX_EVENTS = 500;
const RETENTION_MS = 7 * 24 * 60 * 60 * 1000; // 7 days
const STORAGE_KEY = 'vox_safe_tizen_logs';

class TizenSafeLogger {
  constructor() {
    this._events = [];
    this._load();
  }

  static sanitize(input) {
    if (!input || typeof input !== 'string') return '';
    let result = input;
    // Strip URL query parameters
    result = result.replace(/(https?:\/\/[^\s?"'<>]+)\?[^\s"'<>]+/g, '$1');
    // Auth headers & Bearer tokens
    result = result.replace(/(Authorization|Cookie|Set-Cookie):\s*[^\r\n]+/gi, '$1: [REDACTED]');
    result = result.replace(/Bearer\s+[A-Za-z0-9_\-\.]+/gi, 'Bearer [REDACTED]');
    // Key-value tokens & passwords
    result = result.replace(/\b(access_token|refresh_token|id_token|client_secret|device_code|user_code|api_key|token|password|passwd|pwd|secret)\s*[:=]\s*[^&\s"',;]+/gi, '$1=[REDACTED]');
    // URL auth parameters
    result = result.replace(/([?&](?:key|sig|signature|auth|token|session)=)[^&\s"',;]+/gi, '$1[REDACTED]');
    // IPs
    result = result.replace(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g, '[IP_REDACTED]');
    result = result.replace(/\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}\b/g, '[IP_REDACTED]');
    return result;
  }

  d(category, code, message, context = null) {
    this._log(VoxLogLevel.DEBUG, category, code, message, context);
  }

  i(category, code, message, context = null) {
    this._log(VoxLogLevel.INFO, category, code, message, context);
  }

  w(category, code, message, context = null) {
    this._log(VoxLogLevel.WARNING, category, code, message, context);
  }

  e(category, code, message, context = null, error = null) {
    let msg = message;
    if (error && error.message) {
      msg = `${msg} [${error.name || 'Error'}: ${error.message}]`;
    }
    this._log(VoxLogLevel.ERROR, category, code, msg, context);
  }

  _log(level, category, code, rawMsg, rawContext) {
    const sanitizedMsg = TizenSafeLogger.sanitize(rawMsg).slice(0, 500);
    const sanitizedCode = TizenSafeLogger.sanitize(code).slice(0, 64);

    let sanitizedContext = null;
    if (rawContext && typeof rawContext === 'object') {
      sanitizedContext = {};
      const forbiddenKeys = ['password', 'passwd', 'pwd', 'secret', 'token', 'access_token', 'refresh_token', 'id_token', 'device_code', 'user_code', 'api_key', 'authorization', 'cookie', 'session'];
      for (const [k, v] of Object.entries(rawContext)) {
        const safeKey = TizenSafeLogger.sanitize(k).slice(0, 32);
        const isForbidden = forbiddenKeys.some(fk => safeKey.toLowerCase().includes(fk));
        sanitizedContext[safeKey] = isForbidden ? '[REDACTED]' : TizenSafeLogger.sanitize(String(v)).slice(0, 200);
      }
    }

    if (level !== VoxLogLevel.DEBUG) {
      const event = {
        timestamp: Date.now(),
        level,
        category,
        code: sanitizedCode,
        message: sanitizedMsg,
        context: sanitizedContext,
      };

      this._prune();
      if (this._events.length >= MAX_EVENTS) {
        this._events.shift();
      }
      this._events.push(event);
      this._save();
    }
  }

  getEvents(maxCount = MAX_EVENTS, minLevel = VoxLogLevel.INFO) {
    this._prune();
    const levelOrder = { DEBUG: 0, INFO: 1, WARNING: 2, ERROR: 3 };
    const minVal = levelOrder[minLevel] ?? 1;

    return this._events
      .filter((e) => (levelOrder[e.level] ?? 1) >= minVal)
      .slice(-maxCount)
      .reverse();
  }

  getRecentEvents(maxCount = 50) {
    this._prune();
    return this._events.slice(-maxCount).reverse();
  }

  getEventCount() {
    return this._events.length;
  }

  clearLogs() {
    this._events = [];
    if (typeof localStorage !== 'undefined') {
      try {
        localStorage.removeItem(STORAGE_KEY);
      } catch (ignored) {}
    }
  }

  getFormattedJournal() {
    const events = this.getEvents();
    if (events.length === 0) {
      return 'Ошибок пока не зафиксировано.';
    }

    let out = '=== Журнал событий SmartTube VOX (Tizen) ===\n\n';
    for (const ev of events) {
      const timeStr = new Date(ev.timestamp).toISOString().replace('T', ' ').substring(0, 19);
      out += `[${timeStr}] [${ev.level}] [${ev.category}] ${ev.code}\n`;
      out += `Описание: ${ev.message}\n`;
      if (ev.context) {
        out += `Контекст: ${JSON.stringify(ev.context)}\n`;
      }
      out += '----------------------------------------\n';
    }
    return out;
  }

  _prune() {
    const cutoff = Date.now() - RETENTION_MS;
    this._events = this._events.filter((e) => e.timestamp >= cutoff);
  }

  _load() {
    if (typeof localStorage !== 'undefined') {
      try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (raw) {
          const parsed = JSON.parse(raw);
          if (Array.isArray(parsed)) {
            const cutoff = Date.now() - RETENTION_MS;
            this._events = parsed.filter((e) => e && e.timestamp >= cutoff).slice(-MAX_EVENTS);
          }
        }
      } catch (ignored) {
        this._events = [];
      }
    }
  }

  _save() {
    if (typeof localStorage !== 'undefined') {
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(this._events));
      } catch (ignored) {}
    }
  }
}

const safeLoggerInstance = new TizenSafeLogger();

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    TizenSafeLogger,
    safeLogger: safeLoggerInstance,
    VoxLogLevel,
    VoxLogCategory,
    VoxLogCode,
  };
}
