'use strict';

const crypto = require('crypto');
const { MockLiveTranslationProvider } = require('./provider/mock_live_translation_provider');
const { YandexLiveTranslationProviderAdapter } = require('./provider/yandex_live_translation_provider_adapter');

const SessionState = {
  CREATED: 'CREATED',
  ACTIVE: 'ACTIVE',
  DEGRADED: 'DEGRADED',
  CLOSING: 'CLOSING',
  CLOSED: 'CLOSED',
  FAILED: 'FAILED',
  EXPIRED: 'EXPIRED'
};

const GatewayLimits = {
  MAX_SEGMENT_SIZE_BYTES: 4 * 1024 * 1024, // 4 MB
  MIN_SEGMENT_DURATION_MS: 500, // 0.5s
  MAX_SEGMENT_DURATION_MS: 12000, // 12s
  MAX_QUEUED_SEGMENTS: 10,
  MAX_QUEUED_DURATION_MS: 30000, // 30s
  SESSION_TTL_MS: 10 * 60 * 1000, // 10 minutes inactivity
  MAX_REORDER_WINDOW: 4,
  MAX_STORED_RESULTS_PER_SESSION: 20
};

class LiveSession {
  constructor(options = {}, provider = null) {
    this.sessionId = 'vox_live_' + crypto.randomUUID();
    this.createdAt = Date.now();
    this.lastActivityAt = this.createdAt;
    this.expiresAt = this.createdAt + GatewayLimits.SESSION_TTL_MS;

    this.sourceLanguage = options.sourceLanguage || 'en';
    this.targetLanguage = options.targetLanguage || 'ru';
    this.mode = options.mode || 'BALANCED'; // LOW_LATENCY, BALANCED, STABLE
    this.audioCodec = options.audioCodec || 'opus';
    this.sampleRate = options.sampleRate || 48000;
    this.channels = options.channels || 2;

    this.state = SessionState.CREATED;
    this.generation = 1;
    this.nextExpectedSequence = 0;

    // Ephemeral queues and maps (Zero disk retention)
    this.queuedSegments = []; // Array of segment objects in order
    this.reorderBuffer = new Map(); // sequence -> segment
    this.results = new Map(); // sequence -> result object
    this.dedupeMap = new Map(); // `${generation}:${sequence}:${checksum}` -> status/result

    this.droppedStaleCount = 0;
    this.duplicateCount = 0;
    this.backpressureCount = 0;

    this.provider = provider || new MockLiveTranslationProvider();
    this.isProcessing = false;
  }

  touch() {
    this.lastActivityAt = Date.now();
    this.expiresAt = this.lastActivityAt + GatewayLimits.SESSION_TTL_MS;
  }

  isExpired() {
    return Date.now() > this.expiresAt && this.state !== SessionState.CLOSED;
  }

  getQueueDepth() {
    return this.queuedSegments.length;
  }

  getQueuedDurationMs() {
    return this.queuedSegments.reduce((sum, s) => sum + (s.durationMs || 0), 0);
  }

  updateStateAfterIngest() {
    if (this.state === SessionState.CREATED) {
      this.state = SessionState.ACTIVE;
    }
  }

  handleGenerationChange(newGeneration, startSequence) {
    if (newGeneration > this.generation) {
      // Cancel pending work for old generation
      for (const seg of this.queuedSegments) {
        if (seg.generation < newGeneration && this.provider.cancelSegment) {
          this.provider.cancelSegment(this, seg.sequence, seg.generation).catch(() => {});
        }
      }
      this.queuedSegments = [];
      this.reorderBuffer.clear();
      this.results.clear();
      this.dedupeMap.clear();

      this.generation = newGeneration;
      this.nextExpectedSequence = startSequence;
      this.state = SessionState.ACTIVE;
      return true;
    }
    return false;
  }

  storeResult(sequence, result) {
    this.results.set(sequence, result);
    // Bounded cleanup of oldest results
    if (this.results.size > GatewayLimits.MAX_STORED_RESULTS_PER_SESSION) {
      const oldestKey = this.results.keys().next().value;
      this.results.delete(oldestKey);
    }
  }

  getResult(sequence, generation) {
    const res = this.results.get(sequence);
    if (!res) return null;
    if (generation !== undefined && res.generation !== generation) {
      return null;
    }
    return res;
  }

  toSummary() {
    return {
      sessionId: this.sessionId,
      state: this.state,
      generation: this.generation,
      nextExpectedSequence: this.nextExpectedSequence,
      queueDepth: this.getQueueDepth(),
      queuedDurationMs: this.getQueuedDurationMs(),
      bufferedResultCount: this.results.size,
      sourceLanguage: this.sourceLanguage,
      targetLanguage: this.targetLanguage,
      createdAt: this.createdAt,
      lastActivityAt: this.lastActivityAt,
      expiresAt: this.expiresAt
    };
  }
}

class SessionManager {
  constructor(defaultProvider = null) {
    this.sessions = new Map();
    this.defaultProvider = defaultProvider || new MockLiveTranslationProvider();
    this.cleanupTimer = setInterval(() => this.cleanupExpired(), 30000);
  }

  createSession(options = {}, provider = null) {
    const chosenProvider = provider || this.defaultProvider;
    const session = new LiveSession(options, chosenProvider);
    this.sessions.set(session.sessionId, session);
    session.state = SessionState.ACTIVE;
    return session;
  }

  getSession(sessionId) {
    const session = this.sessions.get(sessionId);
    if (!session) return null;
    if (session.isExpired()) {
      session.state = SessionState.EXPIRED;
      return null;
    }
    session.touch();
    return session;
  }

  closeSession(sessionId) {
    const session = this.sessions.get(sessionId);
    if (!session) return false;
    session.state = SessionState.CLOSED;
    session.queuedSegments = [];
    session.reorderBuffer.clear();
    session.results.clear();
    if (session.provider.closeSession) {
      session.provider.closeSession(session).catch(() => {});
    }
    this.sessions.delete(sessionId);
    return true;
  }

  cleanupExpired() {
    const now = Date.now();
    for (const [id, session] of this.sessions.entries()) {
      if (now > session.expiresAt || session.state === SessionState.CLOSED) {
        session.state = SessionState.EXPIRED;
        if (session.provider.closeSession) {
          session.provider.closeSession(session).catch(() => {});
        }
        this.sessions.delete(id);
      }
    }
  }

  destroy() {
    if (this.cleanupTimer) {
      clearInterval(this.cleanupTimer);
      this.cleanupTimer = null;
    }
    for (const session of this.sessions.values()) {
      session.state = SessionState.CLOSED;
    }
    this.sessions.clear();
  }
}

module.exports = {
  SessionState,
  GatewayLimits,
  LiveSession,
  SessionManager
};
