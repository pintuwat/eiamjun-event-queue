/**
 * The endpoints, driven over real HTTP.
 *
 * This file exists because of a bug that shipped in the shop's own server and stayed invisible for
 * weeks: twelve green tests called the rule directly while the route in front of it answered
 * `400` to every request, because one handler called a function that did not exist. A rule with no
 * working route is not a feature, so every endpoint here is exercised through the server.
 */
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
process.env.DB_FILE = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'event-api-')), 'event.db');
process.env.AUTH_USER = 'stall';
process.env.AUTH_PASSWORD = 'secret';

const { server } = await import('../server/index.js');

let base = '';

before(async () => {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

const BASIC = 'Basic ' + Buffer.from('stall:secret').toString('base64');
const DAY = '2026-11-10';

function call(path, { method = 'GET', body, headers = {} } = {}) {
  return fetch(base + path, {
    method,
    redirect: 'manual',
    headers: {
      accept: 'application/json',
      ...(body ? { 'content-type': 'application/json' } : {}),
      ...headers
    },
    body: body ? JSON.stringify(body) : undefined
  });
}

function asTill(path, options = {}) {
  return call(path, { ...options, headers: { authorization: BASIC, ...(options.headers || {}) } });
}

function order(queueNo, overrides = {}) {
  return {
    queueNo,
    day: DAY,
    source: 'pos',
    total: 60,
    payment: 'qr',
    orderedAt: Date.UTC(2026, 10, 10, 5, 0),
    lines: [{ name: 'ก๋วยเตี๋ยวคลุก', options: 'เส้นหมี่', note: 'ไม่ใส่ถั่ว', qty: 1, unitPrice: 60 }],
    ...overrides
  };
}

test('the healthcheck needs no credentials — Coolify probes carry none', async () => {
  const res = await call('/api/health');
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.ok, true);
  assert.equal(body.timezone, 'Asia/Bangkok');
  assert.equal(body.dayStartsAt, 7);
});

test('the home-screen icon is open, or iOS bookmarks a screenshot of the login page', async () => {
  for (const asset of ['/apple-touch-icon.png', '/icon-192.png', '/manifest.webmanifest']) {
    const res = await call(asset);
    assert.equal(res.status, 200, asset);
  }
});

test('an API call without credentials is 401, and never a redirect', async () => {
  const res = await call('/api/orders');
  assert.equal(res.status, 401);
  // No WWW-Authenticate: that header is what makes Safari put up its own password box on every
  // launch from the home screen, which is the whole reason the cookie exists.
  assert.equal(res.headers.get('www-authenticate'), null);
});

test('a browser without a session is sent to the login page', async () => {
  const res = await call('/', { headers: { accept: 'text/html' } });
  assert.equal(res.status, 302);
  assert.equal(res.headers.get('location'), '/login');
});

test('the login page itself is reachable while logged out', async () => {
  const res = await call('/login', { headers: { accept: 'text/html' } });
  assert.equal(res.status, 200);
});

test('a wrong password does not get a session', async () => {
  const res = await call('/api/login', { method: 'POST', body: { user: 'stall', password: 'nope' } });
  assert.equal(res.status, 401);
  assert.equal(res.headers.get('set-cookie'), null);
});

test('logging in gets a cookie that the board can then use instead of Basic', async () => {
  const login = await call('/api/login', {
    method: 'POST',
    body: { user: 'stall', password: 'secret' }
  });
  assert.equal(login.status, 200);
  const cookie = login.headers.get('set-cookie');
  assert.ok(cookie && cookie.includes('eiamjun_event_session='), 'a session cookie is set');
  assert.ok(cookie.includes('HttpOnly'), 'and is not readable from the page');

  const jar = cookie.split(';')[0];
  const board = await call('/api/orders', { headers: { cookie: jar } });
  assert.equal(board.status, 200);
});

test('the till posts an order and the board can read it back', async () => {
  const posted = await asTill('/api/orders', { method: 'POST', body: order(1) });
  assert.equal(posted.status, 200);
  assert.equal((await posted.json()).replaced, false);

  const board = await asTill(`/api/orders?day=${DAY}`);
  const body = await board.json();
  assert.equal(body.day, DAY);
  const found = body.orders.find((o) => o.queueNo === 1);
  assert.ok(found, 'the order is on the board');
  assert.equal(found.status, 'waiting');
  // camelCase out, snake_case in the table — the page should not have to know about SQL.
  assert.equal(found.lines[0].unitPrice, 60);
  assert.equal(found.lines[0].note, 'ไม่ใส่ถั่ว');
});

test('the same order posted twice is still one card', async () => {
  await asTill('/api/orders', { method: 'POST', body: order(2) });
  const again = await asTill('/api/orders', { method: 'POST', body: order(2) });
  assert.equal((await again.json()).replaced, true);

  const body = await (await asTill(`/api/orders?day=${DAY}`)).json();
  assert.equal(body.orders.filter((o) => o.queueNo === 2).length, 1);
});

test('พร้อมส่ง reaches the ready list the till polls, and undo takes it off again', async () => {
  await asTill('/api/orders', { method: 'POST', body: order(3) });

  const ready = await asTill('/api/orders/ready', {
    method: 'POST',
    body: { day: DAY, queueNo: 3, source: 'pos' }
  });
  assert.equal(ready.status, 200);
  assert.equal((await ready.json()).changed, true);

  let list = await (await asTill(`/api/orders/ready?day=${DAY}&source=pos`)).json();
  assert.ok(list.ready.includes(3));

  await asTill('/api/orders/unready', {
    method: 'POST',
    body: { day: DAY, queueNo: 3, source: 'pos' }
  });
  list = await (await asTill(`/api/orders/ready?day=${DAY}&source=pos`)).json();
  assert.equal(list.ready.includes(3), false, 'the till reads this list as the whole truth');
});

test('collecting an order takes it off the board', async () => {
  await asTill('/api/orders', { method: 'POST', body: order(4) });
  const picked = await asTill('/api/orders/picked', {
    method: 'POST',
    body: { day: DAY, queueNo: 4, source: 'pos' }
  });
  assert.equal((await picked.json()).changed, true);

  const body = await (await asTill(`/api/orders?day=${DAY}`)).json();
  assert.equal(body.orders.some((o) => o.queueNo === 4), false);
});

test('a status call for an order the server has never seen is ok, not an error', async () => {
  // The till may mark something collected while its own order push is still in the outbox, and a
  // 404 here would make that outbox retry the message forever.
  const res = await asTill('/api/orders/picked', {
    method: 'POST',
    body: { day: DAY, queueNo: 9999, source: 'pos' }
  });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).changed, false);
});

test('a bad queue number is refused with a message a person can act on', async () => {
  const res = await asTill('/api/orders/ready', {
    method: 'POST',
    body: { day: DAY, queueNo: 'nope', source: 'pos' }
  });
  assert.equal(res.status, 400);
  assert.match((await res.json()).error, /เลขคิว/);
});

test('a malformed body is a 400 rather than a 500', async () => {
  const res = await fetch(base + '/api/orders', {
    method: 'POST',
    headers: { authorization: BASIC, 'content-type': 'application/json' },
    body: '{ this is not json'
  });
  assert.equal(res.status, 400);
});

test('the day summary adds up what has been taken', async () => {
  const body = await (await asTill(`/api/summary?day=${DAY}`)).json();
  assert.equal(body.ok, true);
  assert.equal(body.day, DAY);
  assert.ok(body.takings > 0);
  assert.ok(Array.isArray(body.items));
});

test('a path cannot climb out of public/', async () => {
  const res = await fetch(base + '/../server/index.js', {
    headers: { authorization: BASIC },
    redirect: 'manual'
  });
  assert.ok(res.status === 403 || res.status === 404, `got ${res.status}`);
});
