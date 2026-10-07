'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('http');
const { GatewayApp } = require('../src/gateway_app');
const { LiveTranslationProvider, ProviderState } = require('../src/provider/live_translation_provider');
const { MockLiveTranslationProvider } = require('../src/provider/mock_live_translation_provider');
const { YandexLiveTranslationProviderAdapter } = require('../src/provider/yandex_live_translation_provider_adapter');
const { RealExperimentalLiveTranslationProvider } = require('../src/provider/real_experimental_provider_adapter');

function makeRequest(server, path, options = {}, body = null) {
  return new Promise((resolve, reject) => {
    const address = server.address();
    const reqOptions = {
      hostname: '127.0.0.1',
      port: address.port,
      path,
      method: options.method || 'GET',
      headers: options.headers || {}
    };

    let payload = null;
    if (body !== null) {
      if (Buffer.isBuffer(body)) {
        payload = body;
        reqOptions.headers['Content-Type'] = reqOptions.headers['Content-Type'] || 'application/octet-stream';
        reqOptions.headers['Content-Length'] = payload.length;
      } else {
        payload = Buffer.from(JSON.stringify(body), 'utf8');
        reqOptions.headers['Content-Type'] = 'application/json';
        reqOptions.headers['Content-Length'] = payload.length;
      }
    }

    const req = http.request(reqOptions, (res) => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => {
        const raw = Buffer.concat(chunks);
        let parsed = null;
        try {
          parsed = JSON.parse(raw.toString('utf8'));
        } catch {
          parsed = raw;
        }
        resolve({
          statusCode: res.statusCode,
          headers: res.headers,
          body: parsed,
          raw
        });
      });
    });

    req.on('error', reject);
    if (payload) {
      req.write(payload);
    }
    req.end();
  });
}

test('SmartTube VOX 8 - Provider Strategy & Contract Tests (Patch #8)', async (t) => {
  let app;
  let server;

  t.beforeEach(async () => {
    app = new GatewayApp();
    server = http.createServer((req, res) => app.handleRequest(req, res));
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  });

  t.afterEach(async () => {
    if (server) {
      await new Promise(resolve => server.close(resolve));
    }
    if (app && app.sessionManager) {
      app.sessionManager.destroy();
    }
  });

  await t.test('1. Provider Capabilities: All 7 standardized provider states are defined', () => {
    assert.equal(ProviderState.AVAILABLE, 'AVAILABLE');
    assert.equal(ProviderState.UNAVAILABLE, 'UNAVAILABLE');
    assert.equal(ProviderState.UNSUPPORTED, 'UNSUPPORTED');
    assert.equal(ProviderState.AUTH_REQUIRED, 'AUTH_REQUIRED');
    assert.equal(ProviderState.RATE_LIMITED, 'RATE_LIMITED');
    assert.equal(ProviderState.DEGRADED, 'DEGRADED');
    assert.equal(ProviderState.ERROR, 'ERROR');
  });

  await t.test('2. Provider Routing: Gateway correctly instantiates MOCK, PASSTHROUGH, YANDEX_VOD_ONLY, and REAL_EXPERIMENTAL', async () => {
    // A: MOCK
    const resMock = await makeRequest(server, '/v1/live/session', {
      method: 'POST',
      headers: { 'X-Vox-Provider': 'mock' }
    }, { sourceLanguage: 'en', targetLanguage: 'ru' });
    assert.equal(resMock.statusCode, 201);
    assert.equal(resMock.body.capabilities.providerId, 'mock_provider');
    assert.equal(resMock.body.capabilities.supportsRawPcm, true);
    assert.equal(resMock.body.capabilities.status, 'AVAILABLE');

    // B: PASSTHROUGH
    const resPass = await makeRequest(server, '/v1/live/session', {
      method: 'POST',
      headers: { 'X-Vox-Provider': 'passthrough' }
    }, { sourceLanguage: 'en', targetLanguage: 'ru' });
    assert.equal(resPass.statusCode, 201);
    assert.equal(resPass.body.capabilities.providerId, 'passthrough_provider');
    assert.equal(resPass.body.capabilities.supportsRawPcm, true);

    // C: YANDEX_VOD_ONLY
    const resYandex = await makeRequest(server, '/v1/live/session', {
      method: 'POST',
      headers: { 'X-Vox-Provider': 'yandex_vod_only' }
    }, { sourceLanguage: 'en', targetLanguage: 'ru' });
    assert.equal(resYandex.statusCode, 201);
    assert.equal(resYandex.body.capabilities.providerId, 'yandex_live_adapter');
    assert.equal(resYandex.body.capabilities.status, 'UNSUPPORTED_INCREMENTAL_TRANSLATION');
    assert.equal(resYandex.body.capabilities.supportsIncremental, false);
    assert.equal(resYandex.body.capabilities.supportsRawPcm, false);

    // D: REAL_EXPERIMENTAL (unconfigured in test env -> UNAVAILABLE)
    const resReal = await makeRequest(server, '/v1/live/session', {
      method: 'POST',
      headers: { 'X-Vox-Provider': 'real_experimental' }
    }, { sourceLanguage: 'en', targetLanguage: 'ru' });
    assert.equal(resReal.statusCode, 201);
    assert.equal(resReal.body.capabilities.providerId, 'real_experimental');
    assert.equal(resReal.body.capabilities.status, 'UNAVAILABLE');
    assert.equal(resReal.body.capabilities.isRealTranslation, false);
  });

  await t.test('3. Real Experimental Provider: Unconfigured endpoint reports UNAVAILABLE without faking success', async () => {
    const unconfigured = new RealExperimentalLiveTranslationProvider({ endpointUrl: null, apiKey: null });
    const caps = unconfigured.capabilities();
    assert.equal(caps.status, ProviderState.UNAVAILABLE);
    assert.equal(caps.isRealTranslation, false);

    await assert.rejects(async () => {
      await unconfigured.startSession({ sessionId: 'test_session' });
    }, (err) => {
      assert.equal(err.code, ProviderState.UNAVAILABLE);
      return true;
    });
  });

  await t.test('4. Real Experimental Provider: Missing API key reports AUTH_REQUIRED', async () => {
    const missingKey = new RealExperimentalLiveTranslationProvider({
      endpointUrl: 'http://127.0.0.1:9999/v1/translate',
      apiKey: null
    });
    const caps = missingKey.capabilities();
    assert.equal(caps.status, ProviderState.AUTH_REQUIRED);
    assert.equal(caps.isRealTranslation, false);
  });

  await t.test('5. Real Provider: Handles remote 429 rate limiting with retry-after header', async () => {
    // Создаем тестовый HTTP mock-сервер, эмулирующий 429 Too Many Requests
    const mockCloud = http.createServer((req, res) => {
      res.writeHead(429, {
        'Content-Type': 'application/json',
        'Retry-After': '5'
      });
      res.end(JSON.stringify({ error: 'Quota exceeded' }));
    });

    await new Promise(resolve => mockCloud.listen(0, '127.0.0.1', resolve));
    const cloudPort = mockCloud.address().port;

    const realProvider = new RealExperimentalLiveTranslationProvider({
      endpointUrl: `http://127.0.0.1:${cloudPort}/v1/stream`,
      apiKey: 'test_token_123'
    });

    const session = { sessionId: 'test_rate_limit', targetLang: 'ru' };
    const segment = {
      sequence: 1,
      generation: 1,
      ptsStartUs: 1000000,
      ptsEndUs: 3000000,
      payload: Buffer.from('fake_pcm_data'),
      durationMs: 2000
    };

    await assert.rejects(async () => {
      await realProvider.translateSegment(session, segment);
    }, (err) => {
      assert.equal(err.code, ProviderState.RATE_LIMITED);
      assert.equal(err.retryAfter, 5);
      return true;
    });

    await new Promise(resolve => mockCloud.close(resolve));
  });

  await t.test('6. Real Provider: Handles remote 500 error gracefully', async () => {
    const mockCloud = http.createServer((req, res) => {
      res.writeHead(500, { 'Content-Type': 'text/plain' });
      res.end('Internal Server Error');
    });

    await new Promise(resolve => mockCloud.listen(0, '127.0.0.1', resolve));
    const cloudPort = mockCloud.address().port;

    const realProvider = new RealExperimentalLiveTranslationProvider({
      endpointUrl: `http://127.0.0.1:${cloudPort}/v1/stream`,
      apiKey: 'test_token_123'
    });

    const session = { sessionId: 'test_error', targetLang: 'ru' };
    const segment = { sequence: 1, generation: 1, payload: Buffer.from('fake_pcm') };

    await assert.rejects(async () => {
      await realProvider.translateSegment(session, segment);
    }, (err) => {
      assert.equal(err.code, ProviderState.ERROR);
      return true;
    });

    await new Promise(resolve => mockCloud.close(resolve));
  });

  await t.test('7. Real Provider: Successful response preserves sequence, PTS and audio headers', async () => {
    const mockCloud = http.createServer((req, res) => {
      res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'X-Vox-Sample-Rate': '16000',
        'X-Vox-Channels': '1'
      });
      res.end(Buffer.from('real_translated_pcm_data_ru'));
    });

    await new Promise(resolve => mockCloud.listen(0, '127.0.0.1', resolve));
    const cloudPort = mockCloud.address().port;

    const realProvider = new RealExperimentalLiveTranslationProvider({
      endpointUrl: `http://127.0.0.1:${cloudPort}/v1/stream`,
      apiKey: 'test_token_123'
    });

    const session = { sessionId: 'test_ok', targetLang: 'ru' };
    const segment = {
      sequence: 7,
      generation: 2,
      sourceStartMs: 4000,
      sourceEndMs: 6000,
      ptsStartUs: 4000000,
      ptsEndUs: 6000000,
      durationMs: 2000,
      payload: Buffer.from('input_pcm_bytes')
    };

    const result = await realProvider.translateSegment(session, segment);
    assert.equal(result.sequence, 7);
    assert.equal(result.generation, 2);
    assert.equal(result.ptsStartUs, 4000000);
    assert.equal(result.ptsEndUs, 6000000);
    assert.equal(result.isRealTranslation, true);
    assert.equal(result.audioData.toString('utf8'), 'real_translated_pcm_data_ru');
    assert.equal(result.providerStatus, ProviderState.AVAILABLE);

    await new Promise(resolve => mockCloud.close(resolve));
  });

  await t.test('8. Privacy: Gateway rejects secret leak attempts and URLs in payload', async () => {
    const res = await makeRequest(server, '/v1/live/session', {
      method: 'POST'
    }, {
      sourceLanguage: 'en',
      providerSecret: 'super_secret_leak_123'
    });
    assert.equal(res.statusCode, 400);
    assert.equal(res.body.error, 'PRIVACY_VIOLATION');
  });
});
