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

  assert.deepEqual(db.readyQueueNos(DAY, 'pos'), [7], 'the ready flag survives the retry');
  db.close();
});

test('the ready list is per terminal', () => {
  const db = tempDb();
  db.recordOrder(order(1, { source: 'pos' }));
  db.recordOrder(order(1, { source: 'pos2' }));
  db.setReady(DAY, 1, 'pos2', true);

  assert.deepEqual(db.readyQueueNos(DAY, 'pos'), []);
  assert.deepEqual(db.readyQueueNos(DAY, 'pos2'), [1]);
  assert.equal(db.openOrders(DAY).length, 2, 'two tills both issuing #1 is ordinary');
  db.close();
});

test('the kitchen can undo a พร้อมส่ง, and the list is what says so', () => {
  const db = tempDb();
  db.recordOrder(order(3));
  db.setReady(DAY, 3, 'pos', true);
  assert.deepEqual(db.readyQueueNos(DAY, 'pos'), [3]);

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
