---
title: "PRD: AlpenFlight"
status: draft
created: 2026-08-25
updated: 2026-08-29
---

# PRD: AlpenFlight

Requirements for AlpenFlight. Target state only. Decisions and their reasons are in `.memlog.md`.

Vocabulary, domain model, and the legacy name mapping: [`domain-model.md`](domain-model.md). Use
those terms exactly. A synonym is a defect.

## 1. Product

AlpenFlight is a multi-tenant service for Swiss glider clubs. It replaces the legacy Flight Logging
System feature for feature, and it transfers a club's data and charging rules intact.

A club exports its legacy database with one tool, uploads the result, verifies its own invoices, and
then runs its flying days on AlpenFlight. Each club migrates on its own schedule.

The first release serves a closed user group. Nobody pays inside the product.

Two capabilities carry the product:

1. The migration, including the verification that a club's invoices reproduce to the cent.
2. The speed of the flight form, measured in clicks and keystrokes.

## 2. Users

| User | Job |
| --- | --- |
| Club system carrier | Owns the charging rules. Answers billing questions. Decides the migration. |
| Duty flight leader | Logs the flying day at the airfield, on a phone, with poor coverage. |
| Club administrator | Runs the roster, the fleet, and the member records. Runs the migration. |
| Pilot | Reserves an aircraft. Reads their own flight history. Enters their own landing time. |
| Instructor | Adds training notes to a completed flight, before it locks. |
| Supplier | Builds, hosts, and supports the product alone. |

Not served in v1: a club with no legacy Flight Logging System history, a motor flight group, a club
outside Switzerland, a club that depends on OGN ingestion, and any paying customer.

## 3. Journeys

UJ-1 to UJ-4 match Flow 1 to Flow 4 in
[`EXPERIENCE.md`](../../ux-designs/ux-fls-2026-08-24/EXPERIENCE.md). Names are fixtures.

- **UJ-1. Log the first tow of the day.** Martin opens Log flight on a phone. Three letters select
  the glider. The form reshapes for an aerotow and fills the tug from the last flight. Three letters
  each select the pilot and the instructor. Save. The flight is open on the server. At wheels-up
  Martin presses NOW on the airborne row — one press, no dialog. The pilot enters the landing time on
  their own phone. The instructor adds notes that evening, before the flight locks.
- **UJ-2. Explain an invoice.** Ruth opens the charging rules. The list shows them in engine run
  order. Ruth reads a rule as a WHEN/THEN sentence, opens the dry run, and picks the member's flight.
  The trace shows each rule that fired, what it consumed, and what it emitted, ending at the total.
- **UJ-3. Move a club onto AlpenFlight.** Peter runs the export tool and uploads one encrypted file.
  AlpenFlight shows record counts side by side, then replays a sample of recorded legacy invoices.
  Each line reproduces to the cent or is marked a mismatch. Peter accepts each mismatch with a reason
  and commits. The club flies on AlpenFlight the next day.
- **UJ-4. Assign a season.** Beatrice sets a date range, picks every Saturday and Sunday from April to
  October, and assigns the duty flight leader and the tow pilot across all of them in one pass. In
  June she corrects one day without touching the rest.
- **UJ-5. Reserve an aircraft.** Sonja opens the scheduler, finds Saturday, sees which aircraft are
  free, and reserves one. The whole club sees it immediately.
- **UJ-6. Book a trial flight.** A member of the public opens the trial-flight form on an unknown
  device, reads plain wording, and sends their contact details. The club receives an email.

## 4. Features and requirements

### 4.1 Club isolation and identity

**FR-1: Structural club isolation.** The system rejects any data access that does not state a club.
- A query written without a club filter fails. It does not return another club's rows, and it does
  not return an empty result silently.
- An automated test proves the failure.
- A user of club A cannot reach any record of club B through any interface, including a direct
  identifier in a URL.

**FR-2: Cross-club person and crew.** A crew member may be a person from another club.
- A flight of club A naming a pilot who belongs only to club B saves, and club A sees it.
- Club B does not see club A's flight because of that reference.
- The same applies to an invoice recipient. *(Q-B8, Q-B9.)*

**FR-3: User and person separation.** A user and a person are separate linked records.
- A person exists with no user.
- A person belongs to several clubs, each membership carrying its own status and roles.
- A user belongs to exactly one club.

**FR-4: Authentication.** A user signs in and receives a short-lived access token that refreshes.
- The token expires in a short period and refreshes without a new sign in.
- A user does not sign in again during a flying day.
- A request rejected for an expired token does not lose unsaved entry. *(Q-B13.)*

**FR-5: Roles and permissions.** A role decides which surface and action a user reaches.
- A pilot cannot open the charging rules.
- A permission failure names what the user lacks.

**FR-6: Profile self-edit.** A user edits their own person record and password. A user cannot change
their own role or club.

**FR-7: Password reset and email confirmation.**
- A reset link expires after a stated period and works once.
- The response is identical whether the email address exists or not.

**FR-8: Audit record.** The system records who changed which record, when, and what changed.
- Every create, update, and delete of a flight, person, aircraft, charging rule, and invoice draft
  produces an audit record.
- A club reads only its own audit records.

### 4.2 Master data

**FR-9: Club master data.** A club administrator manages clubs, aircraft, locations, flight types,
membership statuses, person categories, persons, articles, and email templates.
- A delete that another record references fails and names the reference.

**FR-10: Licences and medicals.** A person carries licence and medical expiry dates.
- A person whose medical expires within a stated period appears on Home.

**FR-11: Create master data in place.** A user creates a missing person or aircraft from inside the
flight form.
- The dialog opens above the flight form. Nothing opens above the dialog.
- On save the dialog closes, the new record is selected in the field that opened it, and the focus
  moves to the next field.
- The part-entered flight loses no value.
- The new record is marked a stub record.

**FR-12: Operating counters.** The system holds a flight counter and an engine counter per aircraft.
The form fills the start counter from the aircraft's last recorded counter.

**FR-13: Reference data.** Countries, counter units, length units, elevation units, location types,
and launch methods exist and match the legacy set.

**FR-14: Translations.** A system administrator manages the interface translations.
- v1 carries German and English.
- A missing translation renders a stated fallback, never an empty string or a raw key. *(Q-B15.)*

**FR-15: System data and logs.** A system administrator reads the system logs and manages the system
data. A club administrator reaches neither.

### 4.3 Flight logging

**FR-16: One form.** A duty flight leader records a flight in one form.
- The reference case needs zero step changes and zero page changes on a phone.
- The form covers a glider flight, a tow flight, and a powered flight.
- A flight saves with fields still empty.

**FR-17: Conditional fields.** The form hides the fields the current selection makes irrelevant.
- A winch launch shows no tug field and no release altitude field. An aerotow shows both.
- A field that appears or disappears does not move the field the user is about to press. The form
  reveals downward and holds the scroll position.

**FR-18: Typeahead over prefetched catalogs.** Every catalog field filters a list the client holds.
- Every catalog field in the form is a typeahead. No plain select exists in the form.
- The form prefetches every catalog before it opens.
- A person picker matches the first name, the last name, and the city together.
- The list opens on focus, before the first keystroke.
- When nothing matches, the last entry creates the record. See FR-11.

**FR-19: Date and time entry.** Every date and time field accepts typing and pointing equally.
- A date field accepts `29.08.2026` typed and a click in a calendar. Both write the same value.
- A time field accepts `1024` and `10:24`, and it formats on blur.
- A clear control empties the field.
- Neither path is a fallback for the other. The product does not use native date or time inputs.

**FR-20: The NOW stamp.** One press stamps the block start or the landing time.
- One press writes the current time to that one field and saves it.
- No dialog and no confirmation.
- It works from the airborne board without opening the flight.
- At least 56 pixels on a phone, inside the thumb arc.
- A hold never blocks it. See FR-40.
- An automatic source can replace the press with no screen change.

**FR-21: Copy from the last flight.** The form fills the tug, the outbound and inbound routes, and
the engine counter from the previous flight on that device.
- The values persist between sessions on the device.
- One control per group performs the copy.
- A copied value carries a visible mark.

**FR-22: Smart defaults.** With nothing to copy, the form falls back to the last takeoff airfield,
then to the club's home airfield. Every default is editable.

**FR-23: Keyboard operation.** A user completes the form with the keyboard alone.
- `Tab` moves to the next field in visual order and skips a hidden field.
- `Enter` in a typeahead takes the highlighted entry and moves on.
- `Enter` in the form saves, and never submits from inside an open typeahead.
- `Esc` closes a dialog or a typeahead, and never discards the form.
- `n` stamps NOW on the highlighted airborne row. `/` focuses the search field. `?` shows the key
  list.

**FR-24: Airborne board.** A duty flight leader sees and acts on every flight in the air.
- The board lists every flight with a block start and no landing time.
- Each row offers the NOW stamp and the landing stamp without opening the flight.
- The elapsed time counts up and never steals the focus.
- Groups: at the start, in the air, landed today, older.
- A full screen on a phone, a panel on Home on a pointer device.

**FR-25: Copy a flight.** A user creates a new flight from an existing one. It carries the aircraft,
the crew, and the route, and no times and no identifiers.

**FR-26: Speed budget.** The reference case costs no more clicks and no more keystrokes than the
legacy form.
- Reference case: one glider flight with a tow, logged from empty.
- An automated browser test counts the mouse events and the key events, and it fails when either
  count exceeds the recorded legacy figure. *(No target until open question 1 is answered.)*

### 4.4 Flight lifecycle

**FR-27: Two state dimensions.** A flight has an air state computed from its timestamps, and a stored
process state.
- The air state derives from the timestamps on every read and is never the stored authority.
- The client and the server derive the state names from one source.
- An illegal process-state transition fails and names both states.

**FR-28: The open flight is shared.** A part-entered flight is a server record the whole club sees.
- A flight saves with no times and no complete crew.
- A different user on a different device opens it and completes it.
- It is never a private draft on one device.

**FR-29: Daily validation.** Scheduled work validates each club's flights and sets the process state.
- A flight that fails validation moves to the invalid state with a readable reason.
- The job never crosses a club boundary. *(Q-B1.)*

**FR-30: Lock gate.** A flight becomes read-only when the lock gate passes.
- A locked flight rejects every field change.
- The screen states that the flight is locked, and when. *(Q-B2, Q-B3.)*

**FR-31: Lock countdown.** A flight inside its final period before the lock states the time
remaining.

**FR-32: Billing gate.** A flight becomes eligible for the rules engine when the billing gate passes.
The engine never processes a flight before it. *(Q-B2, Q-B3.)*

**FR-33: Testable clock.** A test drives a flight from creation through both gates in one run, with
no change to the system clock.

**FR-34: Aircraft movements.** A club administrator records the powered-aircraft movements, on the
same flight record.

### 4.5 Offline work and concurrency

**FR-35: Offline write path.** The flight form, the NOW stamp, and the landing stamp work with no
network.
- The device stores the write locally, and the write survives a browser restart.
- No offline action needs a network round trip to complete on screen.

**FR-36: Offline read of today.** The device holds today's flights, the airborne board, and every
catalog for offline reading.
- Every typeahead filters offline.
- Reservations, member records, accounting, and reports need a connection, and they say so plainly.

**FR-37: Unsent marker.** A record written offline carries a visible marker until the server accepts
it.
- The record shows `UNSENT`, and the top bar shows the unsent count.
- The marker never blocks the user.
- A failed save keeps the value on screen, marks the record unsent, and never retries silently.

**FR-38: Reconnect and conflict.** On reconnect the device applies its queued writes when nothing
conflicts.
- A conflict shows both values side by side, with who wrote each and when.
- Nothing is discarded until a person picks.
- An offline device never blocks another user, and it never takes a hold. *(Q-B12.)*

**FR-39: Hold.** The user who opens the full edit form holds the flight. Every other user sees it
read-only.
- The read-only view names the holder.
- The hold releases when the holder closes the flight, and after a short idle period.
- Any user takes over at any time. The system names who they take over from, and it tells that
  person.

**FR-40: A stamp bypasses the hold.** A hold never blocks the NOW stamp or the landing stamp.
- A duty flight leader stamps a block start on a flight a pilot holds.
- A stamp writes one field and changes no other field.

### 4.6 Reservations and planning

**FR-41: Reservations.** A pilot creates, changes, and cancels a reservation for an aircraft and a
timeslot.
- A reservation names the aircraft, the pilot, the start, and the end.
- The whole club sees it on save.
- A pilot cannot change another pilot's reservation unless their role permits it.

**FR-42: Scheduler.** A pilot sees the club's reservations in a calendar view that shows which
aircraft are free on a chosen day.

**FR-43: Roster day.** A club administrator manages one flying day at one airfield with its duty
assignments.
- A roster day is identified by its airfield and its date.
- A change to one roster day changes no other. *(Q-B14.)*

**FR-44: Season assignment.** A club administrator assigns a duty across a date range in one pass.
- The administrator picks a date range and the weekdays inside it.
- One action creates or updates every matching roster day.
- A later single-day correction leaves the season untouched.

**FR-45: Roster notification.** Scheduled work emails the assigned people about their roster day,
with the club's email template.

### 4.7 Charging and invoicing

**FR-46: Charging rules as WHEN and THEN.** A club system carrier reads and edits a charging rule as
a WHEN condition and a THEN result.
- A rule card reads as one sentence.
- WHEN covers the rule kind, the aircraft, the takeoff and landing airfields, the launch methods, the
  flight types, the crew roles, the person category, the membership status, club membership, the home
  airfield, and the flight duration band.
- THEN covers the article, the charging unit, the recipient, the line text, the three no-landing-fee
  flags, and the club-internal charge flag.
- The rules editor is read-only on a phone.

**FR-47: The run order is visible.** The rule list shows the rules grouped by kind, in the order the
rules engine runs them.
- Each rule shows its kind and its position in the run order.
- The order the list shows is the order the engine uses.
- *Open question 3 decides whether a carrier changes the order.*

**FR-48: Engine-stop flag.** Every rule card shows whether the rule stops the rules engine, with a
distinct mark. It never appears only inside an editor.

**FR-49: Rules engine.** The engine reproduces every recorded legacy invoice line to the cent.
- A recorded legacy flight, replayed against the club's migrated rules, emits the same lines, the
  same amounts, and the same recipients.
- The engine consumes the active flight time in the legacy phase order.
- A rule that decrements by zero does not loop forever. *(Q-B6, Q-B7.)*

**FR-50: Dry run.** A club system carrier replays the engine against one real flight and reads the
trace.
- The trace lists every rule that fires, in run order.
- Each entry shows what it consumed from the remaining active flight time, and what it emitted.
- The trace ends with the remainder and the total.
- The dry run changes nothing.
- The same dry run serves the migration verification. See FR-74.

**FR-51: Invoice draft creation.** Scheduled work runs the engine over the eligible flights and
creates the invoice drafts.
- The job processes only flights past the billing gate.
- A draft moves from prepared to booked. Booked is terminal.
- The job never crosses a club boundary.

**FR-52: Invoice draft management.** A club system carrier reads, edits, and deletes an invoice draft
and its lines.
- A booked draft rejects a change.
- A flight links to the draft it produced, and the draft links back.

**FR-53: Recipient snapshot.** An invoice draft holds a copy of the recipient's name and address, so
a later change to the person does not rewrite history.

**FR-54: Billing expectations.** A club system carrier holds billing expectations — a real flight plus
the invoice it must produce — and runs them to find an unintended change.
- An expectation names one flight, the expected invoice, and the fields that may differ.
- Running one expectation or all of them reports pass or fail, and names every field that differs.
- The screen shows the expected invoice and the last actual invoice together, and names the rules
  that fired.
- A carrier re-baselines an expectation in one action when the difference is intended.
- A club seeds its first expectations from the invoices that FR-74 verified.

**FR-54a: Change preview.** A club system carrier sees what a rule change does before committing it.
- The preview runs against the club's billing expectations, or against a recent real flight.
- It shows the invoice before and after, side by side.
- The change commits only after the carrier confirms with the numbers visible.

**FR-55: Invoice mail export.** Scheduled work exports the invoice drafts as a spreadsheet and emails
it. The export is feature-equivalent to the legacy export, not byte-equivalent.

**FR-56: Invoice interface for an external accounting system.** The system serves the invoice drafts
through an interface that an external synchroniser reads.
- It exposes the same information the legacy interface exposes.
- Access needs authentication and is scoped to one club.

### 4.8 Reporting, search, and lists

**FR-57: Flight reports.** A club administrator reads the standard flight reports. No report shows
another club's record.

**FR-58: Custom report builder.** A club administrator builds a report from fields and conditions. A
built report saves and re-runs.

**FR-59: Spreadsheet export.** A user exports a list or a report as a spreadsheet. It is
feature-equivalent to the legacy export, and it covers only the user's own club.

**FR-60: One search field with filter chips.** Every list offers one search field that matches across
every displayed value, with filter chips beside it.
- The field filters as the user types, and it needs no submit control.
- It matches the aircraft, the pilot, the date, and the remarks together.
- Filter chips are the only filter mechanism. No column carries a filter.
- The sort control sits in the list toolbar.

**FR-61: Record strips.** Every list uses a record strip. The product contains no data table.
- A strip carries an identity zone, a meta zone, a metric zone, and a state marker.
- It stacks on a phone, and it sits side by side on a pointer device.
- It keeps its height when a value is absent.
- An action in the far-right slot acts on the record without opening it.

### 4.9 Public surfaces

**FR-62: Trial-flight registration.** A member of the public registers interest in a trial flight.
- The form collects the contact details the legacy form collects.
- The club receives an email, and the person receives a confirmation.
- Every aviation term carries a plain explanation.

**FR-63: Passenger-flight registration.** As FR-62, for the passenger-flight case.

**FR-64: Landing page.** A public page describes the club and links to both registration forms.
- It needs no authentication.
- The signed-in navigation does not appear on a public page.

**FR-65: Abuse control.** The system limits the submission rate to both unauthenticated endpoints.
- Repeated submissions from one source are refused after a stated limit.
- A refusal does not reveal whether a record was created. *(New capability. Q-B16.)*

### 4.10 Email and scheduled work

**FR-66: Daily report email.** Scheduled work emails a club its daily flight summary, for that club
only, with the club's template.

**FR-67: Licence expiry notification.** Scheduled work warns a person a stated period before a licence
or a medical expires. A person with nothing expiring receives no email.

**FR-68: Monthly aircraft statistic report.** Scheduled work produces the monthly report, per club.

**FR-69: Scheduled work dispatcher.** An external timer starts the scheduled work.
- Every job records its start, its end, and its outcome.
- A failed job does not stop the next job.
- A failure is visible to the supplier without a database query.

**FR-70: Email templates.** A club administrator edits the club's outgoing email templates, in German
and in English.

### 4.11 Migration

**FR-71: Export tool.** A club administrator runs one tool against the legacy database, and the tool
produces one file.
- It needs read access to the legacy database and nothing else.
- It encrypts its output.
- It states what it read.

**FR-72: Self-service upload.** A club administrator uploads the file through the browser.
- It needs no action by the supplier.
- A file the system cannot decrypt or read is rejected with a reason.

**FR-73: Count verification.** Before the commit, the system shows the record counts side by side.
- The comparison covers flights, persons, aircraft, charging rules, reservations, and invoice drafts.
- Each row states the legacy count and the imported count, and it marks a difference.

**FR-74: Invoice verification.** Before the commit, the system replays a sample of the recorded legacy
invoices through the dry run.
- Each sampled line reproduces to the cent, or the system marks it a mismatch.
- The sample size and its selection appear on screen.

**FR-75: Mismatch acceptance.** A club administrator accepts a mismatch with a recorded reason, and
the migration continues.
- The system shows the legacy value and the reproduced value side by side.
- It records the reason, who accepted, and when.
- The migration never blocks, and it never needs the supplier.

**FR-76: Open mismatch list.** An accepted mismatch stays visible in the application until somebody
resolves it.
- Each entry carries the flight, both values, the reason, and the acceptor.
- Resolving records who resolved it and when.

**FR-77: Commit and provision.** A club administrator commits, and the club is ready to fly.
- After the commit the club runs a flying day with no further setup.
- The whole path finishes in one session.
- Each club migrates on its own schedule.

**FR-78: No legacy password migrates.** Every migrated user sets a new password before their first
sign in. The system stores, logs, and transmits no legacy password hash.

### 4.12 Home

**FR-79: Home dashboard.** A user opens the application and sees the club today.
- Home carries an airborne panel, today's flights, today's reservations, and the expiring licences
  and medicals.
- The airborne panel carries the NOW stamp and the landing stamp directly.
- With nothing in the air, the panel says so and offers Log flight.

**FR-80: Fast note path.** An instructor adds a note to a recent flight in one action from Home, while
the flight is not locked.

**FR-81: Four destinations.** The navigation offers Operate, Plan, Records, and Admin.
- Four tabs in the bottom bar on a phone. Four destinations in the top bar on a pointer device.
- Every surface sits inside exactly one destination.

### 4.13 Data governance

**FR-82: Club export.** A club administrator exports the whole club at any time, in a club-readable
form. It covers every record of that club and no other, and it needs no action by the supplier.

**FR-83: Club deletion.** The supplier deletes a club and everything in it after a written request,
including the backups, within a stated period. The period appears in the club's terms.

**FR-84: Data-subject requests.** A person exercises their rights through their club administrator.
- A club administrator finds every record about one person inside their club.
- A club administrator corrects or deletes a person record, subject to flight retention.
- The path is documented for the club administrator.

### 4.14 Supplier operations

**FR-85: Provisioning without the supplier.** A club completes sign-up, migration, and its first
flying day with no message to the supplier. v1 limits sign-up to the closed user group.

**FR-86: Error visibility.** Every unhandled error and every failed job reaches an error tracker with
the club, the user, and the request. No error record carries personal data in plain text.

**FR-87: Backup and restore.** The supplier restores the whole service, and one club, from a backup.
- A whole-service restore is tested and timed.
- One club restores without a restore of the others.
- The backups are held in Switzerland or in the European Union.

## 5. Non-goals

- Growth. Success is cost recovery.
- Competing on the features the established competitors already lead on.
- Any behaviour change to a ported feature, except FR-47, FR-54a, and FR-60.
- OGN ingestion and the OGN aircraft database synchronisation. This is the one exception to feature
  parity. A club that uses OGN keeps its legacy system for that path, or it logs by hand.
- Changes to the two external integration projects. Each club arranges its own handover.
- A native mobile application.
- A free tier.
- An anonymous demonstration sandbox.

## 6. Scope

**In v1.** Every legacy feature except OGN ingestion. The migration path, self-service end to end.
Rules engine parity, held by billing expectations. Flight-form speed parity plus keyboard operation.
Structural club isolation. Offline flight writing plus an offline read of today. Short-lived tokens
with refresh. The charging-rule screens: the grouped rule list, the WHEN/THEN cards, the dry run, and
the change preview. The airborne board and the NOW stamp. Season assignment. German and English. Club
export, club deletion, and the data-subject path.

**Deferred to v2.** Subscription billing: trial expiry, subscription lifecycle states, and hosted
checkout. The architecture reserves the club lifecycle, so the promotion needs no rewrite. OGN
ingestion and the aircraft database synchronisation. Push notifications, an in-app message inbox,
reservation waiting lists, calendar feed export, reservation conflict detection, and per-club
branding. Native mobile applications. French and Italian. Self-service data-subject rights. Public
sign-up.

## 7. Success metrics

**Primary**

- **SM-1** Every recorded legacy invoice line reproduces to the cent, or a club administrator accepted
  it as a mismatch with a reason. Target 100 percent of the corpus, with the accepted count reported.
  Validates FR-49, FR-54, FR-74, FR-75.
- **SM-2** Migration data loss. Target zero. Validates FR-71, FR-73, FR-77.
- **SM-3** Migrations finished with no message to the supplier. Target every migration. Validates
  FR-72, FR-75, FR-77, FR-85.
- **SM-4** Cross-club exposure. Target zero. Validates FR-1, FR-2.
- **SM-5** Clicks and keystrokes for the reference case. Target the recorded legacy pair, or fewer.
  Validates FR-16 to FR-26.

**Secondary**

- **SM-6** A complete flight logged with no network, with every write reaching the server on
  reconnect. Validates FR-35 to FR-38.
- **SM-7** Messages to the supplier per club per month. Target near zero. Validates FR-85, FR-86.
- **SM-8** A club system carrier explains one invoice line to a member with the dry run, unaided.
  Validates FR-50.
- **SM-9** Income covers the running cost every month. Not measurable in v1.

**Counter-metrics — do not optimise**

- **SM-C1** Club count. Enough clubs to cover the running cost is success.
- **SM-C2** Feature count.
- **SM-C3** Elapsed time to log a flight. It measures familiarity and the network, not the interface.
- **SM-C4** Accepted mismatch count. Do not drive it to zero by making acceptance easy.

## 8. Cross-cutting requirements

**Correctness.** Money is exact. No accounting calculation uses a binary floating-point type. Club
isolation is structural. The client and the server derive every state name from one source.

**Responsiveness.** No loading state below 300 milliseconds. A spinner appears only for an invoice
run, a migration import, or a large export. Every list reserves its rows at final geometry, and the
layout never shifts. Every catalog is prefetched before its form opens. Writes are optimistic. Motion
is 110 milliseconds for a state change, 140 for a conditional field, and zero for anything a press
caused. Nothing eases, bounces, or reorders under the pointer.

**Security.** Cross-origin access is scoped. Tokens are short-lived and refresh. A failed
authentication during a write does not lose the entry. No personal data appears in plain text in a log
or an error record.

**Availability.** The service is available on a weekend day when the weather permits flying. No formal
uptime commitment applies to the closed user group.

**Data residency.** Every store, every backup, and every log stays in Switzerland or in the European
Union.

**Accessibility — the signed-in application.** Every action is reachable by keyboard in visual order.
A visible focus ring is never removed. Every input has a programmatic label, and a placeholder is
never the label. No state is carried by colour alone: `AIRBORNE`, `LOCKED`, and `UNSENT` each carry a
word. An error is announced and tied to its field. The elapsed time updates politely and never steals
the focus.

**Accessibility — the public surfaces.** All of the above, and WCAG 2.2 AA in full on an unknown
device. The form works with no scripting beyond validation. No aviation term appears without a plain
explanation. Every target is at least 44 pixels.

**Display.** Dark only. One ground, no light mode, and no setting. The palette carries a high contrast
floor: the WCAG AAA body-text ratio, and no low-contrast grey for any data value.

**Observability.** Every unhandled error and every failed job reaches the supplier without a database
query.

**Testability.** Both time gates are drivable in a test with no change to the system clock.

**Language.** Every text a person reads is ASD-STE100 Simplified Technical English — the interface,
the email, and the error messages, in German and in English.

## 9. Constraints

- Income covers the hosting, the backups, the domain and certificates, the error tracking, the log
  storage, the email delivery, and the payment fee. About five paying clubs cover it.
- One person builds, hosts, and supports the product. A requirement that adds recurring manual work
  for the supplier is a requirement to reconsider.
- Swiss and European data protection law applies from the first real club.
- `flsserver/` and `flsweb/` are reference-only. All new code lands in `alpenflight/`.

## 10. Information architecture

Four destinations plus Home. Detail in
[`EXPERIENCE.md`](../../ux-designs/ux-fls-2026-08-24/EXPERIENCE.md).

| Destination | Holds |
| --- | --- |
| Home | Airborne panel, today, reservations, expiring licences and medicals |
| Operate | Log flight, Airborne board, Logbook, Aircraft movements |
| Plan | Reservations, Scheduler, Roster days, Season assignment |
| Records | Members, Aircraft, Locations, Reports |
| Admin | Charging rules, Invoice drafts, Billing expectations, Articles, Master data, Users, Email templates, Migration, System |

The public surfaces sit outside the four: the landing page and both registration forms.

A dialog stacks one level deep, never two.

## 11. Risks

| Id | Risk | Mitigation |
| --- | --- | --- |
| RK-1 | The rules engine must reproduce a stateful decrement loop exactly | FR-49, FR-54, FR-74, SM-1. Resolve Q-B6 and Q-B7 before the accounting epic. |
| RK-2 | The legacy test suite proves that features exist, not that they behave | Expand the suite early. No epic claims parity on it as it stands. |
| RK-3 | Club isolation is conventional in the legacy system | FR-1, SM-4. Structural enforcement, proven by a failing test. |
| RK-4 | The time gates are undocumented | FR-30, FR-32, FR-33. Resolve Q-B2 and Q-B3 before the lifecycle epic. |
| RK-5 | One person builds and operates this | FR-85, FR-86, SM-7. Every path is self-service, including the failure path. |
| RK-6 | The speed budget has no number | Open question 1. Measure before the first flight-form story. |
| RK-7 | The competitors may already support configurable charging rules | Open question 8. It changes the positioning, not the requirements. |
| RK-8 | The legacy spreadsheet library licence changed | FR-55 and FR-59 need feature equivalence, which frees the library choice. |

## 12. Open questions

**Blocking a story or a decision**

1. **The legacy click and keystroke count for the reference flight.** FR-26 has no target. Owner: the
   supplier. Blocks the first flight-form story.
2. **Rename "flight operator" to "duty flight leader"?** Applied in this PRD and in
   [`domain-model.md`](domain-model.md). Not yet applied in `EXPERIENCE.md` or `DESIGN.md`. Owner: the
   supplier.
3. **Does FR-47 let a carrier change the rule order?** The legacy fixes the order by rule kind. Owner:
   the supplier.
4. **Rename `Delivery` to `InvoiceDraft` in the domain, keeping `/deliveries` on the wire?** Applied
   in this PRD. Confirm the wire path. Owner: the supplier, then `bmad-architecture`.
5. **Does a flight record survive the deletion of a person record?** FR-84 assumes it does, with the
   identity removed. Needs a legal reading before the first real club.
6. **Which languages does the legacy translation table hold?** FR-14 carries two.

**Not blocking**

7. How many clubs run the legacy system today?
8. Do the competitors support configurable charging rules?
9. Which clubs form the closed user group, and when does the promotion happen?
10. What is the real running cost per month?

**Behavioural questions for the `legacy-oracle` agent.** Resolve each before the epic that touches it.
Source: `docs/modernization/01-current-state.md` §9.

| Id | Question | Blocks |
| --- | --- | --- |
| Q-B1 | Which validation failure sets the invalid process state? | FR-29 |
| Q-B2 | Is the lock gate counted in calendar days, business days, or hours? | FR-30, FR-31, FR-33 |
| Q-B3 | Is a gate boundary inclusive, and in which timezone? | FR-30, FR-32, FR-33, FR-68 |
| Q-B4 | What happens to a tow flight when its glider flight is deleted? | FR-16, FR-27 |
| Q-B5 | What stops the tow-flight validation recursion on a cycle? | FR-16, FR-29 |
| Q-B6 | Within one rule kind, which rule applies first? | FR-47, FR-49, FR-50 |
| Q-B7 | Can a rule decrement the active flight time by zero? | FR-49 |
| Q-B8 | Which club's filter admits a flight whose crew belongs to another club? | FR-1, FR-2 |
| Q-B9 | The same for an invoice recipient. | FR-1, FR-2, FR-51 |
| Q-B12 | What is the default when a mutating endpoint has no cache invalidation? | FR-35 to FR-38 |
| Q-B13 | What happens on a failed authentication during a write? | FR-4, FR-37 |
| Q-B14 | What does a club see before it has any airfield or aircraft? | FR-43, FR-85 |
| Q-B15 | What renders when a translation is missing? | FR-14 |
| Q-B16 | What rate limit protects the unauthenticated endpoints? | FR-65 |

Q-B10 and Q-B11 concern OGN ingestion. They return with the v2 OGN epic.

## 13. Assumptions

1. The journey protagonist names stay fixtures until the supplier supplies real club names.
2. The legacy translation table holds more languages than German and English.
3. The legacy planning setup wizard is superseded by the season assignment. v1 ports its outcome, not
   its steps.
4. The external accounting synchroniser polls the invoice interface, and its maintainer adapts it.
5. A flight record survives the deletion of a person record, with the identity removed.
6. No formal uptime commitment applies to the closed user group.
