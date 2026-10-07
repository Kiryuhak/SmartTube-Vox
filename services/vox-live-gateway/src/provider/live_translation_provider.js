'use strict';

/**
 * Стандартизированные состояния провайдера перевода (Section 8).
 */
const ProviderState = {
  AVAILABLE: 'AVAILABLE',
  UNAVAILABLE: 'UNAVAILABLE',
  UNSUPPORTED: 'UNSUPPORTED',
  AUTH_REQUIRED: 'AUTH_REQUIRED',
  RATE_LIMITED: 'RATE_LIMITED',
  DEGRADED: 'DEGRADED',
  ERROR: 'ERROR'
};

/**
 * Base abstraction for live translation providers (Section 7).
 * Any concrete provider (Mock, Passthrough, Yandex adapter, Real Experimental)
 * must implement this contract.
 */
class LiveTranslationProvider {
  /**
   * Returns provider capabilities.
   * @returns {Object}
   */
  capabilities() {
    return {
      supportsRawPcm: false,
      supportsEncodedAudio: false,
      supportsIncremental: false,
      supportsIncrementalAudio: false,
      supportsSequentialSegments: false,
      supportsStreaming: false,
      supportsSession: false,
      supportsSessionContinuation: false,
      supportsCancellation: false,
      supportsSourceLanguageAuto: false,
      supportsVoiceSynthesis: false,
      supportsPartialResults: false,
      targetSampleRate: 16000,
      targetChannels: 1,
      providerId: 'base',
      status: ProviderState.UNAVAILABLE
    };
  }

  /**
   * Initializes a session on provider side if required.
   * @param {Object} session
   * @returns {Promise<Object>}
   */
  async startSession(session) {
    throw new Error('startSession not implemented');
  }

  /**
   * Translates a single audio segment.
   * @param {Object} session
   * @param {Object} segment
   * @returns {Promise<Object>}
   */
  async translateSegment(session, segment) {
    throw new Error('translateSegment not implemented');
  }

  /**
   * Cancels a pending segment translation.
   * @param {Object} session
   * @param {number} sequence
   * @param {number} generation
   * @returns {Promise<boolean>}
   */
  async cancelSegment(session, sequence, generation) {
    return false;
  }

  /**
   * Closes provider session resources.
   * @param {Object} session
   * @returns {Promise<void>}
   */
  async closeSession(session) {
    // default no-op
  }
}

module.exports = {
  LiveTranslationProvider,
  ProviderState
};
