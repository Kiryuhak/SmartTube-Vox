'use strict';

const http = require('http');
const crypto = require('crypto');
const { SessionManager, SessionState, GatewayLimits } = require('./session_manager');
const { MockLiveTranslationProvider } = require('./provider/mock_live_translation_provider');
const { YandexLiveTranslationProviderAdapter } = require('./provider/yandex_live_translation_provider_adapter');

const FORBIDDEN_KEYS = ['videotitle', 'signedurl', 'account', 'cookie', 'token', 'authorization', 'secret'];

function checkPrivacy(obj) {
  if (!obj || typeof obj !== 'object') return null;
  for (const [key, val] of Object.entries(obj)) {
    const lk = key.toLowerCase();
    for (const forbidden of FORBIDDEN_KEYS) {
      if (lk.includes(forbidden)) {
        return `Forbidden privacy field detected: ${key}`;
      }
    }
    if (typeof val === 'string') {
      const lv = val.toLowerCase();
      if (lv.startsWith('http://') || lv.startsWith('https://')) {
        return `Forbidden arbitrary URL detected: ${key}`;
      }
    } else if (typeof val === 'object') {
      const nested = checkPrivacy(val);
      if (nested) return nested;
    }
  }
  return null;
}

class GatewayApp {
  constructor(options = {}) {
    this.options = options;
    this.sessionManager = new SessionManager(options.defaultProvider || new MockLiveTranslationProvider());
    this.rateLimitMap = new Map(); // ip -> { count, resetAt }
  }

  checkRateLimit(ip) {
    const now = Date.now();
    let record = this.rateLimitMap.get(ip);
    if (!record || now > record.resetAt) {
      record = { count: 1, resetAt: now + 60000 };
      this.rateLimitMap.set(ip, record);
      return true;
    }
    record.count++;
    if (record.count > 300) { // 300 requests per minute
      return false;
    }
    return true;
  }

  async parseBody(req) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      let totalBytes = 0;

      req.on('data', chunk => {
        totalBytes += chunk.length;
        if (totalBytes > GatewayLimits.MAX_SEGMENT_SIZE_BYTES + 65536) {
          req.destroy();
          const err = new Error('Payload Too Large');
          err.statusCode = 413;
          reject(err);
          return;
        }
        chunks.push(chunk);
      });

      req.on('end', () => {
        resolve(Buffer.concat(chunks));
      });

      req.on('error', err => reject(err));
    });
  }

  sendJson(res, statusCode, data, headers = {}) {
    res.writeHead(statusCode, {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, X-Vox-Sequence, X-Vox-Generation, X-Vox-Source-Start-Ms, X-Vox-Source-End-Ms, X-Vox-Duration-Ms, X-Vox-Codec, X-Vox-Checksum, X-Vox-Provider',
      ...headers
    });
    res.end(JSON.stringify(data));
  }

  async handleRequest(req, res) {
    const clientIp = req.socket.remoteAddress || '127.0.0.1';

    // CORS preflight
    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, X-Vox-Sequence, X-Vox-Generation, X-Vox-Source-Start-Ms, X-Vox-Source-End-Ms, X-Vox-Duration-Ms, X-Vox-Codec, X-Vox-Checksum, X-Vox-Provider',
        'Access-Control-Max-Age': '86400'
      });
      res.end();
      return;
    }

    if (!this.checkRateLimit(clientIp)) {
      this.sendJson(res, 429, {
        error: 'RATE_LIMIT_EXCEEDED',
        message: 'Too many requests. Please slow down.'
      }, { 'Retry-After': '10' });
      return;
    }

    const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const pathname = parsedUrl.pathname;
    const searchParams = parsedUrl.searchParams;

    // Security: Check for SSRF attempts in query params
    if (searchParams.has('url') || searchParams.has('targetUrl') || searchParams.has('fetch')) {
      this.sendJson(res, 400, {
        error: 'FORBIDDEN_EXTERNAL_URL_FETCH',
        message: 'Gateway does not allow arbitrary URL fetching (SSRF protection).'
      });
      return;
    }

    // Health check
    if (req.method === 'GET' && (pathname === '/health' || pathname === '/')) {
      this.sendJson(res, 200, {
        status: 'ok',
        service: 'SmartTube VOX Live Gateway',
        mode: 'development'
      });
      return;
    }

    try {
      // 1. POST /v1/live/session -> Create Session
      if (req.method === 'POST' && pathname === '/v1/live/session') {
        const bodyBuf = await this.parseBody(req);
        let bodyJson = {};
        if (bodyBuf.length > 0) {
          try {
            bodyJson = JSON.parse(bodyBuf.toString('utf8'));
          } catch (e) {
            this.sendJson(res, 400, { error: 'INVALID_JSON', message: 'Invalid JSON payload' });
            return;
          }
        }

        const privacyViolation = checkPrivacy(bodyJson);
        if (privacyViolation) {
          this.sendJson(res, 400, { error: 'PRIVACY_VIOLATION', message: privacyViolation });
          return;
        }

        // Choose provider: mock vs yandex adapter
        const requestedProvider = req.headers['x-vox-provider'] || bodyJson.provider || 'mock';
        let providerInstance;
        if (requestedProvider === 'yandex' || requestedProvider === 'yandex_live') {
          providerInstance = new YandexLiveTranslationProviderAdapter();
        } else {
          providerInstance = this.options.mockProvider || new MockLiveTranslationProvider(bodyJson.mockOptions || {});
        }

        const session = this.sessionManager.createSession(bodyJson, providerInstance);

        this.sendJson(res, 201, {
          sessionId: session.sessionId,
          serverTime: Date.now(),
          capabilities: providerInstance.capabilities(),
          limits: {
            maxSegmentSizeBytes: GatewayLimits.MAX_SEGMENT_SIZE_BYTES,
            minSegmentDurationMs: GatewayLimits.MIN_SEGMENT_DURATION_MS,
            maxSegmentDurationMs: GatewayLimits.MAX_SEGMENT_DURATION_MS,
            maxQueuedSegments: GatewayLimits.MAX_QUEUED_SEGMENTS,
            maxQueuedDurationMs: GatewayLimits.MAX_QUEUED_DURATION_MS
          },
          state: session.state,
          generation: session.generation
        });
        return;
      }

      // 2. DELETE /v1/live/session/:sessionId -> Close Session
      const sessionDeleteMatch = pathname.match(/^\/v1\/live\/session\/([a-zA-Z0-9_\-]+)$/);
      if (req.method === 'DELETE' && sessionDeleteMatch) {
        const sessionId = sessionDeleteMatch[1];
        const closed = this.sessionManager.closeSession(sessionId);
        if (!closed) {
          this.sendJson(res, 404, { error: 'SESSION_NOT_FOUND', message: `Session ${sessionId} not found or already closed` });
          return;
        }
        this.sendJson(res, 200, { status: 'CLOSED', sessionId });
        return;
      }

      // 3. GET /v1/live/session/:sessionId/status -> Session Status
      const sessionStatusMatch = pathname.match(/^\/v1\/live\/session\/([a-zA-Z0-9_\-]+)\/status$/);
      if (req.method === 'GET' && sessionStatusMatch) {
        const sessionId = sessionStatusMatch[1];
        const session = this.sessionManager.getSession(sessionId);
        if (!session) {
          this.sendJson(res, 404, { error: 'SESSION_NOT_FOUND' });
          return;
        }
        this.sendJson(res, 200, session.toSummary());
        return;
      }

      // 4. POST /v1/live/session/:sessionId/segment -> Ingest Audio Segment
      const segmentIngestMatch = pathname.match(/^\/v1\/live\/session\/([a-zA-Z0-9_\-]+)\/segment$/);
      if (req.method === 'POST' && segmentIngestMatch) {
        const sessionId = segmentIngestMatch[1];
        const session = this.sessionManager.getSession(sessionId);
        if (!session) {
          this.sendJson(res, 404, { error: 'SESSION_NOT_FOUND', message: 'Session expired or not found' });
          return;
        }

        const bodyBuf = await this.parseBody(req);
        if (bodyBuf.length > GatewayLimits.MAX_SEGMENT_SIZE_BYTES) {
          this.sendJson(res, 413, {
            error: 'PAYLOAD_TOO_LARGE',
            message: `Segment exceeds maximum allowed size of ${GatewayLimits.MAX_SEGMENT_SIZE_BYTES} bytes`
          });
          return;
        }

        // Extract metadata from headers or multipart/JSON
        let sequence = parseInt(req.headers['x-vox-sequence'], 10);
        let generation = parseInt(req.headers['x-vox-generation'], 10);
        let durationMs = parseInt(req.headers['x-vox-duration-ms'], 10);
        let sourceStartMs = parseInt(req.headers['x-vox-source-start-ms'], 10);
        let sourceEndMs = parseInt(req.headers['x-vox-source-end-ms'], 10);
        let codec = req.headers['x-vox-codec'] || 'opus';
        let checksum = req.headers['x-vox-checksum'] || null;

        let audioPayload = bodyBuf;

        // If JSON body was sent instead of raw binary
        const contentType = req.headers['content-type'] || '';
        if (contentType.includes('application/json') && bodyBuf.length > 0) {
          try {
            const meta = JSON.parse(bodyBuf.toString('utf8'));
            const privacyViolation = checkPrivacy(meta);
            if (privacyViolation) {
              this.sendJson(res, 400, { error: 'PRIVACY_VIOLATION', message: privacyViolation });
              return;
            }
            if (isNaN(sequence) && meta.sequence !== undefined) sequence = parseInt(meta.sequence, 10);
            if (isNaN(generation) && meta.generation !== undefined) generation = parseInt(meta.generation, 10);
            if (isNaN(durationMs) && meta.durationMs !== undefined) durationMs = parseInt(meta.durationMs, 10);
            if (isNaN(sourceStartMs) && meta.sourceStartMs !== undefined) sourceStartMs = parseInt(meta.sourceStartMs, 10);
            if (isNaN(sourceEndMs) && meta.sourceEndMs !== undefined) sourceEndMs = parseInt(meta.sourceEndMs, 10);
            if (meta.codec) codec = meta.codec;
            if (meta.checksum) checksum = meta.checksum;
            if (meta.audioPayloadBase64) {
              audioPayload = Buffer.from(meta.audioPayloadBase64, 'base64');
            }
          } catch (e) {
            this.sendJson(res, 400, { error: 'INVALID_JSON', message: 'Could not parse JSON segment metadata' });
            return;
          }
        }

        // Metadata validation
        if (isNaN(sequence) || sequence < 0) {
          this.sendJson(res, 400, { error: 'INVALID_SEQUENCE', message: 'x-vox-sequence header required and must be >= 0' });
          return;
        }

        if (isNaN(generation) || generation < 1) {
          generation = session.generation;
        }

        if (isNaN(durationMs) || durationMs < GatewayLimits.MIN_SEGMENT_DURATION_MS || durationMs > GatewayLimits.MAX_SEGMENT_DURATION_MS) {
          this.sendJson(res, 400, {
            error: 'INVALID_SEGMENT_DURATION',
            message: `Segment duration must be between ${GatewayLimits.MIN_SEGMENT_DURATION_MS}ms and ${GatewayLimits.MAX_SEGMENT_DURATION_MS}ms`
          });
          return;
        }

        if (!checksum && audioPayload.length > 0) {
          checksum = crypto.createHash('sha256').update(audioPayload).digest('hex');
        }

        // Generation protection
        if (generation < session.generation) {
          session.droppedStaleCount++;
          this.sendJson(res, 200, {
            status: 'STALE_GENERATION_DROPPED',
            sequence,
            generation,
            currentGeneration: session.generation,
            message: 'Segment discarded because it belongs to an older playback generation'
          });
          return;
        }

        if (generation > session.generation) {
          session.handleGenerationChange(generation, sequence);
        }

        // Deduplication check
        const dedupeKey = `${generation}:${sequence}:${checksum}`;
        if (session.dedupeMap.has(dedupeKey)) {
          session.duplicateCount++;
          const existingResult = session.getResult(sequence, generation);
          this.sendJson(res, 200, {
            status: 'DUPLICATE_ACCEPTED',
            sequence,
            generation,
            resultReady: !!existingResult
          });
          return;
        }
        session.dedupeMap.set(dedupeKey, 'QUEUED');

        // Backpressure check
        const queueDepth = session.getQueueDepth();
        const queuedDurationMs = session.getQueuedDurationMs();
        if (queueDepth >= GatewayLimits.MAX_QUEUED_SEGMENTS || queuedDurationMs >= GatewayLimits.MAX_QUEUED_DURATION_MS) {
          session.backpressureCount++;
          session.state = SessionState.DEGRADED;
          this.sendJson(res, 429, {
            error: 'BACKPRESSURE_EXCEEDED',
            message: 'Live translation worker queue is saturated. Client should delay or drop segments.',
            queueDepth,
            queuedDurationMs,
            retryAfterSeconds: 2
          }, { 'Retry-After': '2' });
          return;
        }

        const segmentObj = {
          sequence,
          generation,
          sourceStartMs: isNaN(sourceStartMs) ? 0 : sourceStartMs,
          sourceEndMs: isNaN(sourceEndMs) ? durationMs : sourceEndMs,
          durationMs,
          codec,
          payload: audioPayload,
          checksum,
          receivedAtMs: Date.now()
        };

        // Queue segment and process with provider
        session.queuedSegments.push(segmentObj);
        session.updateStateAfterIngest();

        // Trigger asynchronous processing
        this.processQueueForSession(session);

        this.sendJson(res, 202, {
          status: 'QUEUED',
          sessionId: session.sessionId,
          sequence,
          generation,
          queueDepth: session.getQueueDepth(),
          estimatedReadyMs: session.provider.getEffectiveDelay ? session.provider.getEffectiveDelay() : 1000
        });
        return;
      }

      // 5. GET /v1/live/session/:sessionId/segment/:sequence -> Poll Result
      const segmentPollMatch = pathname.match(/^\/v1\/live\/session\/([a-zA-Z0-9_\-]+)\/segment\/([0-9]+)$/);
      if (req.method === 'GET' && segmentPollMatch) {
        const sessionId = segmentPollMatch[1];
        const sequence = parseInt(segmentPollMatch[2], 10);
        const reqGenStr = searchParams.get('generation');
        const generation = reqGenStr !== null ? parseInt(reqGenStr, 10) : undefined;

        const session = this.sessionManager.getSession(sessionId);
        if (!session) {
          this.sendJson(res, 404, { error: 'SESSION_NOT_FOUND' });
          return;
        }

        const result = session.getResult(sequence, generation);
        if (!result) {
          // Check if it's still in queue
          const isQueued = session.queuedSegments.some(s => s.sequence === sequence && (generation === undefined || s.generation === generation));
          if (isQueued) {
            this.sendJson(res, 200, {
              status: 'PROCESSING',
              sequence,
              generation: generation || session.generation,
              queueDepth: session.getQueueDepth()
            });
            return;
          }
          this.sendJson(res, 404, {
            error: 'SEGMENT_NOT_FOUND',
            sequence,
            message: 'Segment not found in results or queue'
          });
          return;
        }

        // Return ready or failed result
        this.sendJson(res, 200, {
          status: result.status || 'READY',
          sequence: result.sequence,
          generation: result.generation,
          sourceStartMs: result.sourceStartMs,
          sourceEndMs: result.sourceEndMs,
          durationMs: result.durationMs,
          translatedDurationMs: result.translatedDurationMs,
          codec: result.codec,
          text: result.text,
          audioData: result.audioData ? result.audioData.toString('base64') : null,
          error: result.error || null,
          providerLatencyMs: result.providerLatencyMs,
          completedAtMs: result.completedAtMs,
          isRealTranslation: !!result.isRealTranslation
        });
        return;
      }

      // Not found fallback
      this.sendJson(res, 404, { error: 'NOT_FOUND', message: `Route ${req.method} ${pathname} not found` });
    } catch (err) {
      const code = err.statusCode || 500;
      this.sendJson(res, code, {
        error: err.code || 'INTERNAL_ERROR',
        message: err.message || 'Internal Server Error'
      });
    }
  }

  async processQueueForSession(session) {
    if (session.isProcessing) return;
    session.isProcessing = true;

    try {
      while (session.queuedSegments.length > 0 && session.state !== SessionState.CLOSED) {
        // Sort queue by sequence to preserve ordering
        session.queuedSegments.sort((a, b) => a.sequence - b.sequence);
        const segment = session.queuedSegments.shift();

        if (segment.generation < session.generation) {
          session.droppedStaleCount++;
          continue;
        }

        try {
          const result = await session.provider.translateSegment(session, segment);
          session.storeResult(segment.sequence, result);
          session.nextExpectedSequence = segment.sequence + 1;
        } catch (provErr) {
          if (provErr.code === 'CANCELLED') {
            continue;
          }
          session.storeResult(segment.sequence, {
            sequence: segment.sequence,
            generation: segment.generation,
            status: 'FAILED',
            error: provErr.code || provErr.message,
            durationMs: segment.durationMs,
            failedAtMs: Date.now()
          });
        }
      }
    } finally {
      session.isProcessing = false;
    }
  }
}

module.exports = {
  GatewayApp,
  checkPrivacy
};
