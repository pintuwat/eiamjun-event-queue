# คิวอีเวนต์ — project memory

A till and a kitchen board for a **food event**, built for the same owner and the same Sunmi V2 Pro
as the shop's POS one directory up. Two dishes, a queue ticket, an iPad in the kitchen.

This file is the handoff context. The shop's own POS lives in a separate, private repository, and
its handoff file is worth reading for the hardware and printer lessons — most of them were paid for
there and are inherited here rather than re-learned. Where this file says "the shop's till", that
is what it means.

---

## Status — read this first

**Builds, 33 Android tests and 28 Node tests pass, and the whole loop has been driven on a real
Sunmi V2 Pro** over USB: order → payment QR → queue ticket → kitchen board →
พร้อมส่ง → back on the till, and the board's undo back the other way.

⚠️ **Never printed on paper.** The driver is the shop's proven `SunmiPrinterAdapter`, copied across
unchanged, so Thai and the price column are not in question. What is: the **queue number**, which
is a new line type (`TicketLine.Banner`) drawn at a chosen 120 dots. Nobody has met this paper.
ตั้งค่า → ขนาดเลขคิวบนบัตร moves it without a build, which is the honest way to settle a size.

⚠️ **The seeded prices are placeholders** (50 / 20 / +10 / +10). The dishes and options are what the
owner described; the numbers were never given. `SeedData` seeds only an empty database, and
แก้ไขเมนู changes all of it on the device.

### Shape of the thing

```
app/   Kotlin · Compose · Room · Hilt, package com.eiamjun.event, minSdk 23
web/   Node stdlib only, no build step — kitchen board + order book (node:sqlite)
```

---

## The one decision everything else follows from

**The terminal owns the order; the kitchen owns one flag.**

The till issues the queue number, records the order, prints, and decides when it has been
collected. The board writes exactly one thing — ready / not ready — and `applyReadySet` takes that
list as **the whole truth** for this day's open orders.

That absoluteness is what makes the board's ยังไม่พร้อม undo reach the till, and it is only safe
because no field is written by both sides. There is no conflict to resolve because there is no
second writer. Anything added later that both sides could write breaks this and needs a different
answer.

Two consequences worth keeping:

- A **collected** order is not in `OrderDao.openIdentities`, so a stale poll can never drag a bag
  the staff have handed over back onto the board. The guard is in the `WHERE` clause of
  `markReady` / `markWaiting`, not in a view model — the shop's till learned that a guard above the
  DAO is one careless call site away from being walked around.
- The till's own **พร้อม** override (for an unreachable iPad) writes locally *and queues an outbox
  message*. Without the queue, the next successful poll would undo it. `QueueViewModel.poll()`
  therefore **flushes before it reads**; reversing those two lines reintroduces the bug.

---

## Printing

`printer/` is the shop's printer layer copied in with only the `package` line changed — `Ticket`,
`ReceiptRowBitmap`, `SunmiPrinterAdapter`, `PromptPayQr`, `QrMatrix`, `TicketText`, `EscPos`,
`PreviewPrinter`. **Copied, not depended on**, so a change made on the morning of an event cannot
reach the shop's register. The two will drift; that is the trade and it was taken deliberately.

The parent `CLAUDE.md` has the full account of why the price column is **drawn as a bitmap** and
the seven things that were tried first. Don't repeat any of them. The short version: no font this
head can reach is monospace for Thai and latin at once, and the firmware font cannot spell Thai at
all, so anything that must land in a particular place on the paper is measured in the font that
draws it and sent as a picture.

**One thing is new here: `TicketLine.Banner`.** A doubled printer cell tops out near 48 dots, and a
queue number is held up and read across a counter — it wants a third of the 58mm width per digit.
So it is drawn: `ReceiptRowBitmap.banner` solves the text size for the **cap height of the string**
(a line of digits has no descender, and sizing by font metrics leaves a third of the band empty),
shrinks it if it would overrun the paper, and centres it. Additive — the existing branches are
untouched, and the ESC/POS renderer falls back to double-everything because a firmware font has
one cell and that is the largest thing it can say.

**Two documents, and neither can be sent at the other's size.** The size rides on the `Ticket`
(`font = ReceiptFont.LARGE` for the ticket, none for the receipt, whose layout is settled and whose
default is the driver's). `EventTicketFormatterTest` pins that.

**The receipt puts options on their own line, and the shop's does not.** Forced, in both
directions: this menu has one dish, so the options are the only thing telling two lines apart —
and a `KeyValue` row is **trimmed from the end** to keep the price on the right edge, so
"ก๋วยเตี๋ยวคลุก เส้นหมี่ · เผ็ดน้อย · เพิ่มแคปป๊อป (ไม่ใส่ถั่ว)" lost everything that mattered and
read as a plain bowl at an odd price. The shop's rule against a second line was about *its*
receipt, where the kitchen slip carries the options; this event prints no kitchen slip at all.

**Nothing but the number on the queue ticket** — no dish names, no prices. The kitchen reads the
order off the iPad, and a ticket listing the food invites the customer to check it at the counter,
which is the queue this app exists to shorten. The item **count** is on it, because that is what
stops a two-bag customer walking off with one.

---

## Queue numbers

Issued on the device (`QueueCounter`), restarting at 1 each event day, where the day begins at
**07:00 Asia/Bangkok** (`EventDay`, and `businessDay()` in `web/server/db.js` — the same rule in
both). The ticket prints with the customer standing there and cannot wait on a round trip.

⚠️ **That is only safe with one terminal.** A second would issue the same numbers, and two
customers holding ticket 14 is the one failure this app cannot recover from. `AppSettings.deviceName`
defaults to the literal `"pos"` and **never `Build.MODEL`** — every Sunmi the owner has reports
`V2_PRO-ST`, so a model-derived default means a second terminal introduces itself under a name that
already means the first. Name a second terminal before its first launch.

`(day, source, session, queueNo)` is an order's identity on the server, which is also what makes the
outbox's retries idempotent: a push that timed out *after* the write lands on the same row rather
than putting a second card on the board. A replay deliberately does **not** reset the status — the
kitchen may have tapped พร้อมส่ง in between, and putting the card back would have the number
called twice.

ตั้งค่า → เริ่มนับคิวใหม่ resets by hand, behind a confirmation, because an event can run two
sessions in one calendar day and no automatic rule could know which.

**A reset opens a numbering round, because the number alone was never an identity.** Found on the
terminal, in the event log: `UNIQUE constraint failed: orders.day, orders.queueNo`. The local
`orders` table was unique on `(day, queueNo)` and the server's on `(day, source, queue_no)`, so
resetting the counter to 1 on a day that already held #1 collided — the insert threw, `charge`
failed, and the staff got "บันทึกออเดอร์ไม่สำเร็จ" with no ticket, no sale and a customer standing
there. The half nobody would have seen is worse: had it not thrown locally, `recordOrder` replaces
on conflict, so the afternoon's #1 would have **overwritten a paid order** on the server. Both
outcomes landed exactly on the two-sessions-in-one-day case the button exists for.

So `QueueCounter` keeps a round beside the sequence, a reset steps it, and the round is carried on
`orders.session` here (`MIGRATION_1_2`) and on the server, with the unique indexes now
`(day, session, queueNo)` and `(day, source, session, queue_no)`. Anything cheaper either loses the
morning's orders or loses the button.

The rule is in `QueueNumbering`, as a pure function of what is stored, **separated from the
counter so it can be tested without a device** — this arithmetic reached a real counter once, and a
rule that only runs on hardware is one nobody checks. A reset on a day this terminal has not sold
on yet stays at round 1: there is no #1 to step past, so it claims nothing.

⚠️ **The round reaches four places that draw a number, and every one of them had to be told.**
A queue number is what a person matches against a ticket, so wherever two cards can read the same
number the round is named — the kitchen board's card, the terminal's queue card, the order sheet,
and the cancel confirmation. It is drawn **only past round 1**, because a label that is always
there is one nobody reads. The sorts carry it too (`session` before `queueNo`, on both sides), or
the morning's leftovers interleave with an afternoon whose numbers have started again.

⚠️ **A status message may legitimately carry no round, and that is not the same as round 1 for a
*lookup*.** An order push always names its round — it is being written. A ready/collected/cancelled
message may have been sitting in an outbox since before the column existed, so `matchSession` makes
an absent round match the number in **any** round, while `sessionOf` defaults a written order to 1.
Collapsing the two would have such a message silently miss its order.

The poll's `ready` list is `[{session, queueNo}]` rather than bare numbers, and `KitchenSync`
accepts both shapes — the bare one only means the minutes between deploying the server and
installing the terminal. **Deploy `web/` before the APK**: until the server has the column it drops
the field and the replace-on-conflict above is still live.


---

## The outbox

Every message bound for the board is written to `outbox` first and sent after
(`OrderRepository.charge` writes the order and the outbox row in **one transaction**, so an order
that is recorded is always also queued). `KitchenSync.flush` drains it oldest-first and **stops at
the first failure** rather than skipping — an order and its later "collected" have to arrive in
that order, or the board is told to drop a card it was never given.

The payload is built **at the moment the thing happens**, never at send time. An order can sit in
the outbox for an hour; rebuilding it later would read today's menu and today's device name. The
shop's till once sent fifty bills under a name that had already changed, and that is exactly right
— the ticket in the customer's hand was printed under it.

`orderTime` on the server bounds what it accepts against its own clock (400 days back, a day
forward) and falls back to arrival outside that, so a terminal with a wrong clock cannot file an
order into a month nobody reads — and the order is still **recorded**, because losing a sale over a
bad clock is the worse failure.

---

## The kitchen board (`web/`)

One page, five endpoints, no framework and no build step. iPad on a stand, read from a metre away,
tapped with a wet hand — every size on it is larger than a web page's would be, and the พร้อมส่ง
button is 60px rather than 44.

**A dropped poll redraws the last picture rather than blanking the board.** An empty kitchen and an
unreachable server look identical on a screen, and only one of them means "nothing to cook" — so
the link state is said in words at the top.

**The card moves locally first and the poll corrects it.** A cook who taps and watches nothing
happen for four seconds taps again, and the second tap is what puts two of the same bowl on a pass.

**กำลังทำ is oldest first; พร้อมส่ง is newest first.** A kitchen cooks in the order the orders
arrived; the one just finished is the one being called. A card past 12 minutes gets a gold edge —
not red, because red on this board means a note the cook must not miss.

⚠️ **The narrow layout's media query has to stay at the end of `app.css`.** It overrides `.cards`
and `.column` at equal specificity, so source order is the whole of what makes it win. Placed
before them (where it was first written) a phone got two stacked internal scrollers with about a
card and a half each, and the board looked like it held two orders when it held eight.

Auth is the shop's pattern: Basic for the terminal, a **signed session cookie** for the browser,
and deliberately **no `WWW-Authenticate`** — that header is what makes Safari put up its own
password box, and a page opened from the iPad's home screen has its own credential jar, so the box
came back on every launch. The icons and `/api/health` are open (`OPEN_PATHS`), because iOS fetches
a home-screen icon in a request carrying no session and Coolify's probe carries no credentials.

**A status call for an order the server has never seen answers `200 {changed:false}`, not 404.** The
till may mark something collected while its own order push is still in the outbox, and a 404 would
make that outbox retry forever.

---

## Testing

- `./gradlew test` — the ticket layouts, the PromptPay/ถุงเงิน fixtures and the QR encoder, the
  last two carried over from the shop's till so the money-critical part stays pinned.
- `cd web && npm test` — `db.test.js` for the rules, **`api.test.js` for the routes over real
  HTTP**. That second file exists because of a bug in the shop's server that shipped and stayed
  invisible for weeks: twelve green tests called the rule directly while the route in front of it
  answered 400 to everything. **Any new endpoint needs a test through the server.**

⚠️ **Verify a QR with a real banking app, never with your own reader.** Three bugs got through the
shop's encoder and each produced a code that looked right and scanned as nothing.

---

## Conventions

Same as the parent: comments explain **why**; user-facing strings are Thai, code and commits
English; flat lists and grids, no nested scrolling; no photos on menu items; and when a decision
has a trade-off, it goes in this file rather than staying implicit.
