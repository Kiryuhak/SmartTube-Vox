'use strict';

const { WorkerStage, BackendErrorCode } = require('../models/types');
const { MockPipeline } = require('./mock_pipeline');

class PipelineOrchestrator {
  constructor(config = {}) {
    this.config = config;
    this.pipeline = new MockPipeline(config);
    this.activeWorkers = 0;
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
