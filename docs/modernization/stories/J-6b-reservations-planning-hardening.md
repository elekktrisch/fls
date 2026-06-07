---
id: J-6b
title: Reservations & Planning hardening + inline form validation
epic: E-08
status: todo
journey0: false
carved: true
depends_on: [J-5, J-6]
rolls_up: []   # operator field-test polish, not horizontal S-stories
acceptance:
  # ≥60% feature — inline form validation (the new vertical capability)
  - "[happy] an edit form shows per-field validation messages WHILE TYPING, debounced ~200ms, inline under each field — client-side for trivial rules"
  - "[happy] a non-trivial field is validated by a server-side validate endpoint; its message surfaces inline on the same field without a full submit"
  - "[key-error] a field that fails client validation blocks submit AND shows its inline message; clearing the value clears the message (debounced)"
  # Reservations calendar polish (J-5 surface)
  - "[happy] clubadmin sees a 'Reservations' nav entry and navigates to /reservations"
  - "[happy] the Day/Week toggle renders the SELECTED button with the design's selected style (dark fg-bg + light text per screens-reservations.jsx:106-110), legible — not blacked-out"
  - "[happy] switching to Week mode changes the pager from day-stepping to week-stepping; the label shows the week start–end date range (e.g. '21–27 May 2026')"
  # Planning-day read-only / edit-mode (J-6 surface)
  - "[happy] read-only planning-day view renders ALL fields disabled/read-only — not merely the Save button hidden"
  - "[happy] read-only planning-day view exposes an 'Edit' affordance that switches the form into edit mode"
  - "[happy] Planning → edit planning-day → open a reservation from the inline list → Cancel → returns to the planning-day edit form (not the /reservations overview)"
  # Cross-cutting platform polish
  - "[happy] date inputs + date display render DD.MM.YYYY"
  - "[happy] EVERY aggregate list has ≥1 row for EVERY testuser — enumerate all aggregates, seed any found empty (Persons was empty for clubadmin1; sweep the rest, not just the named example)"
  - "[happy] clubadmin1 opens the Users menu and the list renders (no 400 Bad Request)"
  - "[edge] clubadmin1 does NOT see a 'Clubs' nav entry"
screen: /reservations + /planning (hardening of J-5/J-6) + the shared edit-form + nav shell
headless_pulled_in: server-side form-validate endpoint(s) → consumed by inline-validation UX on edit forms
migration: N/A — no new entity; hardens shipped J-5 (AircraftReservation) / J-6 (PlanningDay). #6 is dev-seed, not a mapper.
parity_test: alpenflight/web/e2e/tests/forms/inline-validation.spec.ts (+ reservations/ + planning/ hardening specs — see Notes)
adr_refs: [0022, 0024, 0008, 0006]
---

## Context

J-5 (reservations) and J-6 (planning) shipped, then operator field-testing turned up
11 rough edges across those two surfaces plus the nav shell, the shared edit forms, and
dev-seed. This journey hardens that surface. Its **≥60% feature** is a genuinely new
cross-cutting capability — **inline validation-while-typing** on edit forms (client-side
where trivial, a server-side validate endpoint for the rest) — built on the planning +
reservation edit forms and extracted into shared infra. The **≤40% tech-debt** folds the
remaining bug/UX fixes (nav role-gating, toggle styling, week paging, read-only/edit-mode,
cancel-return nav, DD.MM.YYYY, seed coverage, Users-400, Clubs-menu-hide). Provable by one
green Playwright run driving the reservations calendar + planning edit + a representative
edit form's debounced inline validation.

## Spec must assert

**Inline form validation (the feature).** A representative edit form (planning-day or
reservation edit — both already on this surface) shows per-field error messages **while the
user types**, debounced ~200ms, rendered inline under the field via the existing
`<af-field-errors>` molecule (`shared/ui/molecules/field-errors/`, S-007). Trivial rules
(required, range, format) validate client-side from the reactive-form validators; a
non-trivial rule (the kind legacy enforced server-side — e.g. an overlap / cross-entity /
uniqueness check) calls a **server-side validate endpoint** and surfaces its message inline
on the offending field, *without* a full save round-trip. Today the edit pages only show a
field's errors on `touched` after blur/submit (`planning-edit.page.ts` field-error wiring) —
the spec asserts the message appears on debounced keystroke, and clears when the value
becomes valid. Legacy reference: AngularJS `$asyncValidators` + the server `Validate*`
actions (legacy-oracle to pin exact server-validated rules per form at ship time).

**Reservations calendar (J-5).** (a) A `Reservations` entry is present in the primary nav
for a club admin and routes to `/reservations`. (b) The Day/Week toggle's selected button
uses the design's selected styling — selected = `--color-fg` background + `--color-bg` text
(screens-reservations.jsx:106-110), legible; the current build renders it "blacked out"
(contrast inverted). (c) In Week mode the pager steps by **weeks** not days, and the header
label shows the week's start–end date range (design subtitle "Week 21 · 21–27 May 2026 ·
LSZF", screens-reservations.jsx:61) — `reservations-prev-week`/`-next-week` already exist;
the assertion is the label + step granularity follow the active view.

**Planning-day read-only + edit-mode (J-6).** (a) Opening a planning-day in read-only mode
renders every field disabled/read-only — assert an input is non-editable, not just that Save
is absent. (b) A read-only view exposes an **Edit** button that flips the form to edit mode
(fields become editable, Save returns). (c) From an edit planning-day, opening a reservation
from the inline list (`<af-reservation-row>`, J-6 T-08b) then pressing **Cancel** returns to
the planning-day edit form — not `/reservations`. The reservation-edit cancel handler must
honor a return-url/referrer passed by the planning inline list (today it hardcodes nav to
`/reservations`).

**Cross-cutting.** (a) Date inputs + display render **DD.MM.YYYY** (`<af-date-picker>` +
date display pipes). (b) Dev seed gives clubadmin1's club ≥1 row per aggregate (Persons was
empty) — assert a list that was empty now shows a row. (c) clubadmin1 opens the Users menu
and the list loads (no 400 — diagnose the users list query/authz for clubadmin1's role). (d)
clubadmin1 does **not** see a `Clubs` nav entry (role-gated out).

## Notes

**One-screen rule — deliberate exception.** Like J-5 (calendar+edit) and J-6 (list+edit+
setup), this is a cohesive hardening of an already-shipped feature *family*, not a fresh
multi-screen process. The spine is the shared **edit-form validation** capability; the
reservations/planning/nav fixes are demonstrated on the same run. If `/do-ship` finds the
inline-validation feature alone is a full journey, it may split the pure-bug riders back out
to ride a later same-surface gate — but the operator's intent (2026-06-07) is to fix these
J-5/J-6 rough edges now, with gate + gallery proof, rather than let them sit as riders with
no near-term same-surface journey.

**Likely task seams** (non-binding, for `/do-ship` — seam-granular):
- `shared/ui/molecules/field-errors/` + a shared debounced-validation directive/util — the inline-while-typing renderer + 200ms debounce (one molecule + one util).
- Server validate endpoint: one `…/validate` resource per form that needs server rules (reservation-overlap, planning-day uniqueness) — name the aggregate, not "the backend".
- `reservations/calendar/reservations-calendar.page.ts` — toggle selected-style (ADR 0024 tokens) + week-vs-day pager granularity + week-range label (one component).
- `planning/edit/planning-edit.page.ts` — read-only renders disabled fields + an Edit-mode toggle (one component).
- `reservations/edit/reservation-edit.page.ts` — Cancel honors a `returnUrl`/referrer query param (one component); planning inline list passes it.
- `shared/ui/organisms/af-date-picker/` + date display pipe — DD.MM.YYYY (one organism + the pipe).
- Nav role-gating: wherever the `af-nav-bar` `items()` list is assembled (the app shell) — add `Reservations`, gate `Clubs` by role/tenant (ADR 0008). `af-nav-bar.component.ts` itself just renders `items()`; the gating is upstream.
- Users list 400 for clubadmin1 — `features/users/` store/page query (likely a role/tenant param the clubadmin1 principal sends differently); diagnose at ship time, it may be backend.
- Dev seed: the V-series `*_dev_*_seed.sql` — **full sweep**: enumerate every aggregate (Person, Aircraft, Location, Flight, AircraftReservation, PlanningDay, AccountingRuleFilter, Article, EmailTemplate, …) and ensure ≥1 row for EVERY testuser (clubadmin/clubadmin1/clubadmin2/pilot/…); Persons was the known-empty case but seed any aggregate found empty, not just Persons. The seam's DoD is "no testuser opens a list to an empty table for an aggregate they should own."

**Pending _BOYSCOUT riders touching this surface** (so `/do-ship` folds them into the ≤40%):
- *Cascade-delete asserted only indirectly* (J-6 T-16, "rides the next planning touch") — this IS a planning touch; add the assertion that a deleted day's assignments are excluded from reads.
- *Producer dedupe soft-delete-blind* (J-6 T-11b/T-16) — only if a task touches the PlanningDay producer SELECT; this journey nominally doesn't, so likely defer (note, don't force).
- Generic infra riders available for the ≤40% fill (operator/`/do-ship` choose): structural post-deploy proof-gallery guard, un-mask migration-ingest constraint in dev/test, CI fail-aggregate, per-journey gallery shot-presence guard.

**Sacred cows / open calls for ship-time `legacy-oracle`:** exact set of server-validated
rules per form (which fields are async-validated in legacy vs purely client); whether
clubadmin1's Users-400 is an authz scope bug or a missing query param; the legacy date
format confirmation (DD.MM.YYYY is the DE/CH convention, but confirm the picker + display).
