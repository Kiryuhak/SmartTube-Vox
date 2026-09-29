import { createServer } from 'node:http';

// Local acceptance fixture only. It never contacts Yandex and uses no real credentials.
const port = Number(process.env.PORT ?? 8787);
let scenario = process.env.MOCK_SCENARIO ?? 'pending_slow_success';
let polls = 0;
const sequences = {
  pending_slow_success: ['authorization_pending', 'slow_down', 'authorization_pending', 'success'],
  expired: ['authorization_pending', 'expired_token'],
  invalid_client: ['invalid_client'],
  network_error: ['network_error', 'network_error', 'network_error'],
  rate_limited: ['rate_limited', 'authorization_pending', 'success'],
  pending: ['authorization_pending'],
};
if (!Object.prototype.hasOwnProperty.call(sequences, scenario)) throw new Error('Unknown MOCK_SCENARIO');

const server = createServer((request, response) => {
  response.setHeader('Content-Type', 'application/json');
  response.setHeader('Cache-Control', 'no-store');
  if (request.method === 'POST' && request.url?.startsWith('/dev/scenario/')) {
    const next = request.url.slice('/dev/scenario/'.length);
    if (!Object.prototype.hasOwnProperty.call(sequences, next)) {
      response.writeHead(400).end('{"state":"invalid_request"}');
      return;
    }
    scenario = next;
    polls = 0;
    response.writeHead(200).end('{"state":"ready"}');
    return;
  }
  if (request.method === 'POST' && request.url === '/dev/device/code') {
    response.writeHead(200).end(JSON.stringify({
      device_code: 'mock-device-code-0123456789abcdef',
      user_code: 'MOCK-ONLY',
      verification_url: 'https://ya.ru/device',
      expires_in: 300,
      interval: 5,
    }));
    return;
  }
  if (request.method !== 'POST' || request.url !== '/oauth/yandex/device/token') {
    response.writeHead(404).end('{"state":"not_found"}');
    return;
  }
  let size = 0;
  request.on('data', chunk => {
    size += chunk.length;
    if (size > 1024) request.destroy();
  });
  request.on('end', () => {
    const sequence = sequences[scenario];
    const state = sequence[Math.min(polls++, sequence.length - 1)];
    if (state === 'rate_limited') response.setHeader('Retry-After', '10');
    response.writeHead(state === 'rate_limited' ? 429 : state === 'network_error' ? 502 : 200);
    response.end(JSON.stringify(state === 'success'
      ? { state, access_token: 'mock-only-never-use-for-yandex', expires_in: 3600 }
      : { state }));
  });
});
server.listen(port, '127.0.0.1', () => {
  process.stdout.write(`Mock broker listening on 127.0.0.1:${port}; scenario=${scenario}\n`);
});
