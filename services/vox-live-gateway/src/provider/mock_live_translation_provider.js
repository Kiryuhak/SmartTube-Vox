'use strict';

const { LiveTranslationProvider } = require('./live_translation_provider');

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
   */
  constructor(options = {}) {
    super();
    this.latencyMode = options.latencyMode || 'NORMAL';
    this.fixedLatencyMs = options.fixedLatencyMs !== undefined ? options.fixedLatencyMs : null;
    this.errorEveryNth = options.errorEveryNth || 0;
    this.errorCode = options.errorCode || 500;
    this.simulate429 = !!options.simulate429;
    this.simulateTimeout = !!options.simulateTimeout;
    this.passthroughAudio = options.passthroughAudio !== false;

    this.segmentCounter = 0;
    this.cancelledSegments = new Set(); // Set of `${sessionId}:${generation}:${sequence}`
  }

  capabilities() {
    return {
      supportsSequentialSegments: true,
      supportsIncrementalAudio: true,
      supportsCancellation: true,
      supportsSessionContinuation: true,
      providerId: 'mock_provider',
      isRealTranslation: false,
      description: 'Deterministic test mock provider with configurable latency and error injection'
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
    // Mock initializes instantly
    return {
      providerSessionId: `mock_${session.sessionId}`,
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
      err.code = 429;
      err.retryAfter = 2;
      throw err;
    }

    if (this.errorEveryNth > 0 && (this.segmentCounter % this.errorEveryNth === 0)) {
      const err = new Error(`Simulated Error ${this.errorCode} on segment ${segment.sequence}`);
      err.code = this.errorCode;
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

    // Return deterministic mock result
    // In passthrough mode, returns segment audio or mock payload
    const mockAudioPayload = this.passthroughAudio && segment.payload
      ? segment.payload
      : Buffer.from(`MOCK_TRANSLATED_AUDIO_SEQ_${segment.sequence}_GEN_${segment.generation}`, 'utf8');

    return {
      sessionId: session.sessionId,
      sequence: segment.sequence,
      generation: segment.generation,
      sourceStartMs: segment.sourceStartMs,
      sourceEndMs: segment.sourceEndMs,
      durationMs: segment.durationMs,
      translatedDurationMs: segment.durationMs,
      codec: segment.codec || 'opus',
      text: `[MOCK_RU_TRANSLATION_SEQ_${segment.sequence}]`,
      audioData: mockAudioPayload,
      providerLatencyMs: delayMs,
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
