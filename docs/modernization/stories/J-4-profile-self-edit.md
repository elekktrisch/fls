---
id: J-4
title: Profile self-edit (/profile — Account / Personal / Pilot / Notifications)
epic: E-06
status: in_progress
started_at: 2026-06-05
journey0: false
carved: true
depends_on: [J-2]
rolls_up: [S-182]
acceptance:
  - Avatar/initials button in the nav bar opens a dropdown with "Profile" + "Sign out"; "Profile" routes to `/profile` (any authenticated principal with a `t_user` row). [happy]
  - Account tab edits the User aggregate's mutable fields (friendlyName, notificationEmail, phoneNumber, languageId) via `PATCH /api/v1/me/profile`; username + clubId + keycloakSub render read-only; a language change refreshes the SPA locale. [happy]
  - Personal-info tab edits the caller's Person contact + address fields (address, zip/city/region, country, phones, private/business email, birthday, …) via `PATCH /api/v1/me/person`; name fields (first/last/mid/company) are read-only (rename stays admin-only). [happy]
  - Pilot-info tab edits the caller's Person licence + medical fields via `PATCH /api/v1/me/person/licences`; the change emits a `person.licences_updated` audit event with a before/after diff (FADP-sensitive provenance, S-027). [happy]
  - Notifications tab edits only the caller-tenant PersonClub's notificationPrefs via `PATCH /api/v1/me/club-membership/notification-prefs`; memberNumber / memberState / role flags stay admin-only. [happy]
  - No-Person state: when `user.person_id` is null, the Personal / Pilot / Notifications tabs show a "ask your club admin to link your member record" banner and disable their forms; the Account tab still works. [edge]
  - Tenant + principal isolation: every endpoint resolves the caller's User/Person/PersonClub from the JWT — no `:id` path param; a caller cannot read or edit another principal's profile (cross-principal edit is structurally impossible). [key-error]
  - A migrated/showcase Person renders its real values in the tabs and a round-trip edit persists + reflects on reload. [happy]
  - Proof gallery shows the **paired legacy `flsweb` /profile ↔ AlpenFlight /profile** (screenshots + the legacy video) so the redesign is judgeable side-by-side — even though there's no data migration. [demonstrability]
screen: /profile   # replacing legacy profile/ — partial redesign (4-tab SaaS); password change NOT carried (Keycloak owns it, ADR 0007)
headless_pulled_in: avatar/initials nav-bar dropdown (Profile + Sign out) — the self-edit entry point; reused later by settings / theme stories
migration: N/A — Person / User / PersonClub are ALREADY migrated (existing mappers in migration-bundle/identity + persons.application.PersonMapper); J-4 makes self-edit fields editable on those existing aggregates, carries no net-new mapper → no fanout. Proof is AlpenFlight-only + the migrated/showcase-Person-renders-and-round-trips check.
parity_test: alpenflight/web/e2e/tests/profile/self-edit.spec.ts
adr_refs: [0007, 0008, 0022, 0023]
---

## Context

`/profile` is the rewrite's first **self-edit** surface — a logged-in user maintains their
own account, contact, pilot/medical, and notification data without an admin. S-182 carves
it as one fat 4-tab slice (Account / Personal / Pilot / Notifications), each tab backed by
its own caller-scoped `PATCH /api/v1/me/*` endpoint on an existing aggregate
(User S-052, Person S-051, PersonClub S-051). Entry is a new avatar/initials dropdown in
the nav bar (Profile + Sign out) — the standard SaaS pattern, reused by later settings
stories. The legacy `profile/` screen (a password form + language + a person form) is
**partially redesigned**, not parity-ported: password change is gone (Keycloak owns
credentials, ADR 0007).

## Spec must assert

The Playwright run (`profile/self-edit.spec.ts`) proves, against a migrated/showcase principal:

1. **Entry:** avatar dropdown → "Profile" → `/profile`; "Sign out" ends the session.
2. **Account round-trip:** edit friendlyName / notificationEmail / phone / language → `PATCH /api/v1/me/profile` → persists + reflects on reload; username/clubId read-only; language change flips the SPA locale.
3. **Personal round-trip:** edit address/contact fields → `PATCH /api/v1/me/person` → persists; name fields read-only.
4. **Pilot round-trip:** edit a licence/medical field → `PATCH /api/v1/me/person/licences` → persists AND a sysadmin fixture reads the `person.licences_updated` audit row (before/after diff).
5. **Notifications round-trip:** toggle a notification pref → `PATCH /api/v1/me/club-membership/notification-prefs` → persists; admin-only membership fields untouched.
6. **No-Person banner:** a fixture User with `person_id = null` → Personal/Pilot/Notifications tabs show the banner + disabled forms; Account still edits.
7. **Isolation:** endpoints take no `:id`; the caller only ever edits their own records (assert a second principal's data is untouched / unreachable).

## Notes

**Migration: none (no new mapper) — but the paired legacy gallery IS in scope.** Person/User/
PersonClub already migrate (prior identity journeys + `persons.application.PersonMapper`). J-4
is self-edit UI + caller-scoped PATCH endpoints on existing aggregates → **no data-migration
`fanout` run required** (no producer SELECT to validate). HOWEVER, `/profile` **replaces a
legacy screen**, so the done-bar's legacy-replacing-screen requirement applies: capture the
**legacy `flsweb` /profile** (screenshots + video) and pair it with the AlpenFlight 4-tab
/profile in the proof gallery (operator ask). The legacy capture uses the **legacy-video
harness** (the same `e2e/tests/**` flsweb-stack capture path J-2 used for legacy flights —
`flights-parity-J2.spec.ts`), which runs in the `fanout`/legacy-video pipeline; J-4 needs that
harness step but NOT the export→migrate half of fanout. Done-bar = AlpenFlight pass video +
4-tab screenshots + the migrated/showcase-Person round-trip + the **paired legacy↔AlpenFlight
/profile** in the gallery. (If ship-time finds a self-edit field the existing Person mapper
doesn't carry, that's a small mapper touch, not a new entity — note it then.)

**Parity exclusions (legacy `profile/` is a redesign):**
- **Password change dropped** — credentials are Keycloak's (ADR 0007); the legacy in-app
  password form is not carried. If a "change password" affordance is wanted, it deep-links
  to the Keycloak account console (out of scope here; note for a follow-up).
- `remarks` (admin free-text notes) and membership identity fields (memberNumber /
  memberState / roles) are **admin-only** — not on any self-edit tab.
- Rename (Person name fields) stays admin-only — read-only here.

**Real-roles proof (J-3 lesson, [[project_real_idp_real_roles_catches_authz_gaps]]):** drive
the spec as a **real PILOT / low-privilege principal** (the screen's actual audience), not an
admin — that's what proves the `isAuthenticated()` + caller-resolved-from-JWT gating and
catches any authz gap. The sysadmin fixture is only for reading the audit row in AC4.

**Showcase-seed extension (the J-3 convention, [[feedback... showcase seed]]):** extend the
showcase seed with a self-editable principal — a Person with full contact + licence/medical +
PersonClub notification prefs, and a separate no-Person user (sysadmin/secretary) for the
banner case. So the tabs render populated and the no-Person edge is reachable.

**Audit + sensitivity:** the Pilot-tab `person.licences_updated` event rides S-027's audit
infra; medical-field PII-redaction policy (which dates emitted vs hashed) + email-change
verification + account-closure (DSAR) are explicitly **deferred** (S-182 open questions) —
not in this journey.

**Likely task seams (non-binding, for /do-ship):** the avatar nav-bar dropdown
(`shared/ui` molecule or nav-bar organism extension); the `/profile` shell + tab routing
(one feature route/component); the four tab components (Account / Personal / Pilot /
Notifications — likely one task each, signal-store-backed); the four `me/*` PATCH endpoint
clusters on User / Person / PersonClub aggregates (one task per aggregate's mutator +
endpoint + IT — `User.updateProfile`, `Person.updateContact`, `Person.updateLicences` +
audit event, `PersonClub.updateNotificationPrefs`); the showcase-seed self-edit-principal
extension; a **legacy-parity capture spec** (`e2e/tests/profile/profile-parity-J4.spec.ts` in
the top-level e2e suite, flsweb-stack, records the legacy /profile video + screenshots — model
on `e2e/tests/flights/flights-parity-J2.spec.ts`) + its wiring into the legacy-video/gallery
pipeline so the paired legacy↔AlpenFlight /profile renders; the spec thicken. Riders to fold (from `_BOYSCOUT`): orval positional-`getN`
naming (J-4 adds 4 `/me/*` endpoints → regenerates the client — good place to set explicit
operationIds), and the e2e prettier/tsc normalization on the new spec.

## Assumptions made

- **Route is `/profile`** opened from a **nav-bar avatar/initials dropdown** (S-182 AC1).
- **No data-migration fanout** — J-4 carries no net-new mapper (existing identity mappers cover
  Person/User/PersonClub). But the **paired legacy↔AlpenFlight /profile gallery IS required**
  (operator) via the legacy-video harness — it's a legacy-replacing screen. If a self-edit field
  is unmigrated, ship adds it to the existing mapper (still no fanout-gating new entity).
- **PersonClub prefs mutator:** assume a new `updateNotificationPrefs(prefs)` mutator (S-182
  open-q option (a) — cleaner than read-then-write of the whole membership shape); refine/ship
  confirms.
- **Password / email-verification / account-closure / avatar** are out of scope (S-182 defers).

## Tasks

Ordered, dependency-first. Each is one seam, sized for a fresh `/do-task` worker. Per-tab
verticals (backend endpoint → frontend tab) so the slice is provable incrementally and the
gallery surfaces early. Backend mutators mostly **already exist** (`User.updateProfile`,
`Person.updateContact`, `Person.updateLicences`); the nav avatar dropdown **already exists**
in `af-nav-bar.component.ts` — these tasks wire caller-scoped `PATCH /me/*` endpoints + DTOs
+ the `/profile` feature. Drive the spec as a **real PILOT** principal (J-3 lesson).

- [x] **T-01 — Spec stub.** `self-edit.spec.ts` committed (`5c5a830e`), PILOT-driven (`pilot1@example.com`),
  thin asserts, red-by-design. testid contract for downstream tabs: shell `profile-page` + `profile-tab-*`/`profile-panel-*`
  (account/personal/pilot/notifications) + `profile-no-person-banner`; fields `profile-account-{friendlyName,
  notificationEmail,phone,language}`, `profile-personal-{address,city,phonePrivate,phoneBusiness,birthday}`,
  `profile-pilot-{licence-glider,medical-expiry}`, `profile-notifications-pref-{flightReports,reservations,clubNews}`.
  Entry reuses nav `af-nav-user` trigger + Profile menuitem. **Open (→T-03):** spec needs the real-idp
  project but its `tests/profile/` path matches the mock `chromium` project — resolve project routing (testMatch/tag) in T-03.
- [x] **T-02 — Showcase-seed self-edit principal.** `V30__dev_profile_self_edit_seed.sql`. Self-edit
  PILOT = reused realm `pilot1@example.com` (club seed-club-1) w/ full Person contact + `has_glider_pilot_licence`
  + `medical_class2_expire_date=2027-09-30` + `licence_number=CH-GLD-0001`; PersonClub prefs `receive_flight_reports=true`,
  `receive_aircraft_reservation_notifications=false`, `receive_planning_day_role_reminder=true`; User self-fields filled.
  No-Person = reused realm `pilot-empty1@example.com` w/ `person_id NULL`. Zero realm churn; V1→V30 flyway clean + idempotent.
  Note: `member_state_id` NULL (no `t_member_state` seeded) — T-10/T-11 assert on notif prefs, not member state.
- [x] **T-03 — `/profile` shell.** `features/profile/{profile.routes.ts,profile-shell.page.ts}` (nz-tabs,
  full testid contract), registered in `app.routes.ts`, i18n keys all 4 locales. Nav user-summary was
  ALREADY app-level (`app.component.ts userSummary()` ← `SessionStore.authenticatedUser()`) → dropdown shows
  on `/profile`, no new wiring. No-Person gating: `hasPerson = SessionStore...personId != null` drives banner +
  `[nzDisabled]` on Personal/Pilot/Notifications (no MeController change needed — `/me` already returns personId).
  Playwright routing fixed: chromium `testMatch` → `tests/!(real-idp|profile)/**`, real-idp → `[real-idp/**, profile/**]`.
  Commit `a2cd80c6`.
- [x] **T-04 — Account endpoint `PATCH /api/v1/me/profile`.** `MeProfileController` (+ `MeProfileUpdateRequest`,
  `MeProfileExceptionHandler`, `users/application/SelfProfileUpdate`, `UsersService.updateOwnProfile`). Caller
  resolved from JWT `sub` (no `:id`); DTO = friendlyName(@NotBlank≤100) / notificationEmail(@NotBlank @Email) /
  phoneNumber(@Nullable≤30) / languageId(@NotNull, must exist); reuses `User.updateProfile` preserving existing
  remarks (admin-only); `operationId=updateMyProfile` (folds orval rider). `MeProfileControllerIT`: happy +
  isolation (A's token, no id, B's row byte-identical) + validation-400, PILOT-driven. OpenAPI snapshot regen'd.
  **Verified via CI** (`next-build` runs the IT on GH runners) — local Testcontainers PG times out at 60s on the
  LXC box (Docker fine; manual PG works), so local IT self-verify is unreliable this window.
- [x] **T-05 — Account tab.** `features/profile/{profile-account.tab.ts, account.store.ts}` — reactive
  `nz-form` (af-form-field/af-input/af-select) with the T-01 testids; `AccountStore` (feature-scoped,
  provided on the shell) loads `/me` + saves via orval **`updateMyProfile(meProfileUpdateRequest)`**;
  username/clubId read-only. **Locale refresh:** on save the store maps the persisted `languageId`→locale
  (`localeForLanguageId`, promoted to `shared/ui/locale` from `features/users/language-options`) and calls
  `LocaleService.set` — the same single switch the bootstrap uses (ng-zorro + transloco + `<html lang>`).
  **Session refresh:** emits a new `profile.updated` MUTATION_BUS event (no sibling-store injection);
  `SessionStore` subscribes → `loadMe()` so the nav avatar reflects the change. **`/me` projection touch:**
  `/me` lacked `friendlyName`/`phoneNumber`/`languageId` for the form's initial values + round-trip-reflect,
  so extended `MeView`/`MeService` SELECT (join `t_language`)/`MeResponse` with
  `friendlyName,phoneNumber,languageId,languageCode` (username/clubId were already present) — snapshot +
  orval client regen'd, `MeProfileControllerIT` strengthened to assert the new projection fields. tsc + lint
  + 327 unit tests + `ng build --configuration mock-auth` green locally.
- [ ] **T-06 — Person-contact endpoint `PATCH /api/v1/me/person`.** JWT→caller's Person,
  `updateContact`; name fields (first/last/mid/company) read-only/ignored; no-Person → clean error;
  `operationId`; IT + isolation. Seam: me-person endpoint.
- [ ] **T-07 — Personal tab.** Contact/address form + store + `PATCH /me/person`; name fields read-only. Seam: Personal tab component.
- [ ] **T-08 — Person-licences endpoint `PATCH /api/v1/me/person/licences` + audit.** `updateLicences`
  + emit `person.licences_updated` audit with before/after diff via `AuditTrail.record`; `operationId`;
  IT incl. audit-row read + isolation. Seam: me-person-licences endpoint.
- [ ] **T-09 — Pilot tab.** Licence/medical form + store + `PATCH /me/person/licences`. Seam: Pilot tab component.
- [ ] **T-10 — Notification-prefs endpoint `PATCH /api/v1/me/club-membership/notification-prefs` + mutator.**
  New `updateNotificationPrefs` mutator on PersonClub (driven via Person); caller-tenant membership
  resolved from JWT; admin-only fields (memberNumber/memberState/roles) untouched; `operationId`; IT +
  isolation. Seam: PersonClub notif-prefs mutator + endpoint.
- [ ] **T-11 — Notifications tab.** 3 pref toggles + store + `PATCH /me/club-membership/notification-prefs`. Seam: Notifications tab component.
- [ ] **T-12 — Legacy-parity capture spec.** `e2e/tests/profile/profile-parity-J4.spec.ts` (top-level
  e2e, flsweb stack — model on `e2e/tests/flights/flights-parity-J2.spec.ts`) records the legacy
  `/profile` video + screenshots; wire into the legacy-video/gallery pipeline so the **paired
  legacy↔AlpenFlight /profile** renders. Seam: parity capture spec + pipeline wiring.
- [ ] **T-13 — Thicken spec + e2e normalization.** Full real assertions from the oracle (entry,
  4 round-trips incl. sysadmin-fixture audit-row read for Pilot, no-Person banner, isolation);
  fold the e2e prettier/tsc rider on the new specs. Seam: spec edit.

- [x] **T-14 — Fix V30 seed regression in J-3 dashboard proof (CI-surfaced).** T-04's push went red:
  `start-dashboard.spec.ts:254` pilot `start-last-flight-card` not found — V30 (`AND person_id IS NULL`)
  links pilot1 to a NEW person, conflicting with the showcase-seed harness step that links pilot1 to the
  8-flights PIC person (README T-03b). Fix so BOTH J-3 dashboard last-flight card AND J-4 profile tabs
  render: ENRICH pilot1's EXISTING showcase person (contact/licence/medical + PersonClub prefs) instead of
  creating a new one + relinking. Seam: V30 seed (+ the showcase-seed harness if the person is created there).
  J-4 must not break J-3's green.
  **Done:** root cause confirmed — pilot1's flights-PIC person + 8-flights linkage come from the
  `ShowcaseSeeder` HARNESS (`@Profile("showcase")`, `seedPersonsAndLinks` → person `…7601…0601`,
  linked via `linkUserPerson(pilot1, …0601)` guarded on `person_id IS NULL`). V30 ran at Flyway time
  (before the harness) and grabbed pilot1's `person_id` with its own orphan person `…7300…0002`
  (`AND person_id IS NULL`), so the harness link no-op'd → last-flight card empty. Fix: **enriched the
  harness person** `…7601…0601` with the full self-edit field set (`ShowcaseSeeder.insertPilot1Person`
  + `insertPilot1PersonClub` + pilot1 `t_user.phone_number` UPDATE); **V30 reduced** to the no-Person
  `pilot-empty1` seed only (its orphan person/person_club/relink removed). So ONE person now backs both
  the J-3 last-flight card and the J-4 profile tabs. Self-edit values preserved per T-02 contract
  (`has_glider_pilot_licence=true`, `licence_number=CH-GLD-0001`, `medical_class2_expire_date=2027-09-30`,
  prefs flightReports=true / reservations=false / planning-reminder=true); pilot1 profile data now lives
  in the harness, not V30 (downstream T-09/T-11/T-13 read the same values). Validated via throwaway
  Postgres 16 + `flywayMigrate` + harness-SQL replay: pilot1 `person_id`→`…7601…0601`, person carries the
  fields, membership carries the prefs, `pilot-empty1` stays `person_id NULL`.

- [x] **T-15 — Early /profile AlpenFlight gallery (operator ask — stale gallery).** The operator's
  `proof-preview/integration-J-4/` gallery is stale + structurally profile-less: the clean-seed
  `alpenflight-proof` job (which feeds it) is scoped to the isolation spec only and never runs `/profile`.
  Wire the AlpenFlight `/profile` capture into the **showcase-seed `alpenflight-dashboard-proof` job**
  (non-blocking, `if: always()`/`!cancelled()`, deploys on red) so the operator gets a live 4-tab gallery
  NOW: (1) add resilient per-tab screenshot capture to `self-edit.spec.ts` (model J-3's `captureVariantShot`
  — shot the panel the moment it's visible, BEFORE deep asserts; PILOT pilot1); (2) add the profile spec to
  the job's `playwright test` invocation; (3) `add_shot` J-4 sidecar entries (4 tabs) + ensure J-4 in
  `_ORDER.md`. Seam: self-edit.spec.ts capture + ci.yml dashboard-proof job. (AlpenFlight-only here; the
  paired legacy↔AlpenFlight gallery is the done-bar T-12.)
  **Done:** (1) added `captureTabShot(page, testInfo, tab)` to `self-edit.spec.ts` (models J-3's
  `captureVariantShot` — clicks the tab, waits for `profile-panel-*` visible, then `fullPage` screenshots
  into `testInfo.outputDir` BEFORE any deep assertion) + a new first test `captures all four /profile tabs
  for the gallery (PILOT pilot1)` that loops the 4 tabs → writes stable names
  `alpenflight-profile-{account,personal,pilot,notifications}.png`. (2) ci.yml `alpenflight-dashboard-proof`
  job: the `id: pw` step (renamed `Run J-3 dashboard + J-4 profile display proof`) now runs BOTH
  `start-dashboard.spec.ts` AND `profile/self-edit.spec.ts` under `--project=real-idp`; the staging step
  (`if: always()`) parameterised `add_shot` with a leading journey arg + added 4 `J-4`/`alpenflight` sidecar
  entries (view = `<Tab> tab`, accurate captions: Account = self-edit fields functional, Personal/Pilot/
  Notifications = tab present+enabled, form lands in its vertical). (3) `_ORDER.md` already carries J-4
  (line 25, after J-3) + it's in the generator's ROADMAP_FALLBACK — no edit needed. Survives-red verified:
  staging + gallery-build are `if: always()`, deploy is gated on `steps.gallery.outcome=='success'` (not on
  the `pw` step outcome), so a red profile assertion still stages the 4 PNGs + deploys. Local checks:
  prettier/eslint clean on the spec, `tsc -p e2e/tsconfig.json` adds ZERO new errors (22 pre-existing in
  unrelated specs, present on base), ci.yml parses as valid YAML, and the real `extractScreenshots` generator
  accepts the emitted sidecar shape (2 shots, 0 errors; missing-PNG → AC5 error, which the `find … ||
  skipping` guard prevents). Full real-idp stack not run locally (Testcontainers unreliable on the LXC box) —
  CI showcase job is the proof. Gallery deploys to `proof-preview/integration-J-4/dashboard`.

- [x] **T-16 — Fix two arch-guard build failures (CI-surfaced on the T-15 run).** `./gradlew build` red on:
  (a) **`ControllerAuditCoverageTest`** — `MeProfileController.updateProfile` (T-04) is a mutating endpoint
  with no audit hookup; the guard requires every mutating controller method to reach `AuditTrail.record`
  (transitively) or be `@AuditedBy`. Fix: emit an audit event in `UsersService.updateOwnProfile`
  (`AuditTrail.record(UPDATE, AuditedTarget.of("User", id, before, after))`) — **this also establishes the
  audit pattern T-06/T-08/T-10 must each follow** (all self-edit endpoints are mutating → all need audit, not
  just licences). (b) **`NativeSqlRegisterTest`** — T-14's `ShowcaseSeeder.insertPilot1PersonClub` is a NEW
  native-SQL call site hitting tenant-scoped `t_person_club` not covered by the existing
  `alpenflight/database/native-sql-register.md` entry; register the new call site (or consolidate into the
  registered one). Seam: UsersService audit + native-sql-register. Verify locally (Testcontainers works now
  the disk is freed): `ControllerAuditCoverageTest` + `NativeSqlRegisterTest` green.

> **Audit requirement (carry into T-06/T-08/T-10):** every `/me/*` self-edit endpoint is mutating, so each
> must emit its own audit event (e.g. `user.profile_updated`, `person.contact_updated`, `person.licences_updated`,
> `personclub.notification_prefs_updated`) or the `ControllerAuditCoverageTest` guard reds the build.
>
> **Gate watch — not J-4 bugs:** (1) the **clean-seed `alpenflight-proof` red is the known J-1 aircraft flake**
> (`aircraft-migration-parity.spec.ts:228` 6≠3 + `:407` S-163 45s timeout — boyscout rider, pre-existing on
> main, J-4 touches no aircraft) — it reds `required`, a real merge-blocker → resolve before the §4 gate via
> the **proof-scoping rider** (per-push clean-seed proof scoped off the journey; full regression → nightly) OR
> the flake fix; operator decision. (2) The **dashboard-proof red is expected stub-tab redness** (Personal/
> Pilot/Notifications field testids don't exist until T-07/09/11) — gallery still deploys (`!cancelled()`).

**Riders folded:** orval explicit-`operationId` (T-04/06/08/10), e2e prettier/tsc on new specs (T-13).
**Not folded** (carve decision): gallery-collapse rider. **Proof-scoping rider now IN-PLAY** (the aircraft
flake reds J-4's `required` gate — fold it or the flake fix before §4; operator call).
