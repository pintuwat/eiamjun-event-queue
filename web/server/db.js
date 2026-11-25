/**
 * The event's order book: one SQLite file through `node:sqlite`, so this server still has **zero
 * dependencies** and can be dropped on anything with Node.
 *
 * Two tables and a key/value row, and two reports on top of them, because a stall asks two
 * different questions. `salesReport` is **one day deep** and is read during a service: the
 * question then is always about the day in progress. `periodReport` is a span of days and is read
 * afterwards — what the event made, which of its days was the good one, what to prep more of next
 * time. Neither is built out of the other's arithmetic, and neither exports: the shop's own server
 * grew a CSV over months of trading and nothing here has asked for one.
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
 * The hour of the shop's own clock a moment falls in, 0–23.
 *
 * Read through `Intl` in [SHOP_TIMEZONE], never off the host: this runs on a VPS in UTC, and an
 * hour taken from the machine would put a 19:00 service at noon.
 */
export function shopHour(at, timeZone = SHOP_TIMEZONE) {
  return Number(
    new Intl.DateTimeFormat('en-GB', { timeZone, hour: '2-digit', hour12: false }).format(
      new Date(at)
    )
  );
}

/** Where an hour sits in the trading day, so 23:00 sorts before the 01:00 that follows it. */
export function tradingHour(hour) {
  return (hour - DAY_STARTS_AT + 24) % 24;
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

    /**
     * Which of this terminal's orders have been **collected**, for the same poll.
     *
     * The board can now close an order itself (ส่งของแล้ว on a ready card), and without this the
     * till would never hear: the order leaves `readyQueueNos`, the till reads that as "not ready",
     * and the next poll would flip a card the staff have handed over back to กำลังทำ and keep it
     * on the queue for the rest of the day.
     *
     * Unlike the ready list this is **not** the whole truth about anything — it is a list of
     * things that have happened and cannot un-happen. The till adds them and never removes on
     * their absence, which is what makes it safe for both sides to write the flag at all: one
     * value, written once, from either end.
     *
     * The whole day, because an order collected an hour ago may belong to a terminal that has been
     * offline since, and a list of a few hundred numbers is a fraction of what the board itself
     * polls.
     */
    pickedQueueNos(day, source) {
      return db
        .prepare(
          `SELECT session, queue_no FROM orders
            WHERE day = ? AND source = ? AND status = 'picked'
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
      // Both figures per dish, because they answer different questions: how many to prep more of,
      // and what the day's money actually came from. A 59-Baht bag outsold two ways at once is
      // invisible in a count alone.
      const items = db
        .prepare(
          `SELECT l.name, SUM(l.qty) AS qty, COALESCE(SUM(l.qty * l.unit_price), 0) AS takings
             FROM order_lines l JOIN orders o ON o.id = l.order_id
            WHERE o.day = ? AND o.status != 'cancelled'
            GROUP BY l.name ORDER BY qty DESC`
        )
        .all(day);
      return { day, ...row, byStatus, items };
    },

    /**
     * Everything the รายงานยอดขาย page draws, in one read.
     *
     * One call rather than the page asking four times: it polls every few seconds, and four
     * requests that can each fail separately give a screen that is half one moment and half
     * another. The figures all come from the same instant this way.
     *
     * The day's totals are [dayTotals] rather than a second copy of that arithmetic — "cancelled
     * is out of the takings" is a rule, and a rule written twice is a rule that will disagree with
     * itself.
     */
    salesReport(day, limit = 12) {
      const totals = this.dayTotals(day);
      const bills = db
        .prepare(
          `SELECT queue_no, session, source, total, status, ordered_at
             FROM orders WHERE day = ? ORDER BY ordered_at DESC, id DESC`
        )
        .all(day);

      // Grouped here rather than in SQL because the hour has to be Bangkok's, and `ordered_at` is
      // an epoch. Doing it in SQL would mean baking a UTC offset into a query — which is exactly
      // how the shop's server once filed every evening's takings under the next day.
      const hours = new Map();
      for (const bill of bills) {
        if (bill.status === 'cancelled') continue;
        const hour = shopHour(bill.ordered_at);
        const seen = hours.get(hour) || { hour, takings: 0, orders: 0 };
        seen.takings += bill.total;
        seen.orders += 1;
        hours.set(hour, seen);
      }

      return {
        ...totals,
        // The trading day starts at 07:00, so that is where the clock is cut: 23:00 comes before
        // 01:00 on this list, and a stall still serving at 00:30 is at the end of its own evening
        // rather than at the start of a day nobody worked.
        hours: [...hours.values()].sort(
          (a, b) => tradingHour(a.hour) - tradingHour(b.hour)
        ),
        recent: bills.slice(0, limit).map((b) => ({
          queueNo: b.queue_no,
          session: b.session,
          source: b.source,
          total: b.total,
          status: b.status,
          orderedAt: b.ordered_at
        }))
      };
    },

    /**
     * The first and last day the order book holds anything on, and how many days that is.
     *
     * The period page needs it before it can draw anything: its own default is **the whole
     * ledger**, and a browser cannot know what that is. It is also what bounds the two date
     * fields, so a period with nothing in it cannot be picked by accident.
     *
     * A day whose only order was cancelled still counts — the stall was open and took it, and
     * leaving it out would make the span disagree with the day list below it, which draws the
     * same day as a zero.
     */
    ledgerSpan() {
      const row = db
        .prepare('SELECT MIN(day) AS first, MAX(day) AS last, COUNT(DISTINCT day) AS days FROM orders')
        .get();
      return { first: row.first || null, last: row.last || null, days: Number(row.days || 0) };
    },

    /**
     * Everything สรุปตามช่วงเวลา draws, over a span of trading days, in one read.
     *
     * The day report above was deliberately one day deep — an event runs for a weekend and the
     * question during a service is always about the day in progress. This is the question asked
     * **afterwards**, and it is a different one: what did the event make, which of its days was
     * the good one, and what should be prepped more of next time. Both read the same rules, and
     * neither is a second copy of the other's arithmetic.
     *
     * `from` and `to` are inclusive `YYYY-MM-DD`, compared as **strings**. That is the whole
     * reason `day` is stored as text rather than derived from the epoch in SQL: Thailand is
     * UTC+7, so an evening order would otherwise land on the next day's takings, and every
     * per-day query is a plain string match instead. ISO dates sort lexicographically, so a
     * `BETWEEN` on them is a real date range.
     *
     * One request per tick, like the day report, and for the same reason: a page that asked
     * separately for its total, its days and its dishes would show half of one moment beside
     * half of another, on figures somebody is about to act on.
     */
    periodReport(from, to) {
      const totals = db
        .prepare(
          `SELECT COUNT(*) AS orders, COALESCE(SUM(total), 0) AS takings
             FROM orders WHERE day >= ? AND day <= ? AND status != 'cancelled'`
        )
        .get(from, to);

      // Counted and totalled rather than merely excluded. Over one day the four status boxes say
      // this; over a period they cannot — a week-old order is not "กำลังทำ", and waiting/ready are
      // about right now. What survives a period is how much came back off it, which is the one
      // figure somebody checks when a total looks wrong.
      const cancelled = db
        .prepare(
          `SELECT COUNT(*) AS orders, COALESCE(SUM(total), 0) AS takings
             FROM orders WHERE day >= ? AND day <= ? AND status = 'cancelled'`
        )
        .get(from, to);

      /**
       * One row per day that sold, and **no row for a day the stall was shut**.
       *
       * The shop's own server pads a month with zeroes, which is right for a shop that opens every
       * day: a gap there is a day worth asking about. An event stall sells on the days there is an
       * event, so padding a three-day weekend out to a calendar month would draw a chart that is
       * 90% empty and read as a collapse in takings.
       */
      const days = db
        .prepare(
          `SELECT day, COUNT(*) AS orders, COALESCE(SUM(total), 0) AS takings
             FROM orders WHERE day >= ? AND day <= ? AND status != 'cancelled'
            GROUP BY day ORDER BY day`
        )
        .all(from, to);

      // Grouped by name across the whole period, which is the point: one event's forty bowls say
      // less than four events' hundred and sixty. Both figures per dish, as on the day report —
      // how much to prep, and where the money came from.
      const items = db
        .prepare(
          `SELECT l.name, SUM(l.qty) AS qty, COALESCE(SUM(l.qty * l.unit_price), 0) AS takings
             FROM order_lines l JOIN orders o ON o.id = l.order_id
            WHERE o.day >= ? AND o.day <= ? AND o.status != 'cancelled'
            GROUP BY l.name ORDER BY qty DESC`
        )
        .all(from, to);

      // The one figure that gets *better* with more days in it: a single service's busy hour is
      // one service, several events' is the hour to have the second pair of hands for. Grouped in
      // JS for the reason `salesReport` is — the hour has to be Bangkok's and `ordered_at` is an
      // epoch, and baking an offset into a query is how the shop's server filed a whole evening
      // under the following day.
      const hours = new Map();
      for (const row of db
        .prepare(
          `SELECT ordered_at, total FROM orders
            WHERE day >= ? AND day <= ? AND status != 'cancelled'`
        )
        .all(from, to)) {
        const hour = shopHour(row.ordered_at);
        const seen = hours.get(hour) || { hour, takings: 0, orders: 0 };
        seen.takings += row.total;
        seen.orders += 1;
        hours.set(hour, seen);
      }

      return {
        from,
        to,
        orders: totals.orders,
        takings: totals.takings,
        cancelled,
        days,
        items,
        hours: [...hours.values()].sort((a, b) => tradingHour(a.hour) - tradingHour(b.hour)),
        // Named in words as well as drawn in the chart, because "which day was the good one" is
        // the question this page is opened with and a bar is read by comparing. Ties go to the
        // earlier day, which is arbitrary and harmless; nothing is decided by it.
        best: days.reduce((top, day) => (!top || day.takings > top.takings ? day : top), null)
      };
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
      // The board's own ส่งของแล้ว. The write it makes was always accepted; what is new is this
      // server telling the terminal's poll which orders were closed that way, so a card closed in
      // the kitchen also leaves the till's queue. Worth reporting because a terminal on the newer
      // build against an older server silently keeps those cards and nothing else would say so.
      //
      // Checked against the function rather than a column, because this one adds no column — it
      // is the nearest thing to the schema read the rest of this list uses: delete the code and
      // the capability stops being claimed, which a hand-written string would not do.
      if (typeof this.pickedQueueNos === 'function') features.push('picked-list');
      // สรุปตามช่วงเวลา. Adds no column either, so it is claimed the same way: delete the code and
      // the capability stops being reported, which a hand-written string would not do. Worth
      // reporting because the page is behind the login and `/api/health` is not — "did the
      // redeploy land?" has no answer from outside otherwise.
      if (typeof this.periodReport === 'function') features.push('period-report');
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
