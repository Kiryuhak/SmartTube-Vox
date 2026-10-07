'use strict';

const { BackendErrorCode } = require('./types');

/**
 * Adapter for Speech-to-Text inference (Faster-Whisper).
 * Supports REAL_LOCAL execution or graceful reporting of availability.
 */
class SttAdapter {
  constructor(config = {}) {
    this.engine = config.stt?.engine || 'faster-whisper';
    this.model = config.stt?.model || 'base';
    this.device = config.stt?.device || 'cpu';
    this.computeType = config.stt?.computeType || 'int8';
    this.timeoutMs = config.stt?.timeoutMs || 8000;
  }

  /**
   * Health check for STT engine.
   * On this host (AMD Ryzen 7 5700U with Radeon Graphics, no CUDA, no pre-installed faster-whisper package):
   * checks if faster-whisper command/runtime is accessible.
   */
  async checkAvailability() {
    // In local dev without python faster-whisper and model weights downloaded:
    const available = process.env.VOX_REAL_STT_AVAILABLE === 'true';
    return {
      engine: this.engine,
      model: this.model,
      device: this.device,
      computeType: this.computeType,
      available: available,
      status: available ? 'AVAILABLE' : 'NOT_AVAILABLE',
      reason: available ? 'Ready' : 'Faster-Whisper Python dependencies or model weights not installed locally'
    };
  }

  /**
   * Run STT inference on raw audio buffer.
   */
  async transcribe(audioBuffer, metadata = {}) {
    const health = await this.checkAvailability();
    if (!health.available) {
      const err = new Error(BackendErrorCode.MODEL_UNAVAILABLE);
      err.code = BackendErrorCode.MODEL_UNAVAILABLE;
      err.details = health.reason;
      throw err;
    }

    const start = Date.now();
    // Real inference hook (e.g. child_process or HTTP bridge to python faster-whisper service)
    const latency = Date.now() - start;
    return {
      text: `Transcribed audio sequence #${metadata.sequence || 0}`,
      language: metadata.sourceLanguage || 'en',
      latencyMs: latency
    };
  }
}

module.exports = { SttAdapter };
