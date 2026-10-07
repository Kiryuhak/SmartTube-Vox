'use strict';

const http = require('http');

function postJson(path, data, headers = {}) {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify(data);
    const req = http.request({
      hostname: '127.0.0.1',
      port: 8788,
      path,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
        ...headers
      }
    }, res => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => resolve({ statusCode: res.statusCode, body: JSON.parse(body) }));
    });
    req.on('error', reject);
    req.write(payload);
    req.end();
  });
}

function postBinary(path, buffer, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      hostname: '127.0.0.1',
      port: 8788,
      path,
      method: 'POST',
      headers: {
        'Content-Type': 'application/octet-stream',
        'Content-Length': buffer.length,
        ...headers
      }
    }, res => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        try {
          resolve({ statusCode: res.statusCode, body: JSON.parse(body) });
        } catch (e) {
          resolve({ statusCode: res.statusCode, body });
        }
      });
    });
    req.on('error', reject);
    req.write(buffer);
    req.end();
  });
}

function getJson(path) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      hostname: '127.0.0.1',
      port: 8788,
      path,
      method: 'GET'
    }, res => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        try {
          resolve({ statusCode: res.statusCode, body: JSON.parse(body) });
        } catch (e) {
          resolve({ statusCode: res.statusCode, body });
        }
      });
    });
    req.on('error', reject);
    req.end();
  });
}

async function runE2E() {
  console.log('=== Starting E2E Verification ===');

  // 1. Health
  const health = await getJson('/health');
  console.log('1. Health check:', health.statusCode, health.body.service);
  if (health.statusCode !== 200) throw new Error('Health check failed');

  // 2. Create Session
  const sessionRes = await postJson('/v1/live/session', {
    sourceLanguage: 'en',
    targetLanguage: 'ru',
    audioCodec: 'opus',
    mockOptions: { fixedLatencyMs: 50 }
  });
  console.log('2. Session created:', sessionRes.statusCode, sessionRes.body.sessionId);
  const sessionId = sessionRes.body.sessionId;

  // 3. Upload 10 sequential audio segments (0..9)
  console.log('3. Uploading 10 sequential audio segments (0..9)...');
  const latencies = [];
  for (let seq = 0; seq < 10; seq++) {
    const t0 = Date.now();
    const payload = Buffer.from(`OPUS_FRAME_DATA_SEQ_${seq}_PTS_${seq * 2000}`);
    const uploadRes = await postBinary(`/v1/live/session/${sessionId}/segment`, payload, {
      'X-Vox-Sequence': seq.toString(),
      'X-Vox-Generation': '1',
      'X-Vox-Duration-Ms': '2000',
      'X-Vox-Source-Start-Ms': (seq * 2000).toString(),
      'X-Vox-Source-End-Ms': ((seq + 1) * 2000).toString(),
      'X-Vox-Checksum': `chk_${seq}`
    });
    const rtt = Date.now() - t0;
    latencies.push(rtt);
    if (uploadRes.statusCode !== 202) {
      throw new Error(`Upload failed on seq ${seq}: ${uploadRes.statusCode}`);
    }
  }
  console.log(`   Uploaded 10 segments successfully. Avg upload RTT: ${(latencies.reduce((a, b) => a + b, 0) / latencies.length).toFixed(1)} ms`);

  // Wait for processing to complete
  await new Promise(r => setTimeout(r, 600));

  // 4. Verify ordered results polling
  console.log('4. Polling ordered results (0..9)...');
  for (let seq = 0; seq < 10; seq++) {
    const res = await getJson(`/v1/live/session/${sessionId}/segment/${seq}`);
    if (res.statusCode !== 200 || res.body.status !== 'READY') {
      throw new Error(`Polling failed on seq ${seq}: ${res.statusCode} ${JSON.stringify(res.body)}`);
    }
  }
  console.log('   All 10 segments processed and ready in correct sequence order!');

  // 5. Deduplication check
  console.log('5. Testing Deduplication...');
  const dupRes = await postBinary(`/v1/live/session/${sessionId}/segment`, Buffer.from('DUP_PAYLOAD'), {
    'X-Vox-Sequence': '0',
    'X-Vox-Generation': '1',
    'X-Vox-Duration-Ms': '2000',
    'X-Vox-Checksum': 'chk_0'
  });
  console.log('   Duplicate response status:', dupRes.body.status);
  if (dupRes.body.status !== 'DUPLICATE_ACCEPTED') throw new Error('Dedupe failed');

  // 6. Generation reset check
  console.log('6. Testing Generation Reset (seek to generation 2)...');
  const gen2Res = await postBinary(`/v1/live/session/${sessionId}/segment`, Buffer.from('GEN2_FRAME'), {
    'X-Vox-Sequence': '50',
    'X-Vox-Generation': '2',
    'X-Vox-Duration-Ms': '2000',
    'X-Vox-Source-Start-Ms': '100000',
    'X-Vox-Source-End-Ms': '102000'
  });
  if (gen2Res.statusCode !== 202) throw new Error('Gen2 failed');

  const staleRes = await postBinary(`/v1/live/session/${sessionId}/segment`, Buffer.from('STALE_FRAME'), {
    'X-Vox-Sequence': '5',
    'X-Vox-Generation': '1',
    'X-Vox-Duration-Ms': '2000'
  });
  console.log('   Stale gen 1 response:', staleRes.body.status);
  if (staleRes.body.status !== 'STALE_GENERATION_DROPPED') throw new Error('Stale drop failed');

  console.log('=== ALL E2E VERIFICATIONS PASSED ===');
}

runE2E().catch(err => {
  console.error('E2E Verification Error:', err);
  process.exit(1);
});
