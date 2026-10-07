'use strict';

const { LiveTranslationProvider, ProviderState } = require('./live_translation_provider');

/**
 * Latency presets for mock provider.
 */
const MockLatencyMode = {
  FAST: 500,
  NORMAL: 2000,
  SLOW: 5000,
  VERY_SLOW: 10000,
  ZERO: 0 // for instantaneous unit tests
};

class MockLiveTranslationProvider extends LiveTranslationProvider {
  /**
   * @param {Object} options
   * @param {number|string} [options.latencyMode='NORMAL']
   * @param {number} [options.fixedLatencyMs=null]
   * @param {number} [options.errorEveryNth=0] - If > 0, every Nth segment throws error
   * @param {number} [options.errorCode=500] - Error HTTP code to simulate
   * @param {boolean} [options.simulate429=false]
   * @param {boolean} [options.simulateTimeout=false]
   * @param {boolean} [options.passthroughAudio=true]
   * @param {string} [options.mode='MOCK'] - 'MOCK' or 'PASSTHROUGH'
   */
  constructor(options = {}) {
    super();
    this.mode = options.mode || (options.passthroughAudio === false ? 'MOCK' : 'PASSTHROUGH');
    this.latencyMode = options.latencyMode || 'NORMAL';
    this.fixedLatencyMs = options.fixedLatencyMs !== undefined ? options.fixedLatencyMs : null;
    this.errorEveryNth = options.errorEveryNth || 0;
    this.errorCode = options.errorCode || 500;
    this.simulate429 = !!options.simulate429;
    this.simulateTimeout = !!options.simulateTimeout;
    this.passthroughAudio = options.passthroughAudio !== false && this.mode !== 'MOCK';

    this.segmentCounter = 0;
    this.cancelledSegments = new Set(); // Set of `${sessionId}:${generation}:${sequence}`
  }

  capabilities() {
    return {
      supportsRawPcm: true,
      supportsEncodedAudio: true,
      supportsIncremental: true,
      supportsIncrementalAudio: true,
      supportsSequentialSegments: true,
      supportsStreaming: true,
      supportsSession: true,
      supportsSessionContinuation: true,
      supportsCancellation: true,
      supportsSourceLanguageAuto: true,
      supportsVoiceSynthesis: true,
      supportsPartialResults: false,
      targetSampleRate: 48000,
      targetChannels: 2,
      providerId: this.mode === 'MOCK' ? 'mock_provider' : 'passthrough_provider',
      status: ProviderState.AVAILABLE,
      isRealTranslation: false,
      description: `Deterministic test ${this.mode.toLowerCase()} provider with configurable latency and error injection`
    };
  }

  getEffectiveDelay() {
    if (this.fixedLatencyMs !== null) {
      return this.fixedLatencyMs;
    }
    if (typeof this.latencyMode === 'number') {
      return this.latencyMode;
    }
    return MockLatencyMode[this.latencyMode] ?? MockLatencyMode.NORMAL;
  }

  async startSession(session) {
    return {
      providerSessionId: `${this.mode.toLowerCase()}_${session.sessionId}`,
      status: 'INITIALIZED'
    };
  }

  async translateSegment(session, segment) {
    const key = `${session.sessionId}:${segment.generation}:${segment.sequence}`;
    this.segmentCounter++;

    // Check cancellation before work
    if (this.cancelledSegments.has(key)) {
      this.cancelledSegments.delete(key);
      const err = new Error('Segment translation cancelled');
      err.code = 'CANCELLED';
      throw err;
    }

    // Error injection checks
    if (this.simulate429) {
      const err = new Error('Simulated Rate Limit 429');
      err.code = ProviderState.RATE_LIMITED;
      err.status = ProviderState.RATE_LIMITED;
      err.statusCode = 429;
      err.retryAfter = 2;
      throw err;
    }

    if (this.errorEveryNth > 0 && (this.segmentCounter % this.errorEveryNth === 0)) {
      const err = new Error(`Simulated Error ${this.errorCode} on segment ${segment.sequence}`);
      err.code = ProviderState.ERROR;
      err.status = ProviderState.ERROR;
      err.statusCode = this.errorCode;
      throw err;
    }

    const delayMs = this.getEffectiveDelay();

    if (this.simulateTimeout) {
      // simulate long delay that triggers client or gateway timeout
      await new Promise(resolve => setTimeout(resolve, 30000));
    } else if (delayMs > 0) {
      await new Promise(resolve => setTimeout(resolve, delayMs));
    }

    // Check cancellation after delay
    if (this.cancelledSegments.has(key)) {
      this.cancelledSegments.delete(key);
      const err = new Error('Segment translation cancelled');
      err.code = 'CANCELLED';
      throw err;
    }

    // Return deterministic mock or passthrough result
    const mockAudioPayload = this.passthroughAudio && segment.payload
      ? segment.payload
      : Buffer.from(`MOCK_TRANSLATED_AUDIO_SEQ_${segment.sequence}_GEN_${segment.generation}`, 'utf8');

    return {
      sessionId: session.sessionId,
      sequence: segment.sequence,
      generation: segment.generation,
      sourceStartMs: segment.sourceStartMs,
      sourceEndMs: segment.sourceEndMs,
      sourceStartPtsUs: segment.ptsStartUs || (segment.sourceStartMs ? segment.sourceStartMs * 1000 : 0),
      sourceEndPtsUs: segment.ptsEndUs || (segment.sourceEndMs ? segment.sourceEndMs * 1000 : 0),
      ptsStartUs: segment.ptsStartUs !== undefined ? segment.ptsStartUs : null,
      ptsEndUs: segment.ptsEndUs !== undefined ? segment.ptsEndUs : null,
      sampleRate: segment.sampleRate || 48000,
      channels: segment.channels || 2,
      encoding: segment.encoding || (segment.codec === 'pcm_16bit' ? 'pcm_16bit' : 'pcm_16le'),
      durationMs: segment.durationMs,
      translatedDurationMs: segment.durationMs,
      codec: segment.codec || 'pcm_16le',
      audioFormat: 'pcm_16le',
      text: `[${this.mode}_RU_TRANSLATION_SEQ_${segment.sequence}]`,
      audioData: mockAudioPayload,
      translatedAudio: mockAudioPayload,
      providerLatencyMs: delayMs,
      providerStatus: ProviderState.AVAILABLE,
      completedAtMs: Date.now(),
      isRealTranslation: false
    };
  }

  async cancelSegment(session, sequence, generation) {
    const key = `${session.sessionId}:${generation}:${sequence}`;
    this.cancelledSegments.add(key);
    return true;
  }

  async closeSession(session) {
    // Clear cancelled keys for session
    for (const key of this.cancelledSegments) {
      if (key.startsWith(`${session.sessionId}:`)) {
        this.cancelledSegments.delete(key);
      }
    }
  }
}

module.exports = {
  MockLatencyMode,
  MockLiveTranslationProvider
};
