'use strict';

const { test, describe, before, after } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { createServer } = require('../src/server');
const { MockLiveTranslationProvider } = require('../src/provider/mock_live_translation_provider');
const { YandexLiveTranslationProviderAdapter } = require('../src/provider/yandex_live_translation_provider_adapter');
const { GatewayLimits } = require('../src/session_manager');

function makeRequest(server, path, options = {}, body = null) {
  return new Promise((resolve, reject) => {
    const address = server.address();
    const port = address.port;
    const reqOptions = {
      hostname: '127.0.0.1',
      port,
      path,
      method: options.method || 'GET',
      headers: options.headers || {}
    };

    const req = http.request(reqOptions, (res) => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => {
        const raw = Buffer.concat(chunks);
        let parsed = null;
        try {
          parsed = JSON.parse(raw.toString('utf8'));
        } catch (e) {
          parsed = raw.toString('utf8');
        }
        resolve({
          statusCode: res.statusCode,
          headers: res.headers,
          body: parsed,
          raw
        });
      });
    });

    req.on('error', err => reject(err));

    if (body !== null) {
      if (Buffer.isBuffer(body)) {
        req.write(body);
      } else if (typeof body === 'object') {
        req.setHeader('Content-Type', 'application/json');
        req.write(JSON.stringify(body));
      } else {
        req.write(body);
      }
    }
    req.end();
  });
}

describe('SmartTube VOX Controlled Live Gateway Tests', () => {
  let serverInstance;
  let testServer;
  let testApp;

  before(async () => {
    // Fast mock provider for unit tests (zero delay)
    const mockProvider = new MockLiveTranslationProvider({ fixedLatencyMs: 5 });
    const { server, app } = createServer({ mockProvider });
    testServer = server;
    testApp = app;

    await new Promise((resolve) => {
      testServer.listen(0, '127.0.0.1', () => resolve());
    });
  });

  after(async () => {
    testApp.sessionManager.destroy();
    await new Promise(resolve => testServer.close(resolve));
  });

  test('1. GET /health returns 200 ok status and development mode', async () => {
    const res = await makeRequest(testServer, '/health');
    assert.equal(res.statusCode, 200);
    assert.equal(res.body.status, 'ok');
    assert.equal(res.body.service, 'SmartTube VOX Live Gateway');
    assert.equal(res.body.mode, 'development');
  });

  test('2. POST /v1/live/session creates a valid live translation session', async () => {
    const res = await makeRequest(testServer, '/v1/live/session', {
      method: 'POST'
    }, {
      sourceLanguage: 'en',
      targetLanguage: 'ru',
      audioCodec: 'opus',
      chunkDurationMs: 2000
    });

    assert.equal(res.statusCode, 201);
    assert.ok(res.body.sessionId);
    assert.ok(res.body.sessionId.startsWith('vox_live_'));
    assert.equal(res.body.state, 'ACTIVE');
    assert.equal(res.body.generation, 1);
    assert.ok(res.body.limits);
    assert.equal(res.body.limits.maxSegmentSizeBytes, 4194304);
    assert.equal(res.body.capabilities.supportsSequentialSegments, true);
  });

  test('3. POST /v1/live/session with Yandex provider reports UNSUPPORTED_INCREMENTAL_TRANSLATION', async () => {
    const res = await makeRequest(testServer, '/v1/live/session', {
      method: 'POST',
      headers: { 'X-Vox-Provider': 'yandex' }
    }, {
      sourceLanguage: 'en',
      targetLanguage: 'ru'
    });

    assert.equal(res.statusCode, 201);
    assert.equal(res.body.capabilities.providerId, 'yandex_live_adapter');
    assert.equal(res.body.capabilities.status, 'UNSUPPORTED_INCREMENTAL_TRANSLATION');
    assert.equal(res.body.capabilities.supportsIncrementalAudio, false);
  });

  test('4. Privacy: Rejects request containing forbidden fields (videoTitle, signedUrl, token)', async () => {
    const res = await makeRequest(testServer, '/v1/live/session', {
      method: 'POST'
    }, {
      sourceLanguage: 'en',
      videoTitle: 'Live News Broadcast',
      token: 'secret123'
    });

    assert.equal(res.statusCode, 400);
    assert.equal(res.body.error, 'PRIVACY_VIOLATION');
  });

  test('5. Security: SSRF Protection rejects query params with arbitrary target URL', async () => {
    const res = await makeRequest(testServer, '/health?url=https://example.com/arbitrary');
    assert.equal(res.statusCode, 400);
    assert.equal(res.body.error, 'FORBIDDEN_EXTERNAL_URL_FETCH');
  });

  test('6. POST /v1/live/session/:sessionId/segment uploads sequential audio segments', async () => {
    // Create session
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;

    const payload = Buffer.from('FAKE_OPUS_AUDIO_SEGMENT_0');
    const res = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/octet-stream',
        'X-Vox-Sequence': '0',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000',
        'X-Vox-Source-Start-Ms': '0',
        'X-Vox-Source-End-Ms': '2000'
      }
    }, payload);

    assert.equal(res.statusCode, 202);
    assert.equal(res.body.status, 'QUEUED');
    assert.equal(res.body.sequence, 0);

    // Wait a brief tick for fast mock provider
    await new Promise(r => setTimeout(r, 20));

    // Poll result
    const pRes = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment/0`);
    assert.equal(pRes.statusCode, 200);
    assert.equal(pRes.body.status, 'READY');
    assert.equal(pRes.body.sequence, 0);
    assert.ok(pRes.body.audioData);
  });

  test('7. Segment size limit: Rejects segment larger than 4MB with 413 Payload Too Large', async () => {
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;

    const oversized = Buffer.alloc(GatewayLimits.MAX_SEGMENT_SIZE_BYTES + 1024);
    const res = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/octet-stream',
        'X-Vox-Sequence': '1',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000'
      }
    }, oversized);

    assert.equal(res.statusCode, 413);
    assert.equal(res.body.error, 'PAYLOAD_TOO_LARGE');
  });

  test('8. Segment duration check: Rejects duration < 500ms or > 12000ms with 400 Bad Request', async () => {
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;

    const resShort = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '1',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '100' // too short
      }
    }, Buffer.from('abc'));

    assert.equal(resShort.statusCode, 400);
    assert.equal(resShort.body.error, 'INVALID_SEGMENT_DURATION');

    const resLong = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '1',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '600000' // 10 minutes, too long
      }
    }, Buffer.from('abc'));

    assert.equal(resLong.statusCode, 400);
    assert.equal(resLong.body.error, 'INVALID_SEGMENT_DURATION');
  });

  test('9. Deduplication: Duplicate segment returns DUPLICATE_ACCEPTED without re-processing', async () => {
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;

    const payload = Buffer.from('IDENTICAL_AUDIO_PAYLOAD');
    const res1 = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '5',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000',
        'X-Vox-Checksum': 'checksum_hash_123'
      }
    }, payload);
    assert.equal(res1.statusCode, 202);

    const res2 = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '5',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000',
        'X-Vox-Checksum': 'checksum_hash_123'
      }
    }, payload);

    assert.equal(res2.statusCode, 200);
    assert.equal(res2.body.status, 'DUPLICATE_ACCEPTED');
  });

  test('10. Generation Reset: Jump/seek resets generation and drops stale older segments', async () => {
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;

    // Send generation 1 segment
    await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '1',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000'
      }
    }, Buffer.from('gen1'));

    // Client jumps / seeks: sends generation 2
    const resGen2 = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '10',
        'X-Vox-Generation': '2',
        'X-Vox-Duration-Ms': '2000'
      }
    }, Buffer.from('gen2'));
    assert.equal(resGen2.statusCode, 202);

    // Late arriving generation 1 segment must be dropped
    const resStale = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '2',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000'
      }
    }, Buffer.from('stale_gen1'));

    assert.equal(resStale.statusCode, 200);
    assert.equal(resStale.body.status, 'STALE_GENERATION_DROPPED');
  });

  test('11. Backpressure: Queue overflow triggers 429 Too Many Requests', async () => {
    // Session with slow mock provider
    const slowProvider = new MockLiveTranslationProvider({ fixedLatencyMs: 10000 });
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;
    const session = testApp.sessionManager.getSession(sessionId);
    session.provider = slowProvider;

    // Fill queue to MAX_QUEUED_SEGMENTS (10)
    for (let i = 0; i < GatewayLimits.MAX_QUEUED_SEGMENTS; i++) {
      session.queuedSegments.push({
        sequence: i,
        generation: 1,
        durationMs: 2000,
        payload: Buffer.from('mock')
      });
    }

    // Next segment must trigger backpressure
    const res = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '99',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000'
      }
    }, Buffer.from('overflow'));

    assert.equal(res.statusCode, 429);
    assert.equal(res.body.error, 'BACKPRESSURE_EXCEEDED');
    assert.ok(res.headers['retry-after']);
  });

  test('12. DELETE /v1/live/session/:sessionId closes session and cleans up', async () => {
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;

    const delRes = await makeRequest(testServer, `/v1/live/session/${sessionId}`, {
      method: 'DELETE'
    });
    assert.equal(delRes.statusCode, 200);
    assert.equal(delRes.body.status, 'CLOSED');

    // Accessing closed session returns 404
    const statusRes = await makeRequest(testServer, `/v1/live/session/${sessionId}/status`);
    assert.equal(statusRes.statusCode, 404);
  });

  test('13. Session Expiry: Expired sessions are cleaned up', async () => {
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;
    const session = testApp.sessionManager.getSession(sessionId);

    // Force expiration
    session.expiresAt = Date.now() - 1000;
    testApp.sessionManager.cleanupExpired();

    const checkRes = await makeRequest(testServer, `/v1/live/session/${sessionId}/status`);
    assert.equal(checkRes.statusCode, 404);
  });

  test('14. Invalid session returns 404 for segment upload or status', async () => {
    const res = await makeRequest(testServer, '/v1/live/session/non_existent_session_id/segment', {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '0',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000'
      }
    }, Buffer.from('test'));
    assert.equal(res.statusCode, 404);
    assert.equal(res.body.error, 'SESSION_NOT_FOUND');
  });

  test('15. Invalid metadata returns 400 Bad Request', async () => {
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;

    // Missing/invalid sequence
    const res = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': 'invalid_seq',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000'
      }
    }, Buffer.from('test'));
    assert.equal(res.statusCode, 400);
    assert.equal(res.body.error, 'INVALID_SEQUENCE');
  });

  test('16. Out-of-order segments: Sequenced properly into queue', async () => {
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;

    // Send sequence 12 then sequence 11
    await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '12',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000'
      }
    }, Buffer.from('seq12'));

    await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '11',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000'
      }
    }, Buffer.from('seq11'));

    // Wait for queue processing
    await new Promise(r => setTimeout(r, 40));

    const res11 = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment/11`);
    assert.equal(res11.statusCode, 200);
    assert.equal(res11.body.sequence, 11);

    const res12 = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment/12`);
    assert.equal(res12.statusCode, 200);
    assert.equal(res12.body.sequence, 12);
  });

  test('17. Mock provider failure injection marks result FAILED without crashing', async () => {
    const errorProvider = new MockLiveTranslationProvider({ errorEveryNth: 1, errorCode: 500, fixedLatencyMs: 5 });
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;
    const session = testApp.sessionManager.getSession(sessionId);
    session.provider = errorProvider;

    await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '0',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000'
      }
    }, Buffer.from('error_test'));

    await new Promise(r => setTimeout(r, 30));

    const pollRes = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment/0`);
    assert.equal(pollRes.statusCode, 200);
    assert.equal(pollRes.body.status, 'FAILED');
  });

  test('18. Rate limit protects gateway against spam', async () => {
    testApp.rateLimitMap.set('127.0.0.1', { count: 350, resetAt: Date.now() + 60000 });
    const res = await makeRequest(testServer, '/health');
    assert.equal(res.statusCode, 429);
    assert.equal(res.body.error, 'RATE_LIMIT_EXCEEDED');

    // Reset rate limit
    testApp.rateLimitMap.clear();
    const okRes = await makeRequest(testServer, '/health');
    assert.equal(okRes.statusCode, 200);
  });

  test('19. PTS metadata ingestion: parses PTS headers and returns them in segment result', async () => {
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;

    const startPtsUs = 12000000;
    const endPtsUs = 14000000;

    const uploadRes = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '0',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000',
        'X-Vox-Pts-Start-Us': String(startPtsUs),
        'X-Vox-Pts-End-Us': String(endPtsUs),
        'X-Vox-Sample-Rate': '48000',
        'X-Vox-Channels': '2',
        'X-Vox-Encoding': 'pcm_16bit'
      }
    }, Buffer.from('test_pcm_payload_bytes'));

    assert.equal(uploadRes.statusCode, 202);

    await new Promise(r => setTimeout(r, 40));

    const pollRes = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment/0`);
    assert.equal(pollRes.statusCode, 200);
    assert.equal(pollRes.body.ptsStartUs, startPtsUs);
    assert.equal(pollRes.body.ptsEndUs, endPtsUs);
    assert.equal(pollRes.body.sampleRate, 48000);
    assert.equal(pollRes.body.channels, 2);
    assert.equal(pollRes.body.encoding, 'pcm_16bit');
  });

  test('20. Invalid PTS range rejection: returns 400 when ptsEndUs < ptsStartUs', async () => {
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;

    const badUploadRes = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '1',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000',
        'X-Vox-Pts-Start-Us': '5000000',
        'X-Vox-Pts-End-Us': '4000000' // End before start!
      }
    }, Buffer.from('pcm_bad_pts'));

    assert.equal(badUploadRes.statusCode, 400);
    assert.equal(badUploadRes.body.error, 'INVALID_PTS_RANGE');
  });

  test('21. Passthrough mode: preserves raw audio payload and returns base64 in poll', async () => {
    const sRes = await makeRequest(testServer, '/v1/live/session', { method: 'POST' });
    const sessionId = sRes.body.sessionId;
    const session = testApp.sessionManager.getSession(sessionId);

    // Mock provider with passthroughAudio = true
    const passthroughProvider = new MockLiveTranslationProvider({ passthroughAudio: true, fixedLatencyMs: 5 });
    session.provider = passthroughProvider;

    const originalAudio = Buffer.from('EXACT_PASSTHROUGH_PCM_DATA_12345');
    await makeRequest(testServer, `/v1/live/session/${sessionId}/segment`, {
      method: 'POST',
      headers: {
        'X-Vox-Sequence': '0',
        'X-Vox-Generation': '1',
        'X-Vox-Duration-Ms': '2000',
        'X-Vox-Pts-Start-Us': '1000000',
        'X-Vox-Pts-End-Us': '3000000'
      }
    }, originalAudio);

    await new Promise(r => setTimeout(r, 40));

    const pollRes = await makeRequest(testServer, `/v1/live/session/${sessionId}/segment/0`);
    assert.equal(pollRes.statusCode, 200);
    assert.ok(pollRes.body.audioData);
    const returnedBuf = Buffer.from(pollRes.body.audioData, 'base64');
    assert.deepEqual(returnedBuf, originalAudio);
  });
});
