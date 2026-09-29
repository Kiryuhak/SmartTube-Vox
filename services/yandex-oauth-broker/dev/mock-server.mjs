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
  refresh_success: ['success'],
  refresh_rotated: ['rotated_success'],
  refresh_invalid_grant: ['invalid_grant'],
  refresh_network_error: ['network_error'],
  refresh_rate_limited: ['rate_limited'],
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
  if (request.method === 'POST' && request.url === '/oauth/yandex/refresh') {
    let size = 0;
    request.on('data', chunk => {
      size += chunk.length;
      if (size > 1024) request.destroy();
    });
    request.on('end', () => {
      const sequence = sequences[scenario] ?? ['success'];
      const state = sequence[Math.min(polls++, sequence.length - 1)];
      if (state === 'rate_limited') {
        response.setHeader('Retry-After', '10');
        response.writeHead(429).end('{"state":"rate_limited"}');
        return;
      }
      if (state === 'network_error') {
        response.writeHead(502).end('{"state":"network_error"}');
        return;
      }
      if (state === 'invalid_grant' || state === 'invalid_client') {
        response.writeHead(200).end(JSON.stringify({ state }));
        return;
      }
      if (state === 'rotated_success') {
        response.writeHead(200).end(JSON.stringify({
          state: 'success',
          access_token: 'mock-rotated-access-token-0123456789',
          refresh_token: 'mock-new-refresh-token-0123456789',
          expires_in: 3600,
        }));
        return;
      }
      response.writeHead(200).end(JSON.stringify({
        state: 'success',
        access_token: 'mock-refreshed-access-token-0123456789',
        expires_in: 3600,
      }));
    });
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
      ? { state, access_token: 'mock-only-never-use-for-yandex', refresh_token: 'mock-refresh-token-initial', expires_in: 3600 }
      : { state }));
  });
});
server.listen(port, '127.0.0.1', () => {
  process.stdout.write(`Mock broker listening on 127.0.0.1:${port}; scenario=${scenario}\n`);
});
