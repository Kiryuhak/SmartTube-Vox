'use strict';

const http = require('http');
const defaultConfig = require('./config');
const { WorkerStage, BackendErrorCode, SessionStatus } = require('./models/types');
const { SessionManager } = require('./session/session_manager');
const { PipelineOrchestrator } = require('./pipeline/pipeline_orchestrator');

const FORBIDDEN_PRIVACY_KEYS = [
  'videotitle', 'channelname', 'account', 'cookie', 'yandextoken', 'accesstoken', 'refreshtoken', 'password'
];

function checkPrivacy(obj) {
  if (!obj || typeof obj !== 'object') return null;
  for (const [key, val] of Object.entries(obj)) {
    const lk = key.toLowerCase();
    for (const forbidden of FORBIDDEN_PRIVACY_KEYS) {
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

class BackendApp {
  constructor(customConfig = {}) {
    this.config = { ...defaultConfig, ...customConfig };
    this.sessionManager = new SessionManager(this.config);
    this.orchestrator = new PipelineOrchestrator(this.config);
    this.server = null;
  }

  async parseBody(req) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      let totalBytes = 0;
      const maxLimit = this.config.maxSegmentBytes || 524288;

      req.on('data', chunk => {
        totalBytes += chunk.length;
        if (totalBytes > maxLimit + 1024) {
          req.destroy(new Error(BackendErrorCode.QUEUE_FULL));
          return;
        }
        chunks.push(chunk);
      });

      req.on('end', () => {
        const raw = Buffer.concat(chunks);
        const contentType = req.headers['content-type'] || '';
        if (contentType.includes('application/json')) {
          try {
            const str = raw.toString('utf8').trim();
            const parsed = str.length > 0 ? JSON.parse(str) : {};
            resolve({ type: 'json', data: parsed, raw });
          } catch (e) {
            reject(new Error(BackendErrorCode.INVALID_AUDIO));
          }
        } else {
          resolve({ type: 'binary', raw });
        }
      });

      req.on('error', err => reject(err));
    });
  }

  sendJson(res, statusCode, data) {
    res.writeHead(statusCode, {
      'Content-Type': 'application/json; charset=utf-8',
      'X-Content-Type-Options': 'nosniff',
      'Cache-Control': 'no-store, no-cache, must-revalidate',
      'Access-Control-Allow-Origin': '*'
    });
    res.end(JSON.stringify(data));
  }

  sendError(res, statusCode, errorCode, messageRu) {
    this.sendJson(res, statusCode, {
      error: errorCode,
      messageRu: messageRu || errorCode,
      timestamp: Date.now()
    });
  }

  handleRequest(req, res) {
    const ip = req.socket.remoteAddress || '127.0.0.1';

    // Rate Limiting
    if (!this.sessionManager.checkRateLimit(ip)) {
      return this.sendError(res, 429, BackendErrorCode.RATE_LIMITED, 'Превышен лимит запросов (Rate Limited)');
    }

    const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const pathname = parsedUrl.pathname;
    const method = req.method.toUpperCase();

    // 1. Health Endpoint
    if (method === 'GET' && pathname === '/health') {
      return this.sendJson(res, 200, {
        status: 'ok',
        uptime: process.uptime(),
        mode: this.config.backendMode
      });
    }

    // 2. Ready Endpoint
    if (method === 'GET' && pathname === '/ready') {
      return this.sendJson(res, 200, {
        ready: true,
        mode: this.config.backendMode,
        stt: this.config.stt,
        translation: this.config.translation,
        tts: this.config.tts,
        maxSessions: this.config.maxConcurrentSessions
      });
    }

    // 3. Metrics Endpoint (safe, aggregate only)
    if (method === 'GET' && pathname === '/v1/live/metrics') {
      const metrics = this.sessionManager.getMetrics();
      return this.sendJson(res, 200, {
        ...metrics,
        backendMode: this.config.backendMode,
        uptimeSec: Math.floor(process.uptime())
      });
    }

    // 4. Session Creation: POST /v1/live/session
    if (method === 'POST' && pathname === '/v1/live/session') {
      return this.parseBody(req).then(({ data }) => {
        const privacyViolation = checkPrivacy(data);
        if (privacyViolation) {
          return this.sendError(res, 400, BackendErrorCode.INVALID_AUDIO, privacyViolation);
        }

        try {
          const session = this.sessionManager.createSession(data || {});
          return this.sendJson(res, 201, {
            sessionId: session.id,
            streamId: session.streamId,
            status: session.status,
            sessionToken: session.sessionToken,
            expiresAt: session.createdAt + session.ttlMs,
            maxSegmentDurationMs: 3000,
            preferredChunkDurationMs: 2000,
            sampleRate: session.sampleRate,
            channels: session.channels
          });
        } catch (err) {
          if (err.message === BackendErrorCode.QUEUE_FULL) {
            return this.sendError(res, 429, BackendErrorCode.QUEUE_FULL, 'Очередь сессий переполнена (Backpressure)');
          }
          return this.sendError(res, 500, BackendErrorCode.INTERNAL, 'Ошибка создания сессии');
        }
      }).catch(err => {
        return this.sendError(res, 400, BackendErrorCode.INVALID_AUDIO, err.message);
      });
    }

    // Маршруты с session ID
    const sessionMatch = pathname.match(/^\/v1\/live\/session\/([^/]+)(.*)$/);
    if (!sessionMatch) {
      return this.sendError(res, 404, 'NOT_FOUND', 'Маршрут не найден');
    }

    const sessionId = sessionMatch[1];
    const subPath = sessionMatch[2] || '';

    // 5. Delete Session: DELETE /v1/live/session/{id}
    if (method === 'DELETE' && subPath === '') {
      const closed = this.sessionManager.closeSession(sessionId);
      if (closed) {
        return this.sendJson(res, 200, { status: 'CLOSED', sessionId });
      } else {
        return this.sendError(res, 404, BackendErrorCode.SESSION_EXPIRED, 'Сессия не найдена или уже завершена');
      }
    }

    const session = this.sessionManager.getSession(sessionId);
    if (!session) {
      return this.sendError(res, 404, BackendErrorCode.SESSION_EXPIRED, 'Сессия истекла или не существует');
    }

    // Проверка сессионного токена
    const tokenHeader = req.headers['x-session-token'];
    if (tokenHeader && tokenHeader !== session.sessionToken) {
      return this.sendError(res, 401, 'AUTH_REQUIRED', 'Неверный sessionToken');
    }

    // 6. Segment Ingestion: POST /v1/live/session/{id}/segment
    if (method === 'POST' && subPath === '/segment') {
      return this.parseBody(req).then(async ({ type, data, raw }) => {
        let sequence = parseInt(req.headers['x-sequence'] || '0', 10);
        let generation = parseInt(req.headers['x-generation'] || '1', 10);
        let startPtsUs = parseInt(req.headers['x-start-pts-us'] || '0', 10);
        let endPtsUs = parseInt(req.headers['x-end-pts-us'] || '0', 10);
        let audioBuf = null;

        if (type === 'json' && data) {
          const pv = checkPrivacy(data);
          if (pv) return this.sendError(res, 400, BackendErrorCode.INVALID_AUDIO, pv);
          sequence = data.sequence !== undefined ? data.sequence : sequence;
          generation = data.generation !== undefined ? data.generation : generation;
          startPtsUs = data.startPtsUs !== undefined ? data.startPtsUs : startPtsUs;
          endPtsUs = data.endPtsUs !== undefined ? data.endPtsUs : endPtsUs;
          if (data.audioBase64) {
            audioBuf = Buffer.from(data.audioBase64, 'base64');
          }
        } else if (type === 'binary') {
          audioBuf = raw;
        }

        if (!audioBuf || audioBuf.length === 0) {
          return this.sendError(res, 400, BackendErrorCode.INVALID_AUDIO, 'Аудиобуфер пуст');
        }

        if (audioBuf.length > (this.config.maxSegmentBytes || 524288)) {
          return this.sendError(res, 429, BackendErrorCode.QUEUE_FULL, 'Размер аудиосегмента превышает лимит');
        }

        // Обновляем поколение сессии при переключении
        if (generation > session.currentGeneration) {
          session.setGeneration(generation);
        } else if (generation < session.currentGeneration) {
          return this.sendError(res, 409, BackendErrorCode.STALE_GENERATION, 'Сегмент устаревшего поколения (Stale Generation)');
        }

        const segment = {
          sequence,
          generation,
          startPtsUs,
          endPtsUs,
          audioBuffer: audioBuf,
          sourceLanguage: session.sourceLanguage,
          targetLanguage: session.targetLanguage,
          stage: WorkerStage.INGESTED,
          createdAt: Date.now()
        };

        session.addSegment(segment);

        // Обработка сегмента через конвейер
        const processed = await this.orchestrator.processSegment(segment, session);

        if (processed.stage === WorkerStage.FAILED) {
          return this.sendError(res, 500, processed.error || BackendErrorCode.INTERNAL, 'Ошибка обработки сегмента');
        }

        if (processed.stage === WorkerStage.STALE) {
          return this.sendError(res, 409, BackendErrorCode.STALE_GENERATION, 'Сегмент признан устаревшим во время инференса');
        }

        const translatedBase64 = processed.translatedAudio ? processed.translatedAudio.toString('base64') : '';

        return this.sendJson(res, 200, {
          sequence: processed.sequence,
          generation: processed.generation,
          status: processed.stage,
          translatedAudio: translatedBase64,
          sampleRate: processed.outputSampleRate || 16000,
          channels: processed.outputChannels || 1,
          encoding: processed.outputEncoding || 'pcm_s16le',
          startPtsUs: processed.startPtsUs,
          endPtsUs: processed.endPtsUs,
          originalText: processed.originalText || '',
          translatedText: processed.translatedText || '',
          sttLatencyMs: processed.sttLatencyMs || 0,
          translationLatencyMs: processed.translationLatencyMs || 0,
          ttsLatencyMs: processed.ttsLatencyMs || 0,
          totalLatencyMs: processed.totalLatencyMs || 0
        });
      }).catch(err => {
        return this.sendError(res, 400, BackendErrorCode.INVALID_AUDIO, err.message);
      });
    }

    // 7. Get Segment: GET /v1/live/session/{id}/segment/{sequence}
    const getSegMatch = subPath.match(/^\/segment\/(\d+)$/);
    if (method === 'GET' && getSegMatch) {
      const seq = parseInt(getSegMatch[1], 10);
      const seg = session.getSegment(seq);
      if (!seg) {
        return this.sendError(res, 404, 'NOT_FOUND', `Сегмент #${seq} не найден`);
      }
      return this.sendJson(res, 200, {
        sequence: seg.sequence,
        generation: seg.generation,
        status: seg.stage,
        originalText: seg.originalText || '',
        translatedText: seg.translatedText || '',
        totalLatencyMs: seg.totalLatencyMs || 0
      });
    }

    return this.sendError(res, 404, 'NOT_FOUND', 'Маршрут не найден');
  }

  listen(port = this.config.port, host = this.config.host) {
    return new Promise((resolve, reject) => {
      this.server = http.createServer((req, res) => this.handleRequest(req, res));
      this.server.listen(port, host, () => {
        resolve(this.server.address());
      });
      this.server.on('error', reject);
    });
  }

  close() {
    return new Promise((resolve) => {
      this.sessionManager.destroy();
      if (this.server) {
        this.server.close(() => resolve());
      } else {
        resolve();
      }
    });
  }
}

module.exports = { BackendApp };
