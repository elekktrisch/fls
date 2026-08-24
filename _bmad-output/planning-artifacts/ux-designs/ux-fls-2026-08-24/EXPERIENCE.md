---
name: AlpenFlight
description: How AlpenFlight works — architecture, states, interactions, and the flying day it must survive.
status: draft
created: 2026-08-24
updated: 2026-08-25
design: ./DESIGN.md
sources:
  - _bmad-output/planning-artifacts/briefs/brief-fls-2026-08-24/brief.md
  - _bmad-output/planning-artifacts/briefs/brief-fls-2026-08-24/addendum.md
  - docs/modernization/01-current-state.md
  - docs/modernization/form-validation-parity-audit.md
---

# AlpenFlight — Experience Spine

Peer document: [`DESIGN.md`](./DESIGN.md), which owns the visual identity. This file owns the
information architecture, the behaviour, the states, the interactions, and the journeys. Both win
against any mock, wireframe, or import.

## Foundation

**One responsive web application. No mobile application.** The brief cuts native applications, and
the web client covers mobile use.

**Two surfaces, one product.**

- **The phone** is the hot path. The operator stands beside an aircraft. Coverage is poor. The
  screen must work with one thumb, and it must work with no network.
- **The pointer device** is the dense variant. The operator uses a laptop at the desk in the hangar.
  The treasurer uses one for the accounting rules and the invoice run.

**Two registers, one product.**

- **The signed-in application** serves club members and staff. This is an internal tool. It is
  dense, it uses club vocabulary, and it assumes the reader knows what a tow is.
- **The three public surfaces** — the landing page, trial-flight registration, and passenger-flight
  registration — serve a stranger on an unknown device. They carry a **stricter accessibility floor
  and consumer wording**. They never assume aviation knowledge.

**No UI system is chosen.** `bmad-architecture` decides the framework and the component library.
This spine states behaviour, so it holds whichever library wins. `DESIGN.md` states the visual
identity, so a chosen library must be overridden to match it, not the reverse.

## Information Architecture

**Four destinations, named by job.** The legacy menu is named by entity. The new one is named by
what the person came to do.

| Destination | Holds | Reached from |
| --- | --- | --- |
| **Home** | The dashboard: airborne panel, today, reservations, expiring licences and medicals | App open |
| **Operate** | Log flight · Airborne · Logbook · Air movements | Tab 1 |
| **Plan** | Reservations · Scheduler · Planning days · Season assignment | Tab 2 |
| **Records** | Members · Aircraft · Locations · Reports | Tab 3 |
| **Admin** | Accounting rules · Deliveries · Articles · Master data · Users · Email templates · System | Tab 4 |

Home is the app-open surface, not a fifth tab. On a phone the four destinations sit in the bottom
bar. On a pointer device they sit in the top bar.

**Surface inventory.** Every surface below delivers a stated need, and every stated need has a
surface.

| Surface | Purpose | Primary person |
| --- | --- | --- |
| Home | See what is happening now, and what needs attention | Operator, all |
| Airborne board | Flights with a block start and no landing. Stamp NOW and Landing | Operator |
| Log flight | Create and complete a flight | Operator, pilot, instructor |
| Logbook | Find a past flight. Search and filter | All |
| Air movements | Motor aircraft movements | Club admin |
| Reservations · Scheduler | Book an aircraft for a timeslot | Pilot |
| Planning days | One flying day: assigned operator and tow pilot | Club admin |
| Season assignment | Assign the operator and tow pilot across a whole season, in one pass | Club admin |
| Members | Person records, licences, medical expiry, member state | Club admin |
| Aircraft | Fleet records and counters | Club admin |
| Reports | Flight reports and the custom report builder | Club admin |
| **Accounting rules** | The ordered rule list, the WHEN/THEN editor, and the dry run | Club system carrier |
| Deliveries | Generated invoice lines, view, edit, delete | Club system carrier |
| Delivery creation test | The regression harness that proves parity | Club system carrier |
| Migration | Upload the legacy export, verify it, and commit | Club administrator |
| Profile | The person's own record and password | All |
| System | Logs, translations, system data | System admin |
| Landing page · Trial flight · Passenger flight | Public, no authentication | Public |

**Dialogs stack one level deep, never two.** The create-in-place person and aircraft forms open as a
dialog above the flight form. Nothing opens above them.

## Voice and Tone

Microcopy. The brand posture lives in `DESIGN.md`. The project writes every operator-facing text in
ASD-STE100 Simplified Technical English: active voice, one meaning per word, one instruction per
sentence.

**Internal register** — the signed-in application.

| Do | Don't |
| --- | --- |
| "The flight is locked. You cannot change it." | "Oops! This flight is no longer editable 😕" |
| "2 flights in the air" | "You currently have 2 active flights!" |
| "M. Weber is editing this flight." | "This record is currently locked by another user." |
| "Not sent. The device has no network." | "Sync pending…" |
| "Rule 2 takes 01:08. 0 remains." | "Rule 2 was successfully applied to the flight." |
| "Add the landing time." | "Don't forget to complete your flight!" |

**Public register** — the landing page and the two registration flows. Same discipline, no jargon.

| Do | Don't |
| --- | --- |
| "Book a trial flight in a glider." | "Book a trial flight." (a stranger does not know) |
| "We send you a confirmation by email." | "Check your inbox!" |
| "A club member calls you to agree a date." | "We'll be in touch." |

**One word for one thing, everywhere.** Fix these and never use a synonym:

| Use | Never |
| --- | --- |
| flight | log, entry, record |
| block start | takeoff time, departure |
| landing time | arrival, landing |
| locked | closed, frozen, read-only |
| open flight | draft, work in progress |
| rule | filter, condition |
| delivery | invoice line, billing item |

## Component Patterns

Behavioural. The visual specification for each one lives in `DESIGN.md.Components`.

| Component | Use | Behaviour |
| --- | --- | --- |
| **Typeahead picker** | Every catalog field, without exception | Filters a prefetched local list. Never waits on the network. A person matches on first name, last name, **and** city together. The list opens on focus, before a keystroke. `Enter` takes the highlighted entry. If nothing matches, the last entry is **Create "…"**, which opens the create-in-place dialog. |
| **Date field** | Every date | One text input. The operator types `25.08.2026` against `([0-9]{2}\.){2}[0-9]{4}`, or opens the calendar and clicks. **Neither path is the fallback for the other.** A clear control empties it. Never a native `<input type="date">`. |
| **Time field** | Every time | One text input. The operator types `1024` or `10:24`; the field formats on blur. A NOW control sits beside it. Never a native `<input type="time">`. |
| **NOW control** | Block start, landing time | One press. No confirmation, no dialog. It writes the current time to that one field and saves it immediately. It works from the airborne board without opening the flight. |
| **Create-in-place dialog** | Person, aircraft | Opens above the form. On save it closes, creates the record, selects it in the field that opened it, and returns focus to the next field. The operator never leaves the flight. |
| **Copy-from-last** | Tow aircraft, routes, engine counter | Fills from the previous flight on this device. Values persist between sessions. A single control per group; never automatic without a visible mark. |
| **Search field** | Logbook, members, aircraft, reports | One field. Matches across every displayed column at once. Filters as the operator types. Never a submit button. |
| **Filter chip** | Beside every search field | Tap to open the values, tap a value to narrow. An active chip shows its value and a clear control. Chips are the **only** filter mechanism. |
| **Record strip** | Every list, everywhere: logbook, airborne board, deliveries, members, aircraft, reports | The only list treatment. **There is no data table.** Four parts — identity, meta, metric, marker — each formatted by how much the reader needs it. **It stacks on a phone and sits side by side on a pointer device**; see `DESIGN.md.Record strips`. Tapping anywhere on the strip opens the record. An action in the far-right slot (NOW, LAND) acts on the record **without** opening it. A strip keeps its height when a value is absent. |
| **List group header** | Any list with groups | Names the group and its count, and restarts the list under it. On the airborne board the groups are `AT THE START`, `IN THE AIR`, `LANDED TODAY`, and older flights. |
| **Sort control** | List toolbar, beside the search field and chips | Sorting lives here, because a strip has no column header. It names the current sort and its direction. **It never filters.** |
| **Rule card** | Accounting rules | Reads as `WHEN … THEN …`. The run order number is on the left. Drag the handle to reorder, which opens the reorder preview. The engine-stop flag is always visible on the card, never hidden in an editor. |
| **Dry run** | Accounting rules, migration verify | Takes one real flight. Shows each rule that fires, in order, what it consumes from the remaining time, and what it emits. Ends with the remainder and the total. |
| **Hold banner** | Any open flight held by somebody else | Names the holder. Offers **Take over**. The fields below are read-only. |
| **Density control** | Logbook, reports, deliveries | Pointer devices only. Switches row height between 44px and 32px. Persists per person. Never appears on a phone. |

## State Patterns

| State | Where | Treatment |
| --- | --- | --- |
| **Cold load** | Any list | Reserved rows at final geometry. No spinner. The layout never shifts when the data arrives. |
| **Fast response** | Any CRUD, any navigation | No loading state at all below 300ms. The result is already there. |
| **Heavy operation** | Invoice run, migration import, large export | A spinner **is** allowed here, with the operation named and, where the count is known, a progress figure. This is the only place a spinner appears. |
| **Empty logbook** | Logbook | "No flight matches." Below it, the active chips, each with a clear control. Never an illustration. |
| **No flights today** | Home, airborne panel | "Nothing in the air." Below it, the **Log flight** control. |
| **Open flight** | Anywhere | Marker `OPEN`. It is a real record on the server, visible to the whole club. It is not a private draft. |
| **Airborne** | Home, airborne board | Marker `AIRBORNE`, full-strength cyan border. Block start set, landing time absent. The elapsed time counts up in the row. |
| **Held by another** | Flight form | Hold banner, read-only fields, **Take over** available. |
| **Unsent** | Any record touched offline | Marker `UNSENT` in red, on the record and in the top bar as a count. It never blocks the operator from continuing. |
| **Conflict** | On reconnect | Both values shown side by side, with who wrote each and when. The operator picks one. Nothing is discarded until they pick. |
| **Locked** | Flight | Marker `LOCKED` in amber. Fields read-only. The screen states why and when it happened. |
| **Will lock** | Flight, within the final day | The flight states the remaining time before it locks. **The deadline is never a surprise.** |
| **Billed** | Flight, delivery | Marker `BILLED` in `{colors.ink-settled}`. The flight links to the delivery it produced. |
| **Validation failure** | Any field | The field takes the red edge marker. The message sits under the field, states what is wrong, and states what to do. Never a summary at the top of the form. Never a dialog. |
| **Save failure** | Any write | The value stays on screen. The record becomes `UNSENT`. Nothing is lost, and nothing is silently retried without a mark. |

## Interaction Primitives

**Loading.** Prefetch every catalog before a form opens — the legacy client already prefetches 13,
and this is what makes the typeahead work at an airfield. Write optimistically: show the result, then
confirm. Reserve space at final geometry. Below 300ms show no loading state.

**Motion.** 110ms linear for a state change, 140ms for a conditional field that appears, 0ms for
anything a press caused. Nothing eases, bounces, or reorders under the pointer.

**Conditional fields.** The selection drives visibility, exactly as legacy does across its 88
conditional directives. A winch launch shows no tow aircraft and no release altitude. **A field that
disappears must not move the field the operator is about to press** — reveal downward, never
upward, and hold the scroll position.

**Keyboard.** New capability; the legacy client has no keyboard handler anywhere in its flight
module.

| Key | Action |
| --- | --- |
| `Tab` | Next field, in visual order, skipping hidden fields |
| `Enter` in a typeahead | Take the highlighted entry, move to the next field |
| `Enter` in the form | Save. Never submit from inside an open typeahead. |
| `Esc` | Close the dialog or the typeahead. Never discard the form. |
| `n` on the airborne board | Stamp NOW on the highlighted row |
| `/` | Focus the search field |
| `?` | Show the key list |

**Touch.** 44px minimum for every target. The NOW control is 56px. The primary control of a screen
sits inside the thumb arc at the bottom right on a phone. No gesture is the only way to reach an
action.

**Autosave.** Every field commits on blur. The form has no Save-draft control, because a started
flight is already saved.

## Concurrency and Holds

A flight is written by several people, on several devices, across several hours. The rules:

1. **One holder at a time.** The person who opens the full edit form holds the flight. Everybody
   else sees it read-only, with the holder's name.
2. **The hold expires.** It releases when the holder closes the flight, and after a short idle
   period. The operator who opens a flight on a laptop and walks to the next glider never blocks the
   pilot who landed.
3. **Take over is always available**, and it always names who is taken over from. The person taken
   over from is told.
4. **Stamp actions bypass the hold.** NOW and Landing are atomic single-field writes from the
   airborne board. They are never blocked. This matters because the block start is stamped at one
   exact moment, and that moment does not wait.
5. **An offline device never holds and never blocks.** It queues its writes and marks them `UNSENT`.
   On reconnect it applies them if nothing conflicts, and raises a conflict if something does.

> `[ASSUMPTION]` The offline rule in point 5 is the facilitator's proposal, not an operator
> decision. Confirm it before the architecture step.

## Speed Budget

The brief makes form speed the second product advantage, and it sets the measure: **clicks and
keystrokes, not seconds.** A click count and a keystroke count are deterministic and need no
practised user.

| Rule | Value |
| --- | --- |
| Reference case | One glider flight with a tow, logged from empty |
| Legacy figure | `[OPEN]` — not yet measured. Addendum §2 requires it. |
| Target | Not more clicks and not more keystrokes than legacy needs |
| Method | A Playwright test counts mouse and key events |
| Extra rule for the phone | The reference case must need **zero** step or page changes |

Every design decision that adds a click must be recorded against this budget. The three-step wizard
in the attempt-1 reference was rejected for this reason.

## Accessibility Floor

Behavioural. The visual contrast requirements live in `DESIGN.md.Colors`.

**Signed-in application — internal floor.**

- Every action reachable by keyboard, in visual order.
- A visible focus ring on every focusable element. It is never removed.
- Every input has a programmatic label. A placeholder is never the label.
- State is never carried by colour alone. `AIRBORNE`, `LOCKED`, and `UNSENT` each carry a word.
- An error is announced to assistive technology and is tied to its field.
- The elapsed time on an airborne row updates politely, and never steals focus.

**Public surfaces — stricter floor.** Everything above, and:

- WCAG 2.2 AA in full, on an unknown device and an unknown browser.
- The form works with no JavaScript beyond validation, and it degrades to a normal submit.
- No aviation term appears without a plain explanation.
- Target size 44px minimum, with no exception.

## Responsive & Platform

| | Phone (< 768px) | Pointer device (≥ 1200px) |
| --- | --- | --- |
| Navigation | Bottom bar, four tabs | Top bar, four destinations |
| Flight form | One column, long scroll, sticky summary above, sticky Save below | One dense form, multiple columns, no sticky elements |
| Row height | 44px always | 44px, with a 32px dense option |
| Record strip | Stacked: identity over meta at full width, metric over marker at the right | Side by side: fixed zones, 104px identity and 88px metric |
| Logbook | Record strips, toolbar above | The same record strips, with dense mode available |
| Airborne board | Full screen, NOW at the right of each row | A panel on the dashboard |
| Rules editor | Read and dry-run only | Full editing and reordering |
| Density control | Absent | Present |

**The rules editor is deliberately read-only on a phone.** Reordering rules changes every future
invoice. That decision is made at a desk.

## Inspiration & Anti-patterns

**What legacy FLS gets right, and what the rewrite must not lose.** Addendum §2 cites the code for
each.

- The flight form keeps the operator on one screen. It creates a person or an aircraft in place.
- Every picker searches. All 21 of them, over 13 catalogs. Not a favoured few.
- The form copies from the last flight: tow aircraft, both routes, engine counter.
- It guesses well: start location falls back to the last one, then to the club home base.
- It prefetches everything before it opens.
- Dates accept the keyboard and the mouse equally.
- It has a dedicated NOW control for the block start.

**What legacy FLS gets wrong, in the operator's own words.**

- **Search.** Filtering and sorting each column separately looked straightforward and is slow in
  use. Replaced by one search field plus chips.
- **Accounting rules.** A rule is a WHEN/THEN pair with an engine-stop flag, and the 567-line editor
  flattens all three into one field list. Worse: the engine is a decrement loop, so **run order
  decides the invoice** — and the list screen never shows the order. Replaced by an ordered list,
  WHEN/THEN cards, and a dry run.

**What the attempt-1 reference proposed, and this spine rejects.**

| Rejected | Reason |
| --- | --- |
| Three-step flight wizard | Adds at least two clicks to the reference case. Breaks the speed budget. |
| Native `<input type="date">` and `<input type="time">` | Slower to type into. Typing is the airfield hot path. Addendum §2 reversed this on 2026-08-24. |
| Rounded status pills | `DESIGN.md` sets every radius to `0`. |
| Home / Logbook / Reservations / Aircraft / Members | Named by entity. Replaced by four destinations named by job. |

## Key Flows

> The protagonist names below are placeholders drawn from the prototype fixtures. Replace them with
> real club names when the operator supplies them.

### Flow 1 — Martin logs the first tow of the day

Martin is the assigned operator at Birrfeld on a Saturday in August. He has a phone in his jacket
pocket and a laptop on the table in the hangar. Eleven pilots want to fly.

1. Martin opens AlpenFlight on his phone. **Home** shows nothing in the air, four reservations for
   today, and one medical that expires in three weeks.
2. Sonja sits in HB-3215 at the runway start. Martin presses **Log flight**.
3. The form opens. Every catalog is already loaded, because it prefetched. He types `ask` and the
   glider picker shows HB-3215 before he finishes the word. `Enter`.
4. The form reshapes. Start type is `Aerotow`, carried from the last flight, so the tow block
   appears. Tow aircraft HB-EAB is filled, copied from the last flight. He does not touch it.
5. He types `aeb` and takes Sonja. He types `rot` and takes Daniel in the second seat. The flight
   type sets itself to `Training`, because Daniel is an instructor.
6. He presses **Save**. The flight is now `OPEN` on the server. It has no times.
7. **The climax.** The tow rolls. Martin watches the gear leave the ground, and with the aircraft
   still in view he presses **NOW** on the airborne row — one press, no dialog, no confirmation. The
   block start is `10:24`. The row turns `AIRBORNE` and starts counting up. *He never looked at the
   screen while pressing.* This is the moment the whole design serves.
8. A member asks him about the fuel invoice. He answers. The row keeps counting.
9. At 11:42 Sonja lands. Martin is at the other end of the field with a different glider. **Sonja
   opens the flight on her own phone and presses NOW beside the landing time.** She can, because the
   flight is a shared record and not Martin's private draft, and because a stamp needs no hold.
10. That evening Daniel opens the flight on his laptop to add the training notes. The flight tells
    him it locks in 1 day and 6 hours. He writes the notes and saves.

### Flow 2 — Ruth sees why the invoice changed

Ruth is the treasurer. She inherited the accounting rules from a predecessor who left the club. A
member has asked why their aerotow costs more than last season.

1. Ruth opens **Admin → Accounting rules** on her laptop.
2. The list shows the rules **in the order the engine runs them**. Rule 1 is *Aerotow, first 10
   minutes*. It carries an amber line: *stops the engine*.
3. She reads rule 1 as a sentence. `WHEN tow · any aircraft · 0–10 min`. `THEN article 4010 · per
   minute · charged to the pilot`.
4. She opens the **dry run** and picks the member's flight from 21 August.
5. **The climax.** The trace shows the engine working: `01:18 in` → rule 1 takes `00:10`, emits
   CHF 25.00 → rule 2 takes `01:08`, emits CHF 68.00 → `0 remains · total CHF 93.00`. **For the
   first time she can see why the number is the number.** She can answer the member.
6. She notices rule 3 should run before rule 2. She drags it up. **The reorder does not commit.**
   AlpenFlight runs the dry run again and shows the invoice before and after, side by side: CHF
   93.00 becomes CHF 88.00.
7. Ruth sees a five-franc change she did not intend. She cancels. **Nothing changed.** The guard did
   its job: it made the money visible before the decision.

### Flow 3 — Peter moves his club onto AlpenFlight

Peter carries the system for his club. He configured the accounting rules eight years ago. His
objection is not the price. It is the risk that billing changes.

1. Peter runs the export tool against the legacy database. It produces one encrypted file.
2. He uploads the file in the browser. Nobody at AlpenFlight touches it.
3. **Verify.** AlpenFlight shows counts side by side: flights, members, aircraft, rules. Each row
   states legacy and imported, and each row must match.
4. **The climax.** The verify step runs the **same dry run** against a sample of the club's recorded
   legacy invoices. Each line reproduces to the cent, or the row is red. **Peter's objection is
   answered by arithmetic, not by a promise.** This is why the dry run is one surface with two jobs.
5. He commits. The club runs the next flying day on AlpenFlight.
6. `[OPEN]` What happens if a line does **not** reproduce? Peter needs a path that is not "call
   support", because there is one operator and he is not staffed for it. Resolve before `bmad-prd`.

### Flow 4 — Beatrice assigns a whole season

Beatrice runs the club roster. It is March.

1. She opens **Plan → Season assignment**.
2. She sets the season range and picks the days: every Saturday and Sunday from April to October.
3. She assigns the operator and the tow pilot across all of them **in one pass**, not one day at a
   time. This is the shape of the real job.
4. In June a tow pilot cannot fly on one Sunday. She opens that single planning day and changes it.
   The season assignment is untouched.

## Open Items

| Item | Owner | Blocks |
| --- | --- | --- |
| `[OPEN]` Legacy click and keystroke count for the reference flight | Operator, per addendum §2 | The speed budget has no number |
| `[OPEN]` The lock and billing time gates: unit and boundary (Q-B2, Q-B3) | `legacy-oracle` | The "will lock" countdown cannot state a figure |
| `[OPEN]` Rule order and minimum decrement in the engine (Q-B6, Q-B7) | `legacy-oracle` | The dry-run trace cannot be built exactly |
| `[OPEN]` What a club does when a migrated invoice line does not reproduce | `bmad-prd` | Flow 3 has no failure path |
| `[ASSUMPTION]` The offline conflict rule (Concurrency, point 5) | Operator | Confirm before `bmad-architecture` |
| `[ASSUMPTION]` Protagonist names are fixtures | Operator | Replace with real club names |
