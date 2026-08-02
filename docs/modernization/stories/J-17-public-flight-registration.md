---
id: J-17
title: Public flight-experience registration — discovery + scenic (/discovery-flight, /scenic-flight)
epic: E-12
status: in_progress
started_at: 2026-08-02
journey0: false
carved: true
depends_on: [J-1, J-4, J-5, J-11, J-12a, J-16]
rolls_up: [S-098, S-025, S-099]
acceptance:
  - "[happy] An anonymous visitor opens /discovery-flight/{clubSlug} for a public-registration-enabled club, sees the form with no nav bar and no /api/v1 prefetch, and picks one of the club's published discovery-flight days."
  - "[happy] Submitting the discovery form POSTs to the public endpoint with the club slug → success panel rendered in-place (no PII in the URL); a glider-trainee Person + PersonClub is created in that club and a double-seater-glider AircraftReservation is booked all-day on the chosen day at the club homebase; a confirmation email reaches Mailpit."
  - "[happy] An anonymous visitor opens /scenic-flight/{clubSlug}, submits → a non-trainee Person + PersonClub is created and NO reservation is booked; a confirmation email reaches Mailpit."
  - "[happy] With 'invoice address differs' set, a second invoice Person is created from the invoice fields (no trainee flag) and the confirmation email goes to NotificationEmail instead of PrivateEmail — for both flows."
  - "[happy] The club's operator email (t_club.send_*_registration_operator_email) receives the organiser notification carrying the reservation outcome; the admin sets that address and manages the discovery-flight days on the club-admin screen."
  - "[key-error] An unknown club slug → 404; a known slug whose club has public_registration_enabled=false → 403; neither creates a Person (asserted by absence)."
  - "[key-error] Repeated anonymous submissions from one source trip the abuse guard → 429 + Retry-After, and the throttled attempt creates no Person (legacy shipped its reCAPTCHA commented out and never validated it server-side — this endpoint is unauthenticated and writes rows, so the guard is new work, not parity)."
  - "[edge] Reservation-skip parity: a club with no double-seater glider, or no homebase, still SUCCEEDS the registration (Person created, no reservation) and the organiser email states why — asserted for at least the no-double-seater case."
  - "[edge] Each accepted submission writes an audit entry attributed to an anonymous actor scoped to the target club, and a club-admin sees it in /system/logs (J-13)."
  - "[edge] Mobile-first (AC-DIR-1..4): single-column at 360×640, ≥44×44px touch targets on <md, native input types; POST only, so no field value ever reaches the URL query string."
screen: /discovery-flight/:clubSlug, /scenic-flight/:clubSlug (+ discovery-day & operator-email panel on the existing club-admin edit screen)
headless_pulled_in: "Tenant-from-URL public-tenant resolution (S-025: slug → allowlist check → Tenants.runAs window + anonymous-actor audit) → both public forms; discovery-flight-day management → the existing club-admin edit screen; transactional email (J-11 EmailTemplate + J-12a dispatch) → the confirmation sent on submit"
migration: "Flow is greenfield (creates Person/PersonClub + a reservation; no legacy Flight is migrated — the roadmap's 'Flight subset' label was wrong). One real mapper delta: widen the CLUB producer SELECT + mapper to carry SendTrialFlightRegistrationOperatorEmailTo / SendPassengerFlightRegistrationOperatorEmailTo into the t_club columns that already exist unmapped (V2:187-188). Discovery-flight DAYS are deliberately NOT migrated — see Notes."
parity_test: "alpenflight/web/e2e/tests/public-registration/public-registration.spec.ts (new, mock inner-loop); alpenflight/web/e2e/tests/real-idp/public-registration-parity.spec.ts (new, anonymous against the deployed stack + Mailpit)"
adr_refs: [0008, 0013, 0022, 0027]
---

## Context

Two anonymous forms let a prospective customer book a discovery flight (legacy *Schnupperflug*, `flsweb/src/tryflight`) or a scenic flight (legacy *Mitflug*, `flsweb/src/passengerflight`) at a specific club without an account. They are the first genuinely **unauthenticated write endpoints** in AlpenFlight: every prior flow — including J-12a's `/join` — runs behind a Keycloak principal. So this journey is where the tenant-from-URL mechanism (ADR 0008 follow-up, S-025) gets built and proven: no principal means the target club comes from the URL, is validated against a public-registration allowlist, and everything downstream runs inside an explicit tenant window.

J-16 already shipped the route shells: `/discovery-flight` and `/scenic-flight` exist as `publicAccess: true` / `showNavBar: false` placeholder components with `publicStub.*` i18n keys. This journey replaces those placeholders with the real forms.

## Spec must assert

The green run drives an **anonymous** browser (no login, no Bearer) against a seeded club with `public_registration_enabled = true` and a `slug`, and proves:

1. **Tenant-from-URL (S-025).** The slug arrives as a path segment; resolution validates it against `Club.slug` (pattern `^[a-z0-9-]{3,64}$`, already on the aggregate) *and* `public_registration_enabled` before any controller body runs. Unknown slug → 404, disabled club → 403, and **neither writes a row** — assert the absence, not just the status code. Missing slug (`/discovery-flight` with no club) redirects to the landing page, matching legacy's `$location.path("/main")` (`TryFlightController.js:8-10`).

2. **Discovery flow — parity vs `flsserver/src/FLS.Server.Service/RegistrationService.cs:54-267`.** Creates a `Person` with the glider-trainee flag plus a `PersonClub` in the target club (`:109-131`); when `InvoiceAddressIsSame == false`, a second invoice `Person` + `PersonClub` **without** the trainee flag (`:133-150`). Then books an **all-day** `AircraftReservation` (`:189-199`) on the selected day at `Club.homebaseId`, pilot = the candidate, on a club-owned **2-seat glider**, carrying the club's configured discovery-flight `flightTypeId`.

3. **Reservation-skip parity — the three documented fallbacks (`:152-180`).** No club-owned double-seater glider, no homebase, or both: the registration still **succeeds** and the organiser email carries the reason. Legacy emits a distinct German sentence per case; the port owns equivalent copy. This is the AC that stops an implementer from making the reservation a hard precondition.

4. **Scenic flow — parity vs `RegistrationService.cs:269-411`.** Person + PersonClub with the trainee flag **false** (`:336-343`), optional invoice Person, and **no reservation, no day selection**. `PassengerFlightRegistrationDetails` is a strict subset of the trial DTO — it lacks only `SelectedDay`.

5. **Email recipients — the exact legacy branch (`:222-255`).** When the invoice address is the same, the confirmation goes to `PrivateEmail`; when it differs, to `NotificationEmail`; when neither is present, **no candidate mail is sent and the flow still succeeds**. Independently, the organiser mail goes to `Club.SendTrialFlightRegistrationOperatorEmailTo` / `…Passenger…` — a *separate* address from "the club admin", and legacy only warns when it is unset. Both templates land as J-11 `EmailTemplate` rows keyed `(clubId, templateKey, languageLocale)`; legacy keys are `TrialFlightRegistrationEmailForTrialPilot` / `NewTrialFlightRegistrationEmail` / `PassengerFlightRegistrationEmailForPassenger` and its passenger-organiser twin.

6. **Abuse guard.** Anonymous + writes rows = spam vector. `JoinRequestSubmitGuard` is the in-repo shape to copy, but it keys on the Keycloak sub, which does not exist here — so the window keys on client IP × club slug. 429 + `Retry-After`, no row written.

7. **Public security + audit.** Both POSTs and the available-days GET are `permitAll()` in `SecurityConfig.java` (currently only springdoc/actuator/error are listed). Every accepted submission writes a J-13 audit entry attributed to an anonymous actor, scoped to the resolved club.

8. **Mobile-first (AC-DIR-1..4 from the S-098/S-099 2026-05-15b amendment).** Single-column at 360×640, ≥44×44px targets on `<md`, native input types, POST-only so no field value reaches the URL.

## Notes

### Routes: use the shipped AlpenFlight names, not the legacy paths

The prior carve targeted `/trialflight` + `/passengerflight`. That is wrong for this codebase: **J-16 already shipped `/discovery-flight` and `/scenic-flight`** as registered public routes with placeholder components and `publicStub.discoveryFlight` = *Schnupperflug* / `publicStub.scenicFlight` = *Mitflug* i18n keys. Building at the legacy paths would orphan those shells and fork the vocabulary. Extend the shipped routes with a `:clubSlug` segment and replace the placeholder components. `real-idp/public-routes.spec.ts` carries a deliberately hardcoded `PUBLIC_ROUTES` list — update it in the same PR (its header comment states this is the intended friction).

### Where discovery-flight days live — the one genuinely open design hole

Legacy reads the bookable days from a per-club **settings row**: `SettingKey.TrialFlightEventDates` holds a JSON date array, and `TrialFlight.AircraftReservation.FlightTypeId` holds the reservation's flight type (`RegistrationService.cs:43,182-186`). **AlpenFlight has no settings table, no settings entity, and no `SETTING` EntityType in the migration bundle** — verified against `MapperLegacyBindings` and the schema. So this has to be decided, not inherited.

Carve decision: a small club-scoped `t_discovery_flight_day` table (club, date, optional flight type, soft delete) plus the club's discovery flight-type — **not** a generic settings bag, which would push configuration semantics into the DB against ADR 0022 directive 2. Managed from the **existing club-admin edit screen** (`features/clubs/edit/`), which also gains the two operator-email fields — that satisfies the headless-homing rule (admin screen, not a test-only affordance) without minting a new screen.

**The days are deliberately not migrated.** They are forward-dated event configuration whose past entries are worthless, and the legacy source is a JSON array inside a settings row — expanding that into rows needs `OPENJSON` in the producer SELECT against an MSSQL version we have not verified, on a path where only the real legacy export validates the SELECT ([[project_synth_bundle_doesnt_validate_producer_select]]). A club admin re-enters upcoming days once in the new panel. If the operator wants them migrated, that is a `[DISCOVERY-DAYS-MIGRATION]` rider, not a silent scope grab. The **two operator-email columns are a different story** and *are* in scope: the `t_club` columns already exist unmapped at `V2:187-188`, so this is a producer-SELECT + mapper widening, and without it the organiser never gets notified.

### Tenant establishment: `Tenants.runAs`, not an interceptor

Legacy fakes a principal — it looks up a club admin (falling back to any active club user) and calls `IdentityService.SetUser` purely to get a tenant + audit actor (`RegistrationService.cs:82-107`). Do not port that. AlpenFlight's equivalent already exists and is proven: `Tenants.runAs(clubId, …)`, the capability that closed S-023, used by `JoinRequestTxWriter` / `JoinRequestEmailListener` / `DeploymentContext`. The prior carve proposed a `PublicTenantInterceptor`; prefer the explicit `runAs` window in the application service, matching the join-requests module — an interceptor sets tenant state for the whole request including the failure paths, which is exactly where a 404/403 must **not** be tenant-scoped.

### Grouping: J-18 folded into J-17

The roadmap listed discovery (J-17) and scenic (J-18) separately. They are two variants of one feature, not independent features: identical registrant + invoice field sets, a strict DTO subset relationship, and identical service wiring except the trainee flag, the reservation, and the template keys. Per the skill's split rule and [[feedback_journey_min_one_screen_not_exactly_one]], fold — the S-025 spine gets built once and both thin forms ride it. S-099 is stamped `rolled_up_into: J-17`; `_ORDER.md` records the fold.

**Deferrable tail.** Scenic-flight is the explicit slack per the carve's unforeseen budget: if the gate surfaces heavy work (most likely in the reservation booking or the anonymous-security seam), `/do-ship` ships discovery-flight complete and re-files scenic as J-18 rather than shipping both half-done. Do not silently drop it — either it ships or it gets re-filed.

### No design reference

`design-reference/screens-public.jsx` contains only `Landing` (shipped as J-16) and `Login`. **There is no reference screen for either registration form.** Build to the legacy field list plus the AC-DIR-1..4 mobile-first directives and ADR 0024's visual conventions — there is no pixel oracle to match here, so do not go hunting for one.

### Seam hints (non-binding, for /do-ship)

- Public-tenant resolution + `permitAll()` additions + anonymous-actor audit — *(seam: platform/security SecurityConfig + a public-tenant resolver alongside the joinrequests pattern)*
- Registration application service, discovery branch (Person + PersonClub + reservation + 2 mails) — *(seam: registrations application service)*
- Registration application service, scenic branch (Person + PersonClub + 2 mails) — *(seam: same service, second entry point)*
- `DiscoveryFlightDay` aggregate + Flyway migration + club-scoped repository — *(seam: one new aggregate)*
- Discovery controller (`POST` + available-days `GET`) and scenic controller (`POST`), each with `@Operation(operationId=…)` so orval emits named methods — *(seam: two public controllers)*
- CLUB mapper widening for the two operator-email columns — *(seam: MapperLegacyBindings CLUB binding + Club aggregate fields)*
- Discovery-day + operator-email panel on the club-admin edit page — *(seam: web features/clubs/edit)*
- Two public form components sharing one registrant fieldset — *(seam: web features/public-registration)*
- Abuse guard keyed on IP × club — *(seam: a submit guard modeled on JoinRequestSubmitGuard)*

### Boyscout riders to fold (the ≤40% slot — `/do-ship` sizes them and clears the bullets)

- **[J-15-MAILPIT-REPORT]** — this journey is the next one to touch the mail path *and* it already needs a Mailpit assertion for its own confirmation emails, so the deferred `waitForMessageWithSubject` proof lands naturally alongside.
- **[DEV-UP-FAIL-LOUD]** — `dev-up-full.sh` reports success while bringing up nothing (the compose CLI plugin is absent on this box), which is what currently blocks local real-idp runs ([[project_real_idp_runs_locally]]). This journey's gate is anonymous-against-the-deployed-stack, so a loud dev-up pays for itself immediately.
- **[GHA-TERNARY-AUDIT]** and **[CI-TROUBLESHOOTING-MARKER]** — both filed by the J-15/J-16/J-30 retro; this journey touches CI with two new spec projects.
- **Form-validation-parity P4** (debounced as-you-type pre-checks) applies directly to these many-field forms; server-on-submit stays the safety net.
- **[SUITE-ISOLATION]** — non-migration spec, so it seeds its own club and data.
- Per-touch: **[COMMENT-STRIP]** on files actually edited, production-code seeding in new ITs (ADR 0027 §3), named `operationId`s on the new endpoints.

### Assumptions made

1. Routes are `/discovery-flight/:clubSlug` and `/scenic-flight/:clubSlug`, extending J-16's shipped shells rather than re-creating the legacy paths.
2. Discovery-flight days become a small club-scoped aggregate managed on the club-admin screen, and are **not** migrated from the legacy settings JSON (recorded above as a reversible decision, not a silent exclusion).
3. Tenant is established with an explicit `Tenants.runAs` window in the application service, not a request interceptor.
4. Discovery registration auto-books the reservation for legacy parity, with the three legacy skip-fallbacks preserved as success paths.
5. The abuse guard is new work with no legacy counterpart (legacy's reCAPTCHA is commented out in `tryflight.html:258-264` and the server never reads `RecaptchaResponse`); an unauthenticated row-writing endpoint should not ship without one.
6. Exact email copy and the organiser-mail reservation sentences are pinned at ship time via `legacy-oracle` on `RegistrationService.cs` + `RegistrationEmailBuildService.cs`; the carve fixes recipients and branch conditions, which is what parity turns on.
