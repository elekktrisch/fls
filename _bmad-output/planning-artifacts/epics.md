---
stepsCompleted:
  - step-01-validate-prerequisites
  - step-02-design-epics
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-fls-2026-08-25/prd.md
  - _bmad-output/planning-artifacts/prds/prd-fls-2026-08-25/domain-model.md
  - _bmad-output/planning-artifacts/architecture/architecture-fls-2026-08-29/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/ux-designs/ux-fls-2026-08-24/DESIGN.md
  - _bmad-output/planning-artifacts/ux-designs/ux-fls-2026-08-24/EXPERIENCE.md
  - _bmad-output/planning-artifacts/briefs/brief-fls-2026-08-24/brief.md
  - _bmad-output/planning-artifacts/briefs/brief-fls-2026-08-24/addendum.md
referenceOnlyDocuments:
  - path: docs/modernization/
    reason: >-
      Not authoritative, per CLAUDE.md, revised 2026-08-29. Use the folder to
      narrow a search. Take exact legacy behaviour from a legacy-oracle read
      against flsserver/ and flsweb/, never from this folder. The useful parts
      are 01-current-state.md for the feature map and legacy-tables/ for the
      per-column schema dump.
  - path: docs/modernization/form-validation-parity-audit.md
    reason: >-
      Probably stale. Its AF columns describe rebuild-1 code that is deleted.
      Use only the legacy bar it records, and verify each fact against
      flsserver/ before it becomes an acceptance criterion.
excludedDocuments:
  - path: docs/attempt-1/
    reason: History, not authority. The spine supersedes ADR-0023.
  - path: docs/modernization/legacy-migration-plan.md
    reason: >-
      Attempt-1 material, not a target reference. Its Destination, Semantics,
      Owned by, and Notes columns are all rebuild-1.
---

# AlpenFlight - Epic Breakdown

## Overview

This document holds the epic and story breakdown for AlpenFlight, rebuild 2. It decomposes the PRD
requirements, the UX design contract, and the architecture spine into stories a developer can build.

**The authority order applies.** `CLAUDE.md`, then `ARCHITECTURE-SPINE.md` (how), then `prd.md`
(what), then `domain-model.md` (the words), then `EXPERIENCE.md` and `DESIGN.md`, then
`docs/modernization/`. A document lower in that list never overrides one above it.

**The requirement ids are stable.** The PRD fixes the pattern `FR-` plus a number, and sometimes one
lowercase letter, as in `FR-54a`. This document never renumbers an id.

## Requirements Inventory

### Functional Requirements

Source: `prd.md` sections 4.1 to 4.14. 90 requirements: FR-1 to FR-89, plus FR-54a.

**4.1 Club isolation and identity**

- **FR-1: Structural club isolation.** The system rejects any data access that does not name a club. A query with no club filter returns no row of another club, and no silent empty result. An automated test proves the failure. A user of club A cannot reach a record of club B through any interface, including a direct identifier in a URL.
- **FR-2: Cross-club person and crew.** A crew member may be a person from another club. Club A saves a flight naming a pilot of club B, and club A sees it. Club B does not see that flight. The same applies to an invoice recipient. (Q-B8, Q-B9.)
- **FR-3: User and person separation.** A user and a person are separate linked records. A person exists with no user. A person belongs to several clubs, each membership carrying its own status and roles. A user belongs to exactly one club.
- **FR-4: Authentication.** A user signs in and receives a short-lived access token that refreshes. The user does not sign in again during a flying day. A request rejected for an expired token does not lose unsaved entry. (Q-B13.)
- **FR-5: Roles and permissions.** A role decides which surface and action a user reaches. A pilot cannot open the charging rules. A permission failure names what the user lacks. The system carries a defined role set, and each role names the surfaces it reaches. (Q-B17.)
- **FR-6: Profile self-edit.** A user edits their own person record and password. A user cannot change their own role or club.
- **FR-7: Password reset and email confirmation.** A reset link expires after a fixed period and works once. The response is identical whether the email address exists or not.
- **FR-8: Audit record.** The system writes an audit entry for every change: who changed the record, which record, when, and what changed. Every create, update, and delete of a flight, person, aircraft, charging rule, and invoice draft produces one. An audit record names its club, and a club reads only its own audit records. This is a behaviour change. (RK-11, question 26.)
- **FR-88: User management.** A club administrator manages the users of their own club. The administrator creates a user, links it to a person, deactivates it, and assigns and removes a role within the FR-5 role set. The administrator cannot reach a user of another club. A deactivated user cannot sign in, and its audit records stay. (Q-B17.)

**4.2 Master data**

- **FR-9: Club master data.** A club administrator manages clubs, aircraft, locations, flight types, membership statuses, person categories, persons, articles, and email templates. A delete that another record references fails and names the reference.
- **FR-10: Licences and medicals.** A person carries licence and medical expiry dates. A person whose medical expires within a fixed period appears on Home.
- **FR-11: In-place record creation.** A user creates a missing person or aircraft from inside the flight form. The dialog opens above the flight form, and nothing opens above the dialog. On save the dialog closes, the field that opened the dialog shows the new record, and the focus moves to the next field. The part-entered flight loses no value. The system marks the new record a stub record. A stub record cannot be an invoice recipient until it carries a name and an address. See FR-53.
- **FR-12: Operating counters.** The system carries a flight counter and an engine counter per aircraft. The form fills the start counter from the aircraft's last entered counter.
- **FR-13: Reference data.** Countries, counter units, length units, elevation units, location types, and launch methods exist and match the legacy set.
- **FR-14: Translations.** A system administrator manages the interface translations. v1 carries German and English. A missing translation renders a defined fallback, never an empty string and never a raw key. A user reads one language. (Q-B15, question 22.)
- **FR-15: System data and logs.** A system administrator reads the system logs and manages the system data. A club administrator reaches neither.

**4.3 Flight logging**

- **FR-16: One form.** A duty flight leader records a flight in one form. The form has no wizard step and no page change on a phone. The form covers a glider flight, a tow flight, and a powered flight. A flight saves with fields still empty.
- **FR-17: Conditional fields.** The form hides the fields the current selection makes irrelevant. A winch launch shows no tug field and no release altitude field; an aerotow shows both. A field that appears or disappears does not move the field the user is about to press. The form reveals downward and holds the scroll position.
- **FR-18: Typeahead over prefetched catalogs.** Every catalog field filters a list the client carries. Every catalog field in the form is a typeahead, and no plain select exists in the form. The form prefetches every catalog before it opens. A person picker matches the first name, the last name, and the city together. The list opens on focus, before the first keystroke. When nothing matches, the last entry creates the record. See FR-11.
- **FR-19: Date and time entry.** Every date and time field accepts typing and pointing equally. A date field accepts `29.08.2026` typed and a click in a calendar, and both write the same value. A time field accepts `1024` and `10:24`, and it formats on blur. The field refuses an impossible value, such as `2530`, and it names the fault. A clear control empties the field. Neither path is a fallback for the other, and the product does not use native date or time inputs. A landing time earlier than the block start is a fault. (Q-B25.)
- **FR-20: The NOW stamp.** One press stamps the block start or the landing time. One press writes the current time to that one field and saves it. No dialog and no confirmation. It works from the airborne board without opening the flight. At least 56 pixels on a phone, in the area a thumb reaches. A hold never blocks it. An automatic source can replace the press with no screen change. See FR-40.
- **FR-21: Copy from the last flight.** The form fills the tug, the outbound and inbound routes, and the engine counter from the previous flight on that device. The values persist between sessions on the device. One control per group performs the copy. A copied value carries a visible mark.
- **FR-22: Smart defaults.** With nothing to copy, the form falls back to the last takeoff airfield, then to the club's home airfield. Every default is editable.
- **FR-23: Keyboard operation.** A user completes the form with the keyboard alone. `Tab` moves to the next field in visual order and skips a hidden field. `Enter` in a typeahead takes the highlighted entry and moves on. `Enter` in the form saves, and never submits from inside an open typeahead. `Esc` closes a dialog or a typeahead, and never discards the form. `n` stamps NOW on the highlighted airborne row, `/` focuses the search field, and `?` shows the key list.
- **FR-24: Airborne board.** A duty flight leader sees and acts on every flight in the air. The board lists every flight with a block start and no landing time. Each row offers the NOW stamp and the landing stamp without opening the flight. The elapsed time counts up and never moves the focus. The groups are: at the start, in the air, landed today, older. A row in the older group offers a correction, so a mistaken stamp leaves the board. It is a full screen on a phone, and a panel on Home on a pointer device.
- **FR-25: Flight copy.** A user creates a new flight from an existing one. It carries the aircraft, the crew, and the route, and no times and no identifiers.
- **FR-26: Speed budget.** The reference case, one glider flight with a tow logged from empty, costs no more clicks and no more keystrokes than the legacy form. An automated browser test counts the mouse events and the key events, and it fails when either count exceeds the measured legacy figure. Until question 1 supplies the figure, the test measures both counts and reports them, and it does not fail. The first flight-form story does not wait for the figure.

**4.4 Flight lifecycle**

- **FR-27: Two state dimensions.** A flight has an air state computed from its timestamps, and a stored process state. The air state derives from the timestamps on every read, and it is never the stored authority. Every combination of timestamps maps to exactly one air state name, including the incomplete combinations, such as a landing time with no block start. The client and the server derive the state names from one source. An illegal process-state transition fails and names both states.
- **FR-28: Shared open flight.** A part-entered flight is a server record the whole club sees. A flight saves with no times and no complete crew. A different user on a different device opens it and completes it. It is never a private draft on one device.
- **FR-29: Daily validation.** Scheduled work validates each club's flights and sets the process state. A flight that fails validation moves to the invalid state with a readable reason. The job never crosses a club boundary. An open flight with no times at all follows a defined path. (Q-B1, Q-B23.)
- **FR-30: Lock gate.** A flight becomes read-only when the lock gate passes. A locked flight rejects every field change. The screen shows that the flight is locked, and when. A queued offline write that meets a locked flight fails, and the device keeps the value on screen. (Q-B2, Q-B3.) See FR-37.
- **FR-31: Lock countdown.** The screen shows the time remaining before the lock, during the final period.
- **FR-32: Billing gate.** A flight becomes eligible for the rules engine when the billing gate passes. The engine never processes a flight before it. The two gates pass in a defined order. (Q-B2, Q-B3, Q-B20.)
- **FR-33: Testable clock.** A test drives a flight from creation through both gates in one run, with no change to the system clock.
- **FR-34: Aircraft movements.** A club administrator enters the powered-aircraft movements, on the same flight record.

**4.5 Offline work and concurrency**

- **FR-35: Offline write path.** The flight form, the NOW stamp, and the landing stamp work with no network. The device stores the write locally, and the write survives a browser restart. No offline action needs a network round trip to complete on screen.
- **FR-36: Offline read of today.** The device stores today's flights, the airborne board, and every catalog for offline reading. Every typeahead filters offline. Reservations, member records, accounting, and reports need a connection, and they say so plainly.
- **FR-37: Unsent marker.** A record written offline carries a visible marker until the server accepts it. The record shows `UNSENT`, and the top bar shows the unsent count. The marker never blocks the user. A failed save keeps the value on screen, marks the record unsent, and never retries silently.
- **FR-38: Reconnect and conflict.** On reconnect the device applies its queued writes when nothing conflicts. A conflict shows both values side by side, with who wrote each and when. Nothing is discarded until a person picks. An offline device never blocks another user, and it never takes a hold. The write model separates a stamped field from a form field. (Q-B12, question 20, AD-9, AD-10, AD-11.)
- **FR-39: Hold.** The user who opens the full edit form holds the flight. Every other user sees it read-only, and the read-only view names the holder. The hold releases when the holder closes the flight, and after a short idle period. Any user takes over at any time. The system names who they take over from, and it tells that person.
- **FR-40: Stamp bypass of the hold.** A hold never blocks the NOW stamp or the landing stamp. A duty flight leader stamps a block start on a flight a pilot holds. A stamp writes one field and changes no other field.

**4.6 Reservations and planning**

- **FR-41: Reservations.** A pilot creates, changes, and cancels a reservation for an aircraft and a timeslot. A reservation names the aircraft, the pilot, the start, and the end. The whole club sees it on save. A pilot cannot change another pilot's reservation unless their role permits it. The aircraft status decides if the aircraft accepts a reservation. (Q-B18.)
- **FR-42: Scheduler.** A pilot sees the club's reservations in a calendar view that shows which aircraft are free on a chosen day.
- **FR-43: Roster day.** A club administrator manages one flying day at one airfield with its duty assignments. A roster day is identified by its airfield and its date. A change to one roster day changes no other. (Q-B14, Q-B26.)
- **FR-44: Season assignment.** A club administrator assigns a duty across a date range in one pass. The administrator picks a date range and the weekdays inside it. One action creates or updates every matching roster day. The screen names every roster day the pass will overwrite, before it runs. A later single-day correction leaves the season untouched.
- **FR-45: Roster notification.** Scheduled work emails the assigned people about their roster day, with the club's email template.

**4.7 Charging and invoicing**

- **FR-46: Charging rules as WHEN and THEN.** A club system carrier reads and edits a charging rule as a WHEN condition and a THEN result, and a rule card reads as one sentence. WHEN covers eleven match fields: the rule kind, the aircraft, the takeoff airfield, the landing airfield, the launch methods, the flight types, the crew roles, the person category, the membership status, club membership, the home airfield, and the flight duration band. THEN covers the article, the charging unit, the recipient, and the line text. THEN also covers the three flags that suppress a landing fee, and the club-internal charge flag. The rules editor is read-only on a phone.
- **FR-47: Visible run order.** The rule list shows the rules grouped by kind, in the order the rules engine runs them. Each rule shows its kind and its position in the run order. The order the list shows is the order the engine uses. (Question 3 decides whether a club system carrier changes the order.)
- **FR-48: Engine-stop flag.** Every rule card shows whether the rule stops the rules engine, with a distinct mark. It never appears only inside an editor.
- **FR-49: Rules engine.** The engine reproduces every migrated legacy invoice line to the cent. A legacy flight from the migrated data, replayed against the club's migrated rules, emits the same lines, the same amounts, and the same recipients. The engine consumes the active flight time in the legacy phase order. A rule that decrements by zero does not loop forever. A flight with zero duration follows a defined path. (Q-B6, Q-B7, Q-B22.)
- **FR-50: Dry run.** A club system carrier replays the engine against one real flight and reads the trace. The trace lists every rule that fires, in run order. Each entry shows what it consumed from the remaining active flight time, and what it emitted. The trace ends with the remainder and the total. The dry run changes nothing. The same dry run serves the migration verification. See FR-74.
- **FR-51: Invoice draft creation.** Scheduled work runs the engine over the eligible flights and creates the invoice drafts. The job processes only flights past the billing gate. A draft moves from prepared to booked, and booked is terminal. A flight that emits no line follows a defined path. The job never crosses a club boundary. A re-run of the job creates no duplicate draft. (Q-B21, Q-B24.) See FR-69.
- **FR-52: Invoice draft management.** A club system carrier reads, edits, and deletes an invoice draft and its lines. A booked draft rejects a change. A flight links to the draft it produced, and the draft links back. The deletion of a draft follows a defined path for its flight. (Q-B21.)
- **FR-53: Recipient snapshot.** An invoice draft carries a copy of the recipient's name and address, so a later change to the person does not change the invoice. A recipient with no name or no address fails, and the failure names the missing field. See FR-11.
- **FR-54: Billing expectations.** A club system carrier keeps billing expectations, a real flight plus the invoice it must produce, and runs them to find an unintended change. An expectation names one flight, the expected invoice, and the fields that may differ. Running one expectation or all of them reports pass or fail, and names every field that differs. The screen shows the expected invoice and the last actual invoice together, and names the rules that fired. A club system carrier re-baselines an expectation in one action when the difference is intended. A club seeds its first expectations from the invoices that FR-74 verified.
- **FR-54a: Change preview.** A club system carrier sees what a rule change does before committing it. The preview runs against the club's billing expectations, or against a recent real flight. It shows the invoice before and after, side by side. The change commits only after the club system carrier confirms with the numbers visible.
- **FR-55: Invoice mail export.** Scheduled work exports the invoice drafts as a spreadsheet and emails it. The export is feature-equivalent to the legacy export, not byte-equivalent.
- **FR-56: Invoice interface for an external accounting system.** The system serves the invoice drafts through an interface that an external synchroniser reads. It exposes the same information the legacy interface exposes. Access needs authentication, and it is scoped to one club.

**4.8 Reporting, search, and lists**

- **FR-57: Flight reports.** A club administrator reads the standard flight reports. No report shows another club's record.
- **FR-58: Custom report builder.** A club administrator builds a report from fields and conditions. A built report saves and re-runs.
- **FR-59: Spreadsheet export.** A user exports a list or a report as a spreadsheet. It is feature-equivalent to the legacy export, and it covers only the user's own club.
- **FR-60: One search field with filter chips.** Every list offers one search field that matches across every displayed value, with filter chips beside it. The field filters as the user types, and it needs no submit control. It matches the aircraft, the pilot, the date, and the remarks together. Filter chips are the only filter mechanism, and no column carries a filter. The sort control sits in the list toolbar.
- **FR-61: Record items.** Every list uses a `RecordItem` inside a `RecordList`. The product contains no data table. An item carries an identity zone, a meta zone, a metric zone, and a state marker. It stacks on a phone, and it sits side by side on a pointer device. It keeps its height when a value is absent. The trailing slot carries one action that changes the record without opening it. (AD-17. See naming note N-1.)

**4.9 Public surfaces**

- **FR-62: Trial-flight registration.** A member of the public registers interest in a trial flight. The form collects the contact details the legacy form collects. The club receives an email, and the person receives a confirmation. Every aviation term carries a plain explanation.
- **FR-63: Passenger-flight registration.** As FR-62, for the passenger-flight case.
- **FR-64: Landing page.** A public page describes the club and links to both registration forms. It needs no authentication. The signed-in navigation does not appear on a public page.
- **FR-65: Abuse control.** The system limits the submission rate to both unauthenticated endpoints. The system refuses repeated submissions from one source after a fixed limit. The limit permits a club open day, where many people submit from one address. A refusal does not reveal whether a record was created. (New capability. Q-B16.)

**4.10 Email and scheduled work**

- **FR-66: Daily report email.** Scheduled work emails a club its daily flight summary, for that club only, with the club's template. A club with no flight that day receives no email.
- **FR-67: Licence expiry notification.** Scheduled work warns a person a fixed period before a licence or a medical expires. A person with nothing expiring receives no email. A person with no email address is skipped, and the job reports the skip. A person in several clubs receives one email per club.
- **FR-68: Monthly report for aircraft statistics.** Scheduled work produces the monthly report, per club. (Q-B3.)
- **FR-69: Scheduled work dispatcher.** An external timer starts the scheduled work. Every job writes its start, its end, and its outcome. A failed job does not stop the next job. A re-run of a job creates no duplicate record, and a partial failure rolls back its own batch. A failure is visible to the supplier without a database query.
- **FR-70: Email templates.** A club administrator edits the club's outgoing email templates, in German and in English.

**4.11 Migration**

- **FR-89: Club provisioning.** A club exists, and its first administrator can sign in, before FR-72 runs. The supplier admits a club to the closed user group. The first administrator receives a sign-in path that does not need a club email configuration. The first administrator reaches the migration upload and nothing else, until FR-77 commits. (Question 15.)
- **FR-71: Export tool.** A club administrator runs one tool against the legacy database, and the tool produces one file. It needs read access to the legacy database and nothing else. It encrypts its output. It reports what it read.
- **FR-72: Self-service upload.** A club administrator uploads the file through the browser. It needs no action by the supplier. A file the system cannot decrypt or read is rejected with a reason. A second upload replaces the first, and the system names what it discards. An abandoned import expires after a fixed period, and it leaves no partial data.
- **FR-73: Count verification.** Before the commit, the system shows the record counts side by side. The comparison covers flights, persons, aircraft, charging rules, reservations, and invoice drafts. Each row shows the legacy count and the imported count, and it marks a difference in either direction.
- **FR-74: Invoice verification.** Before the commit, the system replays a sample of the migrated legacy invoices through the dry run. Each sampled line reproduces to the cent, or the system marks it a mismatch. The sample size and its selection appear on screen. The sample meets a defined minimum. (Question 21.)
- **FR-75: Mismatch acceptance.** A club administrator accepts a mismatch with a written reason, and the migration continues. The system shows the legacy value and the reproduced value side by side. It writes the reason, the acceptor, and the time. The migration never blocks, and it never needs the supplier.
- **FR-76: Open mismatch list.** An accepted mismatch stays visible in the application until somebody resolves it. Each entry carries the flight, both values, the reason, and the acceptor. A resolution writes who resolved it and when.
- **FR-77: Commit and provisioning.** A club administrator commits, and the club is ready to fly. After the commit the club runs a flying day with no further setup. The whole path finishes in one session, and a token refresh does not lose the verification state. Each club migrates on its own schedule.
- **FR-78: New password after migration.** Every migrated user sets a new password before their first sign in. The system stores, logs, and transmits no legacy password hash. The reset path works before the club configures its own email templates. See FR-89.

**4.12 Home**

- **FR-79: Home dashboard.** A user opens the application and sees the club today. Home carries an airborne panel, today's flights, today's reservations, and the expiring licences and medicals. The airborne panel carries the NOW stamp and the landing stamp directly. With nothing in the air, the panel says so and offers Log flight.
- **FR-80: Fast note path.** An instructor adds a note to a recent flight in one action from Home, while the flight is not locked.
- **FR-81: Four destinations.** The navigation offers Operate, Plan, Records, and Admin. Four tabs sit in the bottom bar on a phone, and four destinations sit in the top bar on a pointer device. Every surface sits inside exactly one destination.

**4.13 Data governance**

- **FR-82: Club export.** A club administrator exports the whole club at any time, in a club-readable form. It covers every record of that club and no other, and it needs no action by the supplier.
- **FR-83: Club deletion.** The supplier deletes a club and everything in it after a written request, including the backups, within a fixed period. The period appears in the club's terms. A restore re-applies every deletion the supplier performed after the backup. (Question 16.) See FR-87.
- **FR-84: Data-subject requests.** A person exercises their rights through their club administrator. A club administrator finds every record about one person inside their club. A club administrator corrects or deletes a person record, subject to flight retention. The path is documented for the club administrator. (Questions 5, 16.)

**4.14 Supplier operations**

- **FR-85: Provisioning without the supplier.** A club completes sign-up, migration, and its first flying day with no message to the supplier. v1 limits sign-up to the closed user group. See FR-89.
- **FR-86: Error visibility.** Every unhandled error and every failed job reaches an error tracker with the club, the user, and the request. No error record carries personal data in plain text.
- **FR-87: Backup and restore.** The supplier restores the whole service, and one club, from a backup. A whole-service restore is tested and timed. One club restores without a restore of the others. The supplier keeps the backups in Switzerland or in the European Union.

### NonFunctional Requirements

Source: `prd.md` section 7. 13 requirements.

- **NFR-1: Correctness.** Money is exact, and no accounting calculation uses a binary floating-point type. Club isolation is structural. The client and the server derive every state name from one source.
- **NFR-2: Responsiveness.** No loading state below 300 milliseconds. A spinner appears only for an invoice run, a migration import, or a large export. Every list reserves its rows at final geometry, and the layout never shifts. The client prefetches every catalog before its form opens. The client writes optimistically. Motion is 110 milliseconds for a state change, 140 for a conditional field, and zero for anything a press caused. Nothing eases, bounces, or reorders under the pointer.
- **NFR-3: Security.** The server scopes cross-origin access. A token is short-lived, and it refreshes. A failed authentication during a write does not lose the entry. No personal data appears in plain text in a log or an error record.
- **NFR-4: Availability.** No single outage exceeds one hour during the flying window. The annual figure is 99.4 percent. Planned maintenance is excluded, and it goes outside the flying window. The offline bridge is sized to the same one hour.
- **NFR-5: Data residency.** Every store, every backup, and every log stays in Switzerland or in the European Union.
- **NFR-6: Legibility, the signed-in application.** Every action is reachable by keyboard in visual order. A visible focus ring is never removed. Every input has a programmatic label, and a placeholder is never the label. No state is carried by colour alone: `AIRBORNE`, `LOCKED`, and `UNSENT` each carry a word. Every error is tied to its field. Dropped for the signed-in application: screen-reader optimisation, live-region politeness tuning, and any formal WCAG conformance claim. `RecordList` still carries its table roles.
- **NFR-7: Accessibility, the public surfaces.** All of NFR-6, and WCAG 2.2 AA in full on an unknown device. The form works with no scripting beyond validation. No aviation term appears without a plain explanation. Every target is at least 44 pixels.
- **NFR-8: Display.** Dark only. One ground, no light mode, and no setting. The palette carries a high contrast floor: the WCAG AAA body-text ratio, and no low-contrast grey for any data value.
- **NFR-9: Observability.** Every unhandled error and every failed job reaches the supplier without a database query.
- **NFR-10: Testability.** Both time gates are drivable in a test with no change to the system clock.
- **NFR-11: Language.** Every text a person reads is ASD-STE100 Simplified Technical English: the interface, the email, and the error messages, in German and in English.
- **NFR-12: Platform floor.** The minimum browser is latest-minus-one for Chrome, Safari, Firefox, and Edge. That floor supplies baseline-2024 web features, so the product needs no polyfill budget, and no transpile target below ES2022.
- **NFR-13: Scale.** The design target is 8,000 flight records per year, 10 years of history, about 100,000 flight rows, 300,000 invoice lines, and about 1,000,000 audit rows: roughly 1.5 million rows and 1 to 2 GB. Peak is 200 flight records on the best Saturday, and fewer than 25 concurrent writers per club. Budgets: the nightly engine run finishes in 60 seconds per club and 10 minutes system-wide; a monthly invoice run for the largest club finishes in 60 seconds; a migration import for the largest club finishes in 30 minutes, plus 30 minutes to reproduce 10 years of invoices. AlpenFlight is a single-node workload.

### Additional Requirements

Source: `ARCHITECTURE-SPINE.md` — AD-1 to AD-21, the build gates, the stack, the structural seed, and
the consistency conventions. These bind implementation, so each one carries into a story or into the
template epic.

**`docs/modernization/` is not a source here.** `CLAUDE.md` demoted it on 2026-08-29: it narrows a
search, and it is not authoritative. Exact legacy behaviour comes from a `legacy-oracle` read
against `flsserver/` and `flsweb/`.

**The starter template.** The architecture names **no third-party starter template**. It supplies a
**Structural Seed** instead, and it gives one instruction: *the first epic is the template, not a
feature.* Epic 1 therefore builds the repository skeleton, one thin slice, one deep slice, and the
nine build gates. Every later story copies a skeleton.

- **AR-1: Repository skeleton from the Structural Seed.** Gradle multi-module under `alpenflight/`: `server/platform`, `server/core`, `server/modules-open/flarm`, `server/modules-pro` (charging, invoicing, migration, ogn), `client/platform`, `client/features`, and `deploy/`. Epic 1, story 1.
- **AR-2: One id strategy, fixed by the template epic.** The spine defers the id type and requires it fixed **before any second slice is built**, so no two slices diverge.
- **AR-3: Slice depth declaration (AD-1).** A slice is deep on a trigger: a state machine, an invariant spanning more than one record, money, or a legacy behaviour to reproduce exactly. Otherwise it is thin. A thin slice carries no business rule, and it is promoted to deep the moment it needs one. Expected split: 6 to 8 deep, about 45 thin.
- **AR-4: Row-level security tenancy (AD-2).** Every club-scoped table carries a PostgreSQL row-level-security policy plus `FORCE ROW LEVEL SECURITY`. The application connects as a non-owner role. The session variable is set once, in the transaction opener, never in a controller, a service, or a query. The same Flyway migration that creates a club-scoped table generates its policy.
- **AR-5: Three data kinds (AD-3).** Every table declares system reference, club-scoped, or cross-club link. `OwnerId` and `OwnershipType` are replaced by a plain `club_id` foreign key, and never ported.
- **AR-6: No global person search (AD-4).** A club reads a person only through a `ClubMembership` it owns. A cross-club crew member is added by an explicit link, never by a typeahead over every person.
- **AR-7: Reference data carries no personal data (AD-5).** A reference import drops every owner, operator, and billing column at the reader, before the canonical model.
- **AR-8: The application server holds no state (AD-6).** No in-process session, no in-memory cache a request depends on, no local file the next request needs, and no in-process scheduler. Every scheduled job takes a database lock and is idempotent under re-run.
- **AR-9: Expand-contract for every schema and API change (AD-7).** Deploy N adds the column and writes both, deploy N+1 reads the new one, deploy N+2 drops the old.
- **AR-10: Version tolerance in the client queue (AD-8).** The offline queue is versioned, and the client migrates it after an upgrade before it sends. Every write carries its client version. The server accepts the current version and one back, and rejects anything older with a typed error.
- **AR-11: Field-level conflict with fundamental-field escalation (AD-9).** The fundamental set is closed, declared once **on the server**, and served to the client. The client never keeps its own copy. **Blocked:** the membership of the set needs a `legacy-oracle` read against the 88 legacy conditional directives, before the flight-form epic.
- **AR-12: A stamp is idempotent and never conflicts (AD-10).** First stamp wins, second is a no-op, no dialog.
- **AR-13: A queued write parks at 24 hours (AD-11).** It stops applying by itself, it is never deleted, and it returns as a pending item with Apply and Discard.
- **AR-14: Three module tiers, proven by a standalone build (AD-12).** Core never references a pro module. CI builds and tests core plus the open modules standalone on every commit. A repository split is not the enforcement.
- **AR-15: One tenancy mechanism in both editions (AD-13).** A community install holds one club row and runs the same isolation code. There is no single-tenant mode, no bypass flag, and no second query path.
- **AR-16: Money is exact (AD-14).** `BigDecimal` in Java and `numeric` in PostgreSQL for every monetary and counter value. A CI rule fails the build on a `double` or `float` in a charging, delivery, or invoice type.
- **AR-17: The migration is a pipeline with a replaceable source reader (AD-15).** Source reader, canonical import model, validation, load. Only the reader is source-specific. The reader maps an aircraft or a location to a reference row where the identity matches, and falls back to a club-scoped row where it does not.
- **AR-18: A disposable application node (AD-16).** The node holds no data, the database is separate and managed with point-in-time recovery, and every part of the node is in code, so a rebuild is one command.
- **AR-19: One list component, no table element (AD-17).** `RecordList` holds `RecordItem`, and every list surface uses that pair. One definition renders stacked on a narrow viewport and as aligned zones on a wide one, using CSS grid.
- **AR-20: The interface floor is legibility, not conformance (AD-18).** See NFR-6 and NFR-7.
- **AR-21: REST with OpenAPI, and the generated type is the client's type (AD-19).** `/api/v1/deliveries/*` keeps its wire path, because the external accounting synchroniser polls it. The client generates its TypeScript from the specification and uses the generated type as the component's type. A thin slice has no client service layer: `httpResource` supplies the value, the loading state, and the error state in one declaration.
- **AR-22: One image, one node, everything in code (AD-20).** One container image serves the API and the built client. Docker Compose plus PostgreSQL is the supported install path for both editions. Kubernetes is permitted and never required.
- **AR-23: The legacy suite is an oracle, and AlpenFlight ships its own (AD-21).** `e2e/` drives the legacy app and is read-only. AlpenFlight ships its own end-to-end suite inside `alpenflight/`. A ported feature cites its oracle: the matching legacy spec, or a `legacy-oracle` read. A deep slice carries parity tests for the behaviour it reproduces; a thin slice carries the isolation test and its own CRUD tests. **No epic claims parity on the legacy suite as it stands** (RK-2).
- **AR-24: The nine build gates.** Each one fails the build: a thin slice contains no `application/` package and a deep slice contains all four; core never references a pro module; core plus the open modules build and test standalone; every club-scoped table has an RLS policy and every table declares its data kind; no binary floating-point type in a charging, delivery, or invoice type; no cross-slice reach into another slice's internals; a cross-tenant read test proves a query without a filter returns zero rows; no code calls `now()` directly and the `Clock` is injected; every mutable entity carries a version column.
- **AR-25: The consistency conventions.** Naming from `domain-model.md`; package by feature, never by layer, with `platform/` the only cross-cutting dependency; dates and times stored with an explicit zone, and a time-gate boundary states its zone and its inclusivity; one list envelope and one pagination shape that `RecordList` consumes; soft delete the default for every business record, with a hard delete only in the FR-83 and FR-84 erasure path; a typed conflict on a concurrent update, never a silent overwrite; the device holds the flight write path, today's flights, the airborne board, and the 13 prefetched catalogs, and nothing else; one error shape across every endpoint, with translation to HTTP in `web/` only; no personal data in a log line; German is the source language.
- **AR-26: The stack.** Java 25 LTS, Spring Boot 4.1.1, PostgreSQL 18.6, Angular 22.0.1. Angular 22 carries stable Signal Forms, stable `resource`, `rxResource`, and `httpResource`, and it is zoneless by default. Signal Forms are the mechanism for the flight form's conditional fields. The remaining dependency versions are pinned and verified at repository creation.
- **AR-27: There is no CI on this branch.** Rebuild 1's 11 workflows are archived. Epic 1 brings the new CI, and the build gates ride on it.
- **AR-28: The `legacy-oracle` reads the epics must schedule.** (a) The 88 legacy conditional directives, to close the fundamental-field set, before the flight-form epic. (b) The eight legacy features with no e2e spec: the monthly aircraft statistic report, the invoice mail export, articles CRUD, email-template CRUD, translation CRUD, system-data CRUD, system logs, and the dashboard. (c) The 26 behavioural questions Q-B1 to Q-B26, each attached to the epic that touches it.
- **AR-29: The legacy schema inventory.** 59 tables and 56 entity classes. `docs/modernization/legacy-tables/` holds 59 folders, each with `_table.json` and one JSON file per column. It narrows the search. The migration epic proves every mapping against `flsserver/` before it writes a story, because the folder is not authoritative. It does not read `legacy-migration-plan.md`, which is attempt-1 material.
- **AR-30: The T3 acceptance smoke.** `TESTING.md` names the minimum bar that proves the system runs: sign in, read the current user, read and write a flight, then re-read to confirm persistence. The first parity check for AlpenFlight is a T3 equivalent, and it belongs in Epic 1.
- **AR-31: The legacy defects not to carry forward.** `domain-model.md` section 4.6 lists them: the two misspelled location types, the two misspelled translation keys, the two spellings of `DaecIndex`, the two spellings of `Licence`, the four real member numbers hardcoded in a public repository, the navigation-bar tautology, and the five tenancy defects (`AuditLogsController`, `DashboardService`, `FlightService.cs:1186`, `FlightService.cs:483`, `PersonService.GetPerson(id, controlAccess)`).
- **AR-32: v1 reserves the free plan, and does not build it.** The club kind, the plan, the aircraft limit, and the three lifecycle states are first-class in v1. The free-plan surface and public sign-up are v2.
- **AR-33: `bmad-project-context` runs against `alpenflight/` once it holds code,** to produce `AGENTS.md`. It belongs to Epic 1.

### UX Design Requirements

Source: the UX design contract, `DESIGN.md` (visual identity and tokens) and `EXPERIENCE.md`
(information architecture, behaviour, states, interactions, journeys). Both win against any mock.

**Design tokens and the visual floor**

- **UX-DR1: The token set.** Implement the `DESIGN.md` frontmatter as the single token source: 24 colours (6 surface, 4 ink, 3 signals with 3 dim companions, 2 lines, 1 ink-on-live), 6 typography roles, the 4px spacing scale plus the named sizes (`topbar-h` 52, `tabbar-h` 56, `row-h` 44, `row-h-dense` 32, `item-h` 60, `zone-identity` 104, `zone-metric` 88, `touch-min` 44, `stamp-h` 56, `container-max` 1440), `rounded` 0 everywhere, `elevation.flat` and `elevation.overlay`, and `motion` instant, fast 110ms, reveal 140ms.
- **UX-DR2: The contrast gate.** Every colour that carries a value meets 7:1 against `surface-base`. An automated check proves it. No grey is added between `ink-settled` and `ink-disabled`.
- **UX-DR3: Dark only.** One ground, no light mode, and no theme control.
- **UX-DR4: Number and identifier typography.** Every number and every identifier is monospaced, with `font-variant-numeric: tabular-nums`. A numeric column is right-aligned, a text column left-aligned, and nothing is centred. A time is `10:24`, a duration is `01:18`, and a leading zero is never dropped.
- **UX-DR5: Nothing is round, and nothing carries a shadow for hierarchy.** `rounded.full` serves the account portrait alone. `elevation.overlay` serves a dialog or a menu alone.

**Components to build**

- **UX-DR6: `RecordList` and `RecordItem`.** Two layouts, not one that reflows: stacked on a phone (identity over meta at full width, metric over marker at the right, no fixed zone widths), and side-by-side fixed zones on a pointer device. Dense mode drops 60px to 44px on a pointer device only. An item keeps its height when a value is absent, and an empty metric reads `not set`. One list background, no alternating tone, and no rule between rows. Table roles when the data is tabular, and a label per field when stacked. A trailing 56px action slot sits outside the four zones.
- **UX-DR7: Typeahead picker.** Filters a prefetched local list, and never waits on the network. The list opens on focus, before a keystroke. A person matches first name, last name, and city together. `Enter` takes the highlighted entry. The last entry is Create when nothing matches.
- **UX-DR8: Date field.** One text input plus a calendar. Typed `29.08.2026` and a click write the same value. A clear control empties it. Never a native `<input type="date">`.
- **UX-DR9: Time field.** One text input that accepts `1024` and `10:24`, and formats on blur. A NOW control sits beside it. Never a native `<input type="time">`.
- **UX-DR10: NOW control (`stamp-button`).** 56px tall, at least 96px wide, full `live` fill. The largest control in the product, and the only control allowed to be the largest. No confirmation step.
- **UX-DR11: Create-in-place dialog.** Opens above the form, one level deep, never two. On save it closes, selects the new record in the field that opened it, and returns focus to the next field.
- **UX-DR12: Copy-from-last control.** One control per group, for the tow aircraft, both routes, and the engine counter. It persists between sessions on the device. A copied value carries a visible mark, and the copy is never automatic without one.
- **UX-DR13: Search field.** One field that matches across every displayed value, filters as the user types, and has no submit control.
- **UX-DR14: Filter chip.** The only filter mechanism. An active chip shows its value and a clear control.
- **UX-DR15: List group header.** Names the group and its count, such as `IN THE AIR - 2`, and restarts the list under it.
- **UX-DR16: Sort control.** Sits in the list toolbar, names the current sort and its direction, and never filters. An item has no column header, so this is the only place sorting lives.
- **UX-DR17: List toolbar.** Search field, then filter chips, then the sort control, in that order, above the first group header.
- **UX-DR18: Rule card.** Reads as `WHEN ... THEN ...`, with the run-order number at the left and the engine-stop flag always visible on the card, never only inside an editor.
- **UX-DR19: Dry-run trace.** One line per rule that fired, showing what it consumed and what it emitted, ending with the remainder and the total below a rule.
- **UX-DR20: Hold banner.** Names the holder, offers Take over, and renders the fields below read-only.
- **UX-DR21: Density control.** Pointer devices only, 44px against 32px, persists per person, and never appears on a phone.
- **UX-DR22: State markers.** `OPEN`, `AIRBORNE`, `LOCKED`, `BILLED`, `UNSENT`, each a bordered rectangle with a word. `AIRBORNE` is the only marker with a full-strength border. No state is carried by colour alone.
- **UX-DR23: Field row.** Label at the left in micro type, value at the right in mono. Focus paints the row and sets a 2px cyan inset marker; invalid sets the same marker in `warning`. An empty value reads `not set`, never a dash and never blank.
- **UX-DR24: Top bar and tab bar.** Top bar 52px, always visible, with the wordmark, the club home base, and the account portrait. Four destinations in the top bar on a pointer device, and a 56px bottom tab bar on a phone. No icon-only tab.
- **UX-DR25: Focus ring.** 2px `live`, 1px offset, on every focusable element. It is never removed, and never replaced by a background change alone.

**States, interaction, and layout**

- **UX-DR26: The state set.** Cold load reserves rows at final geometry with no spinner. No loading state below 300ms. A spinner appears only for an invoice run, a migration import, or a large export, and it names the operation. Empty states carry the defined wording and an action, never an illustration. A validation failure sits under its field, never as a summary at the top and never as a dialog. A save failure keeps the value on screen, marks the record `UNSENT`, and never retries silently.
- **UX-DR27: The keyboard map.** `Tab`, `Enter` in a typeahead, `Enter` in the form, `Esc`, `n` on the airborne board, `/`, and `?`. This is a new capability: the legacy client has no keyboard handler in its flight module.
- **UX-DR28: Conditional field reveal.** The form reveals downward, holds the scroll position, and never moves the field the user is about to press. 140ms linear.
- **UX-DR29: Touch.** 44px minimum for every target, 56px for NOW. The primary control of a screen sits inside the thumb arc at the bottom right on a phone. No gesture is the only way to reach an action.
- **UX-DR30: Autosave.** Every field commits on blur. The form has no Save-draft control, because a started flight is already saved.
- **UX-DR31: The responsive matrix.** One column below 768px, two columns from 768px, the dense pointer layout from 1200px, and the container stops at 1440px. The navigation, the flight form, the row height, the record item, the airborne board, and the density control each change as `EXPERIENCE.md` states.
- **UX-DR32: The rules editor is read-only on a phone.** Reordering rules changes every future invoice, and that decision is made at a desk.
- **UX-DR33: Voice and tone.** ASD-STE100 in both registers: the internal register for the signed-in application, and the public register with no jargon. The one-word-one-thing table is binding: flight, block start, landing time, locked, open flight, rule, and the invoice-draft term.
- **UX-DR34: The speed budget instrument.** A Playwright test counts the mouse events and the key events for the reference case. The phone reference case needs zero step changes and zero page changes.
- **UX-DR35: The public surfaces.** WCAG 2.2 AA in full, a form that works with no scripting beyond validation, no aviation term without a plain explanation, 44px targets with no exception, and no signed-in navigation on a public page.

### Naming notes

The naming directive says a rename is applied everywhere in one pass. Three renames were
half-applied. The supplier decided on 2026-08-29 to finish all three. They are now applied.

- **N-1: `RecordItem`, closed.** AD-17 and the UX contract carry `RecordList` and `RecordItem`. `prd.md` FR-61 and `domain-model.md` section 2.7 said "record strip". Both now carry `RecordItem`, and `domain-model.md` section 2.7 gained a `RecordList` row. `EXPERIENCE.md` names the component `RecordItem`.
- **N-2: "Duty flight leader", closed.** The word "operator" carried two meanings. It is now split everywhere: **duty flight leader** for the person at the airfield, and **supplier** for the person who builds and hosts the product. Applied in `prd.md`, `domain-model.md`, `EXPERIENCE.md`, `brief.md`, and `addendum.md`. PRD question 2 and domain-model question 2 are closed. Three open-item rows in `EXPERIENCE.md` named the wrong owner after the earlier partial pass; they now name the supplier.
- **N-3: The surface names, closed.** `EXPERIENCE.md` said "Accounting rules", "Deliveries", "Planning days", and "Delivery creation test". It now says charging rules, invoice drafts, roster days, and billing expectations, and its Admin destination matches `prd.md` section 9. Its one-word-one-thing table carried the reverse rule for "delivery"; that row is replaced by four rows that match `domain-model.md`.

- **N-4: "operator" in AD-5, closed.** The supplier decided that a club records its own ownership claim on its own aircraft record, inside its own tenant. AD-5 now says the reference import drops every column that names or addresses a person, and it names the register columns by their position, not by the word. It also states the club-side half. `domain-model.md` section 2.2 gained the four names the spine introduced: `ReferenceAircraft`, `ClubAircraft`, `ReferenceLocation`, and `ClubLocation`.
- **N-5: A legacy name never appears in our prose, closed.** The supplier set the rule: our documents use the target name, and `domain-model.md` section 4 carries the legacy mapping. Applied to `addendum.md` (supervising pilots, winch drivers, launch methods, cost splits, charging rules), `brief.md` (charging rules), and `EXPERIENCE.md` (membership status, launch method, roster day). A legacy name stays only where the sentence describes the legacy system, such as PRD question Q-B26.

### FR Coverage Map

Every requirement maps to exactly one epic. 90 rows.

| FR | Epic | What it delivers |
| --- | --- | --- |
| FR-1 | 1 | Structural club isolation |
| FR-2 | 1 | Cross-club person and crew |
| FR-3 | 1 | User and person separation |
| FR-4 | 1 | Authentication with a refreshing token |
| FR-5 | 1 | Roles and permissions |
| FR-6 | 1 | Profile self-edit |
| FR-7 | 1 | Password reset and email confirmation |
| FR-8 | 1 | Audit record, per club |
| FR-9 | 2 | Club master data |
| FR-10 | 2 | Licences and medicals |
| FR-11 | 3 | In-place record creation from the flight form |
| FR-12 | 1 | Operating counters |
| FR-13 | 2 | Reference data |
| FR-14 | 2 | Translations |
| FR-15 | 2 | System data and logs |
| FR-16 | 3 | One flight form |
| FR-17 | 3 | Conditional fields |
| FR-18 | 3 | Typeahead over prefetched catalogs |
| FR-19 | 3 | Date and time entry |
| FR-20 | 3 | The NOW stamp |
| FR-21 | 3 | Copy from the last flight |
| FR-22 | 3 | Smart defaults |
| FR-23 | 3 | Keyboard operation |
| FR-24 | 3 | Airborne board |
| FR-25 | 3 | Flight copy |
| FR-26 | 3 | Speed budget |
| FR-27 | 4 | Two state dimensions |
| FR-28 | 4 | Shared open flight |
| FR-29 | 4 | Daily validation |
| FR-30 | 4 | Lock gate |
| FR-31 | 4 | Lock countdown |
| FR-32 | 4 | Billing gate |
| FR-33 | 4 | Testable clock |
| FR-34 | 4 | Aircraft movements |
| FR-35 | 5 | Offline write path |
| FR-36 | 5 | Offline read of today |
| FR-37 | 5 | Unsent marker |
| FR-38 | 5 | Reconnect and conflict |
| FR-39 | 5 | Hold |
| FR-40 | 5 | Stamp bypass of the hold |
| FR-41 | 6 | Reservations |
| FR-42 | 6 | Scheduler |
| FR-43 | 6 | Roster day |
| FR-44 | 6 | Season assignment |
| FR-45 | 6 | Roster notification |
| FR-46 | 7 | Charging rules as WHEN and THEN |
| FR-47 | 7 | Visible run order |
| FR-48 | 7 | Engine-stop flag |
| FR-49 | 7 | Rules engine |
| FR-50 | 7 | Dry run |
| FR-51 | 8 | Invoice draft creation |
| FR-52 | 8 | Invoice draft management |
| FR-53 | 8 | Recipient snapshot |
| FR-54 | 7 | Billing expectations |
| FR-54a | 7 | Change preview |
| FR-55 | 8 | Invoice mail export |
| FR-56 | 8 | Invoice interface for an external accounting system |
| FR-57 | 10 | Flight reports |
| FR-58 | 10 | Custom report builder |
| FR-59 | 10 | Spreadsheet export |
| FR-60 | 1 | One search field with filter chips |
| FR-61 | 1 | Record items |
| FR-62 | 11 | Trial-flight registration |
| FR-63 | 11 | Passenger-flight registration |
| FR-64 | 11 | Landing page |
| FR-65 | 11 | Abuse control |
| FR-66 | 12 | Daily report email |
| FR-67 | 12 | Licence expiry notification |
| FR-68 | 12 | Monthly report for aircraft statistics |
| FR-69 | 4 | Scheduled work dispatcher |
| FR-70 | 2 | Email templates |
| FR-71 | 9 | Export tool |
| FR-72 | 9 | Self-service upload |
| FR-73 | 9 | Count verification |
| FR-74 | 9 | Invoice verification |
| FR-75 | 9 | Mismatch acceptance |
| FR-76 | 9 | Open mismatch list |
| FR-77 | 9 | Commit and provisioning |
| FR-78 | 9 | New password after migration |
| FR-79 | 3 | Home dashboard |
| FR-80 | 3 | Fast note path |
| FR-81 | 1 | Four destinations |
| FR-82 | 13 | Club export |
| FR-83 | 13 | Club deletion |
| FR-84 | 13 | Data-subject requests |
| FR-85 | 13 | Provisioning without the supplier |
| FR-86 | 13 | Error visibility |
| FR-87 | 13 | Backup and restore |
| FR-88 | 1 | User management |
| FR-89 | 9 | Club provisioning |

**Count per epic:** 1 → 13 · 2 → 6 · 3 → 14 · 4 → 9 · 5 → 6 · 6 → 5 · 7 → 7 · 8 → 5 · 9 → 9 ·
10 → 3 · 11 → 4 · 12 → 3 · 13 → 6. Total 90.

### Non-functional and additional coverage

An NFR, an AR, and a UX-DR are not owned by one epic. Each one binds several. This table names where
each is proved.

| Requirement | Proved in |
| --- | --- |
| NFR-1 correctness | Epic 1 (isolation, one state source), Epic 7 (money) |
| NFR-2 responsiveness | Epic 1 (the client platform), Epic 3 (the prefetch and the optimistic write) |
| NFR-3 security | Epic 1 |
| NFR-4 availability | Epic 13 |
| NFR-5 data residency | Epic 13 |
| NFR-6 legibility | Epic 1, then every later epic through the client platform |
| NFR-7 accessibility | Epic 11 |
| NFR-8 display | Epic 1 (the tokens and the contrast gate) |
| NFR-9 observability | Epic 13 |
| NFR-10 testability | Epic 4 |
| NFR-11 language | Every epic |
| NFR-12 platform floor | Epic 1 |
| NFR-13 scale | Epic 4 (the nightly run), Epic 8 (the invoice run), Epic 9 (the import) |
| AR-1 to AR-3, AR-24, AR-27, AR-30, AR-33 | Epic 1 |
| AR-4, AR-5, AR-6, AR-15 | Epic 1, then held by a build gate |
| AR-7, AR-25, AR-26 | Epic 1, then every epic |
| AR-8, AR-10, AR-11, AR-12, AR-13 | Epic 5 |
| AR-9, AR-14, AR-21, AR-22 | Epic 1 |
| AR-16 money | Epic 7, held by a build gate |
| AR-17 migration pipeline | Epic 9 |
| AR-18, AR-23 | Epic 13, Epic 1 |
| AR-19, AR-20 | Epic 1 |
| AR-28 oracle reads | Scheduled per epic, above |
| AR-29 legacy schema | Epic 9 |
| AR-31 legacy defects | Epic 1 (the five tenancy defects), Epic 2 (the spellings), Epic 11 (the navigation bar) |
| AR-32 free plan reserved | Epic 1 (the club kind, the plan, the aircraft limit, the three lifecycle states) |
| UX-DR1 to UX-DR5, UX-DR6, UX-DR13 to UX-DR17, UX-DR22 to UX-DR25 | Epic 1, the client platform |
| UX-DR7 to UX-DR12, UX-DR26 to UX-DR30, UX-DR34 | Epic 3 |
| UX-DR18, UX-DR19, UX-DR32 | Epic 7 |
| UX-DR20, UX-DR21, UX-DR31 | Epic 3, Epic 5, Epic 10 |
| UX-DR33 voice and tone | Every epic |
| UX-DR35 public surfaces | Epic 11 |

## Epic List

13 epics. Every one of the 90 requirements maps to exactly one epic. The dependencies run strictly
forward: no epic needs a later epic to work.

**Seven deep slices** — club, flight, reservation, charging, invoicing, migration, and governance —
against the thin remainder. That matches the spine's expected 6 to 8 (AD-1).

**Three epics sit in the pro tier** — 7, 8, and 9. Core plus the open modules must build and test
standalone without them (AD-12).

### Epic 1: The template — sign in, and see only your club

A user signs in and manages the club fleet. Nobody reaches another club's row, and a query that
forgets the club filter returns zero rows. This epic is the template the spine demands, not a
feature epic alone: it fixes the id strategy, it builds the client platform every later screen
copies, and it ships the nine build gates with the CI that runs them. Every later story copies a
skeleton from here.

- **Slices:** `core/club` **deep** — the club, the user, the person, and the club membership, with the AD-4 invariant across records. `core/aircraft` **thin** — the template thin slice.
- **Governed by:** AD-1, AD-2, AD-3, AD-4, AD-13, AD-17, AD-18, AD-19, AD-21
- **Also delivers:** AR-1 the repository skeleton · AR-2 the id strategy, fixed before any second slice · AR-24 the nine build gates · AR-27 the new CI · AR-30 the T3 acceptance smoke · AR-33 `AGENTS.md` · the client platform: the design tokens, `RecordList` and `RecordItem`, the typeahead, the field row, the search field, the filter chips, the sort control, and the focus ring
- **Oracle first:** Q-B8, Q-B9, Q-B13, Q-B17
- **Blocked by:** question 26 — does the audit record carry a club, and what happens to the migrated audit history?
- **FRs covered:** FR-1, FR-2, FR-3, FR-4, FR-5, FR-6, FR-7, FR-8, FR-12, FR-60, FR-61, FR-81, FR-88

### Epic 2: Set up the club — master data and reference data

A club administrator configures every list the rest of the product uses: persons, locations, flight
types, membership statuses, person categories, articles, and the email templates. The supplier's
reference data supplies the aircraft and airfield facts, with no column that names a person.

- **Slices:** `core/person`, `core/location`, `core/reference`, and about 40 more, all **thin**
- **Governed by:** AD-1, AD-3, AD-4, AD-5
- **Oracle first:** Q-B14, Q-B15, and five of the eight features with no e2e spec — articles CRUD, email-template CRUD, translation CRUD, system-data CRUD, and system logs
- **FRs covered:** FR-9, FR-10, FR-13, FR-14, FR-15, FR-70

### Epic 3: Log the flying day

A duty flight leader logs a flight on a phone, in one form, with no page change. One press of NOW
stamps the block start. This epic carries the speed budget, which the PRD names as one of the two
capabilities that decide whether the product succeeds.

- **Slices:** `core/flight` **deep** — the flight aggregate, the crew assignment, and the tow link
- **Governed by:** AD-1, AD-10, AD-17, AD-19
- **Oracle first:** the `legacy-oracle` read of the **88 legacy conditional directives**. It closes the fundamental-field set that AD-9 needs, and it answers Q-B19 and Q-B25. Also the dashboard read, the eighth feature with no e2e spec.
- **Note:** Home ships here with the airborne panel, today's flights, and the expiring licences. Epic 6 adds the reservations panel. Home works without it.
- **FRs covered:** FR-11, FR-16, FR-17, FR-18, FR-19, FR-20, FR-21, FR-22, FR-23, FR-24, FR-25, FR-26, FR-79, FR-80

### Epic 4: The flight lifecycle — states, gates, and the nightly jobs

A flight moves from open, through valid, to locked, and then becomes eligible for the rules engine.
The screen states when the flight locks. A test drives both gates in one run with no change to the
system clock.

- **Slices:** `core/flight` **deep** — the two state dimensions and the two time gates. The scheduled-work dispatcher arrives here, because this epic runs the first job.
- **Governed by:** AD-1, AD-6, AD-7
- **Oracle first:** Q-B1, Q-B2, Q-B3, Q-B4, Q-B5, Q-B20, Q-B23. Risk R2 is blocking: the gate unit and the boundary appear in no document.
- **FRs covered:** FR-27, FR-28, FR-29, FR-30, FR-31, FR-32, FR-33, FR-34, FR-69

### Epic 5: Work offline, and resolve a conflict

A duty flight leader logs a whole flight with no network. Every write reaches the server on
reconnect, or it waits as a named pending item. Two people who changed different fields never see a
dialog.

- **Slices:** `client/platform` **deep** — the versioned offline queue and the service worker. `core/flight` **deep** — the conflict endpoint.
- **Governed by:** AD-8, AD-9, AD-10, AD-11
- **Oracle first:** Q-B12. Needs the fundamental-field set that Epic 3 closes.
- **FRs covered:** FR-35, FR-36, FR-37, FR-38, FR-39, FR-40

### Epic 6: Plan the season — reservations and roster days

A pilot reserves an aircraft and the whole club sees it. A club administrator assigns a duty across
a whole season in one pass, and corrects one day later without touching the rest. Adds the
reservations panel to Home.

- **Slices:** `core/reservation` **deep** — the overlap invariant. The roster day and the duty assignment are **thin**.
- **Governed by:** AD-1, AD-2
- **Oracle first:** Q-B18, Q-B26
- **FRs covered:** FR-41, FR-42, FR-43, FR-44, FR-45

### Epic 7: Charging rules and the engine — pro tier

A club system carrier reads a rule as one sentence, sees the order the engine runs it in, and
explains an invoice line to a member with the dry run. The engine reproduces a legacy invoice to the
cent, and billing expectations hold it there.

- **Slices:** `modules-pro/charging` **deep** — the nine-phase engine and the decrement loop
- **Governed by:** AD-12, AD-14
- **Oracle first:** Q-B6, Q-B7, Q-B22. This is RK-1 and risk R3, the highest risk in the product.
- **Blocked by:** question 3 — may a club system carrier change the rule order?
- **FRs covered:** FR-46, FR-47, FR-48, FR-49, FR-50, FR-54, FR-54a

### Epic 8: Invoice drafts and the accounting handover — pro tier

Scheduled work creates the invoice drafts from the eligible flights. A club system carrier reads,
edits, and deletes them. The external accounting synchroniser reads them at the wire path it already
polls.

- **Slices:** `modules-pro/invoicing` **deep** — the invoice draft, the invoice line, and the recipient snapshot
- **Governed by:** AD-6, AD-12, AD-14, AD-19
- **Oracle first:** Q-B21, Q-B24, and the invoice mail export, one of the eight features with no e2e spec
- **FRs covered:** FR-51, FR-52, FR-53, FR-55, FR-56

### Epic 9: Move a club onto AlpenFlight — pro tier

A club administrator runs the export tool, uploads one encrypted file, reads the counts side by
side, sees each sampled invoice reproduce to the cent, accepts each mismatch with a written reason,
and commits. The supplier does nothing. The club flies the next day.

- **Slices:** `modules-pro/migration` **deep** — the source reader, the canonical import model, the validation, and the load
- **Governed by:** AD-5, AD-12, AD-15
- **Blocked by:** question 15 (who creates a club and its first administrator), question 21 (the invoice sample and the corpus), question 26 (the migrated audit history)
- **Note:** the legacy schema facts come from `flsserver/` and from `docs/modernization/legacy-tables/`, which narrows the search and is not authoritative.
- **FRs covered:** FR-71, FR-72, FR-73, FR-74, FR-75, FR-76, FR-77, FR-78, FR-89

### Epic 10: Reports and export

A club administrator reads the standard flight reports, builds a report from fields and conditions,
and exports any list as a spreadsheet. No report shows another club's record.

- **Slices:** `core/*` **thin**
- **Governed by:** AD-2, AD-17
- **FRs covered:** FR-57, FR-58, FR-59

### Epic 11: The public surfaces

A member of the public books a trial flight on an unknown device, in plain wording, with no aviation
term left unexplained. This is the one epic under the full WCAG 2.2 AA floor.

- **Slices:** `core/*` **thin**
- **Governed by:** AD-3 (a public surface reads only an explicitly published subset), AD-18, AD-19
- **Oracle first:** Q-B16
- **FRs covered:** FR-62, FR-63, FR-64, FR-65

### Epic 12: The club's email

Scheduled work sends the club its daily flight summary, warns a person before a licence or a medical
expires, and produces the monthly aircraft statistics. A club with nothing to report receives no
email.

- **Slices:** `core/*` **thin**, on the dispatcher Epic 4 delivered
- **Governed by:** AD-6
- **Oracle first:** the monthly aircraft statistic report, one of the eight features with no e2e spec
- **FRs covered:** FR-66, FR-67, FR-68

### Epic 13: Data governance and supplier operations

A club exports itself at any time, and a person exercises their rights through their club
administrator. The supplier deletes one club, restores one club, and sees every failure without a
database query.

- **Slices:** `platform` and `core/club` **deep** — an export and an erasure that span every table
- **Governed by:** AD-4, AD-5, AD-16, AD-20
- **Blocked by:** question 5 (does a flight record survive a person deletion), question 16 (the retention and deletion periods), question 19 (how the system counts a message to the supplier)
- **FRs covered:** FR-82, FR-83, FR-84, FR-85, FR-86, FR-87
