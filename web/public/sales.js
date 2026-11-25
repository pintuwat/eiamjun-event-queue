/**
 * รายงานยอดขาย — the day's money, as it happens.
 *
 * A read-only screen, and that is the whole of its contract: it writes nothing, so it can be left
 * open on a phone in somebody's pocket at the back of the stall without any risk to a service. The
 * board next to it owns one flag and the terminal owns everything else; this one owns nothing.
 *
 * It is **not** linked from the kitchen board, at the owner's word. That screen is worked with
 * both hands full, and a way off it is a way to lose the queue — so this is opened deliberately,
 * by typing `/sales` once on the phone that wants it.
 *
 * One request per tick (`/api/sales`), never four: a page that asked separately for its totals,
 * its items and its bills would show half of one moment beside half of another, and on a figure
 * somebody is about to act on that is worse than being a few seconds old.
 */

import { baht, thaiDate, shopTime, freshness, phrases, meter, empty } from '/report-format.js';

/** Slower than the board's 4s. Money is read, not worked from, and nothing here is a race. */
const POLL_MS = 6000;

const el = {
  day: document.getElementById('day'),
  fresh: document.getElementById('fresh'),
  link: document.getElementById('link'),
  takings: document.getElementById('takings'),
  bills: document.getElementById('bills'),
  average: document.getElementById('average'),
  statuses: document.getElementById('statuses'),
  items: document.getElementById('items'),
  hours: document.getElementById('hours'),
  recent: document.getElementById('recent')
};

/** The last figures that arrived. A dropped poll redraws these rather than blanking the page. */
let report = null;
/** When they arrived, so a page that has quietly stopped updating says so instead of lying. */
let at = 0;

const STATUS_WORDS = {
  waiting: 'กำลังทำ',
  ready: 'พร้อมส่ง',
  picked: 'ส่งแล้ว',
  cancelled: 'ยกเลิก'
};

async function poll() {
  try {
    const res = await fetch('/api/sales', { headers: { accept: 'application/json' } });
    if (res.status === 401) {
      // The session expired while this sat on a bench. Go and get a new one rather than showing
      // a day with no sales in it, which is a figure somebody might believe.
      location.href = '/login';
      return;
    }
    if (!res.ok) throw new Error('HTTP ' + res.status);
    report = await res.json();
    at = Date.now();
    setLink(true);
  } catch {
    setLink(false);
  }
  render();
}

function setLink(up) {
  el.link.textContent = up ? 'ต่อเซิร์ฟเวอร์ได้' : 'ต่อเซิร์ฟเวอร์ไม่ได้';
  el.link.className = 'link-state ' + (up ? 'up' : 'down');
}

function render() {
  if (!report) return;
  el.day.textContent = thaiDate(report.day);
  el.fresh.textContent = freshness(at);

  const bills = Number(report.orders || 0);
  const takings = Number(report.takings || 0);
  el.takings.textContent = baht(takings);
  el.bills.textContent = String(bills);
  el.average.textContent = bills ? baht(Math.round(takings / bills)) : '0';

  drawStatuses();
  drawItems();
  drawHours();
  drawRecent();
}

function drawStatuses() {
  const counts = new Map((report.byStatus || []).map((row) => [row.status, row.n]));
  el.statuses.replaceChildren();
  // A fixed order, and every one of them drawn even at zero. A row that appears when the first
  // bowl is cancelled is a row nobody is looking for; one that reads 0 all day is one the eye
  // already knows the place of.
  for (const status of ['waiting', 'ready', 'picked', 'cancelled']) {
    const box = document.createElement('div');
    box.className = 'status status-' + status;
    const n = document.createElement('span');
    n.className = 'status-n';
    n.textContent = String(counts.get(status) || 0);
    const label = document.createElement('small');
    label.textContent = STATUS_WORDS[status];
    box.append(n, label);
    el.statuses.append(box);
  }
}

function drawItems() {
  const items = report.items || [];
  el.items.replaceChildren();
  if (!items.length) return el.items.append(empty('ยังไม่มีรายการขาย'));

  // The bars are scaled to the best seller, always — a bar that rescales as the day goes on reads
  // as the numbers themselves having moved.
  const top = Math.max(...items.map((i) => i.qty));
  for (const item of items) {
    const row = document.createElement('div');
    // Negative takings is a discount line, not food — see `.item.credit` in the stylesheet.
    row.className = item.takings < 0 ? 'item credit' : 'item';

    const head = document.createElement('div');
    head.className = 'item-head';
    const name = document.createElement('span');
    name.className = 'item-name';
    phrases(name, item.name);
    const qty = document.createElement('span');
    qty.className = 'item-qty';
    qty.textContent = `${item.qty}`;
    head.append(name, qty);

    const money = document.createElement('div');
    money.className = 'item-money';
    // Both figures, because they answer different questions: how many to prep more of, and where
    // the day's money actually came from. A dear item sells few and earns a lot.
    money.textContent = `${baht(item.takings || 0)} บาท`;

    row.append(head, meter(item.qty, top), money);
    el.items.append(row);
  }
}

function drawHours() {
  const hours = report.hours || [];
  el.hours.replaceChildren();
  if (!hours.length) return el.hours.append(empty('ยังไม่มียอดวันนี้'));

  const top = Math.max(...hours.map((h) => h.takings));
  for (const hour of hours) {
    const row = document.createElement('div');
    row.className = 'hour';

    const clock = document.createElement('span');
    clock.className = 'hour-clock';
    clock.textContent = `${String(hour.hour).padStart(2, '0')}:00`;

    const money = document.createElement('span');
    money.className = 'hour-money';
    money.textContent = baht(hour.takings);

    row.append(clock, meter(hour.takings, top), money);
    el.hours.append(row);
  }
}

function drawRecent() {
  const bills = report.recent || [];
  el.recent.replaceChildren();
  if (!bills.length) return el.recent.append(empty('ยังไม่มีบิล'));

  for (const bill of bills) {
    const row = document.createElement('div');
    row.className = 'bill' + (bill.status === 'cancelled' ? ' cancelled' : '');

    const queue = document.createElement('span');
    queue.className = 'bill-queue';
    // The round is named only when there has been more than one, which is the only time two bills
    // of this day can read the same number — the rule every other screen here follows.
    queue.textContent = bill.session > 1 ? `คิว ${bill.queueNo} · รอบ ${bill.session}` : `คิว ${bill.queueNo}`;

    const when = document.createElement('span');
    when.className = 'bill-when';
    when.textContent = `${shopTime(bill.orderedAt)} · ${STATUS_WORDS[bill.status] || bill.status}`;

    const total = document.createElement('span');
    total.className = 'bill-total';
    total.textContent = baht(bill.total);

    row.append(queue, when, total);
    el.recent.append(row);
  }
}

poll();
setInterval(poll, POLL_MS);
// Back from a pocket or a locked screen: show the figures now rather than after a poll.
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) poll();
});
// The "how old is this" line is the only thing that changes without a poll, and it is the whole
// reason a stale page can be told from a quiet one.
setInterval(() => {
  if (report) el.fresh.textContent = freshness(at);
}, 15_000);
