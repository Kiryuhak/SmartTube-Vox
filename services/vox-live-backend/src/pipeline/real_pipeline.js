'use strict';

const { SttAdapter } = require('../models/stt_adapter');
const { TranslationAdapter } = require('../models/translation_adapter');
const { TtsAdapter } = require('../models/tts_adapter');
const { MockPipeline } = require('./mock_pipeline');

class RealPipeline {
  constructor(config = {}) {
    this.config = config;
    this.sttAdapter = new SttAdapter(config);
    this.translationAdapter = new TranslationAdapter(config);
    this.ttsAdapter = new TtsAdapter(config);
    this.mockFallback = new MockPipeline(config);
    this.allowFallback = config.allowFallbackToMock !== false;
  }

  async checkEnginesHealth() {
    const [stt, translation, tts] = await Promise.all([
      this.sttAdapter.checkAvailability(),
      this.translationAdapter.checkAvailability(),
      this.ttsAdapter.checkAvailability()
    ]);

    const allAvailable = stt.available && translation.available && tts.available;
    return {
      allAvailable,
      stt,
      translation,
      tts,
      hardwareProfile: {
        cpu: 'AMD Ryzen 7 5700U with Radeon Graphics (8C/16T)',
        ram: '16 GB (15.3 GB Available)',
        gpu: 'Integrated AMD Radeon Graphics (No CUDA/TensorRT)',
        python: 'Python 3.12.10 (No faster-whisper/torch/piper preinstalled)'
      }
    };
  }

  async runStt(audioBuffer, metadata = {}) {
    try {
      return await this.sttAdapter.transcribe(audioBuffer, metadata);
    } catch (err) {
      if (this.allowFallback) {
        const res = await this.mockFallback.runStt(audioBuffer, metadata);
        res.degraded = true;
        res.fallbackReason = err.message || err.details;
        return res;
      }
      throw err;
    }
  }

  async runTranslation(sourceText, targetLanguage = 'ru') {
    try {
      return await this.translationAdapter.translate(sourceText, targetLanguage);
    } catch (err) {
      if (this.allowFallback) {
        const res = await this.mockFallback.runTranslation(sourceText, targetLanguage);
        res.degraded = true;
        res.fallbackReason = err.message || err.details;
        return res;
      }
      throw err;
    }
  }

  async runTts(translatedText, durationMs = 2000) {
    try {
      return await this.ttsAdapter.synthesize(translatedText, durationMs);
    } catch (err) {
      if (this.allowFallback) {
        const res = await this.mockFallback.runTts(translatedText, durationMs);
        res.degraded = true;
        res.fallbackReason = err.message || err.details;
        return res;
      }
      throw err;
    }
  }
}

module.exports = { RealPipeline };
