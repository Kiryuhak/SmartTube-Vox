'use strict';

const crypto = require('crypto');
const { WorkerStage, BackendErrorCode, SessionStatus } = require('../models/types');

class LiveSession {
  constructor(id, options = {}) {
    this.id = id;
    this.streamId = options.streamId || 'unknown';
    this.sessionToken = options.sessionToken || `stk_${crypto.randomBytes(16).toString('hex')}`;
    this.sourceLanguage = options.sourceLanguage || 'auto';
    this.targetLanguage = options.targetLanguage || 'ru';
    this.sampleRate = options.sampleRate || 16000;
    this.channels = options.channels || 1;
    this.encoding = options.encoding || 'pcm_s16le';
    this.clientVersion = options.clientVersion || 'vox8';

    this.createdAt = Date.now();
    this.lastActivityAt = Date.now();
    this.ttlMs = options.ttlMs || 90000;
    this.status = SessionStatus.ACTIVE;

    this.currentGeneration = options.generation || 1;
    this.segments = new Map(); // sequence -> segment
    this.maxSegments = options.maxSegments || 8;
  }

  touch() {
    this.lastActivityAt = Date.now();
  }

  isExpired() {
    return Date.now() - this.lastActivityAt > this.ttlMs;
  }

  isClosed() {
    return this.status === SessionStatus.CLOSED || this.status === SessionStatus.EXPIRED;
  }

  setGeneration(gen) {
    if (gen > this.currentGeneration) {
      this.currentGeneration = gen;
      // Отменяем старые сегменты предыдущих поколений
      for (const [seq, seg] of this.segments.entries()) {
        if (seg.generation < gen) {
          seg.stage = WorkerStage.STALE;
          seg.error = BackendErrorCode.STALE_GENERATION;
          seg.audioBuffer = null;
          seg.translatedAudio = null;
        }
      }
    }
  }

  addSegment(segment) {
    this.touch();
    // Bounded queue: если превышен лимит сегментов в очереди, вытесняем старейший
    if (this.segments.size >= this.maxSegments) {
      const oldestSeq = Math.min(...this.segments.keys());
      const oldSeg = this.segments.get(oldestSeq);
      if (oldSeg) {
        oldSeg.audioBuffer = null;
        oldSeg.translatedAudio = null;
      }
      this.segments.delete(oldestSeq);
    }
    this.segments.set(segment.sequence, segment);
  }

  getSegment(sequence) {
    this.touch();
    return this.segments.get(sequence) || null;
  }

  close() {
    this.status = SessionStatus.CLOSED;
    for (const seg of this.segments.values()) {
      seg.audioBuffer = null;
      seg.translatedAudio = null;
    }
    this.segments.clear();
  }
}

class SessionManager {
  constructor(config = {}) {
    this.config = config;
    this.sessions = new Map();
    this.ipRateLimits = new Map(); // ip -> { tokens, lastRefill }

    // Периодическая сборка мусора (GC) для истекших сессий
    this.cleanupTimer = setInterval(() => this.cleanupExpiredSessions(), 15000);
    if (this.cleanupTimer.unref) {
      this.cleanupTimer.unref();
    }
  }

  checkRateLimit(ip) {
    const now = Date.now();
    const burst = this.config.rateLimitBurst || 20;
    const rps = this.config.rateLimitRps || 10;

    let bucket = this.ipRateLimits.get(ip);
    if (!bucket) {
      bucket = { tokens: burst, lastRefill: now };
      this.ipRateLimits.set(ip, bucket);
    }

    const elapsedSec = (now - bucket.lastRefill) / 1000;
    bucket.tokens = Math.min(burst, bucket.tokens + elapsedSec * rps);
    bucket.lastRefill = now;

    if (bucket.tokens < 1) {
      return false; // Rate limited
    }

    bucket.tokens -= 1;
    return true;
  }

  createSession(options = {}) {
    // Проверка лимита одновременных сессий
    if (this.sessions.size >= (this.config.maxConcurrentSessions || 50)) {
      this.cleanupExpiredSessions();
      if (this.sessions.size >= (this.config.maxConcurrentSessions || 50)) {
        throw new Error(BackendErrorCode.QUEUE_FULL);
      }
    }

    const sessionId = `live-sess-${crypto.randomBytes(8).toString('hex')}`;
    const session = new LiveSession(sessionId, {
      ...options,
      ttlMs: this.config.sessionTtlMs,
      maxSegments: this.config.maxSegmentsPerSession
    });

    this.sessions.set(sessionId, session);
    return session;
  }

  getSession(sessionId) {
    const session = this.sessions.get(sessionId);
    if (!session) return null;
    if (session.isExpired()) {
      session.close();
      this.sessions.delete(sessionId);
      return null;
    }
    return session;
  }

  closeSession(sessionId) {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.close();
      this.sessions.delete(sessionId);
      return true;
    }
    return false;
  }

  cleanupExpiredSessions() {
    const now = Date.now();
    for (const [id, session] of this.sessions.entries()) {
      if (session.isExpired() || session.isClosed()) {
        session.close();
        this.sessions.delete(id);
      }
    }
  }

  getMetrics() {
    let queuedSegments = 0;
    for (const s of this.sessions.values()) {
      queuedSegments += s.segments.size;
    }
    return {
      activeSessions: this.sessions.size,
      queuedSegments,
      maxConcurrentSessions: this.config.maxConcurrentSessions || 50
    };
  }

  destroy() {
    if (this.cleanupTimer) {
      clearInterval(this.cleanupTimer);
    }
    for (const s of this.sessions.values()) {
      s.close();
    }
    this.sessions.clear();
  }
}

module.exports = { SessionManager, LiveSession };
