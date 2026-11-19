# คิวอีเวนต์ — project memory

A till and a kitchen board for a **food event**, built for the same owner and the same Sunmi V2 Pro
as the shop's POS one directory up. Two dishes, a queue ticket, an iPad in the kitchen.

This file is the handoff context. The shop's own POS lives in a separate, private repository, and
its handoff file is worth reading for the hardware and printer lessons — most of them were paid for
there and are inherited here rather than re-learned. Where this file says "the shop's till", that
is what it means.

---

## Status — read this first

**Builds, 46 Android tests and 40 Node tests pass, and the whole loop has been driven on a real
Sunmi V2 Pro** over USB: order → payment QR → queue ticket → kitchen board →
พร้อมส่ง → back on the till, and the board's undo back the other way.

✅ **Both printed documents are settled on paper.** The receipt joined the queue ticket on
2026-11-18 — the shop's verdict on a sample of each at ขนาดเลขคิว −2 was "อ่านได้ดี", which
covers the three things that were open about it: the shop's name holding one line on both slips,
the 18-of-18 row printing its name whole, and the drawn queue number being big enough to match a
bowl to a card from a spike. So the receipt's shape has the same standing as the kitchen slip's
in the parent repo: **don't change it without being asked.**

✅ **Printed on paper 2026-11-13**, on the shop's own V2 Pro, and the first ticket paid for itself:
the queue number was right and the text around it broke mid-word twice. 0.3.0-ticketfit is the
answer — every line measured and centred, the size one counted step down, and a phrase space in
the footer. See **Printing**. The shop runs ขนาดเลขคิวบนบัตร at −2, so `TicketLine.Banner`'s
chosen 120 dots was close and the control is what settled it, as intended. The receipt reached
paper only on 2026-11-18, because until 0.6.1 the sample button could not print one.

**ตั้งค่า → พิมพ์ตัวอย่าง prints one of EACH document** (0.6.1). It printed only the queue ticket
until then, which left the receipt — the document 0.5.0 changed most, and the one the kitchen now
works from — with no way to see it on paper without ringing up a sale. A sample that covers half
the paper hides the half at risk.

The sample order is the shop's **worst case** rather than a tidy one: `ก๋วยเตี๋ยวคลุก x2` against
`120.-` is exactly 18 of the 18 columns this size has, so a name that is going to lose a character
loses it here. Nothing is recorded and nothing is sent — no order row, no money, no kitchen board.

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

**The queue ticket is measured end to end, and the size had to come down to let it be.**
✅ First printed 2026-11-13, and it came back with two words broken across lines:
"ก๋วยเตี๋ยวคลุกเอี๋ยม / จุ๊น" and "รอเรียกคิวแล้วมารั / บอาหาร". Both lines were `TicketLine.Text`
with `center = true`, which is `printText`, which breaks by character in a font with no fixed cell
— the shop's kitchen slip had the identical fault and `TicketLine.Wrapped` is what fixed it there.
What did not exist was a **centred** measured line: `Text` could centre and could not measure,
`wrappedLeft` could measure and had nothing to say about where the line sat. So
`ReceiptRowBitmap.wrappedCenter` centres each line on its own measured width, the way [row] puts a
price at `paper - width(price)`, and every line on the ticket is `Wrapped` now. The test pins
*none* of them being `Text`, because one short line looks safe right up until the shop renames
itself.

⚠️ **Measuring alone could not have fixed the footer, because Thai writes no spaces between
words.** "รอเรียกคิวแล้วมารับอาหาร" is one unbroken token of twenty printed columns: measuring it
says honestly that it does not fit and then splits it by measured characters, which is a mid-word
break arrived at carefully. The fix is a **phrase space** written into the string where a Thai
writer would put one — "รอเรียกคิว แล้วมารับอาหาร" — which is the only thing on that line a wrap
can land on. Any wording added to this ticket needs the same treatment, and the test pins the
space.

**And the size is half the answer** (`ReceiptFont.MEDIUM`, down from `LARGE` at the shop's word,
"ขนาดตัวอักษรใหญ่ไปหน่อย"). 24 dots is 15 columns and the shop's own name measures 16, so at that
size it could only ever wrap — no amount of typesetting makes a line fit paper it is wider than.
20→18 is the next pair **counted on paper**, which is the only kind this printer may be given, and
at 18 the name sits on one line. The queue number is untouched by any of it: a `Banner` is drawn
to a height in dots and has no font to be sized by, which is why that one is the setting
(ขนาดเลขคิวบนบัตร, the shop runs it at −2) and the body text is not — they asked for exactly that
split.

**Nothing but the number on the queue ticket** — no dish names, no prices. The kitchen reads the
order off the iPad, and a ticket listing the food invites the customer to check it at the counter,
which is the queue this app exists to shorten. The item **count** is on it, because that is what
stops a two-bag customer walking off with one.

**ตั้งค่า has no field for the payment payload — a status line only** (0.3.1). It had one, and the
shop asked for it gone the first day they looked at it. The reasoning is the till's, unchanged: the
merchant QR is the string that decides where the money lands, it is pasted once and scan-tested
once, and a text box sitting in ตั้งค่า invites an edit that sends an evening's takings somewhere
else with nothing on screen to say so. The line says whether one is set and how long it is, which
is what anybody actually needs to read off that screen.

The cost is accepted rather than unnoticed: this app syncs nothing, so the shop's till can pull a
QR from the server and this one cannot — changing the payload here means writing
`shared_prefs/eiamjun-event-settings.xml` over ADB, which is how it was set in the first place.
`SettingsViewModel.setMerchantQr` is kept and uncalled, because the way back is a field that calls
it and nothing else.

⚠️ **The payload carries no merchant name.** Decoded, it is tags 00/01/30/53/58/62/63 — no tag 59
and no 60 — so the name a banking app shows is the bank resolving Biller ID `010753700088205`
(Krungthai, i.e. ถุงเงิน) and the merchant reference inside tag 30. Neither this app nor the shop's
till can influence what that name reads as, and a scan showing "ถุงเงิน" is not evidence of a wrong
payload. What a scan does prove is that the money would reach the right merchant, which is the only
thing worth testing.

---

## What the two screens say, and how loudly

Five things the shop asked for on 2026-11-13, after a service on 0.3.1. All of them are the same
complaint: a stall is worked standing up, at arm's length, with a queue in front of you, and
anything the app only whispers is a thing nobody reads.

**A chosen option is filled in the same gold as เพิ่ม.** The stock `FilterChip` marks its selected
state with a tint a shade off the paper, which is legible at a desk and not across a counter. The
gold is not a new colour — it is `primary`, the one the เพิ่ม button on that very sheet already
carries, so the sheet has one loud colour rather than two.

**คิวถัดไป, คิวรอ and the way to the board came out of the app bar** and into a strip of their own
under it (`QueueStrip`). They were a 20sp title, a 14sp line beneath it and a text action beside
ตั้งค่า, and the bar cannot be made to carry them any bigger: a title slot caps the number, and a
word in the actions row reads as a label rather than as the way somewhere. On the strip the next
number gets 46sp, the button is a button, and the waiting count rides **on** that button because
it is what is behind it.

⚠️ The strip's labels are `softWrap = false`. Compose breaks Thai with a dictionary, and at three
digits "รออยู่ 100 คิว" is within a few dp of the button's edge — the syllable split the shop's
own pad already has a rule against. Running to the edge is allowed; falling in half is not.

**"พร้อม" was read as the kitchen's word, and it is not.** The staff took the button for the board
reporting a dish finished, when it is this terminal saying so — a different claim, made by a
different person, and the one evening it matters is the evening the board cannot be reached. It
reads **กดพร้อมเอง** now: an instruction, carrying เอง, because who is doing the declaring is the
thing to understand before pressing it. The *status* words are untouched — กำลังทำ and พร้อมส่ง
are statuses and were never the confusion.

**ส่งของแล้ว is on the queue card, not only in the sheet.** Handing food over happens to every
order, every time, and it was costing a tap into a dialog — with a queue waiting, that is the tap
the staff pay most often. Loud (filled Basil, full width) on a พร้อมส่ง card and quiet (outlined)
on one still cooking: both can be handed over, since the kitchen may simply never have marked it,
but the ready card is the one somebody is walking towards with a bowl.

**The order-printed dialog reads as two steps, and only one of them is loud at a time** (0.6.0).
Since 0.5.0 the receipt **is** the kitchen's slip, so an order is not finished until it has been
printed — and the dialog still said otherwise: พิมพ์ใบเสร็จ was one of two equal outlined buttons
under the number, labelled just "ใบเสร็จ", while the one filled button said รับออเดอร์ถัดไป. A
screen whose loud button is the way out is a screen that tells you to leave, and the kitchen is
told nothing. So the emphasis follows the work: **พิมพ์ใบเสร็จ** is filled and full width until
the paper is out, then it drops to an outline reading พิมพ์ใบเสร็จซ้ำ and **รับออเดอร์ถัดไป**
takes the colour, under a green ✓ that says the slip has gone.

**Quiet, never disabled.** The sale is recorded and the queue ticket is already in the customer's
hand before this dialog exists, so a staff member who has to move on — a jammed printer, a
customer who walked off — must be able to. A dialog that traps them at a counter with a queue is
worse than one they leave early. The colour says what to do next; it does not decide.

⚠️ **The flag is set from the print's result, never from the tap.** A receipt that did not come
out has not been given to anybody, so a failed print leaves พิมพ์ใบเสร็จ loud and says why, the
same way the queue ticket's own failure is reported. It is also scoped to the order whose dialog
is on screen: a print that finishes after the dialog is dismissed has nothing to say to the next
order's.

พิมพ์บัตรคิวซ้ำ went from half of that row to the quietest thing on the dialog, because it is only
pressed when the queue ticket itself jammed — rare, next to a step that now happens every time.

**ยกเลิก stays behind the dialog, and that is the line between them.** It voids an order already
paid for, with no undo, and a list read at speed is the last place it belongs. The sheet keeps its
own ส่งของแล้ว, for someone who opened a card to read the lines back.

**Checked on the real V2 Pro without ringing up a sale.** Two throwaway queues were written
straight into the device's database — all three SQLite files pulled and integrity-checked first,
then restored and verified row for row — so nothing printed, no money was recorded and nothing
reached the kitchen server. ⚠️ Worth knowing for the next time: a staged `ready` row came back as
`waiting` within seconds, because `poll()` takes the kitchen's list as the whole truth for that
flag. That is the documented behaviour and not a fault, but it means the ready card cannot be seen
this way at all.

**The receipt is the kitchen's slip now, and the layout says so** (0.5.0). The event's kitchen
had been reading orders off an iPad; the shop started handing the printed receipt to it instead,
and asked for the one thing that makes that work — *which queue is this bowl for*, answerable from
a pile at arm's length. So the queue number is a `TicketLine.Banner`, the same drawn digit the
customer's card carries, moved by the same ตั้งค่า control. It had been `คิว 42` in an ordinary
padded row, which is the right size for somebody holding their own slip and useless on a spike.

Three things came off to pay for the room, all asked for:

| Gone | Why it was there, and why it is not |
|---|---|
| The subtitle | `ก๋วยเตี๋ยวคลุก · แคปป๊อป` — the whole menu, printed for somebody who had just bought from it |
| The printed payment QR | ~15mm of paper reprinting an answered question: the customer pays at the counter against the screen before this is ever printed |
| The duplicated time | `คิว 42 · 13:19` and `13/11/2569 13:19` underneath it |

⚠️ **The setting goes with the line it drew.** `shopSubtitle` and `qrDotSize` are deleted, not
left unread — a field in ตั้งค่า that changes nothing on paper is worse than no field, and both
would have sat there looking like they still did something. `AppSettings.merchantQr` and
`promptPayId` stay, and so does `paymentQr()`: the payment **sheet** still draws a code, and that
is how the money is taken. ⚠️ A receipt handed over *before* payment would now carry no way to
pay. The order flow never does that, and anything that changes it has to answer this.

**+2 dots, landing on a counted pair.** The driver's default is `ReceiptFont.RECEIPT` — 18 dots,
and its 20 columns is **interpolated**. The receipt's item rows are drawn by `ReceiptRowBitmap`,
which calibrates against that count, so a size nobody counted puts a drawn row at a different
size from the printed lines beside it. `MEDIUM` is 20 dots and 18 columns, counted on paper, and
is what the queue ticket settled on after the first real print — the two documents now read alike.

✅ **The column arithmetic is the warning, not the verdict — and on this row it was wrong.**
`ก๋วยเตี๋ยวคลุก x2` with `120.-` counts 18 of 18 columns (tone marks take no column), so raising
it three dots in 0.7.0 should have overrun the paper. It did not: printed on 2026-11-18 the name
came out whole. On the Sunmi a `KeyValue` is **measured in dots against the paper**, and
`MEDIUM`'s 18 is the count of "ก" that spans it — a real row mixes Thai letters narrower than
that with latin digits and zero-width marks, so it has room the count cannot see.

⚠️ That is evidence about **one row at one size**, not headroom to spend. A longer row still
trims from the end rather than wrapping, the name losing its tail while `leftTail` protects the
count and the price keeps the right edge. `x10` with `600.-` is the next case up and has never
been printed. **Print it and look; never settle this by counting.**

**The shop's name is `Wrapped`, not `big`.** `big` doubles the width and so halves the columns —
nine at this size — and a centred `Text` goes to `printText`, which breaks by character in a font
with no fixed cell. That is exactly how "เอี๋ยม" lost its "จุ๊น" on the first real queue ticket;
the receipt had the same bug waiting and the size change would have set it off. Measured and
centred it fits one line, and the biggest thing on the paper is the number, which is the point.

**The receipt's lines are not all one size, because its two readers are not the same person**
(0.7.0). The kitchen reads this slip at arm's length off a spike while cooking; the customer reads
the same paper held in a hand. So the words the kitchen acts on go **up three dots** from
`MEDIUM`'s 20 — the dish with its count and price, the options, the note — and the date goes
**down three**, because it is the one line nobody does anything with: it is there to settle an
argument later, not to be read now. The header, รวม and the queue banner are untouched, since
those were approved on paper at the ticket's size.

`TicketLine.Wrapped` and `KeyValue` gained a `dots` of their own to make it possible; `Text`
already had one. The Sunmi driver keeps **one `ReceiptRowBitmap` per distinct size on a ticket**,
built on demand and cached, because the scale is baked into the paint at construction.

Dots rather than another `ReceiptFont`, for the reason the enum carries: its entries hold column
counts somebody counted on paper and 17 and 23 are not among them. Safe here, and not on a padded
row — every line these touch is measured in the font that draws it, against the paper's own dots.

✅ **Printed at these sizes on 2026-11-18 and the item row was not trimmed** — the shop's words:
"ไม่โดนตัด". Which is worth recording because the counting said otherwise: `ก๋วยเตี๋ยวคลุก x2`
with `120.-` is 18 of 18 columns at 20 dots, so three dots up should not have fitted. The row is
measured in dots, not columns (see the note on 18 columns above), and the slack is real.

⚠️ Still trimmed rather than wrapped, so a longer row loses the tail of its **name** — the count
is protected by `leftTail` and the price keeps the right edge. If these sizes move again, print
the longest order the menu can make and look at it.

**One lever moves both queue numbers** (`bannerDots`). ตั้งค่า reads
**ขนาดเลขคิว (บัตรคิว + ใบเสร็จ)**. Two controls would be a state nobody could read off the
settings screen, and two copies of one number is the drift this file keeps recording. Split them
only if the kitchen's slip and the customer's card genuinely want different sizes.

---

## The menu

Two dishes and three keys: ก๋วยเตี๋ยวคลุก, and **แคปป๊อป เล็ก / แคปป๊อป ใหญ่**. Seeded into an
empty database only, so a terminal that has been selling all weekend never has its prices
overwritten by an update. ⚠️ **The prices are still placeholders** — the owner types the real ones
in ตั้งค่า → แก้ไขเมนู, which is the whole reason they are not in code.

**The snack is two keys, not one key with a ขนาด question behind it** (0.8.0, at the owner's
word). A dish with no groups goes straight into the basket on one tap, which is the reason the
order sheet is opened per dish rather than for every key — and a bag of pork rind with a queue
waiting is the clearest case there is for not asking. The size then reaches the ticket and the
kitchen board as part of the **dish's name**, in the large type, instead of on the small options
line beneath it. The shop's own till reached the same conclusion about แคปหมู from the other
direction: it has sizes, and it is the one item the owner named as not wanting a sheet.

**ความเผ็ด is gone** (0.8.0). Four options and a question on every bowl, for a stall that does not
want to be asked.

⚠️ **แก้ไขเมนู on the device cannot do either of those.** It changes a dish's name, its price and
whether it is on sale, and it adds, renames and deletes options *within* a group — but it creates
and deletes neither a **group** nor a **dish**. So every menu change of that shape is a migration
(`MIGRATION_2_3`), and the rows are found by the names they were seeded under, **written out in
the migration rather than read from `SeedData`**: a migration is a record of what happened to one
database on one day, and one that reads today's constants is a record of nothing.

Three things that migration is careful about, each of them a scar from the shop's till:

| | |
|---|---|
| Options deleted before their group | `ON DELETE CASCADE` is off in parts of Room's migration path, and four orphan rows pointing at a group that is gone is a menu nobody can read |
| The INSERT names every column | Two terminals of the same age are not the same schema; the shop's till went down twice on a column with no default |
| The new key does not depend on the rename having matched | The name may have been edited in แก้ไขเมนู since it was seeded, and a stall left with no ใหญ่ key over a spelling is the failure worth designing out. `NOT EXISTS` on the name, so re-running cannot double it |

**Orders already taken are never touched.** A line copies the dish's name, its options and its
price at the moment it is ordered, so a ticket printed before this change still reads "เผ็ดน้อย"
and still says "แคปป๊อป" — which is what was sold, and what the customer was charged for. That is
also what makes editing the menu safe at all.

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

**The card says "คิว" in front of the number**, at the shop's word. A bare 52px numeral on a
board that also carries `2×` quantities and a `3` beside กำลังทำ is read as one of those, and the
two documents the staff match it against — the customer's card and the printed receipt — both say
the word. The label is **inside** `.queue` rather than a third item in `.card-top`: that row's
12px gap is spacing between things, and at that distance it read as two of them. So it keeps a
6px gap of its own and inherits the green a ready card turns the number, instead of being left
orange beside it.

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

**`/api/health` names what the deployment can do** (`features`, from `db.features()`), and it is
open like the rest of that endpoint. It exists because "did the redeploy land?" had no answer from
outside: the page and every read endpoint are behind auth, so last week's code and this week's look
identical. Read off the live schema rather than a hand-bumped version string — a label is true only
until somebody forgets to bump it, and this reports the capability itself. Named capabilities, not
column names: the healthcheck says what the server can do, never what is in it.

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
