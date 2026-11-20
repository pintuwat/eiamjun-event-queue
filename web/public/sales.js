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
  el.fresh.textContent = freshness();

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

/**
 * How old the figures on screen are.
 *
 * A report that quietly stops updating looks exactly like a stall that has stopped selling, and
 * only one of those is worth reacting to — the same reasoning that puts the board's link state in
 * words. Under half a minute it says so plainly rather than counting, because a number that ticks
 * every second is read as the takings changing.
 */
function freshness() {
  if (!at) return '';
  const seconds = Math.round((Date.now() - at) / 1000);
  if (seconds < 30) return 'ล่าสุดเมื่อครู่นี้';
  if (seconds < 90) return 'ล่าสุด 1 นาทีที่แล้ว';
  return `ล่าสุด ${Math.round(seconds / 60)} นาทีที่แล้ว`;
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

    const meter = document.createElement('div');
    meter.className = 'meter';
    const fill = document.createElement('div');
    fill.className = 'meter-fill';
    fill.style.width = `${Math.max(2, Math.round((item.qty / top) * 100))}%`;
    meter.append(fill);

    const money = document.createElement('div');
    money.className = 'item-money';
    // Both figures, because they answer different questions: how many to prep more of, and where
    // the day's money actually came from. A dear item sells few and earns a lot.
    money.textContent = `${baht(item.takings || 0)} บาท`;

    row.append(head, meter, money);
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

    const meter = document.createElement('div');
    meter.className = 'meter';
    const fill = document.createElement('div');
    fill.className = 'meter-fill';
    fill.style.width = `${Math.max(2, Math.round((hour.takings / top) * 100))}%`;
    meter.append(fill);

    const money = document.createElement('span');
    money.className = 'hour-money';
    money.textContent = baht(hour.takings);

    row.append(clock, meter, money);
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

function empty(text) {
  const p = document.createElement('p');
  p.className = 'empty';
  p.textContent = text;
  return p;
}

/** Thousands separated, because 12450 and 1245 are one glance apart on a figure being read fast. */
function baht(n) {
  return Number(n || 0).toLocaleString('en-US');
}

/**
 * The clock the stall keeps, never the one this phone is set to.
 *
 * The same rule the server follows for the trading day: a browser abroad, or a phone whose clock
 * has drifted, must not restate when a sale happened.
 */
function shopTime(at) {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Bangkok',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(new Date(at));
}

/** "ศ. 20 พ.ย. 2569" — the same wording the board puts on its own day. */
function thaiDate(day) {
  if (!day) return '';
  const [year, month, date] = day.split('-').map(Number);
  const months = ['ม.ค.', 'ก.พ.', 'มี.ค.', 'เม.ย.', 'พ.ค.', 'มิ.ย.', 'ก.ค.', 'ส.ค.', 'ก.ย.', 'ต.ค.', 'พ.ย.', 'ธ.ค.'];
  const weekdays = ['อา.', 'จ.', 'อ.', 'พ.', 'พฤ.', 'ศ.', 'ส.'];
  const atDate = new Date(Date.UTC(year, month - 1, date));
  return `${weekdays[atDate.getUTCDay()]} ${date} ${months[month - 1]} ${year + 543}`;
}

/**
 * A dish name broken between words and nowhere else — the board's rule, for the same reason.
 *
 * Thai writes no spaces inside a word, so a browser breaks it with a dictionary and "ลูกชิ้นล้วน"
 * falls as "ลูก" over "ชิ้นล้วน". `word-break: keep-all` does not stop it, so the break points are
 * chosen here: one `nowrap` span per phrase somebody typed a space between.
 */
function phrases(into, text) {
  String(text)
    .split(' ')
    .forEach((word, i) => {
      if (i) into.append(' ');
      const span = document.createElement('span');
      span.className = 'phrase';
      span.textContent = word;
      into.append(span);
    });
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
  if (report) el.fresh.textContent = freshness();
}, 15_000);
