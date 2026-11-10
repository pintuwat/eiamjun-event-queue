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

test('behind a TLS proxy the session cookie is Secure; on a bare LAN it is not', async () => {
  // Both deployments are real. Coolify terminates the certificate at its proxy and forwards
  // x-forwarded-proto; a laptop serving the stall's own wifi has no certificate, and a Secure
  // cookie there is simply never sent back — which locks the iPad out of a board that looks
  // like it is working.
  const behindProxy = await call('/api/login', {
    method: 'POST',
    body: { user: 'stall', password: 'secret' },
    headers: { 'x-forwarded-proto': 'https' }
  });
  assert.match(behindProxy.headers.get('set-cookie'), /;\s*Secure/);

  const onLan = await call('/api/login', {
    method: 'POST',
    body: { user: 'stall', password: 'secret' }
  });
  assert.doesNotMatch(onLan.headers.get('set-cookie'), /;\s*Secure/);
});

test('logging out clears the cookie with the attributes it was set with', async () => {
  // A browser matches a deletion against the attributes it was given, so a Secure cookie cleared
  // without the flag can survive the logout.
  const out = await call('/api/logout', {
    method: 'POST',
    headers: { 'x-forwarded-proto': 'https' }
  });
  const cookie = out.headers.get('set-cookie');
  assert.match(cookie, /Max-Age=0/);
  assert.match(cookie, /;\s*Secure/);
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
  assert.ok(list.ready.some((r) => r.queueNo === 3 && r.session === 1));

  await asTill('/api/orders/unready', {
    method: 'POST',
    body: { day: DAY, queueNo: 3, source: 'pos' }
  });
  list = await (await asTill(`/api/orders/ready?day=${DAY}&source=pos`)).json();
  assert.equal(
    list.ready.some((r) => r.queueNo === 3),
    false,
    'the till reads this list as the whole truth'
  );
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

// ---------------------------------------------------------------- numbering rounds over the wire
//
// The round is carried on four routes, and the one thing a unit test cannot show is whether the
// handlers actually read it off the body. These drive the whole loop the way the terminal and the
// board do: push two orders that both say #1, mark one of them ready, and check the other is
// untouched at both ends.

test('a reset mid-day puts a second #1 on the board rather than replacing the first', async () => {
  const day = '2026-11-20';
  const morning = await asTill('/api/orders', {
    method: 'POST',
    body: order(1, { day, session: 1, total: 60 })
  });
  const afternoon = await asTill('/api/orders', {
    method: 'POST',
    body: order(1, { day, session: 2, total: 110 })
  });

  assert.equal(morning.status, 200);
  assert.equal(afternoon.status, 200);
  const saved = await afternoon.json();
  assert.equal(saved.session, 2);
  assert.equal(saved.replaced, false, 'the afternoon does not overwrite the morning');

  const board = await (await asTill(`/api/orders?day=${day}`)).json();
  assert.equal(board.orders.length, 2);
  // The board needs the round to tell two cards apart, so it has to come back shaped.
  assert.deepEqual(
    board.orders.map((o) => [o.session, o.queueNo, o.total]),
    [[1, 1, 60], [2, 1, 110]]
  );
});

test('พร้อมส่ง on one round leaves the other alone, through the route', async () => {
  const day = '2026-11-21';
  await asTill('/api/orders', { method: 'POST', body: order(1, { day, session: 1 }) });
  await asTill('/api/orders', { method: 'POST', body: order(1, { day, session: 2 }) });

  const tap = await asTill('/api/orders/ready', {
    method: 'POST',
    body: { day, queueNo: 1, session: 2, source: 'pos' }
  });
  assert.equal(tap.status, 200);
  assert.equal((await tap.json()).changed, true);

  const list = await (await asTill(`/api/orders/ready?day=${day}&source=pos`)).json();
  assert.deepEqual(list.ready, [{ session: 2, queueNo: 1 }]);

  const board = await (await asTill(`/api/orders?day=${day}`)).json();
  const statuses = board.orders.map((o) => [o.session, o.status]);
  assert.deepEqual(statuses, [[1, 'waiting'], [2, 'ready']]);
});

test('an order pushed with no round is the first one, and its old outbox messages still land', async () => {
  const day = '2026-11-22';
  // What the build before this one sends — and what its outbox rows still hold, which is why a
  // status message with no round is not rejected.
  const saved = await (
    await asTill('/api/orders', { method: 'POST', body: order(5, { day, session: undefined }) })
  ).json();
  assert.equal(saved.session, 1);

  const picked = await asTill('/api/orders/picked', {
    method: 'POST',
    body: { day, queueNo: 5, source: 'pos' }
  });
  assert.equal((await picked.json()).changed, true);

  const board = await (await asTill(`/api/orders?day=${day}`)).json();
  assert.equal(board.orders.length, 0);
});

test('the healthcheck says what this deployment can do, with no credentials', async () => {
  // The one way to confirm a redeploy from outside: the page and every read endpoint are behind
  // auth, so a server running last week's code looks identical to one running this week's. Read
  // off the live schema rather than a hand-bumped string, which is only true until somebody
  // forgets to bump it.
  const res = await call('/api/health');
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.ok(Array.isArray(body.features));
  assert.ok(
    body.features.includes('queue-rounds'),
    'a server with orders.session says so, and one without must not'
  );
});
