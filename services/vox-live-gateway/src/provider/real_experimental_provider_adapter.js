'use strict';

const http = require('http');
const https = require('https');
const { LiveTranslationProvider, ProviderState } = require('./live_translation_provider');

/**
 * Адаптер для реального внешнего потокового провайдера перевода (Section 14, 15).
 * Читает настройки исключительно из серверного окружения (process.env):
 * - VOX_REAL_PROVIDER_URL: целевой эндпоинт стримингового сервиса (STT -> MT -> TTS)
 * - VOX_REAL_PROVIDER_KEY: серверный ключ авторизации (никогда не попадает в APK)
 *
 * Если сервис не сконфигурирован, адаптер честно возвращает статус UNAVAILABLE или AUTH_REQUIRED,
 * не симулируя фальшивый успех.
 */
class RealExperimentalLiveTranslationProvider extends LiveTranslationProvider {
  constructor(options = {}) {
    super();
    this.endpointUrl = options.endpointUrl || process.env.VOX_REAL_PROVIDER_URL || null;
    this.apiKey = options.apiKey || process.env.VOX_REAL_PROVIDER_KEY || null;
    this.timeoutMs = options.timeoutMs || 8000;
  }

  capabilities() {
    const isConfigured = !!this.endpointUrl;
    const hasKey = !!this.apiKey;

    let status = ProviderState.UNAVAILABLE;
    let reason = 'External realtime streaming translation provider endpoint is not configured in server environment';

    if (isConfigured && !hasKey) {
      status = ProviderState.AUTH_REQUIRED;
      reason = 'Server-side API key missing for external translation provider';
    } else if (isConfigured && hasKey) {
      status = ProviderState.AVAILABLE;
      reason = 'External realtime translation adapter configured';
    }

    return {
      supportsRawPcm: true,
      supportsEncodedAudio: true,
      supportsIncremental: true,
      supportsStreaming: true,
      supportsSession: true,
      supportsCancellation: true,
      supportsSourceLanguageAuto: true,
      supportsVoiceSynthesis: true,
      supportsPartialResults: false,
      targetSampleRate: 16000,
      targetChannels: 1,
      providerId: 'real_experimental',
      status,
      isRealTranslation: isConfigured && hasKey,
      reason
    };
  }

  async startSession(session) {
    const caps = this.capabilities();
    if (caps.status !== ProviderState.AVAILABLE) {
      const err = new Error(caps.reason);
      err.code = caps.status;
      err.status = caps.status;
      throw err;
    }

    return {
      providerSessionId: `real_${session.sessionId}`,
      status: 'INITIALIZED'
    };
  }

  async translateSegment(session, segment) {
    const caps = this.capabilities();
    if (caps.status !== ProviderState.AVAILABLE) {
      const err = new Error(caps.reason);
      err.code = caps.status;
      err.status = caps.status;
      throw err;
    }

    const startTime = Date.now();

    // Отправляем сегмент в реальный стриминговый шлюз
    return new Promise((resolve, reject) => {
      try {
        const parsedUrl = new URL(this.endpointUrl);
        const isHttps = parsedUrl.protocol === 'https:';
        const client = isHttps ? https : http;

        const reqOptions = {
          protocol: parsedUrl.protocol,
          hostname: parsedUrl.hostname,
          port: parsedUrl.port || (isHttps ? 443 : 80),
          path: parsedUrl.pathname + parsedUrl.search,
          method: 'POST',
          headers: {
            'Content-Type': 'application/octet-stream',
            'Content-Length': segment.payload ? segment.payload.length : 0,
            'Authorization': `Bearer ${this.apiKey}`,
            'X-Vox-Sequence': segment.sequence,
            'X-Vox-Generation': segment.generation,
            'X-Vox-Pts-Start-Us': segment.ptsStartUs || 0,
            'X-Vox-Pts-End-Us': segment.ptsEndUs || 0,
            'X-Vox-Sample-Rate': segment.sampleRate || 16000,
            'X-Vox-Channels': segment.channels || 1,
            'X-Vox-Target-Lang': session.targetLang || 'ru'
          },
          timeout: this.timeoutMs
        };

        const req = client.request(reqOptions, (res) => {
          const chunks = [];

          res.on('data', chunk => chunks.push(chunk));
          res.on('end', () => {
            const latencyMs = Date.now() - startTime;
            const resBuffer = Buffer.concat(chunks);

            if (res.statusCode === 401 || res.statusCode === 403) {
              const err = new Error('Authentication required for real translation provider');
              err.code = ProviderState.AUTH_REQUIRED;
              err.status = ProviderState.AUTH_REQUIRED;
              reject(err);
              return;
            }

            if (res.statusCode === 429) {
              const err = new Error('Real translation provider rate limit exceeded (429)');
              err.code = ProviderState.RATE_LIMITED;
              err.status = ProviderState.RATE_LIMITED;
              err.retryAfter = parseInt(res.headers['retry-after'], 10) || 2;
              reject(err);
              return;
            }

            if (res.statusCode >= 500) {
              const err = new Error(`Real translation provider internal error (${res.statusCode})`);
              err.code = ProviderState.ERROR;
              err.status = ProviderState.ERROR;
              reject(err);
              return;
            }

            if (res.statusCode < 200 || res.statusCode >= 300) {
              const err = new Error(`Real translation provider returned unexpected status ${res.statusCode}`);
              err.code = ProviderState.ERROR;
              err.status = ProviderState.ERROR;
              reject(err);
              return;
            }

            // Успешный реальный аудиорезультат
            resolve({
              sessionId: session.sessionId,
              sequence: segment.sequence,
              generation: segment.generation,
              sourceStartMs: segment.sourceStartMs,
              sourceEndMs: segment.sourceEndMs,
              ptsStartUs: segment.ptsStartUs,
              ptsEndUs: segment.ptsEndUs,
              sampleRate: parseInt(res.headers['x-vox-sample-rate'], 10) || 48000,
              channels: parseInt(res.headers['x-vox-channels'], 10) || 2,
              encoding: 'pcm_16le',
              durationMs: segment.durationMs,
              translatedDurationMs: segment.durationMs,
              codec: 'pcm_16le',
              audioData: resBuffer,
              providerLatencyMs: latencyMs,
              providerStatus: ProviderState.AVAILABLE,
              completedAtMs: Date.now(),
              isRealTranslation: true
            });
          });
        });

        req.on('timeout', () => {
          req.destroy();
          const err = new Error('Real translation provider request timeout');
          err.code = ProviderState.ERROR;
          err.status = ProviderState.ERROR;
          reject(err);
        });

        req.on('error', (err) => {
          const error = new Error(`Real translation provider connection failed: ${err.code || 'UNKNOWN'}`);
          error.code = ProviderState.UNAVAILABLE;
          error.status = ProviderState.UNAVAILABLE;
          reject(error);
        });

        if (segment.payload && segment.payload.length > 0) {
          req.write(segment.payload);
        }
        req.end();

      } catch (e) {
        const err = new Error(`Failed to dispatch request to real provider: ${e.message}`);
        err.code = ProviderState.ERROR;
        err.status = ProviderState.ERROR;
        reject(err);
      }
    });
  }

  async cancelSegment(session, sequence, generation) {
    return true;
  }

  async closeSession(session) {
    // zero-retention: no stored state
  }
}

module.exports = {
  RealExperimentalLiveTranslationProvider
};
