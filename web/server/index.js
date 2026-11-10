/**
 * The kitchen board, and the only thing the event terminal talks to.
 *
 * It serves one page — opened in Safari on the stall's iPad — and five endpoints. What it does
 * **not** do is print: the terminal's own head is reached only by native code on that terminal, so
 * printing is local and the board is shared. That split is settled; it is the same one the shop's
 * POS arrived at the long way round, and everything about the shape here follows from it.
 *
 * The consequence worth remembering: the till keeps taking money and printing queue tickets when
 * this server is unreachable. Orders queue in the terminal's outbox and arrive when the wifi does.
 *
 * Node standard library only, no dependencies, so it runs on a laptop at the stall or in a
 * container on Coolify without a build step.
 *
 * Run: node web/server/index.js   (PORT=8080 by default)
 */
import http from 'node:http';
import crypto from 'node:crypto';
import os from 'node:os';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { openDatabase, businessDay, SHOP_TIMEZONE, DAY_STARTS_AT } from './db.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.resolve(HERE, '../public');
const PORT = Number(process.env.PORT || 8080);
/** Lives beside the code by default; set DB_FILE to put it on a volume that is backed up. */
const DB_FILE = process.env.DB_FILE || path.resolve(HERE, '../data/event.db');
const db = openDatabase(DB_FILE);

const MAX_BODY_BYTES = 256 * 1024;

/**
 * Optional HTTP Basic auth. Unset means no auth at all, which is right on a stall's own wifi.
 *
 * Set it the moment this is reachable from the internet — which on Coolify it is by default.
 * Without it anyone with the URL can read the orders **and post fabricated ones onto the board**,
 * and a kitchen cooking food nobody paid for is the failure that costs real money.
 */
const AUTH_USER = process.env.AUTH_USER || '';
const AUTH_PASSWORD = process.env.AUTH_PASSWORD || '';
const AUTH_REQUIRED = Boolean(AUTH_USER && AUTH_PASSWORD);

/**
 * Reachable without credentials.
 *
 * The healthcheck, because Coolify's probe carries none. The icons and the manifest, because iOS
 * fetches them when the page is added to the home screen in a request that does not carry the
 * session — behind auth the bookmark gets a screenshot of the page instead of an icon, and an icon
 * reveals nothing.
 */
const OPEN_PATHS = new Set([
  '/api/health',
  '/login',
  '/login.html',
  '/apple-touch-icon.png',
  '/apple-touch-icon-precomposed.png',
  '/icon-192.png',
  '/manifest.webmanifest'
]);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml'
};

export const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');

    if (req.method === 'POST' && url.pathname === '/api/login') return await handleLogin(req, res);
    if (req.method === 'POST' && url.pathname === '/api/logout') return handleLogout(req, res);

    if (AUTH_REQUIRED && !OPEN_PATHS.has(url.pathname) && !isAuthorized(req)) {
      // Deliberately **no** `WWW-Authenticate`. That header is what makes Safari put up its own
      // password box, and a page opened from the iPad's home screen has its own credential jar —
      // so the box came back on every launch. A browser gets the login page and a cookie that
      // outlives the app being closed; the terminal keeps sending its Basic header and never sees
      // any of this.
      if (wantsHtml(req)) {
        res.writeHead(302, { location: '/login' });
        return res.end();
      }
      return json(res, 401, { ok: false, error: 'ต้องเข้าสู่ระบบ' });
    }

    renewSessionIfStale(req, res);

    if (url.pathname === '/api/health') {
      return json(res, 200, {
        ok: true,
        name: 'ครัวอีเวนต์เอี้ยมจุ๊น',
        day: businessDay(),
        timezone: SHOP_TIMEZONE,
        dayStartsAt: DAY_STARTS_AT
      });
    }
    if (req.method === 'POST' && url.pathname === '/api/orders') return await handleOrder(req, res);
    if (req.method === 'GET' && url.pathname === '/api/orders') return handleBoard(url, res);
    if (req.method === 'GET' && url.pathname === '/api/orders/ready') return handleReadyList(url, res);
    if (req.method === 'POST' && url.pathname === '/api/orders/ready') {
      return await handleStatus(req, res, 'ready');
    }
    if (req.method === 'POST' && url.pathname === '/api/orders/unready') {
      return await handleStatus(req, res, 'unready');
    }
    if (req.method === 'POST' && url.pathname === '/api/orders/picked') {
      return await handleStatus(req, res, 'picked');
    }
    if (req.method === 'POST' && url.pathname === '/api/orders/cancelled') {
      return await handleStatus(req, res, 'cancelled');
    }
    if (req.method === 'GET' && url.pathname === '/api/summary') {
      return json(res, 200, { ok: true, ...db.dayTotals(dayFrom(url)) });
    }

    return await serveStatic(req, res);
  } catch (err) {
    json(res, 500, { ok: false, error: String(err && err.message ? err.message : err) });
  }
});

// ---------------------------------------------------------------- orders

/**
 * The terminal pushing one paid order.
 *
 * Replays are expected, not exceptional: the terminal's outbox retries anything it did not get an
 * answer for, and a push that timed out *after* the write would otherwise put a second card on the
 * board for one customer. `(day, source, queueNo)` is the identity, so a replay lands on the same
 * row — see `recordOrder`.
 */
async function handleOrder(req, res) {
  let body;
  try {
    body = await readJsonBody(req);
  } catch (err) {
    return json(res, 400, { ok: false, error: err.message });
  }
  try {
    const saved = db.recordOrder(body);
    // On stdout as well, because Coolify's log is the only record of a push that goes wrong in a
    // way the board does not show. The terminal's own event log is in memory and gone on restart.
    console.log(
      `order ${saved.day} #${saved.queueNo} · ${saved.source}` +
        (saved.replaced ? ' (ซ้ำ — เขียนทับ)' : '')
    );
    return json(res, 200, { ok: true, ...saved });
  } catch (err) {
    return json(res, 400, { ok: false, error: err.message });
  }
}

function handleBoard(url, res) {
  const day = dayFrom(url);
  return json(res, 200, {
    ok: true,
    day,
    serverNow: Date.now(),
    orders: db.openOrders(day).map(shapeOrder)
  });
}

/** What the terminal polls. The whole truth for that day, so the board's undo propagates back. */
function handleReadyList(url, res) {
  const day = dayFrom(url);
  const source = String(url.searchParams.get('source') || 'pos');
  return json(res, 200, { ok: true, day, source, ready: db.readyQueueNos(day, source) });
}

async function handleStatus(req, res, action) {
  let body;
  try {
    body = await readJsonBody(req);
  } catch (err) {
    return json(res, 400, { ok: false, error: err.message });
  }
  const queueNo = Number(body.queueNo);
  if (!Number.isInteger(queueNo) || queueNo < 1) {
    return json(res, 400, { ok: false, error: 'เลขคิวไม่ถูกต้อง' });
  }
  const day = typeof body.day === 'string' && body.day ? body.day : businessDay();
  const source = String(body.source || 'pos');

  let changed;
  if (action === 'ready') changed = db.setReady(day, queueNo, source, true);
  else if (action === 'unready') changed = db.setReady(day, queueNo, source, false);
  else if (action === 'picked') changed = db.markPicked(day, queueNo, source);
  else changed = db.markCancelled(day, queueNo, source);

  console.log(`${action} ${day} #${queueNo} · ${source} → ${changed ? 'ok' : 'ไม่พบ/ไม่เปลี่ยน'}`);
  // Not an error when nothing changed. A card already collected, or a tap on an order this server
  // has never seen because the terminal is still offline, are both ordinary — and answering 404
  // would make the terminal's outbox retry forever.
  return json(res, 200, { ok: true, changed });
}

/** camelCase for the browser; the SQL rows are snake_case. */
function shapeOrder(row) {
  return {
    queueNo: row.queue_no,
    source: row.source,
    status: row.status,
    total: row.total,
    payment: row.payment,
    orderedAt: row.ordered_at,
    readyAt: row.ready_at,
    lines: row.lines.map((l) => ({
      name: l.name,
      options: l.options,
      note: l.note,
      qty: l.qty,
      unitPrice: l.unit_price
    }))
  };
}

function dayFrom(url) {
  const asked = url.searchParams.get('day');
  return asked && /^\d{4}-\d{2}-\d{2}$/.test(asked) ? asked : businessDay();
}

// ---------------------------------------------------------------- sessions

/**
 * A signed cookie, so the iPad stays logged in.
 *
 * Basic auth alone is what a desktop browser remembers and Safari does not — especially from the
 * home screen, where the saved site password does not follow. The shop was retyping it on every
 * launch, which is the whole reason this exists.
 *
 * The cookie carries nothing but the user and an expiry. Nothing is stored server-side: there is
 * one stall and one password, so a session table would be one row to clean up.
 *
 * `Secure` is set **only when the request arrived over HTTPS**, never unconditionally. Both
 * deployments are real: behind Coolify's proxy there is a certificate and the flag belongs there,
 * while a laptop serving the stall's own wifi has none — and a Secure cookie on plain HTTP is
 * simply never sent back, which locks the iPad out of a board that looks like it is working.
 */
const SESSION_COOKIE = 'eiamjun_event_session';
const SESSION_DAYS = 90;

function sign(value) {
  return crypto.createHmac('sha256', db.sessionSecret()).update(value).digest('base64url');
}

function makeSession(user, expiresAt) {
  const payload = Buffer.from(JSON.stringify({ u: user, exp: expiresAt })).toString('base64url');
  return `${payload}.${sign(payload)}`;
}

function readSession(req) {
  const raw = parseCookies(req).get(SESSION_COOKIE);
  if (!raw) return null;
  const dot = raw.lastIndexOf('.');
  if (dot === -1) return null;
  const payload = raw.slice(0, dot);
  if (!timingSafeEqual(raw.slice(dot + 1), sign(payload))) return null;
  try {
    const session = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    if (!session.exp || session.exp < Date.now()) return null;
    if (session.u !== AUTH_USER) return null; // the password changed; old cookies die with it
    return session;
  } catch {
    return null;
  }
}

function setSessionCookie(res, user, req) {
  const expiresAt = Date.now() + SESSION_DAYS * 86400_000;
  res.setHeader('set-cookie', cookieParts(`${SESSION_COOKIE}=${makeSession(user, expiresAt)}`, req, [
    `Max-Age=${SESSION_DAYS * 86400}`
  ]));
}

/**
 * The cookie's attributes, in one place so setting it and clearing it cannot disagree.
 *
 * A browser matches a deletion against the attributes it was given, so a Secure cookie cleared
 * without the flag can survive the logout — which is the quiet half of this that is easy to miss.
 */
function cookieParts(assignment, req, extra = []) {
  const parts = [assignment, 'Path=/', 'HttpOnly', 'SameSite=Lax', ...extra];
  if (isHttps(req)) parts.push('Secure');
  return parts.join('; ');
}

/**
 * Whether this request reached us over TLS.
 *
 * Coolify terminates the certificate at its proxy and forwards `x-forwarded-proto`; nothing in
 * front of the server on a stall's LAN sets it. Trusting the header from a client is safe here
 * because the only thing it can do is make the cookie **more** restrictive — a forged "https"
 * costs the forger their own session and nobody else anything.
 */
function isHttps(req) {
  const forwarded = String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim();
  return forwarded === 'https' || Boolean(req.socket && req.socket.encrypted);
}

/** Renewed lazily: a cookie past its half-life is refreshed, so a board used daily never expires. */
function renewSessionIfStale(req, res) {
  const session = readSession(req);
  if (!session) return;
  if (session.exp < Date.now() + (SESSION_DAYS * 86400_000) / 2) {
    setSessionCookie(res, session.u, req);
  }
}

function parseCookies(req) {
  const jar = new Map();
  for (const part of (req.headers.cookie || '').split(';')) {
    const eq = part.indexOf('=');
    if (eq === -1) continue;
    jar.set(part.slice(0, eq).trim(), part.slice(eq + 1).trim());
  }
  return jar;
}

/** The terminal's header, or the browser's cookie. Two ways in, on purpose. */
function isAuthorized(req) {
  const header = String(req.headers.authorization || '');
  if (header.startsWith('Basic ')) {
    const decoded = Buffer.from(header.slice(6), 'base64').toString('utf8');
    const split = decoded.indexOf(':');
    if (split !== -1) {
      return (
        timingSafeEqual(decoded.slice(0, split), AUTH_USER) &&
        timingSafeEqual(decoded.slice(split + 1), AUTH_PASSWORD)
      );
    }
  }
  return Boolean(readSession(req));
}

async function handleLogin(req, res) {
  if (!AUTH_REQUIRED) return json(res, 200, { ok: true });
  let body;
  try {
    body = await readJsonBody(req);
  } catch (err) {
    return json(res, 400, { ok: false, error: err.message });
  }
  const user = String(body.user ?? '');
  const password = String(body.password ?? '');
  if (!timingSafeEqual(user, AUTH_USER) || !timingSafeEqual(password, AUTH_PASSWORD)) {
    return json(res, 401, { ok: false, error: 'ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง' });
  }
  setSessionCookie(res, user, req);
  return json(res, 200, { ok: true });
}

function handleLogout(req, res) {
  res.setHeader('set-cookie', cookieParts(`${SESSION_COOKIE}=`, req, ['Max-Age=0']));
  return json(res, 200, { ok: true });
}

/** A page somebody is looking at, as opposed to a fetch from the terminal. */
function wantsHtml(req) {
  return req.method === 'GET' && String(req.headers.accept || '').includes('text/html');
}

/** Constant time, and length-safe: `crypto.timingSafeEqual` throws on a length mismatch. */
function timingSafeEqual(a, b) {
  const left = Buffer.from(String(a));
  const right = Buffer.from(String(b));
  if (left.length !== right.length) return false;
  return crypto.timingSafeEqual(left, right);
}

// ---------------------------------------------------------------- static files

async function serveStatic(req, res) {
  const url = new URL(req.url, 'http://localhost');
  let pathname = decodeURIComponent(url.pathname);
  if (pathname === '/') pathname = '/index.html';
  if (pathname === '/login') pathname = '/login.html';
  // Older iOS probes the "precomposed" name when it does not like a page's declared icon. Same
  // file — that suffix only ever meant "don't add your own gloss", and nothing does that now.
  if (pathname === '/apple-touch-icon-precomposed.png') pathname = '/apple-touch-icon.png';

  const file = path.resolve(PUBLIC_DIR, pathname.slice(1));
  if (file !== PUBLIC_DIR && !file.startsWith(PUBLIC_DIR + path.sep)) {
    return json(res, 403, { ok: false, error: 'forbidden' });
  }
  try {
    const data = await fs.readFile(file);
    res.writeHead(200, {
      'content-type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
      'cache-control': 'no-cache'
    });
    res.end(req.method === 'HEAD' ? undefined : data);
  } catch {
    res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('404');
  }
}

// ---------------------------------------------------------------- helpers

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'));
      } catch {
        reject(new Error('invalid JSON body'));
      }
    });
    req.on('error', reject);
  });
}

function json(res, status, payload) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(payload));
}

function lanUrls(port) {
  const urls = [];
  for (const addrs of Object.values(os.networkInterfaces())) {
    for (const a of addrs || []) {
      if (a.family === 'IPv4' && !a.internal) urls.push(`http://${a.address}:${port}`);
    }
  }
  urls.push(`http://localhost:${port}`);
  return urls;
}

// Importable for the tests without binding a port — `node --test` drives the handler directly, the
// way the shop's server learned to: a rule with a green unit test and a broken route in front of
// it is the bug that shipped and stayed invisible for weeks.
if (process.env.NODE_ENV !== 'test') {
  server.listen(PORT, () => {
    console.log('ครัวอีเวนต์ — เปิดหน้านี้บน iPad:');
    for (const url of lanUrls(PORT)) console.log('  ' + url);
    console.log(`ฐานข้อมูล: ${DB_FILE}`);
    if (!AUTH_REQUIRED) console.log('⚠️  ยังไม่ได้ตั้ง AUTH_USER / AUTH_PASSWORD — ใครเข้า URL ได้ก็อ่านและสั่งได้');
  });
}
