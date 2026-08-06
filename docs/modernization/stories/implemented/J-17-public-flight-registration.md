---
id: J-17
title: Public flight-experience registration — discovery + scenic (/discovery-flight, /scenic-flight)
epic: E-12
status: done
started_at: 2026-08-02
done_at: 2026-08-06
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
parity_test: alpenflight/web/e2e/tests/real-idp/public-registration-parity.spec.ts (anonymous against the deployed stack + Mailpit); alpenflight/web/e2e/tests/public-registration/public-registration.spec.ts (mock inner-loop)
mock_test: alpenflight/web/e2e/tests/public-registration/
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

5. **Email recipients — the exact legacy branch (`:222-255`).** When the invoice address is the same, the confirmation goes to `PrivateEmail`; when it differs, to `NotificationEmail`; when neither is present, **no candidate mail is sent and the flow still succeeds**. Independently, the organiser mail goes to `Club.SendTrialFlightRegistrationOperatorEmailTo` / `…Passenger…` — a *separate* address from "the club admin", and legacy only warns when it is unset. Both templates land as J-11 `EmailTemplate` rows keyed `(clubId, templateKey, languageLocale)`.

6. **Abuse guard.** Anonymous + writes rows = spam vector. `JoinRequestSubmitGuard` is the in-repo shape to copy, but it keys on the Keycloak sub, which does not exist here — so the window keys on client IP × club slug. 429 + `Retry-After`, no row written.

7. **Public security + audit.** Both POSTs and the available-days GET are `permitAll()` in `SecurityConfig.java`. Every accepted submission writes a J-13 audit entry attributed to an anonymous actor, scoped to the resolved club.

8. **Mobile-first (AC-DIR-1..4 from the S-098/S-099 2026-05-15b amendment).** Single-column at 360×640, ≥44×44px targets on `<md`, native input types, POST-only so no field value reaches the URL.

## Tasks

- [x] **T-01** — Author `public-registration.spec.ts` structure, selectors and flow with thin assertions; scaffold the J-17 proof-gallery page + `proofVideo` journey tagging. *(standing slot)*
- [x] **T-02** — Scope the per-push gate: only J-17's own specs run real-idp; prior journeys drop to mock-IdP (full regression stays nightly + the §4 gate). *(standing slot)*
- [x] **T-03** — **[DEV-UP-FAIL-LOUD]** Fix Mailpit readiness in the fan-out bring-up + make every `dev-up-*.sh` step fail loudly instead of printing "Dev stack ready" over a failed compose. *(rider)*
- [x] **T-04** — **[GHA-TERNARY-AUDIT]** Grep the workflows for the `${{ cond && '' || x }}` empty-string-falsy trap; move possibly-empty values to the `||` side. *(rider)*
- [x] **T-05** — Map `send_trial_flight_registration_operator_email` / `send_passenger_flight_registration_operator_email` + the club discovery flight-type onto the `Club` aggregate; expose in `ClubDtos` + `ClubsController` with validation. *(seam: Club aggregate)*
- [x] **T-06** — `DiscoveryFlightDay` aggregate + `V58` migration + club-scoped repository (event date + soft delete; **no per-day flight type** — `Club.discoveryFlightTypeId` is the single source). *(seam: one new aggregate)*
- [x] **T-07** — Public club resolution: slug → club with `public_registration_enabled` check, `permitAll()` matchers in `SecurityConfig`, and the 404 / 403 error contract (neither writes a row). *(seam: platform/security + resolver)*
- [x] **T-07b** — Anonymous audit attribution: establish which field `/system/logs` actually renders and make an anonymous registration entry read as anonymous there. *(seam: MutationAuditEventListener actor_kind + the /system/logs projection)*
- [x] **T-08** — Anonymous abuse guard keyed on client IP × club slug + 429 with `Retry-After`. *(seam: submit guard + exception handler)*
- [x] **T-09** — Registration application service, shared registrant write: `Person.register` + `updateContact` + `joinClub` with `PersonRoleFlags.gliderTrainee()`, plus the optional invoice Person, inside a `Tenants.runAs` window. *(seam: registrations application service)*
- [x] **T-10** — Discovery reservation booking via the aggregate factory + repository (bypassing the member-booking exclusivity probe — see Notes), incl. the three reservation-skip cases and their organiser-email reasons. *(seam: reservation booking in the registration service)*
- [x] **T-11** — Registration application service, scenic branch: Person + PersonClub without the trainee flag, no reservation, no day selection. *(seam: same service, second entry point)*
- [x] **T-12** — Four email templates + dispatch inside `Tenants.runAs` (tenant-scoped override resolution): discovery candidate/organiser, scenic candidate/organiser. Binds the correct model for the scenic pair — legacy's interpolate a `Trial*` namespace and render blank. *(seam: templates/email + TemplatedMailService calls)*
- [x] **T-13a** — Discovery public endpoints: `POST` registration + available-days `GET`, request/response DTOs, `@Operation(operationId=…)`, `201` + `Location`. `selectedDay` is validated against the club's published `DiscoveryFlightDay` rows, and the request carries `sendCouponToInvoiceAddress`. *(seam: discovery controller + DTOs)*
- [x] **T-13b** — Scenic public endpoint: `POST` registration + DTO reusing T-13a's shared registrant payload, `@Operation(operationId=…)`, `201` + `Location`. No day selection. *(seam: scenic controller)*
- [x] **T-14** — CLUB producer-SELECT + mapper widening for the two operator-email columns + a real-producer round-trip IT. *(seam: MapperLegacyBindings CLUB binding)*
- [x] **T-15a** — Admin discovery-day CRUD endpoints (club-admin scoped: list/add/withdraw a club's `DiscoveryFlightDay`), `@Operation(operationId=…)`. *(seam: discovery-day admin controller)*
- [x] **T-15b** — Club-admin edit page: operator-email fields + the discovery-day management panel. *(seam: web features/clubs/edit)*
- [x] **T-14b** — Fanout red (pre-existing, exposed not caused by T-14): the fanout's hardcoded real-idp spec list omits `e2e/tests/real-idp/public-registration-parity.spec.ts`, the only spec carrying a J-17 `proof-journey` video, so every fanout run deploys a 0-asset gallery bookmark. Add the spec to the list; also fold the **[PROOF-HARNESS TRANSIENTS]** rider (the same poll-vs-propagation race, which can also produce a false green). *(seam: fanout spec list + the link-check poll budget)*
- [x] **T-13c** — Batch-boundary red: `AnonymousActorProjectionIT.submitAnonymously` still POSTs the bodyless `202` scaffolding contract that T-13a/T-13b replaced with `consumes=APPLICATION_JSON` + `201`. Green on main, red on branch = journey-caused. *(seam: AnonymousActorProjectionIT)*
- [x] **T-15c** — Club-admin own-club read: the edit page sources its club from `listClubs`, which is sysadmin/flight-operator only, so a pure `CLUB_ADMINISTRATOR` following the join-requests link lands on `/clubs/:id/edit` with a 403 and an empty form — leaving the `[happy]` AC unmeetable for the role it names. Give a club admin a read of its OWN club. *(seam: club read authorisation + the edit page's club source)*
- [x] **T-15d** — Chrome-reachability for the club-admin screen: there is no nav entry to club-edit for a `CLUB_ADMINISTRATOR`, so the screen is effectively URL-only for the role the `[happy]` AC names. Place the nav entry with role visibility + have the proof spec ENTER through it. *(seam: nav-bar sections + role visibility)*
- [x] **T-16** — Shared public-form shell + the registrant fieldset shared by both forms (no shared public layout exists today; each stub rolls its own chrome). *(seam: web features/public-registration shared pieces)*
- [x] **T-17** — `/discovery-flight/:clubSlug` page + store: day picker, conditional invoice block, success panel, missing-slug redirect. *(seam: one component-route + store)*
- [x] **T-17b** — **[PUBLIC-CLUB-NAME]**: no anonymous read of a club's *name* exists, so both public forms head with the raw URL slug — a slug where a club name belongs reads as unfinished on the product's public-facing screen. Add a minimal anonymous club-name read and wire both headings. *(seam: public club read + the shell heading)*
- [x] **T-18** — `/scenic-flight/:clubSlug` page + store (same shell, no day picker). *(seam: one component-route + store)*
- [x] **T-19** — Add both routes to the hardcoded `PUBLIC_ROUTES` list + the mobile-first assertions (360×640 single column, ≥44×44px targets, no PII in URL). **Owns the `no /api/v1 prefetch` half of AC-1**, which is provable only in real-idp — the mock harness prefetches on every route regardless of session, so the assertion is dropped there rather than faked. *(seam: public-routes spec + viewport assertions)*
- [x] **T-22c** — Gate-revealed bug: **every real browser submit 400s.** Jackson 3.1.2 defaults `FAIL_ON_NULL_FOR_PRIMITIVES=true`, so the absent creator property for the **primitive** `sendCouponToInvoiceAddress` — which the form deliberately omits when the invoice address is the same — arrives null and fails deserialization. The server ITs stayed green only because the test helper always writes the key. Fix the DTO to tolerate the omission (not a global Jackson relaxation, which would mask this class everywhere) **and** make the IT helper exercise the omitted-key case so the blindness cannot recur. *(seam: `PublicRegistrationDtos` + `PublicSubmissions` test helper)*
- [x] **T-20** — **[J-15-MAILPIT-REPORT]** Close the deferred jobs-console Mailpit assertion, riding this journey's Mailpit work. *(rider)*
- [x] **T-21** — **[CI-TROUBLESHOOTING-MARKER]** Fail-closed `.ci-troubleshooting` marker: `ci.yml` skips the heavy lane and `required` hard-fails while it exists. *(rider)*
- [x] **T-22a** — Happy-path seed + the two happy flows in `real-idp/public-registration-parity.spec.ts`: discovery (Person + PersonClub with BOTH trainee markers + all-day reservation at the homebase on the chosen day + confirmation in Mailpit) and scenic (Person + PersonClub, trainee false, NO reservation, confirmation in Mailpit). Seeded spec-local, so no other journey's gate inherits a surprise registration or mail. Captures the `proofVideo` the gallery renders. *(seam: the parity spec's happy-path seed + its two happy cases)*
- [x] **T-22a0** — Club homebase write path: `homebaseId` on the `Club` aggregate + `ClubUpdateRequest` / `ClubResponse`, validated against the club's own `Location` rows, plus a homebase picker on the club-admin edit screen. Product gap in its own right — no screen sets the value `/me` already projects and the discovery reservation reads. *(seam: Club aggregate + club-admin edit screen)*
- [x] **T-22b** — Error + edge assertions in the same spec: 404/403 write no Person (assert by absence), 429 + `Retry-After` writes no Person, invoice-differs creates the second Person and mails `NotificationEmail`, the club operator email receives the organiser notification carrying the reservation outcome, reservation-skip (no double-seater) still SUCCEEDS with the reason stated, and the anonymous audit entry is visible to a club-admin in `/system/logs`. *(seam: same spec, error + edge cases)*
- [x] **T-23** — `gap-hunter` security hardening; both guards under-deliver on their own documented promise. **(a)** `PublicRegistrationSubmitGuard` justifies its cap with *"without it, slug enumeration is free"*, but the public club read answers the identical 200/403/404 oracle over the same keyspace **unthrottled**, and its stated "limit at the edge" fallback does not exist. Give the anonymous reads their own budget, generous enough not to lock out shared-NAT visitors. **(b)** `ReservationExclusivityBypassAllowlistTest` matches ArchUnit `callMethod` on the *declared* owner, so a `create` overload, the public JPA repository or an `EntityManager.persist` mints an unprobed reservation with the build green. Widen it and prove the widening reds. *(seam: the two guards + their tests)*
- [x] **T-24** — `gap-hunter` coverage gap + cleanup. **(a)** The coupon radios carry exported testids that no browser spec ever selects — precisely the seam that produced T-22c's "every real submit 400s" bug — so drive them through the browser. **(b)** Remove the `publicStub.body` / `.discoveryFlight` / `.scenicFlight` keys, orphaned in all four locales now the placeholders are gone. *(seam: public-registration spec + i18n)*

## Notes

### Routes: use the shipped AlpenFlight names, not the legacy paths

The prior carve targeted `/trialflight` + `/passengerflight`. That is wrong for this codebase: **J-16 already shipped `/discovery-flight` and `/scenic-flight`** as registered public routes with placeholder components and `publicStub.discoveryFlight` = *Schnupperflug* / `publicStub.scenicFlight` = *Mitflug* i18n keys. Building at the legacy paths would orphan those shells and fork the vocabulary. Extend the shipped routes with a `:clubSlug` segment and replace the placeholder components. `real-idp/public-routes.spec.ts` carries a deliberately hardcoded `PUBLIC_ROUTES` list — update it in the same PR (its header comment states this is the intended friction).

### Where discovery-flight days live — the one genuinely open design hole

Legacy reads the bookable days from a per-club **settings row**: `SettingKey.TrialFlightEventDates` holds a JSON date array, and `TrialFlight.AircraftReservation.FlightTypeId` holds the reservation's flight type (`RegistrationService.cs:43,182-186`). **AlpenFlight has no settings table, no settings entity, and no `SETTING` EntityType in the migration bundle** — verified against `MapperLegacyBindings` and the schema. So this had to be decided, not inherited.

Carve decision: a small club-scoped `t_discovery_flight_day` table (club, event `DATE`, soft delete) plus the club's discovery flight-type — **not** a generic settings bag, which would push configuration semantics into the DB against ADR 0022 directive 2. The flight type stays club-level only (`Club.discoveryFlightTypeId`): legacy had one value for all days, so a per-day column would be a second source for the same setting and a precedence rule no AC exercises. Managed from the **existing club-admin edit screen** (`features/clubs/edit/`), which also gains the two operator-email fields — that satisfies the headless-homing rule (admin screen, not a test-only affordance) without minting a new screen.

**The days are deliberately not migrated.** They are forward-dated event configuration whose past entries are worthless, and the legacy source is a JSON array inside a settings row — expanding that into rows needs `OPENJSON` in the producer SELECT against an MSSQL version we have not verified, on a path where only the real legacy export validates the SELECT ([[project_synth_bundle_doesnt_validate_producer_select]]). A club admin re-enters upcoming days once in the new panel. If the operator wants them migrated, that is a `[DISCOVERY-DAYS-MIGRATION]` rider, not a silent scope grab. The **two operator-email columns are a different story** and *are* in scope: the `t_club` columns already exist unmapped at `V2:187-188`, so this is a producer-SELECT + mapper widening, and without it the organiser never gets notified.

### Tenant establishment: `Tenants.runAs`, not an interceptor

Legacy fakes a principal — it looks up a club admin (falling back to any active club user) and calls `IdentityService.SetUser` purely to get a tenant + audit actor (`RegistrationService.cs:82-107`). Do not port that. AlpenFlight's equivalent already exists and is proven: `Tenants.runAs(clubId, …)`, the capability that closed S-023, used by `JoinRequestTxWriter` / `JoinRequestEmailListener` / `DeploymentContext`. The prior carve proposed a `PublicTenantInterceptor`; prefer the explicit `runAs` window in the application service, matching the join-requests module — an interceptor sets tenant state for the whole request including the failure paths, which is exactly where a 404/403 must **not** be tenant-scoped.

### Grouping: J-18 folded into J-17

The roadmap listed discovery (J-17) and scenic (J-18) separately. They are two variants of one feature, not independent features: identical registrant + invoice field sets, a strict DTO subset relationship, and identical service wiring except the trainee flag, the reservation, and the template keys. Per the skill's split rule and [[feedback_journey_min_one_screen_not_exactly_one]], fold — the S-025 spine gets built once and both thin forms ride it. S-099 is stamped `rolled_up_into: J-17`; `_ORDER.md` records the fold.

### No design reference

`design-reference/screens-public.jsx` contains only `Landing` (shipped as J-16) and `Login`. **There is no reference screen for either registration form.** Build to the legacy field list plus the AC-DIR-1..4 mobile-first directives and ADR 0024's visual conventions — there is no pixel oracle to match here, so do not go hunting for one.

### Reservation overlap — the ship-time constraint the carve missed

`AircraftReservationsService.createReservation` runs a **mandatory** GiST-backed conflict probe and throws 409 (`AircraftReservationsService.java:87`). Legacy books **one all-day reservation per candidate** on the same glider, so a discovery day with five candidates produces five deliberately-overlapping reservations — routed through the member-booking service, candidate #2 onward would be rejected and the registration would fail.

Decision: discovery reservations are created through the aggregate factory + repository **inside the registration service**, bypassing the member-booking service's exclusivity probe. The probe exists for member self-service booking, where double-booking an aircraft is an error; an organiser block-booking a trainee slot is not that. Legacy semantics are preserved exactly (one reservation per candidate, pilot = the candidate). This is a deliberate, narrow bypass — `gap-hunter` should confirm it did not leak into any member-facing path.

AlpenFlight's all-day representation is the half-open `[date 00:00, +1 day)` span, already chosen in J-5 over legacy's `AddTicks(-1)` artifact; equivalent, no action.

### Oracle-pinned parity anchors (`legacy-oracle`, ship time 2026-08-02)

Fixed values to reproduce exactly: reservation `End = SelectedDay.Date.AddDays(1).AddTicks(-1)`, `IsAllDayReservation = true`, `Remarks = "Schnupperflug-Kandidat"` (literal). Aircraft predicate is `AircraftOwnerClubId = club AND NrOfSeats = 2 AND AircraftTypeId = Glider(1)` with **no ORDER BY** — `FirstOrDefault` is DB-order-dependent, so the proof must seed exactly one matching glider to assert deterministically.

`TrialFlight.AircraftReservation.FlightTypeId` is stored as a **JSON-quoted GUID string**, not a raw column; an undeserializable value is swallowed and the reservation is created with a null flight type (`RegistrationService.cs:201-212`). `TrialFlight.EventDates` is a JSON array of ISO datetimes; the legacy test fixture already seeds FGZO with `["2099-06-15T10:00:00","2099-08-25T10:00:00"]` (`_test-fixture.sql:564-582`).

Legacy returns **HTTP 500 for every failure** — unknown club key, no active club user, and any internal exception all surface as one generic German message. J-17's 404/403/429 contract is a deliberate improvement, not a parity miss.

Legacy DTO validation is far looser than its own HTML: only `ClubKey`/`Firstname`/`Lastname` are `[Required]` server-side, while address/zip/city and the whole invoice block are enforced client-side only. **J-17 validates server-side** — ADR 0022 directive 2 puts these rules on the aggregate. Recorded as an intentional divergence.

### Divergences from legacy — deliberate, not parity misses

1. **Passenger email templates are fixed, not ported.** The legacy passenger templates interpolate `$!TrialFlightRegistrationModel.*` while the token dictionary only carries `PassengerFlightRegistrationModel` (`RegistrationEmailBuildService.cs:211-213,271-273`), so Velocity's silent `$!` renders phone, email and remarks **blank in every passenger email ever sent**. Reachable, user-facing, and a copy-paste bug rather than intent — the port binds the correct model and the spec asserts those fields render populated.
2. **Server-side validation** of the fields legacy only guards in the browser (see above).
3. **Typed error contract** (404 / 403 / 429) replacing the blanket 500.
4. Grammar fix in the homebase-missing organiser message (legacy: *"Keine Heimflugplatz"*).

### Assumptions made

1. Routes are `/discovery-flight/:clubSlug` and `/scenic-flight/:clubSlug`, extending J-16's shipped shells rather than re-creating the legacy paths.
2. Discovery-flight days become a small club-scoped aggregate managed on the club-admin screen, and are **not** migrated from the legacy settings JSON (recorded above as a reversible decision, not a silent exclusion).
3. Tenant is established with an explicit `Tenants.runAs` window in the application service, not a request interceptor.
4. Discovery registration auto-books the reservation for legacy parity, with the three legacy skip-fallbacks preserved as success paths.
5. The abuse guard is new work with no legacy counterpart (legacy's reCAPTCHA is commented out in `tryflight.html:258-264` and the server never reads `RecaptchaResponse`); an unauthenticated row-writing endpoint should not ship without one.
6. Exact email copy and the organiser-mail reservation sentences are pinned at ship time via `legacy-oracle` on `RegistrationService.cs` + `RegistrationEmailBuildService.cs`; the carve fixes recipients and branch conditions, which is what parity turns on.

## Outcome

Shipped **both** flows — the deferrable scenic tail was not needed, so J-18 stays folded. 35 tasks: the S-025 public-tenant spine (slug → allowlist → `Tenants.runAs`, 404/403 writing nothing), the first unauthenticated write endpoints with an IP × slug abuse guard and a distinct reach-based budget on the anonymous reads, the `DiscoveryFlightDay` aggregate + club-admin CRUD panel, the `Club` operator-email / discovery-flight-type / `homebaseId` write path, four transactional templates over J-11's resolver, and the two public forms sharing one shell + registrant fieldset. The CLUB mapper delta canonicalises the free-text operator-recipient list, which legacy separates with comma/semicolon/whitespace.

Green at job level on **`d33d3fd4`**: `ci` all 13 jobs, `fan-out parity` 68 steps with both legacy builds, all 4 real-idp shards. `gap-hunter` filed two live gaps at the gate (T-23 guard under-delivery, T-24 unexercised coupon radios) and both were closed on-branch. The gate itself caught the journey's sharpest bug (T-22c): every real browser submit 400'd on an absent primitive while every server IT stayed green, because the IT helper always wrote the key — closed at the source, not the symptom.

Riders folded: [J-15-MAILPIT-REPORT], [CI-TROUBLESHOOTING-MARKER], [GHA-TERNARY-AUDIT], [DEV-UP-FAIL-LOUD], [PROOF-HARNESS TRANSIENTS]. Filed forward: [AUDIT-ACTOR-KIND], [AUDIT-ACTOR-CELL], [FORM-FIRST-PAINT-RED], [FIELDSET-LEGEND-SIZE], [MOCK-CLUB-ID-SHAPE], and [ANON-WRITE-ATTRIBUTION] — the last an operator decision, since recording a client IP on anonymous writes is a privacy question, not just a schema one.
