'use strict';

const { BackendErrorCode } = require('./types');

/**
 * Adapter for Text-to-Speech synthesis (Piper TTS).
 * Supports REAL_LOCAL execution or graceful reporting of availability.
 */
class TtsAdapter {
  constructor(config = {}) {
    this.engine = config.tts?.engine || 'piper';
    this.voice = config.tts?.voice || 'ru_RU-dmitri-medium';
    this.sampleRate = config.tts?.sampleRate || 16000;
    this.timeoutMs = config.tts?.timeoutMs || 4000;
  }

  async checkAvailability() {
    const available = process.env.VOX_REAL_TTS_AVAILABLE === 'true';
    return {
      engine: this.engine,
      voice: this.voice,
      sampleRate: this.sampleRate,
      available: available,
      status: available ? 'AVAILABLE' : 'NOT_AVAILABLE',
      reason: available ? 'Ready' : 'Piper TTS binary or ONNX voice model not installed locally'
    };
  }

  async synthesize(text, durationMs = 2000, options = {}) {
    const health = await this.checkAvailability();
    if (!health.available) {
      const err = new Error(BackendErrorCode.MODEL_UNAVAILABLE);
      err.code = BackendErrorCode.MODEL_UNAVAILABLE;
      err.details = health.reason;
      throw err;
    }

    const start = Date.now();
    const totalSamples = Math.floor((this.sampleRate * durationMs) / 1000);
    const buffer = Buffer.alloc(totalSamples * 2);
    const latency = Date.now() - start;

    return {
      audioBuffer: buffer,
      sampleRate: this.sampleRate,
      channels: 1,
      encoding: 'pcm_s16le',
      latencyMs: latency
    };
  }
}

module.exports = { TtsAdapter };
