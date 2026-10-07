'use strict';

/**
 * Base abstraction for live translation providers.
 * Any concrete provider (Mock, Yandex adapter, etc.) must implement this contract.
 */
class LiveTranslationProvider {
  /**
   * Returns provider capabilities.
   * @returns {Object}
   */
  capabilities() {
    return {
      supportsSequentialSegments: false,
      supportsIncrementalAudio: false,
      supportsCancellation: false,
      supportsSessionContinuation: false,
      providerId: 'base'
    };
  }

  /**
   * Initializes a session on provider side if required.
   * @param {Object} session
   * @returns {Promise<void>}
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
  LiveTranslationProvider
};
