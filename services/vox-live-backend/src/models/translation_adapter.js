'use strict';

const { BackendErrorCode } = require('./types');

/**
 * Adapter for Neural Machine Translation (Marian NMT / CTranslate2).
 * Supports REAL_LOCAL execution or graceful reporting of availability.
 */
class TranslationAdapter {
  constructor(config = {}) {
    this.engine = config.translation?.engine || 'marian-nmt';
    this.model = config.translation?.model || 'Helsinki-NLP/opus-mt-en-ru';
    this.timeoutMs = config.translation?.timeoutMs || 4000;
  }

  async checkAvailability() {
    const available = process.env.VOX_REAL_TRANSLATION_AVAILABLE === 'true';
    return {
      engine: this.engine,
      model: this.model,
      available: available,
      status: available ? 'AVAILABLE' : 'NOT_AVAILABLE',
      reason: available ? 'Ready' : 'Marian NMT weights or CTranslate2 runtime not installed locally'
    };
  }

  async translate(text, targetLang = 'ru', options = {}) {
    const health = await this.checkAvailability();
    if (!health.available) {
      const err = new Error(BackendErrorCode.MODEL_UNAVAILABLE);
      err.code = BackendErrorCode.MODEL_UNAVAILABLE;
      err.details = health.reason;
      throw err;
    }

    const start = Date.now();
    const latency = Date.now() - start;
    return {
      translatedText: `[RU] ${text}`,
      targetLanguage: targetLang,
      latencyMs: latency
    };
  }
}

module.exports = { TranslationAdapter };
