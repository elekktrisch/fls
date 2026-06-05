---
id: J-4
title: Profile self-edit (/profile — Account / Personal / Pilot / Notifications)
epic: E-06
status: todo
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

**Migration: none (no new mapper).** Person/User/PersonClub already migrate (prior identity
journeys + `persons.application.PersonMapper`). J-4 is self-edit UI + caller-scoped PATCH
endpoints on existing aggregates → **no fanout run required**; the done-bar is the
AlpenFlight pass video + the 4-tab screenshots + the "migrated/showcase Person renders its
real values and a round-trip edit sticks" assertion. (If ship-time finds a self-edit field
the existing Person mapper doesn't carry, that's a small mapper touch, not a new entity —
note it then.)

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
extension; the spec thicken. Riders to fold (from `_BOYSCOUT`): orval positional-`getN`
naming (J-4 adds 4 `/me/*` endpoints → regenerates the client — good place to set explicit
operationIds), and the e2e prettier/tsc normalization on the new spec.

## Assumptions made

- **Route is `/profile`** opened from a **nav-bar avatar/initials dropdown** (S-182 AC1).
- **No fanout** — J-4 carries no net-new mapper (existing identity mappers cover Person/User/
  PersonClub); proof is AlpenFlight-only. If a self-edit field is unmigrated, ship adds it to
  the existing mapper (still no fanout-gating new entity).
- **PersonClub prefs mutator:** assume a new `updateNotificationPrefs(prefs)` mutator (S-182
  open-q option (a) — cleaner than read-then-write of the whole membership shape); refine/ship
  confirms.
- **Password / email-verification / account-closure / avatar** are out of scope (S-182 defers).
