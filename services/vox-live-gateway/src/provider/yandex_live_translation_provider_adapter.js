'use strict';

const { LiveTranslationProvider, ProviderState } = require('./live_translation_provider');

/**
 * Adapter for Yandex Live Translation.
 * In SmartTube VOX 8 - Patch #5 & Patch #8, Yandex backend capabilities are confirmed as VOD_ONLY
 * (no WebSocket, no gRPC, no chunked streaming, no incremental audio session, no raw PCM ingestion).
 * This adapter explicitly reports UNSUPPORTED without faking.
 */
class YandexLiveTranslationProviderAdapter extends LiveTranslationProvider {
  capabilities() {
    return {
      supportsRawPcm: false,
      supportsEncodedAudio: true, // Only whole video audio uploads during VOD translation
      supportsIncremental: false,
      supportsIncrementalAudio: false,
      supportsSequentialSegments: false,
      supportsStreaming: false,
      supportsSession: false,
      supportsSessionContinuation: false,
      supportsCancellation: false,
      supportsSourceLanguageAuto: true,
      supportsVoiceSynthesis: true,
      supportsPartialResults: false,
      targetSampleRate: 44100,
      targetChannels: 2,
      providerId: 'yandex_live_adapter',
      providerState: ProviderState.UNSUPPORTED,
      status: 'UNSUPPORTED_INCREMENTAL_TRANSLATION',
      isRealTranslation: false,
      reason: 'Public Yandex VOT backend only supports completed VOD media URLs via HTTP polling'
    };
  }

  async startSession(session) {
    const err = new Error('Yandex Live Translation does not support incremental live stream sessions (VOD_ONLY backend)');
    err.code = 'UNSUPPORTED_INCREMENTAL_TRANSLATION';
    err.status = ProviderState.UNSUPPORTED;
    throw err;
  }

  async translateSegment(session, segment) {
    const err = new Error('Yandex Live Translation does not support incremental audio segment ingestion (VOD_ONLY backend)');
    err.code = 'UNSUPPORTED_INCREMENTAL_TRANSLATION';
    err.status = ProviderState.UNSUPPORTED;
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
