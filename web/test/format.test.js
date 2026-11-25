/**
 * The wording the two report pages share.
 *
 * Pinned in node because these are pure functions and because a Thai date written twice is a date
 * that will disagree with itself — which is what `report-format.js` was extracted to prevent. The
 * DOM halves of that module (`phrases`, `meter`, `empty`) are not here: they are checked by driving
 * the real pages, like every other browser-only rule in this project.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { baht, thaiDate, freshness } from '../public/report-format.js';

test('a Thai date carries its weekday and the Buddhist year', () => {
  // 2026-11-20 is a Friday. The weekday is on it because an event is read against the same
  // weekday — a Saturday's takings mean nothing beside a Tuesday's.
  assert.equal(thaiDate('2026-11-20'), 'ศ. 20 พ.ย. 2569');
  assert.equal(thaiDate('2026-01-01'), 'พฤ. 1 ม.ค. 2569');
  assert.equal(thaiDate('2026-12-31'), 'พฤ. 31 ธ.ค. 2569');
});

test('the year comes off for the near end of a range, where it would only repeat', () => {
  assert.equal(thaiDate('2026-11-20', { year: false }), 'ศ. 20 พ.ย.');
});

test('a day string is read in UTC, so no timezone can move it', () => {
  // ⚠️ The whole reason this is `Date.UTC` and not `new Date(day)`: the string is a calendar date
  // the server already decided (the trading day starts at 07:00 Bangkok), and parsing it in the
  // browser's own zone moves it a day either side of the date line. Pinned by running the same
  // date under two extreme zones — the result must not move.
  const first = thaiDate('2026-11-20');
  const originalTz = process.env.TZ;
  try {
    for (const tz of ['Pacific/Kiritimati', 'Pacific/Midway', 'UTC']) {
      process.env.TZ = tz;
      assert.equal(thaiDate('2026-11-20'), first, tz);
    }
  } finally {
    if (originalTz === undefined) delete process.env.TZ;
    else process.env.TZ = originalTz;
  }
});

test('a missing or malformed day is left alone rather than drawn as NaN', () => {
  assert.equal(thaiDate(''), '');
  assert.equal(thaiDate(null), '');
  assert.equal(thaiDate('rubbish'), 'rubbish');
});

test('money is thousands-separated, because 12450 and 1245 are one glance apart', () => {
  assert.equal(baht(12450), '12,450');
  assert.equal(baht(0), '0');
  assert.equal(baht(null), '0');
  assert.equal(baht(-34), '-34', 'a discount line is money going out, and still a figure');
});

test('under half a minute the page says so plainly rather than counting', () => {
  // A number ticking every second is read as the takings themselves moving.
  const now = 1_000_000_000;
  assert.equal(freshness(now - 5_000, now), 'ล่าสุดเมื่อครู่นี้');
  assert.equal(freshness(now - 45_000, now), 'ล่าสุด 1 นาทีที่แล้ว');
  assert.equal(freshness(now - 300_000, now), 'ล่าสุด 5 นาทีที่แล้ว');
  assert.equal(freshness(0, now), '', 'nothing has arrived yet, so there is nothing to date');
});
