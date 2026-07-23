---
id: J-17
title: Public flight-experience registration — trial + passenger (/trialflight, /passengerflight)
epic: E-12
status: todo
journey0: false
carved: true
depends_on: [J-1, J-4, J-5, J-12a, J-16]
rolls_up: [S-098, S-025, S-099]
acceptance:
  - "[happy] An anonymous visitor opens /trialflight for a public-registration-enabled club (slug in the URL), sees the form with no nav bar, single-column at 360×640, and picks one of the club's available days."
  - "[happy] Submitting the trial form POSTs to /api/v1/trialflightsregistrations with the club slug → success page (no PII echoed in the URL); a glider-trainee Person is created and a double-seater-glider AircraftReservation is booked for the chosen day; a confirmation email reaches Mailpit (registrant + club admin)."
  - "[happy] An anonymous visitor opens /passengerflight for the same club, submits → POST /api/v1/passengerflightsregistrations → a non-trainee Person is created (NO reservation); a confirmation email reaches Mailpit."
  - "[happy] Optional invoice person: when 'invoice address differs' is set, a second (invoice) Person is created from the invoice fields for both flows."
  - "[key-error] An unknown club slug → 404; a known slug whose club has publicRegistrationEnabled=false → 403 (tenant-from-URL allowlist, S-025)."
  - "[key-error] A public submission writes an audit-log entry 'public submission for club X by anonymous actor' (no principal)."
  - "[edge] Mobile-first directive: form is single-column with ≥44×44px touch targets on <md, native input types (date/tel/numeric); values never appear in the URL query string (POST only)."
screen: /trialflight, /passengerflight
headless_pulled_in: "Tenant-from-URL public-tenant resolution (S-025 PublicTenantInterceptor + public-registration allowlist + anonymous-actor audit) → both public forms; email dispatch (existing J-12a infra) → confirmation on submit"
migration: "N/A — greenfield public flow. Registrations CREATE new Person (+ optional invoice Person) and, for trial, an AircraftReservation; no Flight record is created and no legacy entity is migrated. (Corrects the roadmap's stale 'Flight subset' label — verified: legacy RegistrationService creates Person + reservation, not Flight.)"
parity_test: "alpenflight/web/e2e/tests/public-registration/public-registration.spec.ts (new, mock inner-loop); alpenflight/web/e2e/tests/real-idp/public-registration-parity.spec.ts (new, anonymous against the deployed stack + Mailpit)"
adr_refs: [0008, 0013]
---

## Context
Two anonymous public forms let a prospective customer sign up for a trial glider flight (`/trialflight`) or a passenger flight (`/passengerflight`) at a specific club, without an account. They replace legacy `flsweb/src/tryflight` + `flsweb/src/passengerflight`. This is the first real exercise of the **tenant-from-URL** mechanism (ADR 0008 follow-up S-025): a public flow has no authenticated principal, so the target club comes from the URL slug and is validated against a public-registration allowlist before anything runs. It builds directly on J-16's public landing (the `showNavBar:false` / `publicAccess:true` route pattern already exists).

## Spec must assert
The single green run drives an **anonymous** browser (no login) against a seeded club with `publicRegistrationEnabled=true` + a `slug`, and proves:

1. **Tenant-from-URL (S-025).** `/trialflight` and `/passengerflight` carry the club slug (path or param). A `PublicTenantInterceptor` resolves `Club.slug` → tenant context BEFORE the controller runs. Reject: unknown slug → 404; `publicRegistrationEnabled=false` → 403 (ground vs `Club.slug` pattern `^[a-z0-9-]{3,64}$` + the `ux_club_slug` partial-UNIQUE, both already in V5). Each accepted submission writes an anonymous-actor audit entry.
2. **Trial flow (parity vs `flsserver/.../RegistrationService.cs:54-267`).** `GET /api/v1/trialflightsregistrations/availabledates/{slug}` returns the club's bookable days → the form's day radios. `POST /api/v1/trialflightsregistrations` creates a Person (`glider-trainee` flags true) + (optional) invoice Person + a **double-seater-glider AircraftReservation** for `SelectedDay` (reuses J-5 reservations) + sends the trial confirmation email (registrant-or-invoice-recipient AND club admin).
3. **Passenger flow (parity vs `RegistrationService.cs:269-411`).** `POST /api/v1/passengerflightsregistrations` creates a Person (trainee flags false) + (optional) invoice Person; **no reservation**; sends the passenger confirmation email. The passenger DTO is a strict subset of the trial DTO (no `SelectedDay`).
4. **Shared registrant contract (100% common fields).** Firstname, Lastname, AddressLine1, ZipCode, City, CountryId, PrivateEmail, Mobile/Private/Business phone, InvoiceAddressIsSame + invoice-person fields, NotificationEmail, SendCouponToInvoiceAddress, Remarks. Field-level validation as-you-type (mobile-first AC-DIR), server-on-submit is the safety net.
5. **Public security.** Both POSTs + the availabledates GET are in the Spring Security `permitAll()` allowlist (`SecurityConfig.java` — J-16 landing pattern); no Bearer required.
6. **Mobile-first (AC-DIR-1..4).** Single-column at 360×640; ≥44×44px targets on `<md`; native input types; no PII in the URL (POST only); submit under 200ms-RTT+loss either succeeds or shows a retry message (no >3s spinner-lock).

## Notes

### Grouping decision — folded J-17 + J-18 into ONE journey (pick + record)
The roadmap listed trial (J-17) and passenger (J-18) as separate journeys. Carve-time analysis (legacy + rewrite) shows they are **not genuinely independent features** — they're two variants of one public-registration feature: identical registrant + invoice fields, `PassengerFlightRegistrationDetails ⊂ TrialFlightRegistrationDetails` (differs only by `SelectedDay`), identical service wiring except trial reserves a glider + sets a trainee flag + uses trial email templates. The skill's split rule ("split only when the screens are genuinely independent features") + the operator's anti-over-split preference ([[feedback_journey_min_one_screen_not_exactly_one]]) make **fold** the default; the shared **S-025 public-tenant spine is built once** and both thin forms ride it. **J-18 is retired into J-17** (see `_ORDER.md` "Folded at carve time"); S-099 stamped `rolled_up_into: J-17`. **Operator escape hatch:** if you'd rather ship trial first and defer passenger, split S-099 back out to a follow-up J-18 that reuses this journey's spine — but the default green run proves both `/trialflight` and `/passengerflight`.

### Migration is N/A (roadmap label corrected)
The roadmap's `migration: Flight (trial/pax subset)` is wrong — these are going-forward public flows that CREATE Person + (trial) AircraftReservation, not migrations of legacy Flight rows. No new mapper. The created entity TYPES (Person J-4, AircraftReservation J-5) already exist. Per [SUITE-ISOLATION], this non-migration spec sets up its OWN data (a public-registration-enabled club with a slug), not the migration seed.

### No design reference (off-reference — build to the AC directives)
`docs/modernization/design-reference/screens-public.jsx` contains only `Landing` (J-16) + `Login` — **no trial/passenger registration screen design exists**. Build to the legacy field list + the mobile-first AC-DIR-1..4 directives (from S-098/S-099 amendment 2026-05-15b), NOT a pixel oracle. Use the existing public-route layout mechanism (`data: { showNavBar: false, publicAccess: true }`).

### Seam hints (non-binding, for /do-ship)
- **S-025 public-tenant** — `PublicTenantInterceptor` (resolve+validate slug → tenant), the `permitAll()` additions in `SecurityConfig.java`, and the anonymous-actor audit write. *(seam: platform/security PublicTenantInterceptor + SecurityConfig)*
- **Registration application service** — one service with a trial branch (create Person + reservation + trial email) and a passenger branch (create Person + passenger email); shared Person/invoice-person creation + email dispatch. *(seam: a registrations application service reusing Person J-4 + reservation J-5 + mail J-12a)*
- **Trial controller + DTO** — `POST /api/v1/trialflightsregistrations` + `GET …/availabledates/{slug}`. *(seam: TrialFlightRegistrationsController)*
- **Passenger controller + DTO** — `POST /api/v1/passengerflightsregistrations`. *(seam: PassengerFlightRegistrationsController)*
- **Trial form** (public layout, mobile-first, day-radio picker) + **Passenger form** (same minus day) — one `features/public-registration/` folder, two routes. *(seam: web features/public-registration)*
- **Email templates** — port trial + passenger confirmation templates (registrant + organiser) from legacy `Alpinely.TownCrier` into `templates/email/` (J-12a Thymeleaf pattern). *(seam: templates/email/ trial + passenger)*

### Boyscout riders to fold (the ≤40% slot — /do-ship sizes + clears from `_BOYSCOUT.md` on ship)
- Form-validation-parity-audit **P4** as-you-type pre-checks apply to these many-field public forms (debounced client validation; server-on-submit stays the safety step).
- **[WORKFLOW-SLIM]** CI YAML → composites; **[MAINTAINABILITY-TOOLING]** commit the real Qodana baseline (J-17 touches CI with new specs); **[SUITE-ISOLATION]** the spec sets up its own club/data (non-migration).
- **[HISTORY→GIT]** this file is already contract-only; **[COMMENT-STRIP]** per-touch only if touched; new endpoints get `@Operation(operationId=…)` so orval emits named methods (J-3 orval rider); new ITs use production-code seeding (ADR 0027 §3).

### Assumptions made
1. **Folded trial+passenger** into one journey (see decision above) — grouping picked + recorded, not asked, per the skill's "grouping: pick + record" + "split only when genuinely independent."
2. **Trial auto-books the glider reservation** for legacy parity (`RegistrationService` reserves a double-seater for `SelectedDay`). If the gate shows the reservation wiring is heavy, the fallback is create-Person + notify-admin-to-book — but default is parity (auto-reserve).
3. **Slug in the URL** (path segment or `?club=` param) resolves the tenant; the rewrite uses `Club.slug` (e.g. `lszr`), not the legacy `ClubKey` string — the slug column + allowlist already exist (V5).
4. The real-idp/full-chain gate runs these routes **anonymously** (no login) against the deployed stack + Mailpit — public flows carry no Bearer.
5. Email-confirmation recipients (registrant-or-invoice-recipient + club admin) + the exact trial vs passenger template copy are pinned at ship time via `legacy-oracle` on `RegistrationService.cs` (carve captures the shape).
