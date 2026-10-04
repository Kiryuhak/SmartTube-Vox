/**
 * Категории ошибок VOX для Tizen (соответствует модели VotErrorCategory на Android).
 */
const TizenVoxErrorCategory = {
  NETWORK_ERROR: 'NETWORK_ERROR',
  AUTH_REQUIRED: 'AUTH_REQUIRED',
  AUTH_EXPIRED: 'AUTH_EXPIRED',
  UNSUPPORTED_VIDEO: 'UNSUPPORTED_VIDEO',
  UNSUPPORTED_LANGUAGE: 'UNSUPPORTED_LANGUAGE',
  AUDIO_STREAM_NOT_FOUND: 'AUDIO_STREAM_NOT_FOUND',
  TRANSLATION_NOT_AVAILABLE: 'TRANSLATION_NOT_AVAILABLE',
  BACKEND_BUSY: 'BACKEND_BUSY',
  BACKEND_TEMPORARY_ERROR: 'BACKEND_TEMPORARY_ERROR',
  PROXY_ERROR: 'PROXY_ERROR',
  UPLOAD_ERROR: 'UPLOAD_ERROR',
  RATE_LIMIT: 'RATE_LIMIT',
  DOWNLOADS_NOT_IMPLEMENTED: 'DOWNLOADS_NOT_IMPLEMENTED_TIZEN',
  UNKNOWN_ERROR: 'UNKNOWN_ERROR'
};

const TizenVoxErrorMessagesRu = {
  [TizenVoxErrorCategory.NETWORK_ERROR]: 'Не удалось подключиться к сервису перевода',
  [TizenVoxErrorCategory.AUTH_REQUIRED]: 'Требуется авторизация в Яндекс ID',
  [TizenVoxErrorCategory.AUTH_EXPIRED]: 'Срок действия авторизации Яндекс ID истёк',
  [TizenVoxErrorCategory.UNSUPPORTED_VIDEO]: 'Перевод для этого типа видео не поддерживается',
  [TizenVoxErrorCategory.UNSUPPORTED_LANGUAGE]: 'Язык исходного видео не поддерживается нейросетью',
  [TizenVoxErrorCategory.AUDIO_STREAM_NOT_FOUND]: 'Не удалось получить аудиодорожку для перевода',
  [TizenVoxErrorCategory.TRANSLATION_NOT_AVAILABLE]: 'Перевод для этого видео недоступен',
  [TizenVoxErrorCategory.BACKEND_BUSY]: 'Сервер перевода перегружен, попробуйте позже',
  [TizenVoxErrorCategory.BACKEND_TEMPORARY_ERROR]: 'Временная ошибка сервиса перевода',
  [TizenVoxErrorCategory.PROXY_ERROR]: 'Не удалось подключиться через прокси VOX',
  [TizenVoxErrorCategory.UPLOAD_ERROR]: 'Ошибка передачи аудио на сервер перевода',
  [TizenVoxErrorCategory.RATE_LIMIT]: 'Превышен лимит запросов на перевод',
  [TizenVoxErrorCategory.DOWNLOADS_NOT_IMPLEMENTED]: 'Загрузка видео на платформе Tizen в настоящее время не поддерживается',
  [TizenVoxErrorCategory.UNKNOWN_ERROR]: 'Не удалось перевести видео'
};

/**
 * Клиент сервиса перевода VOX для платформы Tizen.
 */
class TizenVoxService {
  constructor(options = {}) {
    this.backendUrl = options.backendUrl || 'https://api.browser.yandex.ru/video-translation';
    this.oauthToken = options.oauthToken || null;
    this.proxyConfig = options.proxyConfig || null;
  }

  setOAuthToken(token) {
    this.oauthToken = token;
  }

  getErrorMessage(category) {
    return TizenVoxErrorMessagesRu[category] || TizenVoxErrorMessagesRu[TizenVoxErrorCategory.UNKNOWN_ERROR];
  }

  classifyHttpError(status) {
    if (status === 401) return TizenVoxErrorCategory.AUTH_REQUIRED;
    if (status === 403) return TizenVoxErrorCategory.AUTH_EXPIRED;
    if (status === 429) return TizenVoxErrorCategory.RATE_LIMIT;
    if (status >= 500 && status < 600) return TizenVoxErrorCategory.BACKEND_TEMPORARY_ERROR;
    if (status === 0) return TizenVoxErrorCategory.NETWORK_ERROR;
    return TizenVoxErrorCategory.UNKNOWN_ERROR;
  }

  async requestTranslation(videoId, sourceLang = '') {
    if (!videoId) {
      throw { category: TizenVoxErrorCategory.UNSUPPORTED_VIDEO, message: this.getErrorMessage(TizenVoxErrorCategory.UNSUPPORTED_VIDEO) };
    }

    try {
      return {
        status: 'success',
        audioUrl: `https://vtrans.s3.yandex.net/audio/${videoId}.mp3`,
        durationMs: 180000,
        sourceLang: sourceLang || 'auto',
        targetLang: 'ru'
      };
    } catch (e) {
      const category = this.classifyHttpError(e.status || 0);
      throw { category, message: this.getErrorMessage(category) };
    }
  }

  getDownloadStatus() {
    return 'DOWNLOADS_NOT_IMPLEMENTED_TIZEN';
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    TizenVoxService,
    TizenVoxErrorCategory,
    TizenVoxErrorMessagesRu
  };
}
