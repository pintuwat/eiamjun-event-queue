/**
 * The event's order book: one SQLite file through `node:sqlite`, so this server still has **zero
 * dependencies** and can be dropped on anything with Node.
 *
 * Two tables and a key/value row. There is no reporting here beyond a day's totals — the shop's
 * own server grew สรุปยอดขาย over months of service, and an event runs for a weekend; what the
 * kitchen needs is the orders that are not yet cooked.
 */
import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

/**
 * The trading day starts at 07:00 Asia/Bangkok, the same rule the terminal applies
 * (`EventDay` in the Android app). A stall still serving at 00:30 belongs to the evening that is
 * still going on, and queue numbers restart on this boundary.
 *
 * The timezone is **fixed rather than read from the host**: a VPS is almost always UTC, which
 * would file every order after 17:00 Thai time under the next day. Override both with env vars
 * for a stall in another timezone.
 */
export const SHOP_TIMEZONE = process.env.SHOP_TIMEZONE || 'Asia/Bangkok';
export const DAY_STARTS_AT = Number(process.env.DAY_STARTS_AT ?? 7);

/** @returns the trading day a moment belongs to, as `YYYY-MM-DD`. */
export function businessDay(at = Date.now(), timeZone = SHOP_TIMEZONE) {
  const shifted = new Date(at - DAY_STARTS_AT * 3600_000);
  // `en-CA` gives ISO order, which is the one locale trick worth using here: the alternative is
  // reading three separate parts out of a formatToParts call.
  return new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).format(shifted);
}

/**
 * When an order was actually taken, bounded against this server's own clock.
 *
 * The terminal stamps it, because an order may sit in that terminal's outbox for an hour and the
 * arrival of the HTTP request says nothing about when the money was taken. The shop's till lost a
 * week of readable books to exactly this: its queued sales were filed by arrival, so a whole day's
 * takings landed on the following morning.
 *
 * Bounded, because a terminal with a wrong clock would otherwise file an order into a month nobody
 * reads — and outside the bounds the order is still **recorded**, at arrival time, since losing an
 * order over a bad clock is the worse failure.
 */
export function orderTime(sent, arrivedAt = Date.now()) {
  const at = Number(sent);
  if (!Number.isFinite(at)) return arrivedAt;
  const floor = arrivedAt - 400 * 86400_000;
  const ceiling = arrivedAt + 86400_000;
  return at >= floor && at <= ceiling ? at : arrivedAt;
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS orders (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  day         TEXT    NOT NULL,
  queue_no    INTEGER NOT NULL,
  -- Which numbering round within the day. เริ่มนับคิวใหม่ on the terminal starts the numbers
  -- again, so one day can hold two #1s and the number alone is not an identity.
  session     INTEGER NOT NULL DEFAULT 1,
  source      TEXT    NOT NULL,
  total       INTEGER NOT NULL,
  payment     TEXT    NOT NULL DEFAULT 'qr',
  status      TEXT    NOT NULL DEFAULT 'waiting',
  ordered_at  INTEGER NOT NULL,
  received_at INTEGER NOT NULL,
  ready_at    INTEGER,
  picked_at   INTEGER
);

-- The terminal issues the queue number, so this is what identifies an order and what makes a retry
-- from its outbox idempotent: the same push lands on the same row rather than creating a second
-- card for one customer. With two terminals each numbering from 1, "14 · POS-2" is the identity —
-- which is why the source is in the key.
--
-- The round is in it because a terminal can be told to start counting from 1 again mid-day. Before
-- it was, the afternoon's first push matched the morning's #1 and the replace below **overwrote a
-- paid order** — the quiet half of a bug whose loud half was the terminal refusing to record the
-- sale at all.
CREATE UNIQUE INDEX IF NOT EXISTS orders_identity
  ON orders (day, source, session, queue_no);

CREATE INDEX IF NOT EXISTS orders_day_status ON orders (day, status);

CREATE TABLE IF NOT EXISTS order_lines (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id   INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  name       TEXT    NOT NULL,
  options    TEXT    NOT NULL DEFAULT '',
  note       TEXT    NOT NULL DEFAULT '',
  qty        INTEGER NOT NULL,
  unit_price INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS order_lines_order ON order_lines (order_id);

CREATE TABLE IF NOT EXISTS server_state (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
`;

export function openDatabase(file) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const db = new DatabaseSync(file);
  db.exec('PRAGMA journal_mode = WAL');
  db.exec('PRAGMA foreign_keys = ON');
  db.exec(SCHEMA);
  migrate(db);
  return api(db);
}

/**
 * Column additions and one-off repairs.
 *
 * `CREATE TABLE IF NOT EXISTS` does nothing to a table that already exists, so a column added to
 * SCHEMA never reaches a live file — the shop's server learned this the hard way. Adding a column
 * is additive and safe to run on every start; anything that is not does not belong here.
 */
function migrate(db) {
  const columns = db.prepare('PRAGMA table_info(orders)').all().map((c) => c.name);
  if (!columns.includes('payment')) {
    db.exec("ALTER TABLE orders ADD COLUMN payment TEXT NOT NULL DEFAULT 'qr'");
  }
  if (!columns.includes('session')) {
    db.exec('ALTER TABLE orders ADD COLUMN session INTEGER NOT NULL DEFAULT 1');
  }

  // The identity index is the one thing here that is *not* purely additive, so it is rebuilt only
  // when it is still the old shape. Everything already recorded is round 1 by the default above, so
  // widening the key cannot collide: it only separates rows that the old key would have merged.
  const identity = db.prepare('PRAGMA index_info(orders_identity)').all();
  if (identity.length && !identity.some((c) => c.name === 'session')) {
    db.exec('DROP INDEX orders_identity');
    db.exec('CREATE UNIQUE INDEX orders_identity ON orders (day, source, session, queue_no)');
  }
}

/**
 * The numbering round a message is about, defaulting to the first.
 *
 * `undefined` is a terminal that predates the column — or an outbox row it built before the update
 * — and everything such a terminal ever issued belongs to round 1, so that is what it means. It is
 * **not** the same as "any round": see [matchSession].
 */
function sessionOf(value) {
  const session = Number(value);
  return Number.isInteger(session) && session >= 1 ? session : 1;
}

/**
 * How a status message finds its order when it carries no round.
 *
 * A pushed *order* always names its round — it is being written, so it has to. A ready/collected/
 * cancelled message may not, because it could have been sitting in an old build's outbox since
 * before the column existed. Rather than guess at round 1 and silently miss, such a message
 * matches the number in **any** round: on every day but a reset day there is only one, and on a
 * reset day a stale message about a collected bag is less harmful than one that does nothing.
 */
function matchSession(value) {
  return value === undefined || value === null || value === '' ? null : sessionOf(value);
}

function api(db) {
  return {
    close: () => db.close(),

    /**
     * Records an order, or **replaces** the one already under that queue number.
     *
     * Replacing rather than appending is what makes the terminal's outbox safe to retry: a push
     * that times out after the server wrote it is sent again, and a second card for one customer
     * is exactly what the kitchen cannot untangle mid-service.
     *
     * The status is deliberately **not** overwritten on a replay. The kitchen may already have
     * tapped พร้อมส่ง between the first write and the retry, and a retry that quietly put the
     * card back into กำลังทำ would have the number called twice.
     */
    recordOrder(payload, arrivedAt = Date.now()) {
      const source = String(payload.source || 'pos').trim() || 'pos';
      const queueNo = Number(payload.queueNo);
      if (!Number.isInteger(queueNo) || queueNo < 1) throw new Error('เลขคิวไม่ถูกต้อง');
      const session = sessionOf(payload.session);

      const orderedAt = orderTime(payload.orderedAt, arrivedAt);
      // The terminal's own day wins, for the same reason it stamps the time: it knew which trading
      // day it was when the money was taken. Only a missing one falls back to the server's.
      const day = typeof payload.day === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(payload.day)
        ? payload.day
        : businessDay(orderedAt);

      const lines = Array.isArray(payload.lines) ? payload.lines : [];
      const total = Number.isFinite(Number(payload.total))
        ? Number(payload.total)
        : lines.reduce((sum, l) => sum + Number(l.unitPrice || 0) * Number(l.qty || 0), 0);

      db.exec('BEGIN IMMEDIATE');
      try {
        const existing = db
          .prepare(
            'SELECT id FROM orders WHERE day = ? AND source = ? AND session = ? AND queue_no = ?'
          )
          .get(day, source, session, queueNo);

        let orderId;
        if (existing) {
          orderId = existing.id;
          db.prepare(
            'UPDATE orders SET total = ?, payment = ?, ordered_at = ? WHERE id = ?'
          ).run(total, String(payload.payment || 'qr'), orderedAt, orderId);
          db.prepare('DELETE FROM order_lines WHERE order_id = ?').run(orderId);
        } else {
          const result = db
            .prepare(
              `INSERT INTO orders (day, queue_no, session, source, total, payment, status, ordered_at, received_at)
               VALUES (?, ?, ?, ?, ?, ?, 'waiting', ?, ?)`
            )
            .run(
              day,
              queueNo,
              session,
              source,
              total,
              String(payload.payment || 'qr'),
              orderedAt,
              arrivedAt
            );
          orderId = Number(result.lastInsertRowid);
        }

        const insertLine = db.prepare(
          `INSERT INTO order_lines (order_id, name, options, note, qty, unit_price)
           VALUES (?, ?, ?, ?, ?, ?)`
        );
        for (const line of lines) {
          insertLine.run(
            orderId,
            String(line.name || ''),
            String(line.options || ''),
            String(line.note || ''),
            Number(line.qty || 1),
            Number(line.unitPrice || 0)
          );
        }
        db.exec('COMMIT');
        return { day, queueNo, session, source, orderId, replaced: Boolean(existing) };
      } catch (err) {
        db.exec('ROLLBACK');
        throw err;
      }
    },

    /**
     * The kitchen's one transition, and its undo.
     *
     * This flag is the **only** thing the board writes, which is what means the two sides never
     * have to be reconciled: the terminal owns an order's contents, its number and whether it has
     * been collected, and the kitchen owns this. There is no field both of them write.
     *
     * A collected order is left alone — a stale tap must not pull a card the staff have already
     * handed over back onto the board.
     */
    setReady(day, queueNo, source, ready, session) {
      const round = matchSession(session);
      const now = Date.now();
      const result = db
        .prepare(
          `UPDATE orders
              SET status = ?, ready_at = ?
            WHERE day = ? AND queue_no = ? AND source = ?
              AND (? IS NULL OR session = ?)
              AND status IN ('waiting', 'ready')`
        )
        .run(ready ? 'ready' : 'waiting', ready ? now : null, day, queueNo, source, round, round);
      return result.changes > 0;
    },

    markPicked(day, queueNo, source, session) {
      const round = matchSession(session);
      const result = db
        .prepare(
          `UPDATE orders SET status = 'picked', picked_at = ?
            WHERE day = ? AND queue_no = ? AND source = ?
              AND (? IS NULL OR session = ?)
              AND status != 'cancelled'`
        )
        .run(Date.now(), day, queueNo, source, round, round);
      return result.changes > 0;
    },

    markCancelled(day, queueNo, source, session) {
      const round = matchSession(session);
      const result = db
        .prepare(
          `UPDATE orders SET status = 'cancelled'
            WHERE day = ? AND queue_no = ? AND source = ? AND (? IS NULL OR session = ?)`
        )
        .run(day, queueNo, source, round, round);
      return result.changes > 0;
    },

    /** Everything still owed to a customer, for the board. */
    openOrders(day) {
      const orders = db
        .prepare(
          `SELECT * FROM orders
            WHERE day = ? AND status IN ('waiting', 'ready')
            ORDER BY session, queue_no`
        )
        .all(day);
      return orders.map((order) => ({ ...order, lines: this.linesFor(order.id) }));
    },

    /**
     * The authoritative set of queue numbers the kitchen has marked ready.
     *
     * The terminal polls this and takes it **as the whole truth** for its waiting and ready
     * orders, which is what makes the board's undo work: a number taken off this list goes back to
     * กำลังทำ on the till too.
     *
     * Each entry is `{session, queueNo}`, not a bare number: after เริ่มนับคิวใหม่ one day holds
     * two #3s, and a list of numbers alone would have the till mark whichever it found first.
     * Every round of the day is in here, since a reset does not hand over food already cooking.
     */
    readyQueueNos(day, source) {
      return db
        .prepare(
          `SELECT session, queue_no FROM orders
            WHERE day = ? AND source = ? AND status = 'ready'
            ORDER BY session, queue_no`
        )
        .all(day, source)
        .map((row) => ({ session: row.session, queueNo: row.queue_no }));
    },

    linesFor(orderId) {
      return db.prepare('SELECT * FROM order_lines WHERE order_id = ? ORDER BY id').all(orderId);
    },

    /** The one figure anybody asks for at the end of an event. */
    dayTotals(day) {
      const row = db
        .prepare(
          `SELECT COUNT(*) AS orders, COALESCE(SUM(total), 0) AS takings
             FROM orders WHERE day = ? AND status != 'cancelled'`
        )
        .get(day);
      const byStatus = db
        .prepare(
          `SELECT status, COUNT(*) AS n FROM orders WHERE day = ? GROUP BY status`
        )
        .all(day);
      const items = db
        .prepare(
          `SELECT l.name, SUM(l.qty) AS qty
             FROM order_lines l JOIN orders o ON o.id = l.order_id
            WHERE o.day = ? AND o.status != 'cancelled'
            GROUP BY l.name ORDER BY qty DESC`
        )
        .all(day);
      return { day, ...row, byStatus, items };
    },

    /**
     * What this deployment can actually do, read off the live schema.
     *
     * "Did the redeploy land?" has no answer otherwise: the page and every read endpoint are
     * behind auth, so from outside, a server running last week's code is indistinguishable from
     * one running this week's. A hand-bumped version string would answer it too, right up to the
     * day somebody forgets to bump it — this is read from the database itself, so it reports the
     * capability rather than a label, and it cannot drift from the truth.
     *
     * Named capabilities, not column names: this rides on the open healthcheck, and what belongs
     * there is "the queue-round fix is live", not a map of the schema.
     */
    features() {
      const columns = db.prepare('PRAGMA table_info(orders)').all().map((c) => c.name);
      const features = [];
      // The reset-opens-a-round fix. Without the column, the server still replaces on
      // `(day, source, queue_no)` and a reset mid-day overwrites a paid order.
      if (columns.includes('session')) features.push('queue-rounds');
      return features;
    },

    /**
     * The key that signs session cookies, kept in the database rather than in an env var so a
     * redeploy does not sign the shop out. SESSION_SECRET overrides it for anyone who would rather
     * hold it outside.
     */
    sessionSecret() {
      if (process.env.SESSION_SECRET) return process.env.SESSION_SECRET;
      const existing = db.prepare('SELECT value FROM server_state WHERE key = ?').get('session_secret');
      if (existing) return existing.value;
      const secret = crypto.randomBytes(32).toString('base64url');
      db.prepare('INSERT INTO server_state (key, value) VALUES (?, ?)').run('session_secret', secret);
      return secret;
    }
  };
}
