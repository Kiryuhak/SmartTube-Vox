'use strict';

const { WorkerStage, BackendErrorCode } = require('../models/types');
const { MockPipeline } = require('./mock_pipeline');
const { RealPipeline } = require('./real_pipeline');

class PipelineOrchestrator {
  constructor(config = {}) {
    this.config = config;
    if (config.backendMode === 'LOCAL_REAL') {
      this.pipeline = new RealPipeline(config);
    } else {
      this.pipeline = new MockPipeline(config);
    }
    this.activeWorkers = 0;
  }

  async checkEnginesHealth() {
    if (this.pipeline && typeof this.pipeline.checkEnginesHealth === 'function') {
      return await this.pipeline.checkEnginesHealth();
    }
    return {
      allAvailable: true,
      mode: 'MOCK',
      stt: { engine: this.config.stt?.engine || 'faster-whisper', available: true, status: 'MOCK' },
      translation: { engine: this.config.translation?.engine || 'marian-nmt', available: true, status: 'MOCK' },
      tts: { engine: this.config.tts?.engine || 'piper', available: true, status: 'MOCK' },
      hardwareProfile: {
        cpu: 'AMD Ryzen 7 5700U with Radeon Graphics (8C/16T)',
        ram: '16 GB (15.3 GB Available)',
        gpu: 'Integrated AMD Radeon Graphics (No CUDA/TensorRT)',
        python: 'Python 3.12.10 (No faster-whisper/torch/piper preinstalled)'
      }
    };
  }

  /**
   * Запускает пайплайн обработки аудиосегмента с отслеживанием стадий и защитой от устаревания.
   */
  async processSegment(segment, session) {
    if (!segment || !session) {
      throw new Error(BackendErrorCode.INTERNAL);
    }

    const checkStale = () => {
      if (session.isExpired() || session.isClosed()) {
        segment.stage = WorkerStage.CANCELLED;
        segment.error = BackendErrorCode.SESSION_EXPIRED;
        return true;
      }
      if (segment.generation < session.currentGeneration) {
        segment.stage = WorkerStage.STALE;
        segment.error = BackendErrorCode.STALE_GENERATION;
        return true;
      }
      return false;
    };

    if (checkStale()) return segment;

    const startTotal = Date.now();
    segment.stage = WorkerStage.INGESTED;

    try {
      this.activeWorkers++;

      // 1. STT Stage
      segment.stage = WorkerStage.STT_PENDING;
      if (checkStale()) return segment;

      segment.stage = WorkerStage.STT_RUNNING;
      const sttRes = await this.pipeline.runStt(segment.audioBuffer, {
        sequence: segment.sequence,
        sourceLanguage: segment.sourceLanguage
      });
      segment.sttLatencyMs = sttRes.latencyMs;
      segment.originalText = sttRes.text;

      // 2. Translation Stage
      if (checkStale()) return segment;
      segment.stage = WorkerStage.TRANSLATION_PENDING;

      segment.stage = WorkerStage.TRANSLATION_RUNNING;
      const transRes = await this.pipeline.runTranslation(segment.originalText, segment.targetLanguage || 'ru');
      segment.translationLatencyMs = transRes.latencyMs;
      segment.translatedText = transRes.translatedText;

      // 3. TTS Stage
      if (checkStale()) return segment;
      segment.stage = WorkerStage.TTS_PENDING;

      segment.stage = WorkerStage.TTS_RUNNING;
      const durationMs = segment.durationMs || (segment.endPtsUs && segment.startPtsUs ? (segment.endPtsUs - segment.startPtsUs) / 1000 : 2000);
      const ttsRes = await this.pipeline.runTts(segment.translatedText, durationMs);
      segment.ttsLatencyMs = ttsRes.latencyMs;
      segment.translatedAudio = ttsRes.audioBuffer;
      segment.outputSampleRate = ttsRes.sampleRate;
      segment.outputChannels = ttsRes.channels;
      segment.outputEncoding = ttsRes.encoding;

      if (checkStale()) return segment;

      segment.stage = WorkerStage.READY;
      segment.totalLatencyMs = Date.now() - startTotal;
      return segment;
    } catch (err) {
      segment.stage = WorkerStage.FAILED;
      segment.error = err.message || BackendErrorCode.INTERNAL;
      return segment;
    } finally {
      this.activeWorkers = Math.max(0, this.activeWorkers - 1);
      // Освобождаем входной сырой буфер после обработки для сохранения ephemeral memory
      segment.audioBuffer = null;
    }
  }
}

module.exports = { PipelineOrchestrator };
