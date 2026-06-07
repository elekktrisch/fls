---
id: J-6b
title: Reservations & Planning hardening + inline form validation
epic: E-08
status: in_progress
journey0: false
carved: true
started_at: 2026-06-07
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
  - "[happy] the flights-list date-range picker works — selecting a from–to range filters the list (operator: currently broken)"
  - "[happy] EVERY aggregate list has ≥1 row for EVERY testuser — found EMPIRICALLY: seed the dev DB, then run a SELECT count per aggregate-table per testuser/club to find the actual empties, seed those (don't hand-enumerate; let the SELECTs tell the truth)"
  - "[happy] clubadmin1 opens the Users menu and the list renders (no 400 Bad Request)"
  - "[edge] clubadmin1 does NOT see a 'Clubs' nav entry"
screen: /reservations + /planning (hardening of J-5/J-6) + the shared edit-form + nav shell
headless_pulled_in: server-side form-validate endpoint(s) → consumed by inline-validation UX on edit forms
migration: N/A — no new entity; hardens shipped J-5 (AircraftReservation) / J-6 (PlanningDay). #6 is dev-seed, not a mapper.
parity_test: alpenflight/web/e2e/tests/forms/inline-validation.spec.ts (+ reservations/ + planning/ hardening specs — see Notes)
# T-02 per-push gate scope (read by ci.yml `changes` job, off `integration/J-6b`):
#  • mock_test → the per-push `alpenflight-mock-e2e` filter. J-6b's OWN mock specs
#    span THREE feature dirs (reservations/planning/forms), so this is a single
#    Playwright positional REGEX (an alternation over the three J-6b spec stems),
#    NOT a dir — a dir token would also pull prior journeys' specs in that dir
#    (J-5 reservations-crud, J-6 planning-crud) and violate "ONLY J-6b's specs".
#    The derive step (`Derive journey mock-e2e filter`) recognises a regex token
#    by its alternation branches and validates each branch's path stem on disk.
#  • parity_test (real-idp half) stays the mock `inline-validation.spec.ts` — J-6b
#    authored NO real-idp spec yet (its real-idp siblings land at T-17, per the
#    spec headers), so the `Derive journey proof spec` step correctly FALLS BACK
#    to the J-0 Locations baseline (a known-good real-chain proof, never no-spec).
#    Once T-17 adds a J-6b `tests/real-idp/…` spec, repoint parity_test at it.
mock_test: alpenflight/web/e2e/tests/(reservations/reservations-hardening|planning/planning-hardening|forms/inline-validation)   # per-push mock-e2e runs ONLY J-6b's 3 specs (12 tests); prior journeys' mock specs run at the §4 gate + nightly
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
- Dev seed: the V-series `*_dev_*_seed.sql` — **empirical full sweep, NOT analytical enumeration** (operator
  2026-06-07): boot the dev DB with the current seed, then run a `SELECT count(*) … GROUP BY club/owner` per
  aggregate table for each testuser/club (one query loop over `information_schema` t_* tables or an explicit
  per-table sweep) — let the SELECTs report which (testuser × aggregate) cells are empty, then add seed rows
  for exactly those. Persons-for-clubadmin1 was the one symptom the operator saw; the truth of the rest comes
  from the counts, not a guessed list. DoD: re-run the count sweep → zero empty cells for any aggregate a
  testuser should own.

**Pending _BOYSCOUT riders touching this surface** (so `/do-ship` folds them into the ≤40%):
- *Cascade-delete asserted only indirectly* (J-6 T-16, "rides the next planning touch") — this IS a planning touch; add the assertion that a deleted day's assignments are excluded from reads.
- *Producer dedupe soft-delete-blind* (J-6 T-11b/T-16) — only if a task touches the PlanningDay producer SELECT; this journey nominally doesn't, so likely defer (note, don't force).
- Generic infra riders available for the ≤40% fill (operator/`/do-ship` choose): structural post-deploy proof-gallery guard, un-mask migration-ingest constraint in dev/test, CI fail-aggregate, per-journey gallery shot-presence guard.

**Sacred cows / open calls for ship-time `legacy-oracle`:** RESOLVED — see `J-6b-oracle.md`.
Key: legacy has no async server validators; the server-validate endpoint pre-checks AlpenFlight's
EXISTING J-5 overlap-409 + J-6 uniqueness constraints inline (no new rule). DD.MM.YYYY confirmed
hardcoded in legacy. Clubs-hide is a NEW operator decision (legacy shows it to all). Users-400 is
NOT authz (diagnose request-shape/BE). Day/Week calendar is greenfield (no legacy parity).

## Tasks

Behavior oracle: `J-6b-oracle.md` (worker input; pruned at §5). FE seam map baked into scopes below.

- [x] **T-01** — spec stub + scaffold the J-6b proof-gallery page. Author `e2e/tests/` J-6b spec skeleton (selectors + flow for: reservations nav+toggle+paging, planning readonly/edit, inline validation, date format, nav gating) with thin assertions; scaffold the per-journey gallery page linked from the persistent index. Calendar is greenfield (AlpenFlight-only shots); edit forms can pair legacy where a legacy ref exists.
- [x] **T-02** — scope the per-push gate to J-6b. Set the journey's `mock_test:`/`parity_test:` frontmatter so per-push runs ONLY J-6b specs heavy (real-idp) + prior journeys (J-5/J-6/…) mock-IdP. Infra exists (J-6 T-02b derive-filter) — just wire J-6b's frontmatter + confirm. **DONE:** `mock_test:` set to a Playwright regex alternation over J-6b's 3 spec stems (its specs span 3 feature dirs, so a single dir token would pull prior journeys' specs); the `Derive journey mock-e2e filter` step extended to recognise+validate a regex token by its alternation branches (single-path case unchanged, all fail-safes preserved). Verified locally: the derived filter resolves to exactly J-6b's 12 tests / 3 files, no prior-journey leakage. `parity_test:` (real-idp half) stays the mock spec → `Derive journey proof spec` correctly falls back to the J-0 Locations baseline (J-6b has no real-idp spec until T-17). actionlint-clean.
- [x] **T-03** — shared inline-validation infra. `<af-field-errors>` molecule + a shared util/directive renders per-field errors WHILE TYPING, debounced ~200ms (valueChanges debounce, not `updateOn:'blur'`/touched-only). Client-side trivial rules. One molecule + one util; reused by T-06/T-07 and adoptable by other forms later.
- [x] **T-04** — reservation `…/validate` endpoint (overlap pre-check). A non-mutating validate path on the Reservation aggregate that runs the EXISTING J-5 aircraft-slot overlap check (the save-time 409) and returns a field-level result. One backend endpoint + reservations aggregate validate method. NO new rule. **DONE:** `POST /api/v1/aircraft-reservations/validate` (`@ReadOnlyQuery`, `isAuthenticated()`, tenant-scoped) → 200 `{valid, field, message}`. Reuses the SAME `existsActiveConflict` probe the save path calls; effective-span normalisation extracted onto the aggregate as `AircraftReservation.effectiveSpan(start,end,isAllDay)` (shared by save + validate, ADR 0022). Self-exclusion via `excludeReservationId`. 4 ITs (no-overlap→valid; overlap→invalid+`field:"start"`+message & no row persisted; edit-own-slot self-excluded→valid; other-club booking→valid/tenant-scoped). `./gradlew check` green; OpenAPI snapshot regenerated.
- [ ] **T-05** — planning-day `…/validate` endpoint (uniqueness pre-check). Non-mutating validate path running the EXISTING J-6 (club,date,location) `ux_pln_club_date_loc` check, field-level result. One backend endpoint + planning aggregate validate method. NO new rule.
- [ ] **T-06** — reservation-edit adopts inline validation. Client `required` on Date/Type/Pilot/Aircraft/Location + conditional Second-Crew (per oracle) while-typing via T-03; async overlap-validate via T-04 surfaced inline. `reservations/edit/reservation-edit.page.ts`.
- [ ] **T-07** — planning-edit adopts inline validation. Client `required` on Date/Location while-typing via T-03; async uniqueness-validate via T-05 inline. `planning/edit/planning-edit.page.ts`.
- [ ] **T-08** — reservations calendar: toggle + pager + label (items #2,#3). `reservations/calendar/reservations-calendar.page.ts`: (a) reproduce the "blacked-out" selected toggle on the deployed page, align selected styling to ADR-0024/design (legible); (b) pager granularity follows view — DAY view steps ±1 day, WEEK view steps ±7 (today `shiftWeek` always steps 7); (c) period label follows view + formats DD.MM.YYYY (day) / DD.MM.YYYY – DD.MM.YYYY (week) (today `weekSubtitle` is locale `3 Jan – 9 Jan`). One component.
- [ ] **T-09** — planning read-only + Edit toggle (items #10,#11). `planning/edit/planning-edit.page.ts`: the operator saw an editable read-only form though `form.disable()` runs on `isView()` — reproduce + find why (route never enters view-mode? disabled styling invisible? a control ignoring `disable`?); ensure ALL fields are visibly read-only AND add an "Edit" affordance to switch view→edit. One component.
- [ ] **T-10** — reservation Cancel returns to planning day (item #9). `reservations/edit/reservation-edit.page.ts` Cancel honors a `returnUrl` query param (today hardcodes `/reservations`); the planning inline `<af-reservation-row>` `[openLink]` passes `returnUrl=/planning/:id/edit`. One component + the call-site.
- [ ] **T-11** — nav gating (items #1,#8). `app.component.ts`: add `{ path:'/reservations', label:'Reservations', icon:'calendar' }` to `TENANT_SECTIONS`; make `/clubs` sysadmin-only (remove from the non-sysadmin `base` so club-admins don't see it). One component.
- [ ] **T-12** — date format DD.MM.YYYY (item #5). `shared/ui/organisms/af-date-picker` set `dd.MM.yyyy` on single + range pickers; align date display pipes to `dd.MM.yyyy` across touched templates. One organism + pipes.
- [ ] **T-13** — flights-list date-range picker fix (item #12, NEW). `features/flights/list/` — the date-range picker doesn't filter; diagnose (likely the `date-value-bridge` range model / af-date-picker range mode) + fix so a from–to range filters the list. Shares the af-date-picker organism with T-12 — order after it. One component/seam.
- [ ] **T-14** — dev-seed empirical sweep (item #6). Boot dev DB with current seed, run a `SELECT count(*)` sweep per `t_*` aggregate per testuser/club, seed exactly the empty cells (Persons-for-clubadmin1 was the symptom). New V-series `*_dev_*_seed.sql`. DoD: re-run sweep → zero empty cells.
- [ ] **T-15** — users-list 400 for club-admin (item #7). `features/users/` — diagnose the 400 for clubadmin1 (NOT authz per oracle; request-shape or BE query); fix so the list loads. May span FE param + backend; escalate only if a contract change is needed.
- [ ] **T-16** — planning cascade-delete assertion (rider, _BOYSCOUT J-6 T-16). Assert a deleted planning-day's `t_planning_day_assignment` rows are excluded from reads (the [key-error] delete proves the day leaves the list but never that assignments are gone). Spec/IT assertion.
- [ ] **T-17** — thicken the J-6b spec to full real assertions (all 14 ACs) + finalize gallery pairing + capture/commit any legacy reference shots for the edit forms. Final task.

**Riders cleared from `_BOYSCOUT.md` by this journey:** cascade-delete assertion (→T-16). Producer-dedupe-soft-delete-blind stays (no producer-SELECT touch here). Generic infra riders (gallery guard, un-mask constraint, CI fail-aggregate, shot-presence guard) NOT pulled — defer to a journey that naturally touches that infra.
