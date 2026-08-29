---
title: "PRD: AlpenFlight"
status: draft
created: 2026-08-25
updated: 2026-08-29
architecture: ../../architecture/architecture-fls-2026-08-29/ARCHITECTURE-SPINE.md
---

# PRD: AlpenFlight

Requirements for AlpenFlight. Target state only. Decisions and their reasons are in `.memlog.md`.

Vocabulary, domain model, and the legacy name mapping: [`domain-model.md`](domain-model.md). Use
those terms exactly. A synonym is a defect.

**The architecture is decided.**
[`ARCHITECTURE-SPINE.md`](../../architecture/architecture-fls-2026-08-29/ARCHITECTURE-SPINE.md)
carries 20 invariants (AD-1 to AD-20) and it is the authority on *how*. This PRD stays the authority
on *what*. Where an `AD-n` is cited below, the spine holds the rule and its memlog holds the reason.

## 1. Product

AlpenFlight is a multi-tenant service for Swiss glider clubs. It replaces the legacy Flight Logging
System feature for feature, and it transfers a club's data and charging rules intact.

**The scope is every legacy feature except OGN ingestion.** Section 4 carries the 90 requirements.
Section 5 carries the boundary.

A club exports its legacy database with one tool, uploads the result, verifies its own invoices, and
then runs its flying days on AlpenFlight. Each club migrates on its own schedule.

The first release serves a closed user group. Nobody pays inside the product.

Two capabilities decide if the product succeeds:

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

**These journeys illustrate the product. They are not the scope. Section 4 is the scope.**

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
  AlpenFlight shows record counts side by side, then replays a sample of the migrated legacy
  invoices. Each line reproduces to the cent or is marked a mismatch. Peter accepts each mismatch with
  a reason and commits. The club flies on AlpenFlight the next day.
- **UJ-4. Assign a season.** Beatrice sets a date range, picks every Saturday and Sunday from April to
  October, and assigns the duty flight leader and the tow pilot across all of them in one pass. In
  June she corrects one day without touching the rest.
- **UJ-5. Reserve an aircraft.** Sonja opens the scheduler, finds Saturday, sees which aircraft are
  free, and reserves one. The whole club sees it immediately.
- **UJ-6. Book a trial flight.** A member of the public opens the trial-flight form on an unknown
  device, reads plain wording, and sends their contact details. The club receives an email.

## 4. Features and requirements

**The id scheme.** An id is stable. The pattern is `FR-` plus a number, and sometimes one lowercase
letter, as in `FR-54a`. A letter marks a requirement added next to an existing one. Never renumber an
id. Tooling must accept the letter.

Section 13 maps every group to its legacy source.

### 4.1 Club isolation and identity

**FR-1: Structural club isolation.** The system rejects any data access that does not name a club.
- The system rejects a query with no club filter. It does not return another club's rows, and it does
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
- The system carries a defined role set. Each role names the surfaces it reaches. *(Q-B17.)*

**FR-6: Profile self-edit.** A user edits their own person record and password. A user cannot change
their own role or club.

**FR-7: Password reset and email confirmation.**
- A reset link expires after a fixed period and works once.
- The response is identical whether the email address exists or not.

**FR-8: Audit record.** The system writes an audit entry for every change.
- The entry names who changed the record, which record, when, and what changed.
- Every create, update, and delete of a flight, person, aircraft, charging rule, and invoice draft
  produces an audit record.
- An audit record names its club.
- A club reads only its own audit records. **This is a behaviour change.** The legacy audit log
  carries no club, and it serves every club's history to any authenticated user. See RK-11 and open
  question 26.

**FR-88: User management.** A club administrator manages the users of their own club.
- The administrator creates a user, links it to a person, and deactivates it.
- The administrator assigns and removes a role, within the role set FR-5 defines.
- The administrator cannot reach a user of another club.
- A deactivated user cannot sign in, and its audit records stay. *(Q-B17.)*

### 4.2 Master data

**FR-9: Club master data.** A club administrator manages clubs, aircraft, locations, flight types,
membership statuses, person categories, persons, articles, and email templates. FR-88 covers users.
- A delete that another record references fails and names the reference.

**FR-10: Licences and medicals.** A person carries licence and medical expiry dates.
- A person whose medical expires within a fixed period appears on Home.

**FR-11: In-place record creation.** A user creates a missing person or aircraft from inside the
flight form.
- The dialog opens above the flight form. Nothing opens above the dialog.
- On save the dialog closes. The field that opened the dialog shows the new record, and the focus
  moves to the next field.
- The part-entered flight loses no value.
- The system marks the new record a stub record.
- A stub record cannot be an invoice recipient until it carries a name and an address. See FR-53.

**FR-12: Operating counters.** The system carries a flight counter and an engine counter per
aircraft. The form fills the start counter from the aircraft's last entered counter.

**FR-13: Reference data.** Countries, counter units, length units, elevation units, location types,
and launch methods exist and match the legacy set.

**FR-14: Translations.** A system administrator manages the interface translations.
- v1 carries German and English.
- A missing translation renders a defined fallback, never an empty string or a raw key. *(Q-B15.)*
- A user reads one language. Open question 22 decides where the preference lives.

**FR-15: System data and logs.** A system administrator reads the system logs and manages the system
data. A club administrator reaches neither.

### 4.3 Flight logging

**FR-16: One form.** A duty flight leader records a flight in one form.
- The form has no wizard step and no page change on a phone.
- The form covers a glider flight, a tow flight, and a powered flight.
- A flight saves with fields still empty.

**FR-17: Conditional fields.** The form hides the fields the current selection makes irrelevant.
- A winch launch shows no tug field and no release altitude field. An aerotow shows both.
- A field that appears or disappears does not move the field the user is about to press. The form
  reveals downward and holds the scroll position.

**FR-18: Typeahead over prefetched catalogs.** Every catalog field filters a list the client carries.
- Every catalog field in the form is a typeahead. No plain select exists in the form.
- The form prefetches every catalog before it opens.
- A person picker matches the first name, the last name, and the city together.
- The list opens on focus, before the first keystroke.
- When nothing matches, the last entry creates the record. See FR-11.

**FR-19: Date and time entry.** Every date and time field accepts typing and pointing equally.
- A date field accepts `29.08.2026` typed and a click in a calendar. Both write the same value.
- A time field accepts `1024` and `10:24`, and it formats on blur.
- The field refuses an impossible value, such as `2530`, and it names the fault.
- A clear control empties the field.
- Neither path is a fallback for the other. The product does not use native date or time inputs.
- A landing time earlier than the block start is a fault. *(Q-B25.)*

**FR-20: The NOW stamp.** One press stamps the block start or the landing time.
- One press writes the current time to that one field and saves it.
- No dialog and no confirmation.
- It works from the airborne board without opening the flight.
- At least 56 pixels on a phone, in the area a thumb reaches.
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
- The elapsed time counts up and never moves the focus.
- Groups: at the start, in the air, landed today, older.
- A row in the older group offers a correction, so a mistaken stamp leaves the board.
- A full screen on a phone, a panel on Home on a pointer device.

**FR-25: Flight copy.** A user creates a new flight from an existing one. It carries the aircraft,
the crew, and the route, and no times and no identifiers.

**FR-26: Speed budget.** The reference case costs no more clicks and no more keystrokes than the
legacy form.
- Reference case: one glider flight with a tow, logged from empty.
- An automated browser test counts the mouse events and the key events, and it fails when either
  count exceeds the measured legacy figure.
- Until open question 1 supplies the figure, the test measures both counts and reports them. It does
  not fail. The first flight-form story does not wait for the figure.

### 4.4 Flight lifecycle

**FR-27: Two state dimensions.** A flight has an air state computed from its timestamps, and a stored
process state.
- The air state derives from the timestamps on every read and is never the stored authority.
- Every combination of timestamps maps to exactly one air state name, including the incomplete
  combinations, such as a landing time with no block start.
- The client and the server derive the state names from one source.
- An illegal process-state transition fails and names both states.

**FR-28: Shared open flight.** A part-entered flight is a server record the whole club sees.
- A flight saves with no times and no complete crew.
- A different user on a different device opens it and completes it.
- It is never a private draft on one device.

**FR-29: Daily validation.** Scheduled work validates each club's flights and sets the process state.
- A flight that fails validation moves to the invalid state with a readable reason.
- The job never crosses a club boundary. *(Q-B1.)*
- An open flight with no times at all follows a defined path. *(Q-B23.)*

**FR-30: Lock gate.** A flight becomes read-only when the lock gate passes.
- A locked flight rejects every field change.
- The screen shows that the flight is locked, and when. *(Q-B2, Q-B3.)*
- A queued offline write that meets a locked flight fails, and the device keeps the value on screen.
  See FR-37.

**FR-31: Lock countdown.** The screen shows the time remaining before the lock, during the final
period.

**FR-32: Billing gate.** A flight becomes eligible for the rules engine when the billing gate passes.
The engine never processes a flight before it. *(Q-B2, Q-B3.)*
- The two gates pass in a defined order. *(Q-B20.)*

**FR-33: Testable clock.** A test drives a flight from creation through both gates in one run, with
no change to the system clock.

**FR-34: Aircraft movements.** A club administrator enters the powered-aircraft movements, on the
same flight record.

### 4.5 Offline work and concurrency

**FR-35: Offline write path.** The flight form, the NOW stamp, and the landing stamp work with no
network.
- The device stores the write locally, and the write survives a browser restart.
- No offline action needs a network round trip to complete on screen.

**FR-36: Offline read of today.** The device stores today's flights, the airborne board, and every
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
- The write model separates a stamped field from a form field. Open question 20 defines it.

**FR-39: Hold.** The user who opens the full edit form holds the flight. Every other user sees it
read-only.
- The read-only view names the holder.
- The hold releases when the holder closes the flight, and after a short idle period.
- Any user takes over at any time. The system names who they take over from, and it tells that
  person.

**FR-40: Stamp bypass of the hold.** A hold never blocks the NOW stamp or the landing stamp.
- A duty flight leader stamps a block start on a flight a pilot holds.
- A stamp writes one field and changes no other field.

### 4.6 Reservations and planning

**FR-41: Reservations.** A pilot creates, changes, and cancels a reservation for an aircraft and a
timeslot.
- A reservation names the aircraft, the pilot, the start, and the end.
- The whole club sees it on save.
- A pilot cannot change another pilot's reservation unless their role permits it.
- The aircraft status decides if the aircraft accepts a reservation. *(Q-B18.)*

**FR-42: Scheduler.** A pilot sees the club's reservations in a calendar view that shows which
aircraft are free on a chosen day.

**FR-43: Roster day.** A club administrator manages one flying day at one airfield with its duty
assignments.
- A roster day is identified by its airfield and its date. *(Q-B26.)*
- A change to one roster day changes no other. *(Q-B14.)*

**FR-44: Season assignment.** A club administrator assigns a duty across a date range in one pass.
- The administrator picks a date range and the weekdays inside it.
- One action creates or updates every matching roster day.
- The screen names every roster day the pass will overwrite, before it runs.
- A later single-day correction leaves the season untouched.

**FR-45: Roster notification.** Scheduled work emails the assigned people about their roster day,
with the club's email template.

### 4.7 Charging and invoicing

**FR-46: Charging rules as WHEN and THEN.** A club system carrier reads and edits a charging rule as
a WHEN condition and a THEN result.
- A rule card reads as one sentence.
- WHEN covers eleven match fields: the rule kind, the aircraft, the takeoff airfield, the landing
  airfield, the launch methods, the flight types, the crew roles, the person category, the membership
  status, club membership, the home airfield, and the flight duration band.
- THEN covers the article, the charging unit, the recipient, and the line text.
- THEN also covers the three flags that suppress a landing fee, and the club-internal charge flag.
- The rules editor is read-only on a phone.

**FR-47: Visible run order.** The rule list shows the rules grouped by kind, in the order the rules
engine runs them.
- Each rule shows its kind and its position in the run order.
- The order the list shows is the order the engine uses.
- *Open question 3 decides whether a club system carrier changes the order.*

**FR-48: Engine-stop flag.** Every rule card shows whether the rule stops the rules engine, with a
distinct mark. It never appears only inside an editor.

**FR-49: Rules engine.** The engine reproduces every migrated legacy invoice line to the cent.
- A legacy flight from the migrated data, replayed against the club's migrated rules, emits the same
  lines, the same amounts, and the same recipients.
- The engine consumes the active flight time in the legacy phase order.
- A rule that decrements by zero does not loop forever. *(Q-B6, Q-B7.)*
- A flight with zero duration follows a defined path. *(Q-B22.)*

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
- A flight that emits no line follows a defined path. *(Q-B21.)*
- The job never crosses a club boundary.
- A re-run of the job creates no duplicate draft. See FR-69. *(Q-B24.)*

**FR-52: Invoice draft management.** A club system carrier reads, edits, and deletes an invoice draft
and its lines.
- A booked draft rejects a change.
- A flight links to the draft it produced, and the draft links back.
- The deletion of a draft follows a defined path for its flight. *(Q-B21.)*

**FR-53: Recipient snapshot.** An invoice draft carries a copy of the recipient's name and address,
so a later change to the person does not change the invoice.
- A recipient with no name or no address fails, and the failure names the missing field. See FR-11.

**FR-54: Billing expectations.** A club system carrier keeps billing expectations — a real flight plus
the invoice it must produce — and runs them to find an unintended change.
- An expectation names one flight, the expected invoice, and the fields that may differ.
- Running one expectation or all of them reports pass or fail, and names every field that differs.
- The screen shows the expected invoice and the last actual invoice together, and names the rules
  that fired.
- A club system carrier re-baselines an expectation in one action when the difference is intended.
- A club seeds its first expectations from the invoices that FR-74 verified.

**FR-54a: Change preview.** A club system carrier sees what a rule change does before committing it.
- The preview runs against the club's billing expectations, or against a recent real flight.
- It shows the invoice before and after, side by side.
- The change commits only after the club system carrier confirms with the numbers visible.

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

**FR-61: Record items.** Every list uses a `RecordItem` inside a `RecordList`. The product
contains no data table.
- An item carries an identity zone, a meta zone, a metric zone, and a state marker.
- It stacks on a phone, and it sits side by side on a pointer device.
- It keeps its height when a value is absent.
- The trailing slot carries one action that changes the record without opening it.

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
- The system refuses repeated submissions from one source after a fixed limit.
- The limit permits a club open day, where many people submit from one address.
- A refusal does not reveal whether a record was created. *(New capability. Q-B16.)*

### 4.10 Email and scheduled work

**FR-66: Daily report email.** Scheduled work emails a club its daily flight summary, for that club
only, with the club's template. A club with no flight that day receives no email.

**FR-67: Licence expiry notification.** Scheduled work warns a person a fixed period before a licence
or a medical expires. A person with nothing expiring receives no email.
- A person with no email address is skipped, and the job reports the skip.
- A person in several clubs receives one email per club.

**FR-68: Monthly report for aircraft statistics.** Scheduled work produces the monthly report, per
club.

**FR-69: Scheduled work dispatcher.** An external timer starts the scheduled work.
- Every job writes its start, its end, and its outcome.
- A failed job does not stop the next job.
- A re-run of a job creates no duplicate record. A partial failure rolls back its own batch.
- A failure is visible to the supplier without a database query.

**FR-70: Email templates.** A club administrator edits the club's outgoing email templates, in German
and in English.

### 4.11 Migration

**FR-89: Club provisioning.** A club exists, and its first administrator can sign in, before FR-72
runs.
- The supplier admits a club to the closed user group. Open question 15 decides who creates the club
  record and how.
- The first administrator receives a sign-in path that does not need a club email configuration.
- The first administrator reaches the migration upload and nothing else, until FR-77 commits.

**FR-71: Export tool.** A club administrator runs one tool against the legacy database, and the tool
produces one file.
- It needs read access to the legacy database and nothing else.
- It encrypts its output.
- It reports what it read.

**FR-72: Self-service upload.** A club administrator uploads the file through the browser.
- It needs no action by the supplier.
- A file the system cannot decrypt or read is rejected with a reason.
- A second upload replaces the first, and the system names what it discards.
- An abandoned import expires after a fixed period, and it leaves no partial data.

**FR-73: Count verification.** Before the commit, the system shows the record counts side by side.
- The comparison covers flights, persons, aircraft, charging rules, reservations, and invoice drafts.
- Each row shows the legacy count and the imported count, and it marks a difference.
- A row marks a difference in either direction.

**FR-74: Invoice verification.** Before the commit, the system replays a sample of the migrated legacy
invoices through the dry run.
- Each sampled line reproduces to the cent, or the system marks it a mismatch.
- The sample size and its selection appear on screen.
- The sample meets a defined minimum. Open question 21 supplies the number and the selection rule.

**FR-75: Mismatch acceptance.** A club administrator accepts a mismatch with a written reason, and
the migration continues.
- The system shows the legacy value and the reproduced value side by side.
- It writes the reason, the acceptor, and the time.
- The migration never blocks, and it never needs the supplier.

**FR-76: Open mismatch list.** An accepted mismatch stays visible in the application until somebody
resolves it.
- Each entry carries the flight, both values, the reason, and the acceptor.
- A resolution writes who resolved it and when.

**FR-77: Commit and provisioning.** A club administrator commits, and the club is ready to fly.
- After the commit the club runs a flying day with no further setup.
- The whole path finishes in one session. A token refresh does not lose the verification state.
- Each club migrates on its own schedule.

**FR-78: New password after migration.** Every migrated user sets a new password before their first
sign in. The system stores, logs, and transmits no legacy password hash.
- The reset path works before the club configures its own email templates. See FR-89.

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
including the backups, within a fixed period. The period appears in the club's terms.
- A restore re-applies every deletion the supplier performed after the backup. See FR-87.

**FR-84: Data-subject requests.** A person exercises their rights through their club administrator.
- A club administrator finds every record about one person inside their club.
- A club administrator corrects or deletes a person record, subject to flight retention.
- The path is documented for the club administrator.
- Open question 16 supplies the flight retention period.

### 4.14 Supplier operations

**FR-85: Provisioning without the supplier.** A club completes sign-up, migration, and its first
flying day with no message to the supplier. v1 limits sign-up to the closed user group. See FR-89.

**FR-86: Error visibility.** Every unhandled error and every failed job reaches an error tracker with
the club, the user, and the request. No error record carries personal data in plain text.

**FR-87: Backup and restore.** The supplier restores the whole service, and one club, from a backup.
- A whole-service restore is tested and timed.
- One club restores without a restore of the others.
- The supplier keeps the backups in Switzerland or in the European Union.

## 5. Scope and non-goals

### The three offerings — added 2026-08-29

The product ships as three offerings, not one. This supersedes the brief's single flat price.

| Offering | Runs where | Carries |
| --- | --- | --- |
| **Paid SaaS** | The supplier's server | Everything |
| **Free plan** (v2) | The supplier's server | Everything, limited to one club and two aircraft, with the inactivity lifecycle |
| **Community edition** | The club deploys it itself | Core plus the open modules. No invoicing, no migration, no commercial support |

**Three module tiers carry the split, and the boundary is real.**

- **Core** — Apache-2.0, in both editions: flight logging, master data, logbook, statistics, tenancy,
  identity, reference-data sync.
- **Open module** — Apache-2.0, in both editions, optional: FLARM. The SaaS ships it pre-installed and
  pre-configured. That is a distribution difference, never a code difference.
- **Pro module** — closed source, SaaS only: invoicing, migration, OGN.

The boundary is enforced by a build, not by a repository split: CI builds and tests core plus the open
modules **standalone on every commit**. See AD-12.

**The migration is the paid moat, deliberately.** A club that self-deploys cannot bring its legacy FLS
data. The community edition is the destination for a club that never ran FLS, never the escape route
from it.

**v1 reserves the shape; it does not build the free plan.** The club kind, the plan, the aircraft
limit, and the three lifecycle states exist in v1. The free-plan surface and public sign-up are v2.

### In v1

Every legacy feature except OGN ingestion. The migration path, self-service end to end. Rules engine
parity, held by billing expectations. Flight-form speed parity plus keyboard operation. Structural
club isolation. Offline flight writing plus an offline read of today. Short-lived tokens with refresh.
The charging-rule screens: the grouped rule list, the WHEN/THEN cards, the dry run, and the change
preview. The airborne board and the NOW stamp. Season assignment. German and English. Club export,
club deletion, and the data-subject path.

### Deferred to v2

Subscription billing: the paid plan, the plan lifecycle states, and hosted checkout. The architecture
reserves the club lifecycle, so the promotion needs no rewrite. OGN ingestion and the aircraft
database synchronisation. Push notifications, an in-app message inbox, reservation waiting lists,
calendar feed export, reservation conflict detection, and per-club branding. Native mobile
applications. French and Italian. Self-service data-subject rights. Public sign-up.

**The free plan is also the demonstration.** The product is freemium, not a trial, and there is no
separate demo surface. A visitor signs up, gets a working club, and keeps it while they use it.

- A free account carries one club.
- A free club carries a maximum of two aircraft. Open question 30 defines what the count includes.
- Nothing expires because time passed. A free club stays active while a user signs in.
- The free plan needs public sign-up. The two v2 items ship together.

**The club lifecycle.** A club moves through three states, and no state destroys data.

| State | Trigger | What the club does |
| --- | --- | --- |
| Active | A user signs in | Everything the plan permits |
| Locked | No user of the club signs in for one month | Open question 31 defines it |
| Dormant | The prolongation ceiling of two years passes | Nothing. The data stays for ten years |

- Scheduled work sends a warning email every month. Each email carries a control that prolongs the
  club, and the club export that FR-82 produces.
- A club prolongs up to a ceiling of two years.
- The system never deletes a free club outright. A dormant club is soft-deleted and kept for ten
  years, so a club keeps its business records. FR-83 stays the path for a deletion a club asks for.
- A paying club has no inactivity rule. It pays, so it stays active.

**v1 reserves it. v2 builds it.** The architecture makes the club kind, the plan, the aircraft limit,
and the three lifecycle states first-class. v1 ships no plan surface and no housekeeping job.

**A free club is a club, not a code path.** Every rule the product applies to a club applies to it:
the club filter, the charging rules, the scheduled work, and the audit record. The plan and the state
add policy only — the aircraft limit, the lifecycle above, and what the club may write.

A visitor enters whatever they want, and no paying club sees any of it. That constraint is the
tenancy question again: open question 28 names every shared entity a free club may create, and
open question 29 names what a free club never does. RK-13 carries the risk.

Each sign-up creates a club, so the free plan exercises the provisioning path FR-89 defines on every
use.

### Never

- Growth. Success is cost recovery.
- Competing on the features the established competitors already lead on.
- A native mobile application.
- A temporary trial. The free plan replaces it, and it does not expire.
- Changes to the two external integration projects. Each club arranges its own handover.

### Behaviour changes to a ported feature

A ported feature keeps its legacy behaviour, except where a requirement states a change. These
requirements state one: FR-1, FR-8, FR-11, FR-20 to FR-26, FR-31, FR-35 to FR-40, FR-44, FR-47,
FR-48, FR-50, FR-54, FR-54a, FR-60, FR-61, FR-65, FR-71 to FR-78, FR-79 to FR-81, and FR-82 to FR-89.

Two of these correct a legacy isolation defect: FR-1 and FR-8. `domain-model.md` §4.6 records each
defect. The flight reports already filter by club in the legacy, so FR-57 is parity.

OGN ingestion is the one exception to feature parity. A club that uses OGN keeps its legacy system for
that path, or it logs by hand.

### Counter-metrics — do not optimise

- **SM-C1** Club count. Enough *paying* clubs to cover the running cost is success. From v2, count the
  paying clubs and the free clubs apart. A rising free count with a flat paying count is a cost, not a
  result.
- **SM-C2** Feature count.
- **SM-C3** Elapsed time to log a flight. It measures familiarity and the network, not the interface.
- **SM-C4** Accepted mismatch count. Do not reduce it to zero by making acceptance easy.

## 6. Success metrics

**Primary**

- **SM-1** Every migrated legacy invoice line reproduces to the cent, or a club administrator accepted
  it as a mismatch with a reason. Target 100 percent of the corpus, with the accepted count reported.
  Open question 21 defines the corpus. Validates FR-49, FR-54, FR-74, FR-75.
- **SM-2** Migration data loss. Target zero. Validates FR-71, FR-73, FR-77.
- **SM-3** Migrations finished with no message to the supplier. Target every migration. Instrument:
  open question 19. Validates FR-72, FR-75, FR-77, FR-85, FR-89.
- **SM-4** Cross-club exposure. Target zero. Validates FR-1, FR-2.
- **SM-5** Clicks and keystrokes for the reference case. Target the measured legacy pair, or fewer.
  Validates FR-16 to FR-26.

**Secondary**

- **SM-6** A complete flight logged with no network, with every write reaching the server on
  reconnect. Validates FR-35 to FR-38.
- **SM-7** Messages to the supplier per club per month. Target near zero. Instrument: open question
  19. Validates FR-85, FR-86.
- **SM-8** A club system carrier explains one invoice line to a member with the dry run, unaided.
  Validates FR-50.
- **SM-9** Income covers the running cost every month. Not measurable in v1.

The counter-metrics are in section 5.

## 7. Non-functional requirements

**NFR-1: Correctness.** Money is exact. No accounting calculation uses a binary floating-point type.
Club isolation is structural. The client and the server derive every state name from one source.

**NFR-2: Responsiveness.** No loading state below 300 milliseconds. A spinner appears only for an
invoice run, a migration import, or a large export. Every list reserves its rows at final geometry,
and the layout never shifts. The client prefetches every catalog before its form opens. The client
writes optimistically. Motion is 110 milliseconds for a state change, 140 for a conditional field, and
zero for anything a press caused. Nothing eases, bounces, or reorders under the pointer.

**NFR-3: Security.** The server scopes cross-origin access. A token is short-lived, and it refreshes.
A failed authentication during a write does not lose the entry. No personal data appears in plain text
in a log or an error record.

**NFR-4: Availability.** No single outage exceeds **one hour** during the flying window. The annual
figure is **99.4 percent**. Planned maintenance is excluded, and it goes outside the flying window —
a Swiss gliding club does not fly from October to March. The offline bridge is sized to the same one
hour, because beyond an hour the conflict volume passes what a person will reconcile. The cap on one
outage is the number that drives the design; the annual percentage alone would permit one long
outage on the best flying Saturday of the year.

**NFR-5: Data residency.** Every store, every backup, and every log stays in Switzerland or in the
European Union.

**NFR-6: Legibility — the signed-in application.** Every action is reachable by keyboard in visual
order. A visible focus ring is never removed. Every input has a programmatic label, and a placeholder
is never the label. No state is carried by colour alone: `AIRBORNE`, `LOCKED`, and `UNSENT` each carry
a word. Every error is tied to its field.

**Reduced 2026-08-29, deliberately.** This requirement was named *accessibility*. It is now named
*legibility*, because every rule above is already demanded by the speed budget and by sunlight — the
fastest duty flight leader never touches the mouse, and a phone at midday on an airfield is
functionally a low-vision device. **Dropped for the signed-in application:** screen-reader optimisation, live-region
politeness tuning, and any formal WCAG conformance claim. Nobody tests against a screen reader here.
`RecordList` still carries its table roles, because twenty lines now beats touching every screen after
the promotion to a real launch. NFR-7 is **unchanged**.

**NFR-7: Accessibility — the public surfaces.** All of NFR-6, and WCAG 2.2 AA in full on an unknown
device. The form works with no scripting beyond validation. No aviation term appears without a plain
explanation. Every target is at least 44 pixels.

**NFR-8: Display.** Dark only. One ground, no light mode, and no setting. The palette carries a high
contrast floor: the WCAG AAA body-text ratio, and no low-contrast grey for any data value.

**NFR-9: Observability.** Every unhandled error and every failed job reaches the supplier without a
database query.

**NFR-10: Testability.** Both time gates are drivable in a test with no change to the system clock.

**NFR-11: Language.** Every text a person reads is ASD-STE100 Simplified Technical English — the
interface, the email, and the error messages, in German and in English.

**NFR-12: Platform floor.** The minimum browser is **latest-minus-one** for Chrome, Safari, Firefox,
and Edge. That floor supplies baseline-2024 web features, so the product needs no polyfill budget and
no transpile target below ES2022. Offline storage, the service worker, the prefetched catalogs, and
the 56-pixel targets all rest on it. **Question 18 is closed.**

**NFR-13: Scale.** The design target is the largest club in the closed user group: **8,000 flight
records per year**, **10 years of history**, about **100,000 flight rows**, **300,000 invoice lines**,
and about **1,000,000 audit rows** — roughly 1.5 million rows and 1 to 2 GB. Peak is **200 flight
records on the best Saturday** and **fewer than 25 concurrent writers per club**.

Budgets: the nightly engine run finishes in **60 seconds per club** and **10 minutes system-wide**; a
monthly invoice run for the largest club finishes in **60 seconds**; a migration import for the
largest club finishes in **30 minutes**, plus **30 minutes** to reproduce 10 years of invoices.

**Question 17 is closed.** The numbers are an informed estimate, not a measurement — no live legacy
instance was available. They are derived from the SFVS 2024 annual report (48 clubs, 1,997 members,
4,183 declared flights) and cross-checked against the Swiss aircraft register (472 gliders plus 242
powered gliders). **Revisit if** the first real migration reports more than 150,000 flights or 3
million audit rows, or if an import passes 60 minutes.

**The conclusion that follows:** AlpenFlight is a single-node workload. Nothing in this product
justifies a message broker, a read replica, a cache tier, or a shard key.

## 8. Constraints

- Income covers the hosting, the backups, the domain and certificates, the error tracking, the log
  storage, the email delivery, and the payment fee. About five paying clubs cover it.
- A free club consumes the same resources and pays nothing. From v2 the paying clubs carry the free
  clubs as well. The two-aircraft limit and the inactivity deletion are the only bounds on that load.
  See RK-16.
- One person builds, hosts, and supports the product. Reconsider any requirement that adds recurring
  manual work for the supplier.
- Swiss and European data protection law applies from the first real club.
- `flsserver/` and `flsweb/` are reference-only. All new code lands in `alpenflight/`.

## 9. Information architecture

Four destinations plus Home. Detail in
[`EXPERIENCE.md`](../../ux-designs/ux-fls-2026-08-24/EXPERIENCE.md).

| Destination | Contains |
| --- | --- |
| Home | Airborne panel, today, reservations, expiring licences and medicals |
| Operate | Log flight, Airborne board, Logbook, Aircraft movements |
| Plan | Reservations, Scheduler, Roster days, Season assignment |
| Records | Members, Aircraft, Locations, Reports |
| Admin | Charging rules, Invoice drafts, Billing expectations, Articles, Master data, Users, Email templates, Migration, System |

The public surfaces sit outside the four: the landing page and both registration forms.

A dialog stacks one level deep, never two.

## 10. Risks

| Id | Risk | Mitigation | Question |
| --- | --- | --- | --- |
| RK-1 | The rules engine must reproduce a stateful decrement loop exactly | FR-49, FR-54, FR-74, SM-1 | Q-B6, Q-B7, Q-B22 |
| RK-2 | The legacy test suite proves that features exist, not that they behave | Expand the suite early. No epic claims parity on it as it stands. | — |
| RK-3 | Club isolation is conventional in the legacy system | **Mitigated.** AD-2: row-level security with `FORCE ROW LEVEL SECURITY` and a non-owner role. One build gate proves a query with no filter returns zero rows — one test that cannot be forgotten, instead of ~200 that can. | Q-B8, Q-B9 |
| RK-4 | The time gates are undocumented | FR-30, FR-32, FR-33 | Q-B2, Q-B3, Q-B20 |
| RK-5 | One person builds and operates this | FR-85, FR-86, FR-89, SM-7 | 19 |
| RK-6 | The speed budget has no number | FR-26 measures and reports until the figure exists | 1 |
| RK-7 | The competitors may already support configurable charging rules | It changes the positioning, not the requirements | 8 |
| RK-8 | The legacy spreadsheet library licence changed | FR-55 and FR-59 need feature equivalence, which frees the library choice | — |
| RK-9 | A club cannot sign in before it configures its own email | FR-78, FR-89 | 15 |
| RK-10 | A job re-run duplicates an invoice draft | FR-51, FR-69 | Q-B24 |
| RK-11 | The legacy audit log serves every club to any authenticated user, and it carries no club column | FR-8. The migration must derive a club per audit row, or start the history empty. | 26 |
| RK-12 | **CLOSED 2026-08-29.** The legacy carries three tenancy mechanisms, and a shared read cannot be told from a forgotten filter | AD-2 and AD-3. Row-level security is the floor, three declared data kinds replace the three mechanisms, and a table declaring no kind fails the build. | — |
| RK-13 | **CLOSED 2026-08-29.** A free club writes into a shared entity, and a paying club reads it | AD-3 removed the shared writable entity as a category. No club writes anything another club reads. | — |
| RK-14 | A real free club locks every winter and is nagged every month | A Swiss gliding club is inactive from October to March, and the lock triggers after one month. The lock destroys nothing, but the nagging drives a real club away. Size one prolongation against a season. | 31 |
| RK-15 | The ten-year soft delete keeps every abandoned sign-up | A free plan that is also the demonstration collects abandoned clubs. Ten years of storage each is a cost with no return. Scope the retention to a club that issued an invoice. | 32 |
| RK-16 | A free club costs hosting and returns nothing | The free plan is a v2 item. Size the free load against the cost recovery target before it ships. | 17, 30 |

## 11. Open questions

An id is stable. The list is not in sequence. `domain-model.md` §5 carries questions 11 to 14.

**Blocking a story or a decision**

1. **The legacy click and keystroke count for the reference flight.** FR-26 measures and reports until
   this exists. Owner: the supplier.
2. **CLOSED 2026-08-29.** "Flight operator" is renamed to **duty flight leader** everywhere: this
   PRD, [`domain-model.md`](domain-model.md), `EXPERIENCE.md`, `DESIGN.md`, `brief.md`, and
   `addendum.md`. The word "operator" names nobody. The supplier builds and hosts the product.
3. **Does FR-47 let a club system carrier change the rule order?** The legacy fixes the order by rule
   kind. Owner: the supplier.
4. **CLOSED 2026-08-29.** `Delivery` is `InvoiceDraft` in the domain, and **`/api/v1/deliveries/*`
   keeps its wire path**, because the Proffix accounting synchroniser polls it (AD-19).
5. **Does a flight record survive the deletion of a person record?** FR-84 assumes it does, with the
   identity removed. Needs a legal reading before the first real club.
6. **Which languages does the legacy translation table hold?** FR-14 carries two.
15. **Who creates a club and its first administrator?** FR-89. The supplier admits the club, but the
    creation path, the first credential, and the closed-user-group gate are all undefined. Blocks the
    migration epic. Owner: the supplier.
16. **What is the retention period for a flight record, and the deletion period for a club?** FR-83
    and FR-84 both cite a period that no requirement supplies. Needs a legal reading. Owner: the
    supplier.
17. **CLOSED 2026-08-29.** What data volume must the system carry? NFR-13 now carries the numbers and
    the budgets, plus the revisit condition. Derived from public Swiss gliding statistics, because no
    live legacy instance was available.
18. **CLOSED 2026-08-29.** Which browser version is the floor? Latest-minus-one for Chrome, Safari,
    Firefox, and Edge. NFR-12 carries it.
19. **How does the system count a message to the supplier?** SM-3 and SM-7 have no instrument, and no
    support channel exists in this PRD. Define the channel, or mark both metrics not measurable in v1.
    Owner: the supplier.
20. **ANSWERED 2026-08-29.** Which write model applies to a field the full form changes offline?
    **Field-level conflict, with a fundamental-field escalation** (AD-9). Two devices changing
    different fields both apply. The same field changed differently raises a conflict dialog naming
    both authors and both values. A change to a fundamental field — one that reshapes the record, such
    as the launch method — escalates to a whole-record conflict. The person whose write arrives second
    resolves it. While a conflict is open the server value stands and the flight bills on it.
    **A stamp never conflicts** (AD-10): the NOW press and the landing stamp are idempotent, first
    stamp wins, no dialog. **A queued write parks at 24 hours** (AD-11): it stops applying by itself,
    it is never deleted, and it returns as a pending item with Apply and Discard.
    *Still open, at epic level:* the exact membership of the fundamental-field set. Source: a
    `legacy-oracle` read against the 88 legacy conditional directives, before the flight-form epic.
21. **What is the minimum invoice sample, and what is the corpus?** FR-74 verifies a sample. SM-1
    targets 100 percent of "the corpus". Supply the minimum sample size, the selection rule, and the
    corpus definition. Blocks the migration epic. Owner: the supplier.
22. **Which language does a user read, and where does the preference live?** FR-14 and FR-70. The
    interface and the email must pick the same language. Owner: the supplier.
23. **ANSWERED 2026-08-29. The shared list is empty.** There is no shared *writable* entity at all
    (AD-3). Three data kinds replace the legacy's three mechanisms. **System reference** —
    supplier-owned, imported from an authoritative source, writable by no club: `ReferenceAircraft`
    from the BAZL register, `ReferenceLocation` from OurAirports. **Club-scoped** — one club, RLS
    enforced: `ClubAircraft`, `ClubLocation`, and everything else. **Cross-club link** — a row the
    owning club creates, such as a `ClubMembership`. A table declaring no kind fails the build.
    This removes both hazards a shared entity carried: a free club polluting what a paying club reads,
    and one club deleting a record another club still uses.
24. **ANSWERED 2026-08-29.** Where does the club filter get enforced? **In the database** (AD-2).
    PostgreSQL row-level security with a session variable set once in the transaction opener, plus
    `FORCE ROW LEVEL SECURITY` and a non-owner application role. Repository scoping is ergonomics and
    never the guarantee. Compile-time type enforcement was **rejected**: it costs the most and
    guarantees the least, because raw SQL, the bulk importer, and any job opening its own connection
    walk past it. The decisive argument against test-only enforcement: proper testing means ~200
    isolation tests a developer must remember to write, and forgetting the test fails exactly like
    forgetting the filter. Row-level security needs **one** test that cannot be forgotten.
25. **ANSWERED 2026-08-29.** What replaces `OwnerId` and `OwnershipType`? A plain `club_id` foreign
    key on every club-scoped table — indexable, and it carries a policy. The polymorphic discriminator
    **disappears**; the legacy carried it and ignored it, evidenced at `FlightService.cs:483`. A
    system-reference table carries no tenant column at all, and a cross-club link is read through the
    link the owning club created.
26. **Does the audit log carry a club, and what happens to the migrated audit history?** FR-8 requires
    that a club reads only its own audit records. The legacy `AuditLogs` table carries six columns and
    no club. Every migrated audit row needs a club derived from its `RecordId`, or the history starts
    empty at cutover. Blocks the identity epic and the migration epic. Owner: the supplier, then
    `bmad-architecture`.
28. **CLOSED 2026-08-29. The answer is none.** No shared writable entity exists, so a free club can
    write nothing another club reads (AD-3). The second half — a free user searching a real person of
    another club — is closed by AD-4: **there is no global person search.** A club reads a person only
    through a `ClubMembership` it owns, and a cross-club crew member is added by an explicit link.
    That rule is required independently of the free plan, because the system holds names, addresses,
    licences, and medical expiry, and letting any club enumerate every person is a personal-data
    exposure under the Swiss DSG and the GDPR.
29. **What does a free club not do, and what bounds it?** It produces no invoice a person outside the
    club receives, it counts in no success metric apart from the free count in SM-C1, and it raises no
    supplier alert. Name the write rate limit, the storage cap, and the rule for the content people
    enter. Owner: the supplier.
30. **What does the two-aircraft limit count?** An aircraft is a shared entity in the legacy, and
    question 23 decides whether it stays shared. The limit must count the aircraft a club owns, which
    the legacy holds in `Aircraft.AircraftOwnerClubId`, not the aircraft a club can see. A club that
    flies aerotow needs a tug and two gliders, so two aircraft excludes it. Confirm the number is
    intended. Owner: the supplier.
31. **What does the locked state do, and what leaves it?** The periods are decided: one month of no
    sign-in locks a club, a club prolongs up to a ceiling of two years, and the ceiling makes it
    dormant. Four things are open. Does a locked club read its own data, or only export it? Does any
    sign-in prolong the club, or only the control in the email? Is the two-year ceiling counted from
    the club's creation or from its first lock? **And a seasonal caution:** a Swiss gliding club is
    inactive from October to March, so a real free club locks every winter and gets nagged every
    month. Decide whether one prolongation adds a month or a year. Owner: the supplier.
32. **Which free club earns the ten-year retention?** A soft delete for ten years is decided, and it
    protects a real club's business records. An abandoned sign-up holds no business record and costs
    storage for ten years. Decide whether the ten years applies to every free club, or only to a club
    that issued an invoice. Also decide whether the terms put the retention duty on the club or on the
    supplier. Needs a legal reading before the free plan ships. Owner: the supplier.
34. **DEFERRED 2026-08-29 to the v2 free-plan epic.** Does a new free club get seeded demonstration
    data? It does not block v1, because v1 reserves the club kind and the lifecycle without building
    the free-plan surface. One constraint now applies: seeded data is **club-scoped**, never written
    into system reference data (AD-3), so it cannot reach another club.

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
| Q-B17 | Which roles does the legacy `Role` enum carry, and which surface does each reach? | FR-5, FR-88 |
| Q-B18 | Does the legacy refuse a flight or a reservation for an unserviceable aircraft? | FR-16, FR-41 |
| Q-B19 | Does a legacy crew assignment carry a time range, and does the form capture it? | FR-16, FR-25 |
| Q-B20 | Which gate passes first, the lock gate or the billing gate? | FR-30, FR-32 |
| Q-B21 | What happens to a flight when its invoice draft is deleted, and when it emits no line? | FR-51, FR-52 |
| Q-B22 | What does the engine emit for a zero-duration or inverted-time flight? | FR-49 |
| Q-B23 | Does the daily validation process an open flight with no times? | FR-29 |
| Q-B24 | Does a legacy job re-run create a duplicate invoice draft? | FR-51, FR-69 |
| Q-B25 | Does the legacy form refuse a landing time earlier than the block start? | FR-19 |
| Q-B26 | What identifies a legacy planning day: the airfield and the date only? | FR-43, FR-44 |

Q-B10 and Q-B11 concern OGN ingestion. They return with the v2 OGN epic.

Questions 27 and 33 are closed. The free plan and the demonstration are one thing: a visitor signs up,
gets one club, and keeps it while they use it. Section 5 carries the answer, and questions 31, 32,
and 34 carry what the merge left open.

## 12. Assumptions

1. The journey protagonist names stay fixtures until the supplier supplies real club names.
2. The legacy planning setup wizard is superseded by the season assignment. v1 ports its outcome, not
   its steps.
3. The external accounting synchroniser polls the invoice interface, and its maintainer adapts it.
4. No formal uptime commitment applies to the closed user group.

Two former assumptions are now open questions: the legacy translation languages is question 6, and
the survival of a flight record after a person deletion is question 5.

## 13. Traceability

Every requirement group maps to the legacy feature inventory in
`docs/modernization/01-current-state.md` §2, and to the Playwright suite in `e2e/`.

| Group | Legacy inventory section | e2e directory |
| --- | --- | --- |
| 4.1 Club isolation and identity | Identity, auth, and tenancy | `auth/`, `multi-tenant/`, `profile/`, `masterdata/` |
| 4.2 Master data | Master data | `masterdata/` |
| 4.3 Flight logging | Flight operations | `flights/` |
| 4.4 Flight lifecycle | Flight operations, Email & scheduled jobs | `flights/` |
| 4.5 Offline work and concurrency | — new in v1 | — |
| 4.6 Reservations and planning | Reservations & planning | `reservations/`, `planning/` |
| 4.7 Charging and invoicing | Accounting & invoicing pipeline | `accounting/` |
| 4.8 Reporting, search, and lists | Flight operations (reports) | `reporting/` |
| 4.9 Public surfaces | Public (no-auth) flows | `public/` |
| 4.10 Email and scheduled work | Email & scheduled jobs | `email/`, `flights/`, `accounting/` |
| 4.11 Migration | — new in v1 | — |
| 4.12 Home | Dashboard | — no direct spec |
| 4.13 Data governance | — new in v1 | — |
| 4.14 Supplier operations | — new in v1 | — |

**New in v1, with no legacy source.** FR-20 to FR-26, FR-31, FR-33, FR-35 to FR-40, FR-44, FR-47,
FR-48, FR-50, FR-54, FR-54a, FR-60, FR-61, FR-65, FR-71 to FR-78, FR-80 to FR-89. Each needs its
acceptance criteria written from this PRD, not from the legacy code.

**Legacy features with no e2e spec.** The monthly aircraft statistic report, the delivery mail export,
articles CRUD, email-template CRUD, language-translation CRUD, system-data CRUD, system logs, and the
dashboard. See RK-2. These carry the highest parity risk, and each needs a `legacy-oracle` read before
its epic.
