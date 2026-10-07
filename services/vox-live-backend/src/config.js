'use strict';

module.exports = {
  port: parseInt(process.env.PORT || '8090', 10),
  host: process.env.HOST || '127.0.0.1',
  nodeEnv: process.env.NODE_ENV || 'development',
  backendMode: process.env.VOX_BACKEND_MODE || 'MOCK', // 'MOCK' or 'LOCAL_REAL'

  // Limits and Queues
  maxConcurrentSessions: parseInt(process.env.VOX_MAX_CONCURRENT_SESSIONS || '50', 10),
  maxConcurrentSegments: parseInt(process.env.VOX_MAX_CONCURRENT_SEGMENTS || '100', 10),
  maxSegmentsPerSession: parseInt(process.env.VOX_MAX_SEGMENTS_PER_SESSION || '8', 10),
  maxQueuedDurationMs: parseInt(process.env.VOX_MAX_QUEUED_DURATION_MS || '16000', 10),
  maxSegmentBytes: parseInt(process.env.VOX_MAX_SEGMENT_BYTES || '524288', 10), // 512 KB
  sessionTtlMs: parseInt(process.env.VOX_SESSION_TTL_MS || '90000', 10), // 90 seconds

  // Rate Limiter
  rateLimitRps: parseInt(process.env.VOX_RATE_LIMIT_RPS || '10', 10),
  rateLimitBurst: parseInt(process.env.VOX_RATE_LIMIT_BURST || '20', 10),

  // Pipeline Engines
  stt: {
    engine: process.env.VOX_STT_ENGINE || 'faster-whisper',
    model: process.env.VOX_STT_MODEL || 'base',
    device: process.env.VOX_STT_DEVICE || 'cpu',
    computeType: process.env.VOX_STT_COMPUTE_TYPE || 'int8'
  },
  translation: {
    engine: process.env.VOX_TRANSLATION_ENGINE || 'marian-nmt',
    model: process.env.VOX_TRANSLATION_MODEL || 'Helsinki-NLP/opus-mt-en-ru'
  },
  tts: {
    engine: process.env.VOX_TTS_ENGINE || 'piper',
    voice: process.env.VOX_TTS_VOICE || 'ru_RU-dmitri-medium',
    sampleRate: parseInt(process.env.VOX_TTS_SAMPLE_RATE || '16000', 10)
  }
};
