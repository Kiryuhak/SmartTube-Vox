import assert from 'node:assert/strict';
import test from 'node:test';
import { handleRequest } from '../src/broker.mjs';

const url = 'https://broker.example/oauth/yandex/device/token';
const payload = {
  device_code: '0123456789abcdef0123456789abcdef',
  request_id: '5d908340-3d90-4e50-a390-e2350648ac35',
};

function request(body = payload, options = {}) {
  return new Request(options.url ?? url, {
    method: options.method ?? 'POST',
    headers: {
      'Content-Type': options.contentType ?? 'application/json',
      'CF-Connecting-IP': '192.0.2.1',
    },
    body: options.method === 'GET' ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
  });
}

function env(gateStatus = 204) {
  return {
    YANDEX_CLIENT_ID: 'public-client-id',
    YANDEX_CLIENT_SECRET: 'test-secret-never-echo',
    RATE_GATE: {
      getByName: () => ({ fetch: async () => new Response(null, { status: gateStatus }) }),
    },
  };
}

async function run(yandexBody, status = 200) {
  let upstreamCall;
  const result = await handleRequest(request(), env(), async (upstreamUrl, init) => {
    upstreamCall = { upstreamUrl, init };
    return new Response(JSON.stringify(yandexBody), { status });
  });
  return { result, body: await result.json(), upstreamCall };
}

test('fixed upstream and server-side credentials; response excludes secrets', async () => {
  const { result, body, upstreamCall } = await run({
    access_token: 'access-token', refresh_token: 'refresh-token', expires_in: 3600,
  });
  assert.equal(result.status, 200);
  assert.deepEqual(body, { state: 'success', access_token: 'access-token', expires_in: 3600 });
  assert.equal(upstreamCall.upstreamUrl, 'https://oauth.yandex.ru/token');
  assert.equal(upstreamCall.init.method, 'POST');
  const form = new URLSearchParams(upstreamCall.init.body);
  assert.deepEqual([...form.keys()].sort(), ['client_id', 'client_secret', 'code', 'grant_type']);
  assert.equal(form.get('client_secret'), 'test-secret-never-echo');
  assert.equal(form.get('code'), payload.device_code);
  assert.equal(form.get('grant_type'), 'device_code');
  assert.ok(!JSON.stringify(body).includes('test-secret-never-echo'));
  assert.ok(!JSON.stringify(body).includes('refresh-token'));
  assert.equal(result.headers.get('Cache-Control'), 'no-store');
});

test('Yandex states are normalized without descriptions or raw server data', async () => {
  for (const state of [
    'authorization_pending', 'slow_down', 'access_denied', 'invalid_client',
    'unauthorized_client', 'expired_token', 'invalid_grant',
  ]) {
    const { body } = await run({ error: state, error_description: 'secret upstream detail' }, 400);
    assert.deepEqual(body, { state });
  }
});

test('Yandex 500, timeout, and malformed response fail closed', async () => {
  assert.deepEqual((await run({ error: 'server_error' }, 500)).body, { state: 'temporary_server_error' });
  const timeout = await handleRequest(request(), env(), async () => { throw new Error('timeout'); });
  assert.deepEqual(await timeout.json(), { state: 'network_error' });
  const malformed = await handleRequest(request(), env(), async () => new Response('not JSON'));
  assert.deepEqual(await malformed.json(), { state: 'temporary_server_error' });
});

test('method, path, body shape and size are restricted before upstream', async () => {
  let calls = 0;
  const upstream = async () => { calls++; throw new Error('must not call'); };
  const cases = [
    [request(payload, { method: 'GET' }), 405],
    [request(payload, { url: 'https://broker.example/anything' }), 404],
    [request({ ...payload, client_id: 'attacker' }), 400],
    [request({ ...payload, device_code: 'short' }), 400],
    [request({ ...payload, request_id: 'not-a-uuid' }), 400],
    [request('{not-json'), 400],
    [request({ ...payload, padding: 'x'.repeat(2000) }), 413],
  ];
  for (const [input, status] of cases) {
    assert.equal((await handleRequest(input, env(), upstream)).status, status);
  }
  assert.equal(calls, 0);
});

test('rate limit and missing configuration block exchange', async () => {
  let calls = 0;
  const upstream = async () => { calls++; throw new Error('must not call'); };
  const rateLimited = await handleRequest(request(), env(429), upstream);
  assert.equal(rateLimited.status, 429);
  assert.deepEqual(await rateLimited.json(), { state: 'rate_limited' });
  assert.equal((await handleRequest(request(), {}, upstream)).status, 503);
  assert.equal(calls, 0);
});
