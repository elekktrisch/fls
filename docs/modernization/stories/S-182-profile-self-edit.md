---
id: S-182
title: Profile self-edit — /profile with Account / Personal / Pilot / Notifications tabs
epic: E-06
status: todo
depends_on: [S-052, S-051, S-178]
integration_base: integration/users-suite
acceptance:
  - **/profile route** (any authenticated principal with a `t_user` row). Opened from a new avatar / initials button in the nav bar that exposes a dropdown with "Profile" + "Sign out". Standard SaaS pattern.
  - **Tab structure.** Four tabs, each backed by its own endpoint:
    - **Account.** Edits the User aggregate's mutable fields: `friendlyName`, `notificationEmail`, `phoneNumber`, `languageId`, `remarks` is **NOT** included (admin-only free-text notes). `PATCH /api/v1/me/profile` accepts these fields; backend calls `User.updateProfile(...)`. Username + clubId + keycloakSub are read-only (identity-binding per S-052).
    - **Personal info.** Edits the Person aggregate's contact + address fields when `user.person_id` is set: `addressLine1/2`, `zip`, `city`, `region`, `countryId`, `privatePhone`, `mobilePhone`, `businessPhone`, `faxNumber`, `emailPrivate`, `emailBusiness`, `preferMailToBusinessMail`, `birthday`, `spotLink`, `enableAddress`. `PATCH /api/v1/me/person` calls `Person.updateContact(...)`. Name fields (firstname/lastname/midname/companyName) are read-only — rename remains admin-only per legacy semantics. With auto-Person fallback from S-178, every new join-request approval results in a `person_id` set on day 1; this tab is rarely empty.
    - **Pilot info.** Edits the Person aggregate's licence + medical fields: licence flags, `licenceNumber`, medical expiry dates, instructor expiry dates, start permissions. `PATCH /api/v1/me/person/licences` calls `Person.updateLicences(...)`. **Audit visibility:** every change emits a `person.licences_updated` audit event with full before/after diff (medical data is per-Swiss-FADP sensitive — clear provenance per S-027).
    - **Notifications.** Edits the caller-tenant `PersonClub`'s `notificationPrefs` sub-record only (memberNumber / memberStateId / role flags stay admin-only). `PATCH /api/v1/me/club-membership/notification-prefs` calls `PersonClub.updateNotificationPrefs(...)` (new mutator if needed; S-051 ships `applyMembership` covering the full shape — refine extracts the prefs-only path or co-opts).
  - **No-Person state.** When `user.person_id` is null (rare — sysadmins, club secretaries who aren't pilots), the Personal / Pilot / Notifications tabs render a banner: "Ask your club admin to link your member record. Some profile fields are unavailable until then." Account tab is unaffected.
  - **Authorisation.** Every endpoint is gated by `isAuthenticated()`; tenant context is the caller's; cross-principal edits are structurally impossible (each endpoint resolves the caller's User / Person from the JWT, never accepts an `:id` path parameter).
  - **Rate-limit on email changes.** Notification-email + Person email_private / email_business changes are not rate-limited at the domain layer but the existing audit log + a Bucket4j-equivalent 5-changes-per-hour on `/me/profile` + `/me/person` give a soft cap. Refine confirms.
  - **Tests.** Backend ITs cover happy path + tenant-isolation + null-`person_id` gating per endpoint. New Playwright spec `alpenflight/web/e2e/tests/profile/self-edit.spec.ts` covers: open from avatar; each tab round-trips; Personal tab disabled-banner case (test fixture creates a User without person_id); language change refreshes the SPA locale; licence-change emits an audit-visible event (admin can read the audit log row in a sysadmin test fixture).
estimate: L
adr_refs: [0007, 0008, 0022, 0023]
---

## Context

Q10 + Q14 grilling outcomes: profile self-edit is one fat slice with four tabs. All field categories are in scope: User mutables, Person contact + address, Person licence + medical, PersonClub notification prefs. Lives at `/profile` opened from a new avatar dropdown in the nav bar. Personal / Pilot / Notifications tabs gate on `user.person_id` being set; auto-Person at approval (S-178) means new pilots arrive with this already true.

Field-level column-level encryption for sensitive Person data (Vision §2 NFR) is **not** in scope here — that's a cross-cutting future story. Audit-event emission is on; that's the current ground-truth defense for medical data provenance.

## Cross-story contracts

- **Consumes:** S-052 User aggregate + `updateProfile`; S-051 Person aggregate + `updateContact` / `updateLicences` + PersonClub `applyMembership` (or a new prefs-only mutator); S-178 ensures `person_id` is set at approval time so the Personal/Pilot/Notifications tabs aren't perpetually disabled.
- **Produces:** First self-edit surface in the rewrite. The avatar / nav-bar dropdown pattern is a primitive future stories reuse (settings, sign-out, theme toggle).

## Open design questions (for refine)

- **PersonClub prefs-only mutator.** Today's `PersonClub.applyMembership` takes the whole shape (memberNumber + memberStateId + roles + prefs + active). Self-edit only touches prefs. Refine: (a) extract a `updateNotificationPrefs(prefs)` mutator; (b) read-then-write the full shape with admin-only fields preserved server-side. (a) is cleaner; (b) avoids API drift.
- **Audit-log surface for medical changes.** Acceptance 2 (Pilot info tab) calls for a `person.licences_updated` audit event with before/after diff. S-027's audit infrastructure shape is principal-friendly but PII-redaction policy must be extended for medical fields (which dates are emitted vs. hashed). Refine pins the policy. The audit blob layered with the medical-encryption-at-rest follow-up story is a known sequencing concern.
- **Email-change verification.** Today's `notificationEmail` change is silent. Should changing it require an email verification round-trip (KC's `VERIFY_EMAIL` action)? Defer — vision didn't pin this, and S-134's email-verification is signup-only. File as a follow-up.
- **Account closure.** Self-edit doesn't include "delete my account" today — only admins soft-delete users. Vision C5 (GDPR/FADP DSAR rights) demands it eventually; out of scope here, file as a dedicated DSAR story.
- **Avatar / display photo.** Not in scope. Vision C19 (whitelabel) covers club assets, not personal avatar. File as a future enhancement.
