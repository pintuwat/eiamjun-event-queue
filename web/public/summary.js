/**
 * สรุปยอดขายตามช่วงเวลา — what the stall made over a span of days, and which of them was the good one.
 *
 * The day report next door (`/sales`) is read **during** a service and is always about the day in
 * progress. This one is read afterwards, and asks the questions a day cannot answer: what did the
 * event make, which day carried it, and what should be prepped more of next time. Same stylesheet,
 * same wording module, different question — not a filter bolted onto the other page, because the
 * two return different shapes and one screen quietly meaning either is a figure read wrong.
 *
 * Read-only like its neighbour, and that is the whole contract: it writes nothing to the order
 * book, so it can sit open on a phone through a whole service without any risk to one.
 *
 * **Not linked from the kitchen board**, for the reason the day report is not: that screen is
 * worked with both hands full and a way off it is a way to lose the queue. Typed once as
 * `/summary` and bookmarked.
 */
import { baht, thaiDate, freshness, phrases, meter, empty } from '/report-format.js';

/**
 * Polled only while the chosen period contains today — see [tick].
 *
 * Slower than the day report's 6s, because most of what this page shows is about days that are
 * over and cannot change. A period of finished days is fetched once and left alone.
 */
const POLL_MS = 30_000;

const el = {
  link: document.getElementById('link'),
  presets: document.getElementById('presets'),
  from: document.getElementById('from'),
  to: document.getElementById('to'),
  words: document.getElementById('period-words'),
  takings: document.getElementById('takings'),
  bills: document.getElementById('bills'),
  average: document.getElementById('average'),
  days: document.getElementById('days'),
  best: document.getElementById('best'),
  cancelled: document.getElementById('cancelled'),
  fresh: document.getElementById('fresh'),
  dayRows: document.getElementById('day-rows'),
  items: document.getElementById('items'),
  hours: document.getElementById('hours')
};

/** The last figures that arrived. A dropped poll redraws these rather than blanking the page. */
let report = null;
/** When they arrived, so a page that has quietly stopped updating says so instead of lying. */
let at = 0;
/**
 * The period on screen, and the **only** state this page holds.
 *
 * The preset chips are derived from it rather than being a second setting beside it: a chip is lit
 * when it would set exactly this period. The alternative — a chosen preset *and* two dates — can
 * hold "7 วัน selected, dates saying something else", which is a state nobody could read off the
 * screen and which the two halves would then have to agree about. Same shape as the board's
 * jingle control, for the same reason.
 *
 * Empty until the server answers: the default period is the whole ledger, and only the server
 * knows what that is.
 */
let period = { from: '', to: '' };

/**
 * Both read from the server and never worked out here.
 *
 * The trading day starts at 07:00 Bangkok, so "today" is not the browser's idea of today — and
 * this page may be open on a phone abroad or on a clock that has drifted. `span` is what bounds
 * the date fields and what "ทั้งหมด" means.
 */
let today = '';
let span = { first: null, last: null, days: 0 };

/** Calendar arithmetic on `YYYY-MM-DD`, in UTC, because that string is a date and not an instant. */
function addDays(day, delta) {
  const [y, m, d] = day.split('-').map(Number);
  const at = new Date(Date.UTC(y, m - 1, d + delta));
  return at.toISOString().slice(0, 10);
}

function monthStart(day) {
  return `${day.slice(0, 7)}-01`;
}

/**
 * The four periods worth one tap, built from the server's own today.
 *
 * ⚠️ `ทั้งหมด` is the page's default and the reason it is useful at all: an event's order book is
 * a handful of trading days, and "how did the event go" is what this is opened with. A page that
 * opened on today would say less than the live report already does, better.
 */
function presets() {
  if (!today) return [];
  return [
    { key: 'today', label: 'วันนี้', from: today, to: today },
    { key: 'week', label: '7 วัน', from: addDays(today, -6), to: today },
    { key: 'month', label: 'เดือนนี้', from: monthStart(today), to: today },
    { key: 'all', label: 'ทั้งหมด', from: span.first || today, to: span.last || today }
  ];
}

/**
 * The period lives in the URL, not in `localStorage`.
 *
 * A saved period silently back in force on the next launch is a total read as the wrong thing —
 * and the jingle's setting is saved precisely because the opposite is true of it: a wrong interval
 * is heard, a wrong period is believed. In the URL it is also shareable and bookmarkable, which is
 * how this page is opened in the first place.
 */
function writeUrl() {
  const url = new URL(location.href);
  url.searchParams.set('from', period.from);
  url.searchParams.set('to', period.to);
  history.replaceState(null, '', url);
}

function choose(from, to) {
  period = { from, to };
  writeUrl();
  paintControls();
  load();
}

async function load() {
  const query = period.from && period.to ? `?from=${period.from}&to=${period.to}` : '';
  try {
    const res = await fetch('/api/sales/period' + query, {
      headers: { accept: 'application/json' }
    });
    if (res.status === 401) {
      // The session expired while this sat in a pocket. Go and get a new one rather than drawing a
      // period with no sales in it, which is a figure somebody might believe.
      location.href = '/login';
      return;
    }
    if (!res.ok) throw new Error('HTTP ' + res.status);
    report = await res.json();
    at = Date.now();
    today = report.today || today;
    span = report.span || span;
    // The server is the authority on what period it actually answered — it swaps a backwards pair
    // and widens an absent one — so the controls follow the answer rather than the request.
    period = { from: report.from, to: report.to };
    writeUrl();
    paintControls();
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

function paintControls() {
  el.presets.replaceChildren();
  for (const preset of presets()) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className =
      'chip' + (preset.from === period.from && preset.to === period.to ? ' on' : '');
    button.textContent = preset.label;
    button.addEventListener('click', () => choose(preset.from, preset.to));
    el.presets.append(button);
  }

  // Bounded by the ledger, so a period with nothing in it cannot be picked by accident — and the
  // upper bound is today rather than `span.last`, because a day that has sold nothing yet is still
  // a day somebody may want to look at.
  for (const [input, value] of [[el.from, period.from], [el.to, period.to]]) {
    input.value = value || '';
    if (span.first) input.min = span.first;
    if (today) input.max = today > (span.last || today) ? today : span.last || today;
  }
}

/** A typed date applies at once; backwards is swapped by the server and reported back. */
function onDateInput() {
  const from = el.from.value;
  const to = el.to.value;
  if (!from && !to) return;
  choose(from || to, to || from);
}

function render() {
  if (!report) return;

  el.words.textContent = periodWords();
  el.fresh.textContent = freshness(at);

  const bills = Number(report.orders || 0);
  const takings = Number(report.takings || 0);
  el.takings.textContent = baht(takings);
  el.bills.textContent = String(bills);
  el.average.textContent = bills ? baht(Math.round(takings / bills)) : '0';
  el.days.textContent = String((report.days || []).length);

  // Named in words as well as drawn below, because "which day was the good one" is the question
  // this page is opened with and a chart is read by comparing. Hidden on a single-day period,
  // where the best day is the only day and saying so is noise.
  const best = report.best;
  el.best.textContent =
    best && (report.days || []).length > 1
      ? `วันที่ขายดีที่สุด ${thaiDate(best.day, { year: false })} · ${baht(best.takings)} บาท`
      : '';

  // Only when there is one. "ยกเลิก 0 บิล" is a line nobody reads, and a line that appears the
  // first time a bowl is cancelled is one the eye finds.
  const cancelled = report.cancelled || { orders: 0, takings: 0 };
  el.cancelled.textContent = cancelled.orders
    ? `ยกเลิก ${cancelled.orders} บิล · ${baht(cancelled.takings)} บาท (ไม่รวมในยอดข้างบน)`
    : '';

  drawDays();
  drawItems();
  drawHours();
}

/** "ศ. 20 พ.ย. 2569" for one day, and both ends for a span. */
function periodWords() {
  if (!report.from) return '';
  if (report.from === report.to) return `ยอดรวม ${thaiDate(report.from)}`;
  return `ยอดรวม ${thaiDate(report.from, { year: false })} – ${thaiDate(report.to)}`;
}

/**
 * One row per day that sold, in order, with a meter scaled to the best of them.
 *
 * ⚠️ **No row for a day the stall was shut**, which the server decides and this only draws. The
 * shop's own server pads a month with zeroes, which is right for a shop that opens daily; padding
 * a three-day event out to a calendar month draws a chart that is mostly empty and reads as a
 * collapse in takings.
 *
 * It is a list and not a link: a day's own history is read in `/sales`, which is the live report
 * and already answers it, and a second route into one question is the duplication this project
 * keeps warning about.
 */
function drawDays() {
  const days = report.days || [];
  el.dayRows.replaceChildren();
  if (!days.length) return el.dayRows.append(empty('ยังไม่มียอดขายในช่วงนี้'));

  const top = Math.max(...days.map((d) => d.takings));
  for (const day of days) {
    const row = document.createElement('div');
    row.className = 'day-row' + (report.best && day.day === report.best.day && days.length > 1 ? ' best' : '');

    const head = document.createElement('div');
    head.className = 'day-head';
    const label = document.createElement('span');
    label.className = 'day-label';
    phrases(label, thaiDate(day.day));
    const money = document.createElement('span');
    money.className = 'day-money';
    money.textContent = `${baht(day.takings)} บาท`;
    head.append(label, money);

    const foot = document.createElement('div');
    foot.className = 'day-foot';
    const count = document.createElement('span');
    count.className = 'day-count';
    // Both figures, as on every other panel here: how busy it was, and what it made.
    count.textContent = `${day.orders} บิล · ${baht(Math.round(day.takings / Math.max(1, day.orders)))} บาท/บิล`;
    foot.append(count);

    row.append(head, meter(day.takings, top), foot);
    el.dayRows.append(row);
  }
}

function drawItems() {
  const items = report.items || [];
  el.items.replaceChildren();
  if (!items.length) return el.items.append(empty('ยังไม่มีรายการขาย'));

  // Scaled to the best seller, always — a bar that rescales as the period widens reads as the
  // numbers themselves having moved.
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
    qty.textContent = String(item.qty);
    head.append(name, qty);

    const money = document.createElement('div');
    money.className = 'item-money';
    money.textContent = `${baht(item.takings || 0)} บาท`;

    row.append(head, meter(item.qty, top), money);
    el.items.append(row);
  }
}

/**
 * The hours, summed across every day in the period.
 *
 * This is the one figure that gets *better* with more days in it: one service's busy hour is one
 * service, while several events' is the hour to have a second pair of hands for. Cut on the
 * trading day rather than on midnight by the server, so 23:00 sorts before the 01:00 after it.
 */
function drawHours() {
  const hours = report.hours || [];
  el.hours.replaceChildren();
  if (!hours.length) return el.hours.append(empty('ยังไม่มียอดในช่วงนี้'));

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

/**
 * Only fetch while the period on screen can still change.
 *
 * A finished event's figures are finished; polling them is a request a minute for a number that
 * cannot move. A period containing today is live, and that is the only case worth a timer.
 */
function tick() {
  if (report && period.to >= today) load();
}

el.from.addEventListener('change', onDateInput);
el.to.addEventListener('change', onDateInput);

// A URL may carry the period — a bookmark of one particular event. Absent, the server answers
// with the whole ledger, which is this page's default.
const asked = new URL(location.href).searchParams;
if (asked.get('from') || asked.get('to')) {
  period = { from: asked.get('from') || asked.get('to'), to: asked.get('to') || asked.get('from') };
}

load();
setInterval(tick, POLL_MS);
// Back from a pocket or a locked screen: show the figures now rather than after a poll.
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) load();
});
// The "how old is this" line is the only thing that changes without a fetch, and it is the whole
// reason a stale page can be told from a quiet one.
setInterval(() => {
  if (report) el.fresh.textContent = freshness(at);
}, 15_000);
