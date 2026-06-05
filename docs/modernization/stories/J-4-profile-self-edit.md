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

- [x] **T-01 — Spec stub.** Author `alpenflight/web/e2e/tests/profile/self-edit.spec.ts`: nav
  avatar dropdown → `/profile`, 4 tabs present (Account/Personal/Pilot/Notifications), selectors
  for each tab's key fields, thin assertions (tabs render). Drive as PILOT. Commits the screen shape.
- [ ] **T-02 — Showcase-seed self-edit principal.** Extend the showcase seed: a self-editable
  PILOT principal (Person w/ full contact + licence/medical + PersonClub notif prefs) so tabs
  render populated, AND a separate no-Person user (`person_id` null) for the banner edge. Reuse
  existing realm users where possible (avoid net-new Keycloak realm churn). Seam: seed migration(s).
- [ ] **T-03 — `/profile` shell + tab routing + nav user-summary + no-Person gating.** New
  `features/profile/` route + shell page with 4 tab segments (tab bodies stubbed), wire the
  existing nav avatar dropdown to the session user summary, verify Sign out, render the no-Person
  banner + disable Personal/Pilot/Notifications when person unlinked (Account stays live). First
  gallery capture lands here. Seam: the profile feature shell/route.
- [ ] **T-04 — Account endpoint `PATCH /api/v1/me/profile`.** Caller-scoped (JWT→User), self-fields
  only (friendlyName, notificationEmail, phoneNumber, languageId); username/clubId/keycloakSub
  immutable; reuse `User.updateProfile`; explicit `operationId` (folds orval rider). IT incl.
  cross-principal isolation. Seam: User me-profile endpoint cluster.
- [ ] **T-05 — Account tab.** Account form + signal store + `PATCH /me/profile` via regenerated
  orval client; language change refreshes the SPA locale; username/clubId read-only. Seam: Account tab component.
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

**Riders folded:** orval explicit-`operationId` (T-04/06/08/10), e2e prettier/tsc on new specs (T-13).
**Not folded** (carve decision): gallery-collapse + proof-scoping CI riders (infra-heavy, off this surface).
