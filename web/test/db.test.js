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

test('the report cuts the hours on the trading day, not on midnight', () => {
  const db = tempDb();
  // 23:30 and 00:30 Bangkok — one evening, either side of the calendar's midnight. The second is
  // later in the same service and has to sort after the first, or the report reads as the money
  // having arrived before the stall opened.
  db.recordOrder(order(1, { orderedAt: Date.UTC(2026, 10, 10, 16, 30), total: 100 }));
  db.recordOrder(order(2, { orderedAt: Date.UTC(2026, 10, 10, 17, 30), total: 50 }));

  const report = db.salesReport(DAY);
  assert.deepEqual(
    report.hours.map((h) => h.hour),
    [23, 0]
  );
  assert.equal(report.takings, 150);
  // Newest first, which is what a list somebody is watching has to be.
  assert.deepEqual(report.recent.map((b) => b.queueNo), [2, 1]);
  db.close();
});

test('a cancelled bill is out of the money but still on the list', () => {
  const db = tempDb();
  db.recordOrder(order(3, { total: 100 }));
  db.recordOrder(order(4, { total: 60 }));
  db.markCancelled(DAY, 4, 'pos');

  const report = db.salesReport(DAY);
  assert.equal(report.takings, 100, 'cancelled money was never taken');
  assert.equal(report.hours.reduce((sum, h) => sum + h.takings, 0), 100);
  // Still shown, struck through, because somebody reading this list is usually checking exactly
  // that a mistake came back off the day.
  assert.ok(report.recent.some((b) => b.queueNo === 4 && b.status === 'cancelled'));
  db.close();
});

test('each dish carries both its count and its money', () => {
  const db = tempDb();
  db.recordOrder(order(5));
  db.recordOrder(order(6));

  const items = db.salesReport(DAY).items;
  const noodles = items.find((i) => i.name === 'ก๋วยเตี๋ยวคลุก');
  assert.equal(noodles.qty, 2);
  assert.equal(noodles.takings, 100, '2 × 50');
  db.close();
});

test('the poll says which orders the kitchen itself handed over', () => {
  const db = tempDb();
  db.recordOrder(order(7));
  db.recordOrder(order(8));
  // What the board's own ส่งของแล้ว does. Without this list the till reads #7's absence from the
  // ready list as "not ready" and puts the card it has already handed over back into กำลังทำ.
  db.markPicked(DAY, 7, 'pos');

  assert.deepEqual(db.pickedQueueNos(DAY, 'pos'), [{ session: 1, queueNo: 7 }]);
  assert.deepEqual(db.readyQueueNos(DAY, 'pos'), [], 'collected is not ready');
  assert.deepEqual(
    db.openOrders(DAY).map((o) => o.queue_no),
    [8],
    'and it is off the board'
  );
  db.close();
});

test('the collected list is per terminal and per round, like the ready one', () => {
  const db = tempDb();
  db.recordOrder(order(1, { source: 'pos' }));
  db.recordOrder(order(1, { source: 'pos-2' }));
  db.recordOrder(order(1, { session: 2 }));

  db.markPicked(DAY, 1, 'pos', 2);

  // Only the afternoon's #1 on this till. The morning's is still owed to a customer, and the
  // other terminal's #1 is a different order entirely — the round and the source are both part of
  // what identifies one.
  assert.deepEqual(db.pickedQueueNos(DAY, 'pos'), [{ session: 2, queueNo: 1 }]);
  assert.deepEqual(db.pickedQueueNos(DAY, 'pos-2'), []);
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

test('a promotion keeps the money and the bottle count both true', () => {
  const db = tempDb();
  // Three bottles at 39 with "3 for 100": the dish stays at its own price and the saving arrives
  // as a line of its own. That is the whole reason this shape was chosen over a bundle line —
  // `qty` stays in bottles, so the report cannot report 1 where 3 were sold.
  db.recordOrder(
    order(1, {
      total: 100,
      lines: [
        { name: 'น้ำซอสก๋วยเตี๋ยว', options: '', note: '', qty: 3, unitPrice: 39 },
        { name: 'ส่วนลด น้ำซอสก๋วยเตี๋ยว', options: 'โปรฯ 3 ชิ้น 100.-', note: '', qty: 1, unitPrice: -17 }
      ]
    })
  );

  const totals = db.dayTotals(DAY);
  assert.equal(totals.takings, 100, 'the day reads the bundle price, not 117');

  const bottles = totals.items.find((i) => i.name === 'น้ำซอสก๋วยเตี๋ยว');
  assert.equal(bottles.qty, 3, 'three bottles were sold and the report has to say three');
  assert.equal(bottles.takings, 117, 'the dish row is the dish at its own price');

  const saving = totals.items.find((i) => i.name === 'ส่วนลด น้ำซอสก๋วยเตี๋ยว');
  assert.equal(saving.takings, -17);
  // The two rows reconcile to the headline, which is what makes the split honest rather than a
  // figure that disagrees with itself on the same screen.
  assert.equal(
    totals.items.reduce((sum, i) => sum + i.takings, 0),
    totals.takings
  );
});

test('a promotion over several bundles discounts each one', () => {
  const db = tempDb();
  // Seven bottles: two bundles at 100 and one at 39 — 239, with the saving carried per bundle so
  // the row reads as "two bundles were discounted".
  db.recordOrder(
    order(1, {
      total: 239,
      lines: [
        { name: 'น้ำซอสก๋วยเตี๋ยว', options: '', note: '', qty: 7, unitPrice: 39 },
        { name: 'ส่วนลด น้ำซอสก๋วยเตี๋ยว', options: 'โปรฯ 3 ชิ้น 100.-', note: '', qty: 2, unitPrice: -17 }
      ]
    })
  );

  const totals = db.dayTotals(DAY);
  assert.equal(totals.takings, 239);
  assert.equal(totals.items.find((i) => i.name === 'น้ำซอสก๋วยเตี๋ยว').qty, 7);
  assert.equal(
    totals.items.reduce((sum, i) => sum + i.takings, 0),
    239
  );
});
