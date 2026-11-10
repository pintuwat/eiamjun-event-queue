/**
 * The kitchen board.
 *
 * It polls, it draws, and it has exactly one button. Everything else about an order belongs to the
 * terminal — which is what means the two sides never have to be reconciled, so this page can be
 * this small and still be trusted during a service.
 *
 * Plain ES modules, no framework, no build step: this is served off a laptop or a small container
 * at a stall, and it has to keep working when nobody is around to rebuild it.
 */

const POLL_MS = 4000;
/** Minutes after which a card is marked as having waited. */
const LATE_MINUTES = 12;

const el = {
  day: document.getElementById('day'),
  counts: document.getElementById('counts'),
  link: document.getElementById('link'),
  waiting: document.getElementById('cards-waiting'),
  ready: document.getElementById('cards-ready'),
  tallyWaiting: document.getElementById('tally-waiting'),
  tallyReady: document.getElementById('tally-ready')
};

/** The last picture that arrived. A dropped poll redraws this rather than blanking the board. */
let board = { day: '', orders: [] };
/** Queue numbers whose button has been pressed and whose answer has not come back yet. */
const pending = new Set();

async function poll() {
  try {
    const res = await fetch('/api/orders', { headers: { accept: 'application/json' } });
    if (res.status === 401) {
      // The session expired while the board sat on a stand. Go and get a new one rather than
      // showing an empty kitchen.
      location.href = '/login';
      return;
    }
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    board = { day: data.day, orders: data.orders || [] };
    setLink(true);
  } catch {
    // Deliberately keeps the last board up. An empty kitchen and an unreachable server look
    // identical on a screen, and only one of them means "nothing to cook".
    setLink(false);
  }
  render();
}

function setLink(up) {
  el.link.textContent = up ? 'ต่อเซิร์ฟเวอร์ได้' : 'ต่อเซิร์ฟเวอร์ไม่ได้';
  el.link.className = 'link-state ' + (up ? 'up' : 'down');
}

function render() {
  el.day.textContent = board.day ? thaiDate(board.day) : '';

  const waiting = board.orders.filter((o) => o.status === 'waiting');
  const ready = board.orders.filter((o) => o.status === 'ready');

  el.tallyWaiting.textContent = String(waiting.length);
  el.tallyReady.textContent = String(ready.length);
  el.counts.textContent = `${board.orders.length} คิวค้าง`;

  // Oldest first in กำลังทำ: a kitchen cooks in the order the orders arrived, and a board that
  // sorts any other way is a board that has to be reasoned about.
  draw(el.waiting, waiting.sort(byOrderedAt), false, 'ไม่มีคิวรอทำ');
  // Ready ones newest first — the one just finished is the one being called.
  draw(el.ready, ready.sort((a, b) => (b.readyAt || 0) - (a.readyAt || 0)), true, 'ยังไม่มีคิวพร้อมส่ง');
}

function byOrderedAt(a, b) {
  return a.orderedAt - b.orderedAt;
}

function draw(container, orders, isReady, emptyText) {
  container.replaceChildren();
  if (orders.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'empty';
    empty.textContent = emptyText;
    container.append(empty);
    return;
  }
  for (const order of orders) container.append(card(order, isReady));
}

function card(order, isReady) {
  const minutes = Math.max(0, Math.round((Date.now() - order.orderedAt) / 60000));
  const late = !isReady && minutes >= LATE_MINUTES;

  const root = document.createElement('div');
  root.className = 'card' + (isReady ? ' ready' : '') + (late ? ' late' : '');

  const top = document.createElement('div');
  top.className = 'card-top';
  const queue = document.createElement('span');
  queue.className = 'queue';
  queue.textContent = String(order.queueNo);
  const waited = document.createElement('span');
  waited.className = 'waited' + (late ? ' late' : '');
  waited.textContent = isReady ? 'พร้อมแล้ว' : `รอ ${minutes} นาที`;
  top.append(queue);
  // Named only when there is more than one round, which is the only time it tells the cook
  // anything — and the only time two cards on this board can read the same number.
  if (order.session > 1) {
    const round = document.createElement('span');
    round.className = 'round';
    round.textContent = `รอบ ${order.session}`;
    top.append(round);
  }
  top.append(waited);

  const lines = document.createElement('div');
  lines.className = 'lines';
  for (const line of order.lines) {
    const row = document.createElement('div');
    row.className = 'line';
    const qty = document.createElement('span');
    qty.className = 'line-qty';
    qty.textContent = `${line.qty}×`;
    const body = document.createElement('div');
    body.className = 'line-body';
    const name = document.createElement('div');
    name.className = 'line-name';
    name.textContent = line.name;
    body.append(name);
    if (line.options) {
      const options = document.createElement('div');
      options.className = 'line-options';
      options.textContent = line.options;
      body.append(options);
    }
    if (line.note) {
      const note = document.createElement('div');
      note.className = 'line-note';
      note.textContent = line.note;
      body.append(note);
    }
    row.append(qty, body);
    lines.append(row);
  }

  const button = document.createElement('button');
  if (isReady) {
    button.className = 'undo';
    button.textContent = 'ยังไม่พร้อม';
    button.addEventListener('click', () => setReady(order, false, button));
  } else {
    button.textContent = 'พร้อมส่ง';
    button.addEventListener('click', () => setReady(order, true, button));
  }
  button.disabled = pending.has(key(order));

  root.append(top, lines, button);
  return root;
}

/**
 * What tells two cards apart.
 *
 * The round is in it because the till can be told to start counting from 1 again mid-service, so
 * one board can show two #3s — and the local move below finds a card by this key.
 */
function key(order) {
  return `${order.source}/${order.session || 1}#${order.queueNo}`;
}

/**
 * The one write this page makes.
 *
 * The card is moved **locally first** and the poll corrects it afterwards: a cook who taps and
 * watches nothing happen for four seconds taps again, and the second tap is what puts two of the
 * same bowl on a pass. The button is disabled until the answer lands, for the same reason.
 */
async function setReady(order, ready, button) {
  const id = key(order);
  pending.add(id);
  button.disabled = true;

  const local = board.orders.find((o) => key(o) === id);
  if (local) {
    local.status = ready ? 'ready' : 'waiting';
    local.readyAt = ready ? Date.now() : null;
  }
  render();

  try {
    const res = await fetch(`/api/orders/${ready ? 'ready' : 'unready'}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        day: board.day,
        queueNo: order.queueNo,
        session: order.session,
        source: order.source
      })
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    setLink(true);
  } catch {
    setLink(false);
  } finally {
    pending.delete(id);
    // Straight back to the server's picture, so a failed tap does not leave the board telling the
    // cook something the till will never hear about.
    poll();
  }
}

/** "จ 10 พ.ย. 2569" — the weekday first, which is how a stall's staff say which day it is. */
function thaiDate(day) {
  const [year, month, date] = day.split('-').map(Number);
  const months = ['ม.ค.', 'ก.พ.', 'มี.ค.', 'เม.ย.', 'พ.ค.', 'มิ.ย.', 'ก.ค.', 'ส.ค.', 'ก.ย.', 'ต.ค.', 'พ.ย.', 'ธ.ค.'];
  const weekdays = ['อา.', 'จ.', 'อ.', 'พ.', 'พฤ.', 'ศ.', 'ส.'];
  const at = new Date(Date.UTC(year, month - 1, date));
  return `${weekdays[at.getUTCDay()]} ${date} ${months[month - 1]} ${year + 543}`;
}

poll();
setInterval(poll, POLL_MS);
// Coming back to a board that has been in a pocket or asleep should not mean waiting out a poll.
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) poll();
});
// The waited-minutes figure is the only thing on the card that changes without a poll, so it is
// redrawn on its own minute — the board is read for exactly this when a queue backs up.
setInterval(() => render(), 30_000);
