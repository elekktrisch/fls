---
title: "PRD: AlpenFlight"
status: draft
created: 2026-08-25
updated: 2026-08-25
---

# PRD: AlpenFlight

*Working title — confirm. The product name carries over from rebuild 1.*

## 0. Document Purpose

This PRD tells `bmad-architecture` and `bmad-create-epics-and-stories` what AlpenFlight must do. It
does not say how to build it. It is written for the supplier, who is the only reader who must act on
it today.

The document builds on three inputs and does not repeat them. Read them for depth:

| Input | Location | What it holds |
| --- | --- | --- |
| Product brief and addendum | [`../../briefs/brief-fls-2026-08-24/`](../../briefs/brief-fls-2026-08-24/) | Positioning, the two advantages, the cost-recovery model, the constraint triage |
| UX experience and design spine | [`../../ux-designs/ux-fls-2026-08-24/`](../../ux-designs/ux-fls-2026-08-24/) | Information architecture, four flows, component behaviour, the design tokens |
| Legacy reverse-engineering | [`docs/modernization/01-current-state.md`](../../../../docs/modernization/01-current-state.md) | 52 features in 8 domains, risks R1–R14, behavioural questions Q-B1 to Q-B16 |

**How to read this document.** The vocabulary lives in [`domain-model.md`](domain-model.md), which
also holds the domain model and the legacy-to-target name mapping. Every section uses those terms
exactly, and never a synonym. §4 groups the features and nests the functional requirements
under them. Requirement numbers FR-1 to FR-87 are global and stable. Journey numbers UJ-1 to UJ-4
match Flow 1 to Flow 4 in the UX experience spine. `[ASSUMPTION]` tags mark what the supplier has
not confirmed, and §14 indexes every one of them.

Depth that belongs to a downstream document lives in [`addendum.md`](addendum.md). The addendum
holds the rejected alternatives, the rebuild-1 constraint disposition, and the map from each open
behavioural question to the requirements it blocks.

| Companion | What it holds |
| --- | --- |
| [`domain-model.md`](domain-model.md) | The ubiquitous language, the domain model, and the legacy-to-target name mapping |
| [`addendum.md`](addendum.md) | Rejected alternatives, the constraint disposition, the behavioural-question map |

## 1. Vision

Swiss glider clubs run their flying weekends on the legacy Flight Logging System. The system works.
Its foundation does not: the server and the client both reached end of life years ago, and no
supplier patches a security defect in either. The clubs stay, because every alternative asks them to
enter years of accounting configuration again, by hand.

AlpenFlight removes that cost. It rebuilds the legacy Flight Logging System feature for feature, and
it carries the club's data and accounting rules across intact. A club exports its legacy database
with one tool, uploads the result, proves that its own invoices still reproduce to the cent, and
then runs its next flying day on a maintained system. **AlpenFlight sells the migration first and
the operations software second.**

Two things make AlpenFlight worth building instead of buying. The first is that migration, which
only somebody who knows the legacy database can build. The second is the speed of the flight form.
The legacy form logs a flight fast because it hides irrelevant fields, searches every catalog as the
flight operator types, creates a missing pilot in place, and copies from the last flight. A rewrite
loses that by accident. AlpenFlight measures the cost in clicks and keystrokes, and it does not
regress.

## 2. Target User

### 2.1 Jobs To Be Done

**The club system carrier.** Usually a volunteer on the committee. The buyer.

- Keep the club billing correct, so that no member disputes an invoice.
- Understand accounting rules that a predecessor configured and then left.
- Move the club off an unmaintained system with no risk that billing changes.
- Answer a member's question about an invoice in a few minutes.

**The flight operator.** The person at the airfield who logs the flying day.

- Log a flight beside the aircraft, on a phone, with poor mobile coverage.
- Stamp the block start at the moment the gear leaves the ground, while watching the aircraft.
- Continue after every interruption, and lose nothing.
- Hand a part-entered flight to a pilot or an instructor who completes it later.

**The club administrator.** Runs the roster, the fleet, and the member records.

- Assign the flight operator and the tow pilot across a whole season in one pass.
- Keep licence and medical expiry dates current, and see what expires soon.
- Move the whole club onto AlpenFlight with no help from the supplier.

**The pilot.** Flies a few times a month.

- Reserve an aircraft for a timeslot.
- Read their own flight history.
- Enter their own landing time when the flight operator is at the other end of the field.

**The instructor.** Flies with a student.

- Add training notes to a completed flight, before the next flight or hours later.
- See when the flight locks, so that the deadline never arrives as a surprise.

**The supplier.** Builds, hosts, and supports the product alone.

- Onboard a club with no manual work.
- Carry as little support load per club as possible. Their capacity, not money, is the real limit.

### 2.2 Non-Users (v1)

- **A club with no legacy Flight Logging System history.** The migration is the advantage, and they
  have nothing to migrate. A competitor serves them at least as well.
- **A motor flight group, and a club outside Switzerland.** The vision names them as a later option.
  The first release does not serve them.
- **A club that depends on OGN ingestion today.** §5 records this exception. Such a club keeps its
  legacy system for that path, or its flight operator logs the flights by hand.
- **A paying customer.** The first release serves a closed user group. Nobody pays inside the
  product. §6.2 defers the whole subscription model.

### 2.3 Key User Journeys

*UJ-1 to UJ-4 mirror Flow 1 to Flow 4 in
[`EXPERIENCE.md`](../../ux-designs/ux-fls-2026-08-24/EXPERIENCE.md). Read that document for the beat
detail. UJ-5 and UJ-6 are new here. The protagonist names are fixtures from the UX prototype.
`[ASSUMPTION: the names stay fixtures until the supplier supplies real club names.]`*

- **UJ-1. Martin logs the first tow of the day.**
  - **Persona and context:** Martin is the assigned flight operator at a Swiss airfield on a
    Saturday in August. They carry a phone in a jacket pocket, and they keep a laptop in the hangar.
    Eleven pilots want to fly.
  - **Entry state:** authenticated, on the Home surface, on a phone.
  - **Path:** Martin opens Log flight when the first pilot sits at the runway start. They type three
    letters and take the glider from the picker. The form reshapes for an aerotow, and the tow
    aircraft is already filled from the last flight. They take the pilot and the instructor by three
    letters each, and they save. The flight is now an open flight on the server, with no times.
  - **Climax:** the tow rolls. Martin watches the gear leave the ground and presses **NOW** on the
    airborne row — one press, no dialog, no confirmation. The row starts to count up. **Martin never
    looked at the screen while pressing.**
  - **Resolution:** the flight is airborne and shared. The pilot enters the landing time on their own
    phone from the other end of the field. The instructor adds training notes that evening.
  - **Edge case:** the airfield loses mobile coverage. Every stamp and every field still writes, the
    record shows `UNSENT`, and the device sends the queue on reconnect.

- **UJ-2. Ruth sees why the invoice changed.**
  - **Persona and context:** Ruth is the treasurer and the club system carrier. They inherited the
    accounting rules from a predecessor who left the club. A member has asked why an aerotow costs
    more than last season.
  - **Entry state:** authenticated on a laptop, on Admin → Accounting rules.
  - **Path:** the list shows the rules in the order the rules engine runs them. Ruth reads rule 1 as
    a sentence, opens the dry run, and picks the member's flight.
  - **Climax:** the trace shows the rules engine working — the flight time entering, each rule
    consuming part of it and emitting an amount, and the remainder reaching zero. **Ruth can see why
    the number is the number, and can answer the member.**
  - **Resolution:** Ruth drags a rule up. The reorder does not commit. AlpenFlight shows the invoice
    before and after, side by side. Ruth sees a change they did not intend, and cancels. Nothing
    changed.

- **UJ-3. Peter moves the club onto AlpenFlight.**
  - **Persona and context:** Peter carries the system for a club that runs the legacy Flight Logging
    System. They configured the accounting rules eight years ago. **Their objection is not the
    price. It is the risk that billing changes.**
  - **Entry state:** unauthenticated at the start. Peter can reach the legacy database.
  - **Path:** Peter runs the export tool, which produces one encrypted file. They upload it in the
    browser. AlpenFlight shows counts side by side — flights, persons, aircraft, and rules — and
    each row must match.
  - **Climax:** the verify step replays a sample of the club's recorded legacy invoices through the
    same dry run. Each line reproduces to the cent, or the row is red. **Arithmetic answers Peter's
    objection, not a promise.**
  - **Resolution:** Peter accepts the few lines that did not reproduce, states a reason for each, and
    commits. The club runs its next flying day on AlpenFlight, and the accepted mismatches stay in an
    open list.
  - **Edge case:** a count does not match. The verify step names the record type and the difference,
    and Peter can run the export again with no help from the supplier.

- **UJ-4. Beatrice assigns a whole season.**
  - **Persona and context:** Beatrice is the club administrator and runs the roster. It is March.
  - **Entry state:** authenticated on a laptop, on Plan → Season assignment.
  - **Path:** Beatrice sets the season range, picks every Saturday and Sunday from April to October,
    and assigns the flight operator and the tow pilot across all of them **in one pass**.
  - **Climax:** the whole season fills from one action. The legacy Flight Logging System made this a
    per-day task.
  - **Resolution:** in June a tow pilot cannot fly on one Sunday. Beatrice opens that single planning
    day and changes it. The season assignment stays untouched.

- **UJ-5. Sonja reserves the club glider.**
  - **Persona and context:** Sonja is a pilot who flies a few times a month. It is Wednesday evening,
    and the forecast for Saturday is good.
  - **Entry state:** authenticated on a phone, on Plan.
  - **Path:** Sonja opens the scheduler, finds Saturday, sees which aircraft are free, and reserves
    one for the morning.
  - **Climax:** the reservation appears for the whole club immediately.
  - **Resolution:** on Saturday the flight operator sees the reservation on Home before the day
    starts.

- **UJ-6. A stranger books a trial flight.**
  - **Persona and context:** somebody who has never flown a glider finds the club website. They use
    an unknown phone and an unknown browser.
  - **Entry state:** unauthenticated, on the public landing page.
  - **Path:** they open the trial-flight form, read plain wording with every aviation term
    explained, and send their contact details.
  - **Climax:** the club receives the registration by email, and the person receives a confirmation.
  - **Resolution:** the club contacts them to arrange a date.
  - **Edge case:** a robot sends the form many times. The abuse control stops it, and no club mailbox
    fills.

## 3. Glossary

**The glossary moved to its own document: [`domain-model.md`](domain-model.md).**

That document holds the ubiquitous language, the domain model, and the mapping from every legacy
name to its target name. It is derived from the legacy entity classes, the legacy enums, and the
German source text of the legacy interface.

**Every term this PRD uses comes from `domain-model.md` §3.** A synonym is a defect. If you need a
word that document does not hold, add it there first.

> **Caution: `domain-model.md` §7 records three findings that change this PRD.** One renames a term
> this document uses throughout, one touches an external contract, and one contradicts FR-47. Read
> §7 before you act on §4.

## 4. Features

*Each subsection is one coherent feature. The functional requirements nest under it and carry global
numbers. Consequences are testable statements. A requirement states a capability, never a mechanism;
the mechanism belongs to `bmad-architecture`.*

### 4.1 Club isolation and identity

**Description.** Every record in AlpenFlight belongs to exactly one club, and no query may cross that
boundary by accident. The legacy system enforces this by convention only, which risk hotspot R1 names
as the largest correctness risk in the rewrite. AlpenFlight enforces it structurally: a query that
omits the club filter must fail, and a test must prove that it fails.

Identity keeps the legacy split between a user and a person. A user logs in and belongs to one club.
A person is a human who can belong to several clubs. Collapsing the two breaks the pilot roster at a
site where members fly for more than one club.

**Functional Requirements:**

#### FR-1: Structural club isolation

The system rejects any data access that does not state a club. Realizes UJ-1, UJ-2, UJ-3.

**Consequences (testable):**
- A query written without a club filter fails. It does not return rows from another club, and it
  does not return an empty result silently.
- An automated test proves the failure. A review convention does not satisfy this requirement.
- A user authenticated for club A cannot read, write, or delete any record of club B through any
  interface, including a direct identifier in a URL.

#### FR-2: Cross-club person and flight crew

A flight crew member can be a person from a club other than the club that operates the flight.

**Consequences (testable):**
- A flight of club A that names a pilot who belongs only to club B saves, and club A sees it.
- Club B does not see club A's flight because of that crew reference.
- The same rule applies to the recipient of a delivery.

**Notes:** `[NOTE FOR PM]` Q-B8 and Q-B9 record that the legacy rule which admits such a record is
not written down. Resolve both with the `legacy-oracle` agent before this requirement reaches a
story.

#### FR-3: User and person separation

The system keeps a user and a person as separate records, and it links them.

**Consequences (testable):**
- A person exists with no user. A visiting pilot needs no login.
- A person belongs to several clubs at once, and each membership carries its own member state and
  person category.
- A user belongs to exactly one club.

#### FR-4: Authentication with a short-lived token and refresh

A user signs in and receives a short-lived access token that the system refreshes without a new sign
in.

**Consequences (testable):**
- The access token expires in a short period. The legacy 14-day token does not carry forward.
- The client refreshes the token before it expires, and the user does not sign in again during a
  flying day.
- A rejected request because of an expired token does not lose the user's unsaved entry.

#### FR-5: Roles and permissions

The system assigns roles to a user, and a role decides which surface and which action the user
reaches.

**Consequences (testable):**
- A pilot cannot open the accounting rules.
- A club administrator cannot reach another club's data, whatever their role.
- A permission failure states what the user lacks. It does not present an empty screen.

#### FR-6: Profile self-edit

A user edits their own person record and changes their own password.

**Consequences (testable):**
- A user changes their own address, telephone number, and password.
- A user cannot change their own role or their own club.

#### FR-7: Password reset and email confirmation

A user who forgot their password requests a reset by email, and a new user confirms their email
address.

**Consequences (testable):**
- A reset link expires after a stated period, and it works once.
- The reset response is the same whether the email address exists or not.

#### FR-8: Audit record on every change

The system records who changed which record, when, and what changed.

**Consequences (testable):**
- Every create, update, and delete of a flight, a person, an aircraft, an accounting rule, and a
  delivery produces an audit record.
- A club administrator reads the audit record for one flight.
- A club sees only its own audit records.
- A paid club and an unpaid club receive the same audit guarantee.

### 4.2 Master data

**Description.** The club configures the lists that the rest of the product uses: aircraft,
locations, flight types, start types, member states, person categories, articles, and email
templates. Every catalog feeds a picker in the flight form, so the shape of this data decides how
fast a flight logs.

**Functional Requirements:**

#### FR-9: Club master-data management

A club administrator creates, reads, updates, and deletes the club's master data.

**Consequences (testable):**
- The set covers clubs, aircraft, locations, flight types, member states, person categories,
  persons, articles, and email templates.
- A delete that another record references fails, and the message names what references it.

#### FR-10: Person records with licence and medical expiry

A club administrator holds a licence type and a medical expiry date on a person.

**Consequences (testable):**
- A person carries a licence expiry date and a medical expiry date.
- A person whose medical expires within a stated period appears on Home.

#### FR-11: Create master data in place

A user creates a missing person or a missing aircraft from inside the flight form, and does not leave
the form. Realizes UJ-1.

**Consequences (testable):**
- The create dialog opens above the flight form, and nothing opens above the dialog.
- On save, the dialog closes, the new record is selected in the field that opened it, and the focus
  moves to the next field.
- The part-entered flight loses no value.

#### FR-12: Aircraft operating counters

The system holds an operating counter per aircraft, and the flight form uses the last recorded value.

**Consequences (testable):**
- A motor flight records a start counter and a landing counter.
- The form fills the start counter from the last recorded counter of that aircraft.

#### FR-13: Reference data

The system holds the reference lists that the legacy system holds: countries, counter unit types,
length unit types, elevation unit types, location types, and start types.

**Consequences (testable):**
- Every reference list the legacy system serves has an equivalent.
- A reference list a club does not configure needs no club action to work.

#### FR-14: Language translation administration

A system administrator manages the interface translations.

**Consequences (testable):**
- The first release carries German and English.
- A missing translation key renders a stated fallback and never renders an empty string or a raw key.

**Notes:** `[NOTE FOR PM]` Q-B15 records that the legacy behaviour for a missing translation is not
documented. `[ASSUMPTION: the legacy translation table holds more languages than German and English.
The first release carries two, and the rest wait for a club to ask.]`

#### FR-15: System data and system logs

A system administrator reads the system logs and manages the system data.

**Consequences (testable):**
- The surfaces exist and match the legacy capability.
- A club administrator cannot reach either surface.

### 4.3 Flight logging — the airfield hot path

**Description.** This feature carries the second product advantage. The flight operator logs a flight
beside the aircraft, on a phone, with poor coverage and constant interruption. Every decision here is
measured in clicks and keystrokes against the legacy form, not in seconds.

The form is one dense form on a pointer device, and a single-column long scroll on a phone. It is
never a wizard. Fields appear and disappear according to the aircraft and the start type, exactly as
the legacy form does across its 88 conditional directives.

**Functional Requirements:**

#### FR-16: Log a flight in one form

A flight operator records a flight in one form, with no step change and no page change. Realizes
UJ-1.

**Consequences (testable):**
- The reference case — one glider flight with a tow, from empty — needs zero step changes and zero
  page changes on a phone.
- The form covers a glider flight, a tow flight, and a motor flight.
- A flight saves while fields are still empty.

#### FR-17: Conditional field visibility

The form hides the fields that the current selection makes irrelevant. Realizes UJ-1.

**Consequences (testable):**
- A winch launch shows no tow aircraft field and no release altitude field.
- An aerotow shows both.
- A field that appears or disappears does not move the field the flight operator is about to press.
  The form reveals downward and holds the scroll position.

#### FR-18: Typeahead over a prefetched catalog

Every catalog field filters a list that the client already holds, and it never waits on the network.
Realizes UJ-1.

**Consequences (testable):**
- Every catalog field in the flight form is a typeahead. There is no plain select anywhere in the
  form.
- The form prefetches every catalog before it opens.
- A person picker matches the first name, the last name, and the city together.
- The list opens on focus, before the first keystroke.
- When nothing matches, the last entry offers to create the record, which opens FR-11.

#### FR-19: Date and time entry by keyboard or by pointer

Every date field and every time field accepts typing and pointing equally.

**Consequences (testable):**
- A date field accepts `25.08.2026` typed, and it accepts a click in a calendar. Both write the same
  value.
- A time field accepts `1024` and `10:24`, and it formats the value when the field loses focus.
- Neither path is a fallback for the other, and a clear control empties the field.

> **Caution: this reverses a rebuild-1 preference.** Rebuild 1 chose native date and time inputs.
> The supplier reversed that on 2026-08-24, because typing is the airfield hot path and a native
> input is slower to type into. Do not restore the rebuild-1 preference without raising it again.

#### FR-20: The NOW stamp

A flight operator stamps the block start or the landing time with one press. Realizes UJ-1.

**Consequences (testable):**
- One press writes the current time to that one field and saves it immediately.
- No dialog and no confirmation appears.
- The control works from the airborne board without opening the flight.
- The control is at least 56 pixels on a phone, and it sits inside the thumb arc.
- A stamp is never blocked by a hold. See FR-40.

**Notes:** The supplier intends a later source for the block start, derived from FLARM vectors.
Design the control so that an automatic source replaces the press with no screen change.

#### FR-21: Copy from the last flight

The form fills the tow aircraft, the outbound route, the inbound route, and the engine counter from
the previous flight on that device.

**Consequences (testable):**
- The values persist between sessions on the same device.
- One control per group performs the copy.
- A copied value carries a visible mark. The form never copies silently.

#### FR-22: Smart defaults

The form fills a start location and a landing location when it has nothing to copy.

**Consequences (testable):**
- The form falls back to the last start location, and then to the club's home base.
- The flight operator can overwrite every default.

#### FR-23: Keyboard operation

A user completes the flight form with the keyboard alone. **This is a new capability, not a port.**
The legacy client has no keyboard handler anywhere in its flight module.

**Consequences (testable):**
- `Tab` moves to the next field in visual order, and it skips a hidden field.
- `Enter` inside a typeahead takes the highlighted entry and moves to the next field.
- `Enter` in the form saves, and it never submits from inside an open typeahead.
- `Esc` closes a dialog or a typeahead, and it never discards the form.
- `n` stamps NOW on the highlighted row of the airborne board.
- `/` moves the focus to the search field, and `?` shows the key list.

#### FR-24: The airborne board

A flight operator sees every flight that is in the air now, and acts on any of them. Realizes UJ-1.

**Consequences (testable):**
- The board lists every flight with a block start and no landing time.
- Each row offers the NOW stamp and the landing stamp directly, without opening the flight.
- The elapsed time on a row counts up, and it never steals the focus.
- The board groups its rows: at the start, in the air, landed today, and older.
- The board is a full screen on a phone, and a panel on Home on a pointer device.

#### FR-25: Copy a whole flight

A user creates a new flight from an existing flight.

**Consequences (testable):**
- The new flight carries the aircraft, the crew, and the route of the source flight.
- The new flight carries no time and no identifier from the source flight.

#### FR-26: Speed budget conformance

The reference case costs no more clicks and no more keystrokes than the legacy form costs.

**Consequences (testable):**
- The reference case is one glider flight with a tow, logged from empty.
- An automated browser test counts the mouse events and the key events.
- The test fails when either count is above the recorded legacy figure.

> **Warning: the legacy figure does not exist yet.** Nobody has measured it. Open question 1 in §13
> names it. Record the pair of numbers before the first flight-form story starts, or this
> requirement has no target.

### 4.4 Flight lifecycle and state

**Description.** A flight carries two states. The air state is computed from the timestamps and is
never the stored authority. The process state is stored, and it decides billing eligibility. Two time
gates move a flight forward: the lock gate makes it read-only, and the billing gate admits it to the
rules engine.

Risk hotspot R2 records that the unit and the boundary of both gates are undocumented. A fresh
database reaches neither gate, so the system looks broken to a new club until somebody can move a
flight through the gates in a test.

**Functional Requirements:**

#### FR-27: Two-dimensional flight state

The system holds an air state computed from the flight timestamps, and a stored process state.

**Consequences (testable):**
- The air state derives from the timestamps every time it is read. It is never stored as the
  authority.
- The client and the server derive the process state names from one source. They never hold two
  copies of the list.
- An illegal process-state transition fails, and the message names the current state and the
  requested state.

#### FR-28: The open flight is a shared record

A part-entered flight is a server record that the whole club sees. Realizes UJ-1.

**Consequences (testable):**
- A flight saves with no times and no complete crew.
- A different user on a different device opens the same flight and completes it.
- An open flight is never a private draft on one device.

#### FR-29: Daily flight validation

Scheduled work validates the flights of the club and sets the process state of each one.

**Consequences (testable):**
- A flight that fails validation moves to the invalid process state, and the reason is readable.
- A flight that passes stays eligible for the next gate.
- The job runs for every club, and it never crosses a club boundary.

**Notes:** `[NOTE FOR PM]` Q-B1 records that the legacy rule which separates an invalid flight from
an unprocessed flight is undocumented. Resolve it with the `legacy-oracle` agent.

#### FR-30: The lock gate

A flight becomes read-only after the lock gate passes.

**Consequences (testable):**
- A locked flight rejects every field change.
- The screen states that the flight is locked, and states when the lock happened.
- The gate uses the legacy unit and the legacy boundary.

**Notes:** `[NOTE FOR PM]` Q-B2 and Q-B3 record that the unit, the boundary, and the timezone of the
gate are undocumented. Resolve all three before this requirement reaches a story.

#### FR-31: The lock countdown

A flight that is inside its final period before the lock states the remaining time. Realizes UJ-1.

**Consequences (testable):**
- An instructor who opens a completed flight sees how long remains before it locks.
- **The deadline never arrives as a surprise.**

#### FR-32: The billing gate

A flight becomes eligible for the rules engine after the billing gate passes.

**Consequences (testable):**
- The rules engine never processes a flight before the gate passes.
- The gate uses the legacy unit and the legacy boundary.

#### FR-33: A testable clock

A test moves a flight through both time gates without a change to the system clock.

**Consequences (testable):**
- An automated test drives a flight from creation, through the lock gate, to the billing gate, in one
  run.
- A new club can see the whole lifecycle on demonstration data.

#### FR-34: Air movements

A club administrator records the movements of a motor aircraft.

**Consequences (testable):**
- The surface matches the legacy air-movements capability.
- The records use the same flight record as a glider flight.

### 4.5 Offline work and concurrency

**Description.** An alpine airfield has no reliable mobile coverage. This is a condition of the
environment, not an enhancement. A flight record is written by several people, on several devices,
across several hours: the flight operator at the runway, the flight operator at wheels-up, a pilot or
the flight operator at the landing, and the instructor later.

The concurrency model is one holder at a time on the full edit form. A stamp never takes a hold,
because the block start is stamped at one exact moment, and that moment does not wait.

**Functional Requirements:**

#### FR-35: Offline flight write path

A flight operator creates a flight, edits it, and stamps its times with no network connection.
Realizes UJ-1.

**Consequences (testable):**
- The flight form, the NOW stamp, and the landing stamp all work offline.
- The device stores the write locally, and the write survives a browser restart.
- No offline action needs a network round trip to complete on screen.

#### FR-36: Offline read of today

The device holds today's flights, the airborne board, and every catalog for offline reading.

**Consequences (testable):**
- The airborne board renders offline with the flights the device already knew.
- Every typeahead filters offline, because the catalogs are already on the device.
- Reservations, member records, accounting, and reports need a connection, and they state that
  plainly when it is absent.

#### FR-37: The unsent marker

Any record that a device wrote offline carries a visible marker until the server accepts it.

**Consequences (testable):**
- The record shows `UNSENT`, and the top bar shows the count of unsent records.
- The marker never blocks the flight operator from continuing.
- A failed save keeps the value on screen and marks the record unsent. It loses nothing, and it
  retries nothing silently without a mark.

#### FR-38: Reconnect and conflict

On reconnect the device applies its queued writes when nothing conflicts, and raises a conflict when
something does.

**Consequences (testable):**
- A queued write applies when no other change touched the same record since the device went offline.
- A conflict shows both values side by side, with who wrote each one and when.
- Nothing is discarded until a person picks a value.
- An offline device never blocks another user, and it never takes a hold.

#### FR-39: The hold on the edit form

The user who opens the full flight edit form holds it. Every other user sees the flight read-only.

**Consequences (testable):**
- The read-only view names the holder.
- The hold releases when the holder closes the flight, and it releases after a short idle period.
- Any user can take over the hold at any time, and the system names who they take over from.
- The system tells the user who was taken over from.

#### FR-40: A stamp bypasses the hold

The NOW stamp and the landing stamp are never blocked by a hold. Realizes UJ-1.

**Consequences (testable):**
- A flight operator stamps a block start on a flight that a pilot currently holds.
- The stamp writes one field, and it changes no other field.

### 4.6 Reservations and planning

**Description.** A club plans before it flies. Somebody assigns the flight operator and the tow pilot
across every weekend day of a whole season, in one batch, and corrects single days later. Over the
following weeks the pilots reserve aircraft for timeslots.

**Functional Requirements:**

#### FR-41: Reservation management

A pilot creates, changes, and cancels a reservation for an aircraft and a timeslot. Realizes UJ-5.

**Consequences (testable):**
- A reservation names the aircraft, the pilot, the start time, and the end time.
- The whole club sees a reservation as soon as it saves.
- A pilot cannot change another pilot's reservation unless their role permits it.

#### FR-42: The reservation scheduler

A pilot sees the club's reservations in a calendar view. Realizes UJ-5.

**Consequences (testable):**
- The view shows which aircraft are free on a chosen day.
- The view matches the legacy scheduler capability.

#### FR-43: Planning day

A club administrator manages one flying day at one location, with its assigned roles. Realizes UJ-4.

**Consequences (testable):**
- A planning day is identified by its location and its date.
- The day carries the assigned flight operator, tow pilot, and instructor.
- A change to one planning day does not change any other day.

#### FR-44: Season assignment

A club administrator assigns a planning day role across a date range in one pass. Realizes UJ-4.

**Consequences (testable):**
- The administrator picks a date range and picks the weekdays inside it.
- One action creates or updates every matching planning day.
- A later single-day correction leaves the rest of the season untouched.

**Notes:** This is new. The legacy system offers a per-day task and a setup wizard, and no batch
assignment. `[ASSUMPTION: the legacy planning setup wizard is superseded by the season assignment,
and the first release ports the wizard's outcome rather than its steps.]`

#### FR-45: Planning day notification

Scheduled work sends the assigned people an email about their planning day.

**Consequences (testable):**
- The email names the day, the location, and the role.
- The message uses the club's email template.

### 4.7 Accounting rules and deliveries

**Description.** **This is the sacred cow.** Clubs configured these rules over many years, and the
buyer's true objection is the risk that billing changes. The rules engine is a decrement loop: rules
match against the remaining active flight time, consume part of it, emit a delivery item, and repeat
until no rule matches.

The engine behaviour does not change. Only the screen changes. The legacy screen flattens the WHEN
condition, the THEN result, and the engine-stop flag into one undifferentiated field list, and it
never shows the run order — although the run order decides the invoice.

**Functional Requirements:**

#### FR-46: Accounting rule management as WHEN and THEN

A club system carrier reads and edits an accounting rule as a WHEN condition and a THEN result.
Realizes UJ-2.

**Consequences (testable):**
- A rule card reads as one sentence: WHEN a condition, THEN a result.
- The WHEN part covers the legacy condition fields: the rule filter type, the aircraft, the start and
  landing locations, the start types, the flight types, the crew types, the person category, the
  member state, club membership, the home base, and the flight duration band.
- The THEN part covers the legacy result fields: the article, the accounting unit type, the
  recipient, the delivery line text, the three no-landing-tax flags, and the club-internal charge
  flag.
- The rules editor is read-only on a phone. Reordering rules changes every future invoice, and that
  decision is made at a desk.

#### FR-47: Explicit rule order

The rule list shows the rules in the order the rules engine runs them, and a club system carrier
changes that order. Realizes UJ-2.

**Consequences (testable):**
- Each rule card shows its run order number.
- A drag changes the order.
- The order the list shows is the order the engine uses. No hidden second order exists.

**Notes:** `[NOTE FOR PM]` Q-B6 records that the legacy rule which decides priority when two rules
match the same active flight time is undocumented. Resolve it with the `legacy-oracle` agent before
this requirement reaches a story. The answer decides whether the order is a stored field or a derived
one.

#### FR-48: The engine-stop flag is visible

Every rule card shows whether the rule stops the rules engine.

**Consequences (testable):**
- The flag appears on the card, and never only inside an editor.
- A rule that stops the engine carries a distinct visible mark.

#### FR-49: Rules engine parity

The rules engine reproduces every recorded legacy delivery item to the cent.

**Consequences (testable):**
- A recorded legacy flight, replayed against the club's migrated rules, emits the same delivery items
  with the same amounts and the same recipients.
- The engine consumes the active flight time in the legacy order.
- A rule that decrements by zero does not loop forever.

**Notes:** `[NOTE FOR PM]` Q-B7 records that the legacy behaviour for a zero decrement is
undocumented. Resolve it with the `legacy-oracle` agent.

#### FR-50: The dry run

A club system carrier replays the rules engine against one real flight and reads the trace. Realizes
UJ-2, UJ-3.

**Consequences (testable):**
- The trace lists every rule that fires, in run order.
- For each rule the trace shows what it consumed from the remaining active flight time, and what it
  emitted.
- The trace ends with the remainder and the total.
- The dry run changes nothing.
- **The same dry run serves the migration verify step.** One surface, two jobs. See FR-72.

#### FR-51: The reorder guard

A reorder of the rules does not commit until the club system carrier sees its effect on money.
Realizes UJ-2.

**Consequences (testable):**
- A reorder runs the dry run against a recent real flight.
- The system shows the invoice before and after, side by side.
- The carrier confirms with the numbers visible, or cancels and nothing changes.

#### FR-52: Delivery creation

Scheduled work runs the rules engine over the eligible flights and creates the deliveries.

**Consequences (testable):**
- The job processes only flights that passed the billing gate.
- A delivery moves from prepared to booked, and booked is terminal.
- The job never crosses a club boundary.

#### FR-53: Delivery management

A club system carrier reads, edits, and deletes a delivery and its delivery items.

**Consequences (testable):**
- The carrier corrects a delivery item before the delivery is booked.
- A booked delivery rejects a change.
- A flight links to the delivery it produced, and the delivery links back.

#### FR-54: Billing expectations

A club system carrier holds a set of billing expectations — a real flight plus the invoice it should
produce — and runs them to find out whether a rule change did anything they did not intend.

**Consequences (testable):**
- An expectation names one flight, the expected invoice, and the fields that may differ.
- Running one expectation, or all of them, reports pass or fail and names every field that differs.
- The screen shows the expected invoice and the last actual invoice together, and names the rules
  that fired.
- **A carrier re-baselines an expectation in one action when the difference is what they intended.**
  A difference is a normal outcome of a rule change, not a defect.
- A club seeds its first expectations from the invoices that FR-74 verified during its migration.

**Notes:** **This is not a parity test.** Parity means the legacy system and AlpenFlight producing the
same line, which FR-74 proves once. An expectation is the club's own record of what a flight should
bill, and it lives for as long as the club does. See [`domain-model.md`](domain-model.md) §7.4.

#### FR-54a: Change preview

A club system carrier sees what a rule change would do **before** they commit it.

**Consequences (testable):**
- The preview runs against the club's billing expectations, or a recent real flight.
- It shows the invoice before and after, side by side.
- The change does not commit until the carrier confirms with the numbers visible.

**Notes:** **This is new.** The legacy has no before-and-after view anywhere in its accounting
screens; a carrier must save a rule change first and then read failure messages. See
[`domain-model.md`](domain-model.md) §7.4.

#### FR-55: Delivery mail export

Scheduled work exports the deliveries as a spreadsheet and sends it by email.

**Consequences (testable):**
- The export is feature-equivalent to the legacy export. It is not byte-equivalent.
- The export carries every field the legacy export carries.

#### FR-56: The deliveries interface for an external accounting system

The system serves the deliveries through an interface that an external accounting synchroniser reads.

**Consequences (testable):**
- The interface exposes the same delivery and delivery-item information the legacy interface exposes.
- Access needs authentication, and it is scoped to one club.

**Notes:** `[ASSUMPTION: the external Proffix synchroniser polls the deliveries interface, and its
maintainer adapts it to the new interface. The brief puts a change to that project out of scope, so
each club arranges its own handover.]`

### 4.8 Reporting, search, and export

**Description.** A club administrator reads flight reports and builds custom reports. Search is a
named weakness of the legacy product: per-column filter and sort looked straightforward and proved
inefficient in use. AlpenFlight replaces it with one search field and a small set of deliberate
filters.

**Functional Requirements:**

#### FR-57: Flight reports

A club administrator reads the standard flight reports.

**Consequences (testable):**
- The reports match the legacy report set.
- A report never shows a record from another club.

#### FR-58: The custom report builder

A club administrator builds a report by choosing fields and conditions.

**Consequences (testable):**
- The builder matches the legacy custom-report capability.
- A built report saves and re-runs.

#### FR-59: Spreadsheet export

A user exports a list or a report as a spreadsheet.

**Consequences (testable):**
- The export is feature-equivalent to the legacy export, not byte-equivalent.
- The export carries only the records of the user's own club.

#### FR-60: One search field with filter chips

Every list offers one search field that matches across every displayed value at once, and filter
chips beside it.

**Consequences (testable):**
- The search field filters as the user types, and it needs no submit control.
- The field matches the aircraft, the pilot, the date, and the remarks together.
- Filter chips are the only filter mechanism. No column carries a filter.
- The sort control sits in the list toolbar, because a record strip has no column header.

> **This is a recorded behaviour change from the legacy product.** The brief forbids an unrecorded
> behaviour change. The supplier named the legacy per-column filter as inefficient in use on
> 2026-08-24, and chose this model.

#### FR-61: Record strips

Every list uses a record strip, and the product contains no data table.

**Consequences (testable):**
- A strip carries an identity zone, a meta zone, a metric zone, and a state marker.
- A strip stacks on a phone, and sits side by side on a pointer device.
- A strip keeps its height when a value is absent, so the list stays aligned.
- An action in the far-right slot acts on the record without opening it.

### 4.9 Public surfaces

**Description.** Three surfaces need no authentication: the landing page, the trial-flight
registration, and the passenger-flight registration. A stranger meets them on an unknown device, so
they carry a stricter accessibility floor and plainer wording than the signed-in application.

**Functional Requirements:**

#### FR-62: Trial-flight registration

A member of the public registers interest in a trial flight. Realizes UJ-6.

**Consequences (testable):**
- The form collects the contact details the legacy form collects.
- The club receives the registration by email, and the person receives a confirmation.
- Every aviation term on the form carries a plain explanation.

#### FR-63: Passenger-flight registration

A member of the public registers interest in a passenger flight.

**Consequences (testable):**
- The behaviour matches FR-62 for the passenger-flight case.

#### FR-64: The landing page

A member of the public reaches a public page that describes the club and links to the two
registration forms.

**Consequences (testable):**
- The page needs no authentication.
- The signed-in navigation does not appear on a public page.

> **Caution: the legacy client shows the navigation bar on both public pages.** The condition at
> `flsweb/src/index.js:50` is always true, which risk hotspot R12 records as a defect. Do not
> reproduce it, and cover it with a test.

#### FR-65: Abuse control on the public endpoints

The system limits the rate of submissions to the two unauthenticated endpoints.

**Consequences (testable):**
- Repeated submissions from one source are refused after a stated limit.
- A refusal does not reveal whether a record was created.
- No club mailbox fills from an automated submission.

**Notes:** `[NOTE FOR PM]` Q-B16 records that the legacy system has no such control. This is a new
capability.

### 4.10 Email and scheduled work

**Description.** Email is the primary notification channel. The brief cuts push notifications and an
in-app message inbox from the first release. Scheduled work runs the batch jobs the legacy system
runs.

**Functional Requirements:**

#### FR-66: The daily report email

Scheduled work sends the club a daily summary of its flights.

**Consequences (testable):**
- The email covers the flights of the stated day for one club only.
- The message uses the club's email template.

#### FR-67: The licence expiry notification

Scheduled work warns a person before their licence or medical expires.

**Consequences (testable):**
- The warning arrives a stated period before the expiry date.
- A person with no expiring document receives no email.

#### FR-68: The monthly aircraft statistic report

Scheduled work produces the monthly aircraft statistic report.

**Consequences (testable):**
- The report matches the legacy report.
- The job runs per club.

#### FR-69: The scheduled work dispatcher

An external timer starts the scheduled work, and the system reports the result of each run.

**Consequences (testable):**
- Every job records its start, its end, and its outcome.
- A failed job does not stop the next job.
- A job that fails is visible to the supplier without a database query.

#### FR-70: Club email templates

A club administrator edits the templates the system uses for its outgoing email.

**Consequences (testable):**
- A template holds the club's own wording.
- A template renders in German and in English.

### 4.11 Migration from the legacy Flight Logging System

**Description.** **This is the first product advantage, and the reason a club chooses AlpenFlight.**
The whole path is self-service. The supplier touches nothing, because the supplier has no capacity to
touch anything.

The path has four steps: export, upload, verify, and commit. The verify step is the one that answers
the buyer's objection, and it answers it with arithmetic.

**Functional Requirements:**

#### FR-71: The export tool

A club administrator runs one tool against the legacy database, and the tool produces one file.
Realizes UJ-3.

**Consequences (testable):**
- The tool needs read access to the legacy database and nothing else.
- The tool encrypts its output.
- The tool reads every table the migration needs, and it states what it read.

#### FR-72: Self-service upload

A club administrator uploads the export file through the browser. Realizes UJ-3.

**Consequences (testable):**
- The upload needs no action by the supplier.
- The system rejects a file it cannot decrypt or cannot read, and it states why.

#### FR-73: Count verification

Before the commit, the system shows the record counts side by side. Realizes UJ-3.

**Consequences (testable):**
- The comparison covers flights, persons, aircraft, accounting rules, reservations, and deliveries.
- Each row states the legacy count and the imported count.
- A row that does not match is marked, and it names the difference.

#### FR-74: Invoice parity verification

Before the commit, the system replays a sample of the club's recorded legacy invoices through the dry
run. Realizes UJ-3.

**Consequences (testable):**
- Each sampled delivery item reproduces to the cent, or the row is marked as a mismatch.
- The verification uses the same dry run as FR-50.
- The sample size and its selection are stated on screen.

#### FR-75: Mismatch acceptance

A club administrator accepts a mismatch with a recorded reason, and the migration continues. Realizes
UJ-3.

**Consequences (testable):**
- The system shows the legacy value and the reproduced value, side by side, for every mismatch.
- The administrator states a reason for each acceptance, and the system records who accepted it and
  when.
- The migration never blocks, and it never needs the supplier.

#### FR-76: The open mismatch list

Accepted mismatches stay visible inside the application until somebody resolves them.

**Consequences (testable):**
- A club sees its open mismatch list after the migration commits.
- Each entry carries the flight, the legacy value, the reproduced value, the reason, and the acceptor.
- Resolving an entry records who resolved it and when.

#### FR-77: Commit and provision

A club administrator commits the migration, and the club is ready to fly. Realizes UJ-3.

**Consequences (testable):**
- After the commit the club runs a flying day with no further setup.
- The whole path finishes in one session.
- A club migrates on its own schedule. No coordinated switch-over date exists.

#### FR-78: No legacy password migrates

No password moves from the legacy system into AlpenFlight.

**Consequences (testable):**
- Every migrated user sets a new password before their first sign in.
- No legacy password hash is stored, logged, or transmitted.

### 4.12 Home and the flying day

**Description.** Home is the app-open surface, not a fifth destination. It shows what happens now and
what needs attention.

**Functional Requirements:**

#### FR-79: The Home dashboard

A user opens the application and sees the state of the club today. Realizes UJ-1, UJ-5.

**Consequences (testable):**
- Home carries an airborne panel, today's flights, today's reservations, and the expiring licences
  and medicals.
- The airborne panel carries the NOW stamp and the landing stamp directly.
- When nothing is in the air, the panel says so and offers Log flight.

#### FR-80: The fast note path

An instructor adds a note to a recent flight without navigating the full logbook.

**Consequences (testable):**
- A recent flight is reachable in one action from Home.
- The note saves against a completed flight while the flight is not locked.

#### FR-81: The four destinations

The navigation offers four destinations named by job: Operate, Plan, Records, and Admin.

**Consequences (testable):**
- A phone shows four tabs in the bottom bar. A pointer device shows four destinations in the top bar.
- Every surface in the product sits inside exactly one destination.

### 4.13 Data governance surfaces

**Description.** The system holds names, addresses, licence data, and medical expiry dates. The duty
to protect that data applies from the first real club, whether or not anybody pays. These
requirements give the club the surfaces it needs to meet the duty.

**Functional Requirements:**

#### FR-82: Club data export

A club administrator exports the whole club at any time, in a form the club can read.

**Consequences (testable):**
- The export covers every record of that club, and no record of any other club.
- The export needs no action by the supplier.

#### FR-83: Club deletion on request

The supplier deletes a club and everything inside it after a written request.

**Consequences (testable):**
- The deletion removes every record of that club, including its backups, within a stated period.
- The stated period appears in the club's terms.

#### FR-84: Data-subject requests through the club administrator

A person exercises their right to see, correct, and delete their record through their club
administrator.

**Consequences (testable):**
- A club administrator finds every record about one person inside their club.
- A club administrator corrects or deletes a person record, subject to the retention rules for a
  flight record.
- The path is documented for the club administrator.

**Notes:** `[ASSUMPTION: a flight record must survive the deletion of the person record, because the
club needs its flight history. The person's identity is removed from the flight instead. Confirm this
with a legal reading before the first real club joins.]`

### 4.14 Supplier operations

**Description.** One person hosts and supports the product. These requirements exist so that the
support load stays near zero, which the cost model needs.

**Functional Requirements:**

#### FR-85: Club provisioning without the supplier

A new club reaches a working system without any manual action by the supplier.

**Consequences (testable):**
- A club completes sign-up, migration, and its first flying day with no message to the supplier.
- The first release limits sign-up to the closed user group.

#### FR-86: Error visibility

The supplier sees an unhandled error and a failed scheduled job without reading a database.

**Consequences (testable):**
- Every unhandled error reaches an error tracker with the club, the user, and the request.
- An error record never carries a member's personal data in plain text.

#### FR-87: Backup and restore

The supplier restores the whole service, and one club, from a backup.

**Consequences (testable):**
- A restore of the whole service is tested and timed.
- A restore of one club is possible without a restore of the others.
- The backup is held in Switzerland or in the European Union.

## 5. Non-Goals (Explicit)

- **AlpenFlight is not an income business.** Success is cost recovery, not growth. The supplier's
  time is a contribution and is not costed.
- **AlpenFlight does not compete as the better glider club system.** It follows the established
  competitors on their strengths. It leads only on the migration and on the speed of the flight
  form.
- **AlpenFlight does not change any ported behaviour**, unless this PRD records the change. §4.8
  FR-60 is the one recorded behaviour change in the first release.
- **AlpenFlight does not accept OGN ingestion in the first release.** **This is the one explicit
  exception to feature parity.** The legacy system accepts direct database writes from the OGN
  analyser, which risk hotspot R9 names as an external writer that bypasses every domain invariant.
  A club that uses OGN today keeps its legacy system for that path, or its flight operator logs the
  flights by hand.
- **AlpenFlight does not change the two external integration projects.** Each club arranges its own
  handover with their maintainers.
- **AlpenFlight is not a native mobile application.** The web client covers mobile use.
- **AlpenFlight does not carry a free tier.** A free tier acquires unknown users, and this product
  has none. Its clubs are a known, finite list.
- **AlpenFlight does not host an anonymous demonstration sandbox in the first release.** It exists to
  convert unknown users. A demonstration by the supplier is cheaper.

## 6. MVP Scope

### 6.1 In Scope

- **Every feature of the legacy Flight Logging System**, with faithful behaviour and no deprecation,
  except the OGN ingestion path recorded in §5. This is the product promise: a club's configuration
  survives the move.
- **The migration path**: export, upload, verify, mismatch acceptance, and commit. All self-service.
- **Accounting rule parity**, proven once against recorded legacy results at migration time (FR-74),
  and then held by the club's own billing expectations (FR-54).
- **Flight-form efficiency parity**, measured in clicks and keystrokes, plus keyboard completion,
  which is new.
- **Structural club isolation.** A query that omits the club filter fails.
- **Offline flight logging**, plus an offline read of today.
- **Short-lived access tokens with refresh.**
- **The accounting rules screens redesigned**: the ordered list, the WHEN and THEN rule cards, the
  dry run, and the reorder guard.
- **The airborne board and the NOW stamp.**
- **Season assignment as a batch action.**
- **German and English.**
- **Data governance surfaces**: club export, club deletion on request, and the data-subject path.

### 6.2 Out of Scope for MVP

- **Subscription billing.** No trial expiry, no subscription lifecycle state machine, and no hosted
  checkout. The closed user group pays outside the product, or pays nothing. **Deferred to v2.**
  `[NOTE FOR PM]` The architecture must still reserve the club lifecycle, so that the later
  promotion needs no rewrite.
- **OGN ingestion and the OGN aircraft database synchronisation.** Deferred to v2. See §5.
- **Push notifications, an in-app message inbox, reservation waiting lists, calendar feed export,
  reservation conflict detection, and per-club branding.** Rebuild 1 committed to all six. The brief
  cuts all six. They compete where the competitors already lead, and they use the time the migration
  needs.
- **Native mobile applications.** The web client covers mobile use.
- **French and Italian.** They follow when a club asks.
- **Self-service data-subject rights for a person.** The club administrator serves the request in the
  first release.
- **An anonymous demonstration sandbox with a nightly reset.**
- **Public sign-up.** The first release admits the closed user group only.

## 7. Success Metrics

**Primary**

- **SM-1: Accounting parity.** Every recorded legacy delivery item reproduces to the cent, or a club
  administrator accepted it as a mismatch with a reason. Target: 100 percent of the corpus, with the
  accepted count reported. Validates FR-49, FR-54, FR-74, FR-75.
- **SM-2: Migration data loss.** Every flight, person, aircraft, and accounting rule transfers.
  Target: zero loss. Validates FR-71, FR-73, FR-77.
- **SM-3: Self-service migration.** A club administrator finishes a migration with no message to the
  supplier. Target: every migration in the closed user group. Validates FR-72, FR-75, FR-77, FR-85.
- **SM-4: Cross-club exposure.** Target: zero. A test fails when a query omits the club filter.
  Validates FR-1, FR-2.
- **SM-5: Cost to log a flight.** The reference case costs no more clicks and no more keystrokes than
  the legacy form costs. Target: the recorded legacy pair, or fewer. Validates FR-16 to FR-26.

**Secondary**

- **SM-6: Airfield logging.** A flight operator logs a complete flight with no network connection,
  and every write reaches the server on reconnect. Target: the whole reference case. Validates FR-35
  to FR-38.
- **SM-7: Support load.** Messages to the supplier per club per month. Target: near zero. **This is
  the figure most likely to break the cost model.** Validates FR-85, FR-86.
- **SM-8: The buyer's objection is answered.** A club system carrier explains one invoice line to a
  member using the dry run, without help. Validates FR-50.
- **SM-9: Income against infrastructure cost.** Income covers the running cost every month.
  **Not measurable in the first release**, because §6.2 defers billing. It becomes measurable at the
  promotion to a real launch.

**Counter-metrics (do not optimise)**

- **SM-C1: Club count.** Growth is not a success measure. Enough clubs to cover the running cost is
  success. Counterbalances SM-9.
- **SM-C2: Feature count.** A new feature that the competitors already lead on is a loss, not a gain.
  It uses the time the migration needs. Counterbalances any pressure on §6.2.
- **SM-C3: Elapsed time to log a flight.** Do not optimise a stopwatch figure. It measures the flight
  operator's familiarity and the network, not the interface. Counterbalances SM-5.
- **SM-C4: Accepted mismatch count.** Do not drive this to zero by making acceptance easy. A club
  that accepts many mismatches has an unproven migration. Counterbalances SM-1 and SM-3.

## 8. Cross-Cutting Non-Functional Requirements

**Correctness.**

- Money is exact. Every accounting calculation reproduces to the cent, and no calculation uses a
  binary floating-point type.
- Club isolation is structural, not conventional. See FR-1.
- The client and the server derive every state name from one source. See FR-27.

**Performance and responsiveness.**

- Any action below 300 milliseconds shows no loading state at all.
- A spinner appears only for a genuinely heavy operation: an invoice run, a migration import, or a
  large export. A spinner never appears for normal record work, for navigation, or for a list load.
- Every list reserves its rows at final geometry before the data arrives. The layout never shifts.
- Every catalog is prefetched before the form that uses it opens.
- Writes are optimistic: the system shows the result, then confirms it.
- Motion is 110 milliseconds for a state change, 140 milliseconds for a conditional field, and zero
  for anything a press caused. Nothing eases, bounces, or reorders under the pointer.

**Trustworthiness.** The supplier defined this in their own words on 2026-08-24: instant reaction to
every click; data reliably saved, always; no playfulness; everything straight and exact; no
misaligned element; no flicker during loading. The rules above serve that definition.

**Security.**

- Cross-origin access is scoped. The legacy system allows every origin, which risk hotspot R6 records.
- The access token is short-lived and refreshes. See FR-4.
- A failed authentication during a write does not lose the user's entry. See FR-4.
- No member's personal data appears in plain text in a log or an error record.

**Availability.** The service must be available on a weekend day when the weather permits flying.
`[ASSUMPTION: no formal uptime commitment applies to the closed user group. A weekday outage is
tolerable. A Saturday outage is not. Set a real target at the promotion to a real launch.]`

**Data residency.** Every store, every backup, and every log stays in Switzerland or in the European
Union.

**Accessibility.** Two floors, in one product.

- *The signed-in application — internal floor.* Every action is reachable by keyboard in visual
  order. A visible focus ring is never removed. Every input has a programmatic label, and a
  placeholder is never the label. No state is carried by colour alone; `AIRBORNE`, `LOCKED`, and
  `UNSENT` each carry a word. An error is announced and tied to its field. The elapsed time updates
  politely and never steals focus.
- *The public surfaces — stricter floor.* Everything above, and WCAG 2.2 AA in full on an unknown
  device. The form works with no scripting beyond validation. No aviation term appears without a
  plain explanation. Every target is at least 44 pixels.

**Display mode.** Dark only. One ground, no light mode, no setting, and no branch. **Caution: a dark
ground is harder to read in direct sunlight, and an airfield is in direct sunlight.** The supplier
accepted this cost twice. The palette therefore carries a high contrast floor: target the WCAG AAA
body-text ratio, and forbid a low-contrast grey for any data value.

**Observability.** Every unhandled error and every failed scheduled job reaches the supplier without a
database query. See FR-86.

**Testability.** Both time gates are drivable in a test with no change to the system clock. See
FR-33.

## 9. Constraints and Guardrails

**Cost.** Income must cover the running cost: hosting, backups, the domain and certificates, error
tracking, log storage, email delivery, and the payment fee. About five paying clubs cover it, not
fifty. **The price decision matters less than it looks, so this PRD spends no effort on price.** It
spends effort on a migration that succeeds. The model excludes the supplier's time and the support
effort per club, and the support effort is the figure most likely to make the model wrong.

**Capacity.** One person builds, hosts, and supports this. Every commitment in this document respects
that limit. A requirement that adds recurring manual work for the supplier is a requirement to
reconsider.

**Privacy.** The system holds names, addresses, licence data, and medical expiry dates. Swiss and
European data protection law applies from the first real club, whether or not anybody pays. §4.13
gives the club the surfaces it needs.

**Legacy is reference-only.** The two legacy repositories are independent upstream projects. All new
code lands in `alpenflight/`. A change to legacy is legitimate only as a fix for something obviously
wrong, and only after the supplier is told.

**Operator-facing language.** Every text a person reads is written in ASD-STE100 Simplified Technical
English. This applies to the interface, to email, and to error messages, in German and in English.

## 10. Information Architecture

*Full detail lives in [`EXPERIENCE.md`](../../ux-designs/ux-fls-2026-08-24/EXPERIENCE.md). This
summary exists so that the epics can be cut along it.*

Four destinations, named by job, plus Home as the app-open surface.

| Destination | Holds |
| --- | --- |
| **Home** | The dashboard: airborne panel, today, reservations, and expiring licences and medicals |
| **Operate** | Log flight, Airborne board, Logbook, Air movements |
| **Plan** | Reservations, Scheduler, Planning days, Season assignment |
| **Records** | Members, Aircraft, Locations, Reports |
| **Admin** | Accounting rules, Invoice drafts, Billing expectations, Articles, Master data, Users, Email templates, Migration, System |

Public surfaces sit outside the four destinations: the landing page, the trial-flight registration,
and the passenger-flight registration.

**A dialog stacks one level deep, never two.** The create-in-place person form and aircraft form open
above the flight form. Nothing opens above them.

## 11. Risk and Mitigations

| Id | Risk | Why it matters | Mitigation in this PRD |
| --- | --- | --- | --- |
| RK-1 | The rules engine must reproduce a stateful decrement loop exactly | Clubs configured it over years. A wrong result breaks every invoice, and it proves the buyer's objection right. | FR-49, FR-54, FR-74, SM-1. Resolve Q-B6 and Q-B7 with the `legacy-oracle` agent before the accounting epic starts. |
| RK-2 | The legacy test suite is not a parity oracle | The 43-spec suite proves that features exist. It does not prove that they behave correctly. Nine specs are rebuild-1 artefacts that assert lightly. | Sequence the suite expansion early in the backlog. No epic may claim parity on the suite as it stands. |
| RK-3 | Club isolation is enforced by convention in the legacy system | One forgotten filter exposes another club's data. This is the largest correctness risk in the rewrite. | FR-1, SM-4. Structural enforcement, proven by a failing test. |
| RK-4 | The time gates are undocumented | A fresh database reaches neither gate, so the system looks broken. The unit, the boundary, and the timezone are all unknown. | FR-30, FR-32, FR-33. Resolve Q-B2 and Q-B3 before the flight lifecycle epic starts. |
| RK-5 | One person builds and operates this | Capacity limits every commitment. Support effort per club can break the cost model. | FR-85, FR-86, SM-7. Every migration path is self-service, including the failure path. |
| RK-6 | The speed budget has no number | FR-26 has no target until somebody measures the legacy form. | Open question 1. Measure before the first flight-form story starts. |
| RK-7 | The competitors may already support configurable accounting rules | If they do, the advantage narrows to the migration alone, and the positioning in §1 needs a correction. | Open question 3. The addendum names a cheap test. It changes the positioning, not the requirements. |
| RK-8 | The spreadsheet library licence changed | The legacy export library moved to a non-commercial licence past its 2018 version. | FR-55 and FR-59 require feature equivalence, not byte equivalence, which frees the choice. `bmad-architecture` picks the library. |

## 12. Constraint Disposition

The brief triaged the 34 rebuild-1 constraints. This PRD closes the ones the brief assigned to it.
The full table lives in [`addendum.md`](addendum.md) §2. The summary:

| Constraint | Disposition here |
| --- | --- |
| C3 structural club isolation | **Kept.** FR-1. |
| C4 Swiss or European data residency | **Kept.** §8. |
| C5 data-subject rights | **Kept, scoped.** FR-84, through the club administrator. Self-service is deferred. |
| C6 migration in one self-service session | **Kept.** FR-77. |
| C7 the legacy invariants survive | **Kept.** FR-1 to FR-3, FR-27, FR-30, FR-32, FR-49. The OGN invariant is the one exception. |
| C9 a validated mapping for the database reshape | **Kept.** FR-73, FR-74. |
| C11 accounting parity by a test corpus | **Kept.** FR-54. |
| C12 an audit record on every change | **Kept.** FR-8. |
| C13 refresh-token authentication | **Kept.** FR-4. |
| C14 no legacy password migrates | **Kept.** FR-78. |
| C16 spreadsheet export is feature-equivalent | **Kept.** FR-55, FR-59. |
| C20 email is the primary notification channel | **Kept.** §4.10. |
| C21, C22 mobile-first plus a dense desktop variant | **Kept.** §8, FR-61. |
| C25 multi-tenant service with self-onboarding | **Kept, scoped.** FR-85. Sign-up is limited to the closed user group. |
| C29 the trial expires | **Deferred to v2.** §6.2. |
| C31 subscription lifecycle states | **Deferred to v2**, but the architecture reserves the club lifecycle. §6.2. |
| C32 identical isolation and audit for a paid and an unpaid club | **Kept.** FR-1, FR-8. |
| C33 hosted checkout, no card data held | **Deferred to v2.** §6.2. |

## 13. Open Questions

1. **What are the click count and the keystroke count for the reference flight on the legacy form?**
   FR-26 has no target until somebody measures it. Owner: the supplier. Blocks the first flight-form
   story.
2. **How many clubs run the legacy Flight Logging System today?** This is the real addressable list.
   It changes the positioning and the price, not the requirements. Owner: the supplier.
3. **Do the competitors support accounting rules that each club configures?** If they do, the
   advantage narrows to the migration alone, and §1 needs a correction. The addendum names a cheap
   test. Owner: the supplier.
4. **Which clubs form the closed user group, and when does the promotion to a real launch happen?**
   It decides when SM-9 becomes measurable and when §6.2 reopens. Owner: the supplier.
5. **The sixteen open behavioural questions Q-B1 to Q-B16.** Each one blocks a domain decision.
   [`addendum.md`](addendum.md) §3 maps each question to the requirements it blocks. Owner: the
   `legacy-oracle` agent, before the epic that touches it starts.
6. **Does a flight record survive the deletion of a person record?** §4.13 assumes it does, with the
   identity removed. A legal reading must confirm it before the first real club joins.
7. **Which languages does the legacy translation table hold?** FR-14 carries German and English. The
   legacy set may be larger, which would make this a recorded reduction rather than a port.
8. **What is the real running cost per month?** §9 states the model. The figures are placeholders
   until the supplier supplies the real ones.

## 14. Assumptions Index

*Every `[ASSUMPTION]` in this document, for explicit confirmation.*

1. §2.3 — The protagonist names are fixtures from the UX prototype, and they stay fixtures until the
   supplier supplies real club names.
2. §4.2 FR-14 — The legacy translation table holds more languages than German and English. The first
   release carries two, and the rest wait for a club to ask.
3. §4.6 FR-44 — The legacy planning setup wizard is superseded by the season assignment. The first
   release ports the wizard's outcome, not its steps.
4. §4.7 FR-56 — The external Proffix synchroniser polls the deliveries interface, and its maintainer
   adapts it to the new interface. Each club arranges its own handover.
5. §4.13 FR-84 — A flight record survives the deletion of a person record, with the person's identity
   removed from the flight. Confirm with a legal reading.
6. §8 Availability — No formal uptime commitment applies to the closed user group. A weekday outage
   is tolerable, and a Saturday outage is not.
