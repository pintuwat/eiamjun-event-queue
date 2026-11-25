/**
 * The wording both report pages share — money, Thai dates, the stall's clock, and the one rule
 * that stops a dish name falling in half.
 *
 * Extracted when สรุปตามช่วงเวลา arrived beside รายงานยอดขาย and wanted all of it. A Thai date
 * written twice is a date that will disagree with itself — one page saying "ศ. 20 พ.ย. 2569" and
 * the other "20/11/2569" is the drift this project keeps recording — and `phrases` is a rule about
 * how Thai breaks, which is exactly the kind of thing that must exist once.
 *
 * Pure functions and no module-level DOM, so a node test can pin the dates and the money without a
 * browser. `phrases` touches `document` inside itself only, which is what keeps that true.
 */

/** Thousands separated, because 12450 and 1245 are one glance apart on a figure read fast. */
export function baht(n) {
  return Number(n || 0).toLocaleString('en-US');
}

const MONTHS = ['ม.ค.', 'ก.พ.', 'มี.ค.', 'เม.ย.', 'พ.ค.', 'มิ.ย.', 'ก.ค.', 'ส.ค.', 'ก.ย.', 'ต.ค.', 'พ.ย.', 'ธ.ค.'];
const WEEKDAYS = ['อา.', 'จ.', 'อ.', 'พ.', 'พฤ.', 'ศ.', 'ส.'];

/**
 * "ศ. 20 พ.ย. 2569" — the same wording the board puts on its own day.
 *
 * Built from the `YYYY-MM-DD` string in **UTC**, never from a local `new Date(day)`: the string is
 * a calendar date the server already decided (the trading day starts at 07:00 Bangkok), and
 * parsing it in the browser's own zone moves it a day either side of the date line.
 *
 * The weekday is on it because an event is read against the same weekday — a Saturday's takings
 * mean nothing beside a Tuesday's.
 */
export function thaiDate(day, { year = true } = {}) {
  if (!day) return '';
  const [y, m, d] = String(day).split('-').map(Number);
  if (!y || !m || !d) return String(day);
  const at = new Date(Date.UTC(y, m - 1, d));
  const tail = year ? ` ${y + 543}` : '';
  return `${WEEKDAYS[at.getUTCDay()]} ${d} ${MONTHS[m - 1]}${tail}`;
}

/**
 * The clock the stall keeps, never the one this phone is set to.
 *
 * The same rule the server follows for the trading day: a browser abroad, or a phone whose clock
 * has drifted, must not restate when a sale happened.
 */
export function shopTime(at) {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Bangkok',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(new Date(at));
}

/**
 * A name broken between words and nowhere else — the kitchen board's rule, for the same reason.
 *
 * Thai writes no spaces inside a word, so a browser breaks it with a dictionary and "ลูกชิ้นล้วน"
 * falls as "ลูก" over "ชิ้นล้วน". `word-break: keep-all` does not stop it, so the break points are
 * chosen here: one `nowrap` span per phrase somebody typed a space between.
 */
export function phrases(into, text) {
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

/**
 * How old the figures on screen are.
 *
 * A report that quietly stops updating looks exactly like a stall that has stopped selling, and
 * only one of those is worth reacting to — the same reasoning that puts the board's link state in
 * words. Under half a minute it says so plainly rather than counting, because a number that ticks
 * every second is read as the takings changing.
 */
export function freshness(at, now = Date.now()) {
  if (!at) return '';
  const seconds = Math.round((now - at) / 1000);
  if (seconds < 30) return 'ล่าสุดเมื่อครู่นี้';
  if (seconds < 90) return 'ล่าสุด 1 นาทีที่แล้ว';
  return `ล่าสุด ${Math.round(seconds / 60)} นาทีที่แล้ว`;
}

/** A meter scaled to the best row, with a floor so a tiny one is still visible. */
export function meter(value, top) {
  const bar = document.createElement('div');
  bar.className = 'meter';
  const fill = document.createElement('div');
  fill.className = 'meter-fill';
  fill.style.width = `${Math.max(2, Math.round((Number(value) / (top || 1)) * 100))}%`;
  bar.append(fill);
  return bar;
}

/** The empty-state line every panel on these two pages uses. */
export function empty(text) {
  const p = document.createElement('p');
  p.className = 'empty';
  p.textContent = text;
  return p;
}
