import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { openDatabase, businessDay, orderTime } from '../server/db.js';

function tempDb() {
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'event-db-')), 'event.db');
  return openDatabase(file);
}

const DAY = '2026-11-10';

function order(queueNo, overrides = {}) {
  return {
    queueNo,
    day: DAY,
    source: 'pos',
    total: 60,
    payment: 'qr',
    orderedAt: Date.UTC(2026, 10, 10, 5, 0, 0),
    lines: [
      { name: 'ก๋วยเตี๋ยวคลุก', options: 'เส้นหมี่ · เผ็ดน้อย', note: '', qty: 1, unitPrice: 50 },
      { name: 'แคปป๊อป', options: '', note: '', qty: 1, unitPrice: 10 }
    ],
    ...overrides
  };
}

test('the trading day starts at 07:00 Bangkok, not midnight', () => {
  // 00:30 Bangkok on the 11th is 17:30 UTC on the 10th. A stall still serving then belongs to the
  // evening that is still going on, so this must read as the 10th.
  assert.equal(businessDay(Date.UTC(2026, 10, 10, 17, 30)), '2026-11-10');
  // 07:30 Bangkok on the 11th — 00:30 UTC — is the new day.
  assert.equal(businessDay(Date.UTC(2026, 10, 11, 0, 30)), '2026-11-11');
  // And 06:30 Bangkok on the 11th is still the 10th.
  assert.equal(businessDay(Date.UTC(2026, 10, 10, 23, 30)), '2026-11-10');
});

test('an order is dated by the terminal, within a sane window of the server clock', () => {
  const now = Date.UTC(2026, 10, 10, 10, 0);
  const anHourAgo = now - 3600_000;
  assert.equal(orderTime(anHourAgo, now), anHourAgo, 'a queued order keeps the time it was taken');
  // A terminal with a badly wrong clock must not file an order into a month nobody reads — but the
  // order is still recorded, at arrival, because losing a sale over a clock is the worse failure.
  assert.equal(orderTime(Date.UTC(2020, 0, 1), now), now);
  assert.equal(orderTime(now + 10 * 86400_000, now), now);
  assert.equal(orderTime(undefined, now), now);
  assert.equal(orderTime('rubbish', now), now);
});

test('pushing the same order twice leaves one card, not two', () => {
  const db = tempDb();
  const first = db.recordOrder(order(1));
  const again = db.recordOrder(order(1));

  assert.equal(first.replaced, false);
  assert.equal(again.replaced, true);

  const open = db.openOrders(DAY);
  assert.equal(open.length, 1, 'a retry from the outbox must not add a second card');
  assert.equal(open[0].lines.length, 2, 'and must not double the lines either');
  db.close();
});

test('a replay does not put a card the kitchen has finished back into กำลังทำ', () => {
  const db = tempDb();
  db.recordOrder(order(7));
  assert.equal(db.setReady(DAY, 7, 'pos', true), true);

  db.recordOrder(order(7));

  assert.deepEqual(
    db.readyQueueNos(DAY, 'pos'),
    [{ session: 1, queueNo: 7 }],
    'the ready flag survives the retry'
  );
  db.close();
});

test('the ready list is per terminal', () => {
  const db = tempDb();
  db.recordOrder(order(1, { source: 'pos' }));
  db.recordOrder(order(1, { source: 'pos2' }));
  db.setReady(DAY, 1, 'pos2', true);

  assert.deepEqual(db.readyQueueNos(DAY, 'pos'), []);
  assert.deepEqual(db.readyQueueNos(DAY, 'pos2'), [{ session: 1, queueNo: 1 }]);
  assert.equal(db.openOrders(DAY).length, 2, 'two tills both issuing #1 is ordinary');
  db.close();
});

test('the kitchen can undo a พร้อมส่ง, and the list is what says so', () => {
  const db = tempDb();
  db.recordOrder(order(3));
  db.setReady(DAY, 3, 'pos', true);
  assert.deepEqual(db.readyQueueNos(DAY, 'pos'), [{ session: 1, queueNo: 3 }]);

  assert.equal(db.setReady(DAY, 3, 'pos', false), true);
  assert.deepEqual(db.readyQueueNos(DAY, 'pos'), [], 'the till reads this list as the whole truth');
  db.close();
});

test('a collected order cannot be dragged back onto the board', () => {
  const db = tempDb();
  db.recordOrder(order(4));
  db.markPicked(DAY, 4, 'pos');

  assert.equal(db.setReady(DAY, 4, 'pos', true), false, 'a stale tap changes nothing');
  assert.equal(db.openOrders(DAY).length, 0);
  db.close();
});

test('a cancelled order is out of the takings and off the board', () => {
  const db = tempDb();
  db.recordOrder(order(5));
  db.recordOrder(order(6));
  db.markCancelled(DAY, 6, 'pos');

  const totals = db.dayTotals(DAY);
  assert.equal(totals.orders, 1);
  assert.equal(totals.takings, 60);
  assert.equal(db.openOrders(DAY).length, 1);
  db.close();
});

test('the day total counts food still cooking, because it has been paid for', () => {
  const db = tempDb();
  db.recordOrder(order(1));
  db.recordOrder(order(2));
  db.setReady(DAY, 2, 'pos', true);
  db.recordOrder(order(3));
  db.markPicked(DAY, 3, 'pos');

  const totals = db.dayTotals(DAY);
  assert.equal(totals.orders, 3);
  assert.equal(totals.takings, 180);
  // Money is taken when the queue ticket prints, so waiting, ready and collected are all takings.
  db.close();
});

test('items are counted by name across the day', () => {
  const db = tempDb();
  db.recordOrder(order(1));
  db.recordOrder(order(2, {
    lines: [{ name: 'ก๋วยเตี๋ยวคลุก', options: 'มาม่า', note: '', qty: 2, unitPrice: 50 }],
    total: 100
  }));

  const items = db.dayTotals(DAY).items;
  // Field by field, not deepEqual: `node:sqlite` hands back null-prototype rows, which
  // deepStrictEqual refuses to match against a plain object however identical the contents.
  assert.equal(items[0].name, 'ก๋วยเตี๋ยวคลุก');
  assert.equal(items[0].qty, 3);
  assert.equal(items[1].name, 'แคปป๊อป');
  assert.equal(items[1].qty, 1);
  db.close();
});

test('an order with no day of its own is filed by the time it was taken', () => {
  const db = tempDb();
  // 23:30 UTC on the 10th is 06:30 Bangkok on the 11th — still the 10th's trading day.
  db.recordOrder(order(9, { day: undefined, orderedAt: Date.UTC(2026, 10, 10, 23, 30) }));
  assert.equal(db.openOrders('2026-11-10').length, 1);
  db.close();
});

test('a queue number that is not a number is refused rather than stored as one', () => {
  const db = tempDb();
  assert.throws(() => db.recordOrder(order('')), /เลขคิว/);
  assert.throws(() => db.recordOrder(order(0)), /เลขคิว/);
  db.close();
});

// ---------------------------------------------------------------- numbering rounds
//
// เริ่มนับคิวใหม่ on the terminal starts the queue at 1 again, which an event genuinely wants: two
// services can run on one calendar day. Before the round was part of an order's identity that
// button was a trap at both ends — the terminal's own unique index refused the afternoon's first
// order outright, and here the replace-on-conflict that makes the outbox safe to retry would have
// **overwritten a paid order**. These pin the half that is this file's.

test('a reset mid-day gives the afternoon its own #1 rather than replacing the morning', () => {
  const db = tempDb();
  const morning = db.recordOrder(order(1, { session: 1, total: 60 }));
  const afternoon = db.recordOrder(order(1, { session: 2, total: 110 }));

  assert.equal(morning.replaced, false);
  assert.equal(afternoon.replaced, false, 'the second #1 is a new order, not a replacement');
  assert.notEqual(morning.orderId, afternoon.orderId);

  const open = db.openOrders(DAY);
  assert.equal(open.length, 2);
  assert.deepEqual(open.map((o) => [o.session, o.queue_no, o.total]), [[1, 1, 60], [2, 1, 110]]);

  // And the day's takings still cover the whole day. A round is a numbering decision, not a
  // separate ledger.
  const totals = db.dayTotals(DAY);
  assert.equal(totals.orders, 2);
  assert.equal(totals.takings, 170);
});

test('a retry still replaces its own order, round and all', () => {
  const db = tempDb();
  db.recordOrder(order(1, { session: 1 }));
  db.recordOrder(order(1, { session: 2, total: 110 }));
  const retry = db.recordOrder(order(1, { session: 2, total: 110 }));

  assert.equal(retry.replaced, true);
  assert.equal(db.openOrders(DAY).length, 2, 'the outbox retry adds no third card');
});

test('an order with no round named belongs to the first one', () => {
  const db = tempDb();
  // What a terminal that predates the column sends, and what its outbox rows still hold.
  const saved = db.recordOrder(order(4, { session: undefined }));
  assert.equal(saved.session, 1);
  assert.equal(db.recordOrder(order(4, { session: 1 })).replaced, true, 'and it is the same order');
});

test('พร้อมส่ง names the round, so it cannot call the wrong #1', () => {
  const db = tempDb();
  db.recordOrder(order(1, { session: 1 }));
  db.recordOrder(order(1, { session: 2 }));

  assert.equal(db.setReady(DAY, 1, 'pos', true, 2), true);
  assert.deepEqual(db.readyQueueNos(DAY, 'pos'), [{ session: 2, queueNo: 1 }]);

  const morning = db.openOrders(DAY).find((o) => o.session === 1);
  assert.equal(morning.status, 'waiting', "the morning's #1 is left alone");

  // Both rounds are in the list the terminal takes as the whole truth: a reset does not hand over
  // food that was already cooking, so an order from the morning can still be marked ready.
  db.setReady(DAY, 1, 'pos', true, 1);
  assert.deepEqual(db.readyQueueNos(DAY, 'pos'), [
    { session: 1, queueNo: 1 },
    { session: 2, queueNo: 1 }
  ]);
});

test('a status message with no round matches whichever round has that number', () => {
  const db = tempDb();
  // An outbox row written by the old build carries no round. Missing the order entirely would be
  // worse than acting on the only one that can be meant on a day with one round.
  db.recordOrder(order(7, { session: 1 }));
  assert.equal(db.markPicked(DAY, 7, 'pos', undefined), true);
  assert.equal(db.openOrders(DAY).length, 0);
});

test('cancelling and collecting are scoped to their round too', () => {
  const db = tempDb();
  db.recordOrder(order(2, { session: 1, total: 60 }));
  db.recordOrder(order(2, { session: 2, total: 110 }));

  assert.equal(db.markCancelled(DAY, 2, 'pos', 1), true);
  assert.equal(db.markPicked(DAY, 2, 'pos', 2), true);

  const totals = db.dayTotals(DAY);
  assert.equal(totals.orders, 1, 'the cancelled one is out of the takings');
  assert.equal(totals.takings, 110);
  assert.equal(db.openOrders(DAY).length, 0, 'and neither is still owed to anybody');
});
