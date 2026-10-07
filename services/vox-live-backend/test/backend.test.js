'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { BackendApp } = require('../src/backend_app');
const { WorkerStage, BackendErrorCode } = require('../src/models/types');

function request(serverAddr, options, postData = null) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      hostname: serverAddr.address,
      port: serverAddr.port,
      method: options.method || 'GET',
      path: options.path,
      headers: options.headers || {}
    }, (res) => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => {
        const bodyRaw = Buffer.concat(chunks).toString('utf8');
        let bodyJson = null;
        try {
          bodyJson = JSON.parse(bodyRaw);
        } catch (_) {}
        resolve({
          statusCode: res.statusCode,
          headers: res.headers,
          body: bodyJson,
          raw: bodyRaw
        });
      });
    });

    req.on('error', reject);

    if (postData) {
      if (Buffer.isBuffer(postData)) {
        req.write(postData);
      } else if (typeof postData === 'object') {
        req.write(JSON.stringify(postData));
      } else {
        req.write(postData);
      }
    }
    req.end();
  });
}

test('VOX Live Backend Test Suite', async (t) => {
  let app;
  let addr;

  t.beforeEach(async () => {
    app = new BackendApp({
      port: 0, // dynamic port
      host: '127.0.0.1',
      backendMode: 'MOCK',
      sessionTtlMs: 1000, // 1 sec for fast test
      maxConcurrentSessions: 3,
      maxSegmentsPerSession: 4,
      rateLimitBurst: 50,
      rateLimitRps: 50
    });
    addr = await app.listen(0, '127.0.0.1');
  });

  t.afterEach(async () => {
    if (app) {
      await app.close();
    }
  });

  await t.test('1. Health check returns 200 and mode MOCK', async () => {
    const res = await request(addr, { path: '/health' });
    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(res.body.status, 'ok');
    assert.strictEqual(res.body.mode, 'MOCK');
  });

  await t.test('2. Ready check returns model information', async () => {
    const res = await request(addr, { path: '/ready' });
    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(res.body.ready, true);
    assert.strictEqual(res.body.stt.engine, 'faster-whisper');
    assert.strictEqual(res.body.translation.engine, 'marian-nmt');
    assert.strictEqual(res.body.tts.engine, 'piper');
  });

  await t.test('3. Safe metrics endpoint returns aggregated data without sensitive text', async () => {
    const res = await request(addr, { path: '/v1/live/metrics' });
    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(res.body.activeSessions, 0);
    assert.strictEqual(res.body.backendMode, 'MOCK');
    assert.strictEqual(typeof res.body.uptimeSec, 'number');
  });

  await t.test('4. Session creation and privacy violation check', async () => {
    // Normal session
    const res = await request(addr, {
      method: 'POST',
      path: '/v1/live/session',
      headers: { 'Content-Type': 'application/json' }
    }, {
      streamId: 'stream123',
      sourceLanguage: 'en',
      targetLanguage: 'ru'
    });
    assert.strictEqual(res.statusCode, 201);
    assert.ok(res.body.sessionId.startsWith('live-sess-'));
    assert.ok(res.body.sessionToken.startsWith('stk_'));

    // Privacy violation: forbidden videoTitle
    const badRes = await request(addr, {
      method: 'POST',
      path: '/v1/live/session',
      headers: { 'Content-Type': 'application/json' }
    }, {
      streamId: 'stream123',
      videoTitle: 'Secret Personal Video Title'
    });
    assert.strictEqual(badRes.statusCode, 400);
    assert.ok(badRes.body.messageRu.includes('Forbidden privacy field'));
  });

  await t.test('5. Segment ingestion and mock inference pipeline', async () => {
    // Create session
    const sessRes = await request(addr, {
      method: 'POST',
      path: '/v1/live/session',
      headers: { 'Content-Type': 'application/json' }
    }, { streamId: 'stream_segment' });
    const sessionId = sessRes.body.sessionId;
    const sessionToken = sessRes.body.sessionToken;

    // Ingest 2 seconds dummy PCM
    const dummyAudio = Buffer.alloc(16000 * 2 * 2); // 2 sec at 16kHz 16bit
    const segRes = await request(addr, {
      method: 'POST',
      path: `/v1/live/session/${sessionId}/segment`,
      headers: {
        'Content-Type': 'application/json',
        'X-Session-Token': sessionToken,
        'X-Sequence': '1',
        'X-Generation': '1',
        'X-Start-Pts-Us': '0',
        'X-End-Pts-Us': '2000000'
      }
    }, {
      audioBase64: dummyAudio.toString('base64'),
      durationMs: 2000
    });

    assert.strictEqual(segRes.statusCode, 200);
    assert.strictEqual(segRes.body.status, WorkerStage.READY);
    assert.strictEqual(segRes.body.sequence, 1);
    assert.strictEqual(segRes.body.generation, 1);
    assert.ok(segRes.body.translatedAudio.length > 0);
    assert.ok(segRes.body.translatedText.includes('Перевод прямой трансляции'));
    assert.ok(segRes.body.sttLatencyMs >= 0);
    assert.ok(segRes.body.translationLatencyMs >= 0);
    assert.ok(segRes.body.ttsLatencyMs >= 0);
    assert.ok(segRes.body.totalLatencyMs >= 0);
  });

  await t.test('6. Stale generation segment rejection', async () => {
    const sessRes = await request(addr, {
      method: 'POST',
      path: '/v1/live/session',
      headers: { 'Content-Type': 'application/json' }
    }, { streamId: 'stream_stale' });
    const sessionId = sessRes.body.sessionId;

    const dummyAudio = Buffer.alloc(1000);

    // Send generation 2
    await request(addr, {
      method: 'POST',
      path: `/v1/live/session/${sessionId}/segment`,
      headers: {
        'Content-Type': 'application/json',
        'X-Sequence': '1',
        'X-Generation': '2'
      }
    }, { audioBase64: dummyAudio.toString('base64') });

    // Send generation 1 (stale)
    const staleRes = await request(addr, {
      method: 'POST',
      path: `/v1/live/session/${sessionId}/segment`,
      headers: {
        'Content-Type': 'application/json',
        'X-Sequence': '2',
        'X-Generation': '1'
      }
    }, { audioBase64: dummyAudio.toString('base64') });

    assert.strictEqual(staleRes.statusCode, 409);
    assert.strictEqual(staleRes.body.error, BackendErrorCode.STALE_GENERATION);
  });

  await t.test('7. Session TTL auto-expiry and cleanup', async () => {
    const sessRes = await request(addr, {
      method: 'POST',
      path: '/v1/live/session',
      headers: { 'Content-Type': 'application/json' }
    }, { streamId: 'stream_expiry' });
    const sessionId = sessRes.body.sessionId;

    // Wait 1.1s for session to expire (TTL is 1000ms)
    await new Promise(r => setTimeout(r, 1100));

    const segRes = await request(addr, {
      method: 'POST',
      path: `/v1/live/session/${sessionId}/segment`,
      headers: { 'Content-Type': 'application/json' }
    }, { audioBase64: Buffer.alloc(100).toString('base64') });

    assert.strictEqual(segRes.statusCode, 404);
    assert.strictEqual(segRes.body.error, BackendErrorCode.SESSION_EXPIRED);
  });

  await t.test('8. Session deletion', async () => {
    const sessRes = await request(addr, {
      method: 'POST',
      path: '/v1/live/session',
      headers: { 'Content-Type': 'application/json' }
    }, { streamId: 'stream_del' });
    const sessionId = sessRes.body.sessionId;

    const delRes = await request(addr, {
      method: 'DELETE',
      path: `/v1/live/session/${sessionId}`
    });
    assert.strictEqual(delRes.statusCode, 200);
    assert.strictEqual(delRes.body.status, 'CLOSED');

    // Subsequent call gives 404
    const delAgain = await request(addr, {
      method: 'DELETE',
      path: `/v1/live/session/${sessionId}`
    });
    assert.strictEqual(delAgain.statusCode, 404);
  });

  await t.test('9. Queue limit (Backpressure) on concurrent sessions', async () => {
    // maxConcurrentSessions = 3
    await request(addr, { method: 'POST', path: '/v1/live/session', headers: { 'Content-Type': 'application/json' } });
    await request(addr, { method: 'POST', path: '/v1/live/session', headers: { 'Content-Type': 'application/json' } });
    await request(addr, { method: 'POST', path: '/v1/live/session', headers: { 'Content-Type': 'application/json' } });

    // 4th session exceeds limit
    const overflowRes = await request(addr, { method: 'POST', path: '/v1/live/session', headers: { 'Content-Type': 'application/json' } });
    assert.strictEqual(overflowRes.statusCode, 429);
    assert.strictEqual(overflowRes.body.error, BackendErrorCode.QUEUE_FULL);
  });
});
