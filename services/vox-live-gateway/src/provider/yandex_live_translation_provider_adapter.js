'use strict';

const { LiveTranslationProvider } = require('./live_translation_provider');

/**
 * Adapter for Yandex Live Translation.
 * In SmartTube VOX 8 - Patch #5, Yandex backend capabilities are confirmed as VOD_ONLY
 * (no WebSocket, no gRPC, no chunked streaming, no incremental audio session).
 * This adapter explicitly reports UNSUPPORTED_INCREMENTAL_TRANSLATION without faking.
 */
class YandexLiveTranslationProviderAdapter extends LiveTranslationProvider {
  capabilities() {
    return {
      supportsSequentialSegments: false,
      supportsIncrementalAudio: false,
      supportsCancellation: false,
      supportsSessionContinuation: false,
      providerId: 'yandex_live_adapter',
      status: 'UNSUPPORTED_INCREMENTAL_TRANSLATION',
      reason: 'Public Yandex VOT backend only supports completed VOD media URLs via HTTP polling'
    };
  }

  async startSession(session) {
    const err = new Error('Yandex Live Translation does not support incremental live stream sessions (VOD_ONLY backend)');
    err.code = 'UNSUPPORTED_INCREMENTAL_TRANSLATION';
    throw err;
  }

  async translateSegment(session, segment) {
    const err = new Error('Yandex Live Translation does not support incremental audio segment ingestion (VOD_ONLY backend)');
    err.code = 'UNSUPPORTED_INCREMENTAL_TRANSLATION';
    throw err;
  }

  async cancelSegment(session, sequence, generation) {
    return false;
  }

  async closeSession(session) {
    // no-op
  }
}

module.exports = {
  YandexLiveTranslationProviderAdapter
};
