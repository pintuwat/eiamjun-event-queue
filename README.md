# คิวอีเวนต์ — ก๋วยเตี๋ยวคลุกเอี้ยมจุ๊น

A till and a kitchen board for a food event. Two dishes, a queue ticket for the customer, and an
iPad in the kitchen that says what has come in and lets the cook tap **พร้อมส่ง**.

A sibling of the shop's own POS, and deliberately not part of it: its own repository, its own
Gradle build, its own package (`com.eiamjun.event`), its own database and its own server. Nothing
here can reach the shop's register, and both apps can sit on one terminal at once.

```
  Sunmi V2 Pro ──built-in printer──►  บัตรคิว + ใบเสร็จ   (works with no network at all)
        │
        └── POST /api/orders ──►  kitchen server ──►  iPad board ──► พร้อมส่ง ──┐
                                                                                │
        ◄────────────────── GET /api/orders/ready ◄──────────────────────────────┘
```

---

## Status

**Builds, tests pass, APK produced, and the whole loop has been driven on a real V2 Pro** — order →
payment QR → queue ticket → kitchen board → พร้อมส่ง → back on the till, both directions, including
the board's undo.

⚠️ **Nothing has been printed on paper yet.** The driver is the shop's own, proven
`SunmiPrinterAdapter`, so Thai and the price column are not in doubt — but the queue number is a new
kind of line (`TicketLine.Banner`, drawn at a chosen 120 dots) and **no one has met this paper**.
That size is a guess with a control next to it: ตั้งค่า → **ขนาดเลขคิวบนบัตร**, ±1 level at a time.

⚠️ **The prices in the app are placeholders.** The dishes and options are what the owner described;
the numbers are not. ตั้งค่า → แก้ไขเมนู changes every one of them without a rebuild.

---

## Before the first event

1. **แก้ไขเมนู — put the real prices in.** ก๋วยเตี๋ยวคลุก is seeded at 50, แคปป๊อป at 20,
   เพิ่มแคปป๊อป and พิเศษ at +10. All editable, including adding or removing options.
2. **ตั้งค่า → QR ร้านค้า** — paste the shop's ถุงเงิน / Thai QR payload.
   **Then print one receipt and scan it with a real banking app.** This project has had three QR
   bugs that each produced a code which looked perfect and scanned as nothing.
3. **ตั้งค่า → ที่อยู่เซิร์ฟเวอร์** — where the kitchen board runs. Leaving it blank is allowed:
   the till still takes money and prints tickets, and the orders queue up until it is set.
4. **ขนาดเลขคิวบนบัตร** — พิมพ์บัตรตัวอย่าง, look at the paper, adjust, repeat.

---

## Running the kitchen server

Zero dependencies — Node standard library only, no build step.

```bash
node web/server/index.js
```

It prints the LAN URLs to open on the iPad. Put the page on the home screen: it is built for that
(standalone, its own icon, safe-area insets).

The order book is SQLite at `web/data/event.db` by default. Point it somewhere backed up:

```bash
DB_FILE=/path/event.db node web/server/index.js
```

Tests — the rules **and** the HTTP routes in front of them:

```bash
cd web && npm test
```

### On Coolify

The Dockerfile is at the repository root, so the defaults work — point Coolify at this repository
and **mount a persistent volume at `/data`**. Without the volume a redeploy replaces the container
and takes the order book with it; the app recreates an empty one, so nothing errors and a service
would simply start with every outstanding queue number gone.

Set `AUTH_USER` and `AUTH_PASSWORD`. On Coolify this is reachable from the internet by default,
and without them anyone with the URL can read the orders *and post fabricated ones onto the
board* — a kitchen cooking food nobody paid for costs real money.

Then point the terminal at the **https** URL in ตั้งค่า → ที่อยู่เซิร์ฟเวอร์. The terminal sends its
password as a Basic header on every request; over plain http that is in the clear on whatever
network the stall is using. The session cookie the iPad gets follows the same rule on its own —
`Secure` when the request arrived over TLS, plain when it did not, so the one build works behind
Coolify's proxy and on a laptop at the stall.

Three files make up the order book — `event.db`, `event.db-wal` and `event.db-shm` — and they live
beside each other, so the volume has to be mounted at the **directory** (`/data`), never at the
`.db` file. The `-wal` is routinely the largest of the three; a backup that copies only the `.db`
copies a database missing its most recent writes.

---

## The Android app

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew test              # ticket layout, PromptPay fixtures, the QR encoder
```

JDK 21 at `~/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home`. `minSdk` is 23 and must
stay there — the Sunmi V1s is the floor.

`applicationId` is `com.eiamjun.event`, different from the shop's till on purpose: both can sit on
one terminal, so this can be tried without taking the shop's register off the device.

Bump `versionCode` in `app/build.gradle.kts` for every build that goes on a device, and keep the
same signing keystore — an update signed with a different key cannot install over the old one, and
uninstalling **wipes the local database**, which during a service means every outstanding queue
number is gone.

---

## How it works

**The till is the source of truth for an order; the kitchen owns one flag.**

The terminal issues the queue number, records the order, prints the ticket and decides when it has
been collected. The kitchen board writes exactly one thing, พร้อมส่ง, and the till reads that list
as the whole truth for its open orders — which is what makes the board's undo work, and what means
no field is written by both sides. There is nothing to reconcile because there is no conflict to
have.

**Printing is local; the board is shared.** The Sunmi's built-in head is reachable only by native
code on that terminal — no browser, no bridge, no server. So the till keeps taking money and
printing queue tickets when the wifi is gone, and the orders flush out of an outbox when it
returns. Everything else about the shape follows from that.

**The queue number is issued on the device.** The ticket prints with the customer standing there
and cannot wait on a round trip. That is only safe with **one** terminal — a second one would issue
the same numbers, and two customers holding ticket 14 is the one failure this app cannot recover
from. A second terminal needs its own name in ตั้งค่า before its first launch.

**Numbers restart at 07:00, not midnight**, so a stall still serving at 00:30 belongs to the evening
that is still going on. ตั้งค่า → เริ่มนับคิวใหม่ resets by hand, for an event that runs two
sessions in one day.

**Nothing on the queue ticket but the number.** The kitchen reads the order off the iPad; a ticket
listing the food invites the customer to check it at the counter, which is the queue this app
exists to shorten. The **receipt** — printed when they collect, if they want one — carries the
number, the items, the options and the prices.

---

## What is deliberately not here

- **No cash tender or change calculation.** The owner asked for QR; เงินสด records the payment kind
  so the receipt carries no code, and the sum is not worked out.
- **No reporting beyond a day's takings.** The shop's own server grew สรุปยอดขาย over months; an
  event runs for a weekend.
- **No creating option *groups* in the app.** Prices, names, options and on-sale all edit on the
  device; the questions the stall asks were decided when the menu was written.
- **No "edit the order after the kitchen has it".** An order can be cancelled, which tells the
  board; silently rewriting food a cook may already be making is not something to fake.
