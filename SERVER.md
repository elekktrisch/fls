# SERVER.md — How flsserver Actually Works

Companion to `CLAUDE.md` (which is the surface map: build commands, project layout, conventions). This document is the **mental model** — the non-obvious dynamics you only get from reading code, not from listing files.

All file paths are relative to `flsserver/src/`. Line numbers reflect the state at the time of writing; treat as approximate after edits.

## The system in one sentence

A flight enters as raw data → gets validated by a nightly job → ages two days → gets locked → ages three more days → a rules engine matches it against configurable accounting filters → produces a Delivery (invoice draft) → an export job mails the deliveries as Excel ZIPs → an external Proffix sync (separate repo) picks them up and pushes them to the actual accounting system.

```
manual entry / OGN import
            ↓
    Flight (NotProcessed)
            ↓  DailyFlightValidationJob (22:00 UTC)
    Valid  ───or───  Invalid (user fixes, re-validates)
            ↓  same job, after ≥2 days
    Locked
            ↓  DeliveryCreationJob (22:00 UTC), after ≥3 more days
    DeliveryPrepared  ───or───  DeliveryPreparationError (no rules matched)
            ↓  external Proffix sync books invoice
    DeliveryBooked (terminal — no edits, ever)

    Side branch from Valid/Locked/DeliveryPrepared:
    ExcludedFromDeliveryProcess  ⇄  Valid
```

## Five load-bearing insights

### 1. "Workflow" is HTTP + cron, not Quartz

The `job_scheduling_data_2_0.xsd` sitting in `FLS.Server.Service/` is a **dead schema** — there's no in-process Quartz scheduler.

The actual flow:

1. An OS-level cron / Task Scheduler runs the `FLS.Workflow.Activator` console app.
2. The console app reads credentials from `AppSettings.Default`, fetches an OAuth bearer token from `/Token` (`FLS.Workflow.Activator/Program.cs:172`).
3. It then GETs an endpoint on `WorkflowsController` (`FLS.Server.Web/Controllers/WorkflowsController.cs`):

   | Endpoint                                            | What it runs                                                                             |
   | --------------------------------------------------- | ---------------------------------------------------------------------------------------- |
   | `GET /api/v1/workflows/`                            | All workflows (router based on current UTC hour)                                         |
   | `GET /api/v1/workflows/flightvalidation`            | `DailyFlightValidationJob`                                                               |
   | `GET /api/v1/workflows/dailyreports`                | `DailyReportJob`                                                                         |
   | `GET /api/v1/workflows/monthlyreports{/year/month}` | `AircraftStatisticReportJob` (optional override)                                         |
   | `GET /api/v1/workflows/planningdaymails`            | `PlanningDayNotificationJob`                                                             |
   | `GET /api/v1/workflows/deliverycreation`            | `DeliveryCreationJob`                                                                    |
   | `GET /api/v1/workflows/deliverymailexport`          | `DeliveryMailExportJob`                                                                  |
   | `GET /api/v1/workflows/testmails`                   | Test email                                                                               |

4. `WorkflowService.Run()` (`FLS.Server.Service/WorkflowService.cs:117-158`) dispatches by **current UTC hour**:

   - 12:00 UTC → `PlanningDayNotificationJob`
   - 22:00 UTC → `DailyFlightValidationJob` + `DailyReportJob` + `LicenceNotificationJob` + `DeliveryCreationJob`
   - 2nd of month, 23:00 UTC → `AircraftStatisticReportJob` + `DeliveryMailExportJob` + `AircraftDatabaseSyncJob`

**Implication for ops:** to trigger any workflow manually you just `curl -H "Authorization: Bearer $TOKEN" .../api/v1/workflows/<name>`. No need for Windows Task Scheduler — a Linux crontab with `curl` is sufficient.

### 2. Two-dimensional flight state machine

Every flight has *two* independent state fields:

- **`FlightAirState`** — computed, never authoritatively stored. Derived in `GetCalculatedFlightAirStateId()` from timestamps and flags: `New(0) → FlightPlanOpen(5) → MightBeStarted(8) → Started(10) → MightBeLandedOrInAir(15) → Landed(20) → FlightPlanClosed(25)`. This is the *physical* state.
- **`FlightProcessState`** — stored, workflow-driven. This is the *administrative* state and is what the rest of the system gates on.

`FlightProcessState` values and transitions (enforced in `FlightService.cs:1380-1440`):

```
NotProcessed(0)
    ↓ DailyFlightValidationJob
Invalid(28) ──user edits and re-runs validation──> Valid(30)
                                                      ↓ LockFlights() after ≥2 days
                                                   Locked(40)
                                                      ↓ DeliveryCreationJob after ≥3 more days
                                                   DeliveryPrepared(50) — or — DeliveryPreparationError(45)
                                                      ↓ external Proffix sync books invoice
                                                   DeliveryBooked(60)  ← TERMINAL

Side branch: Valid / Locked / DeliveryPrepared / DeliveryPreparationError → ExcludedFromDeliveryProcess(99) → Valid
```

**Gotcha — time gating is real:**
- `LockFlights` requires age ≥ 2 days.
- `CreateDeliveriesFromFlights` requires lock-age ≥ 3 days.
- A fresh DB looks broken end-to-end until you either backdate seed data or hit the endpoint with a clock-manipulated DB.

**Single entity, three flavors:** Glider, Tow, and Motor flights are all the same `Flight` row, discriminated by `FlightAircraftType` (`GliderFlight=1, TowFlight=2, MotorFlight=4`). Master data like `FlightType` and `FlightCostBalanceType` carry `IsForGliderFlights / IsForTowFlights / IsForMotorFlights` flags. Glider flights with `StartType=Towing` carry a `TowFlightId` pointing at the tow plane's flight row — validation recurses through it.

### 3. The rules engine is a stateful decrement loop, not a match table

Looking at just `FLS.Server.Service/RulesEngine/RulesEngine.cs` you'd think it's a plain "match conditions → apply action" framework. The real subtlety is in **`Accounting/DeliveryItemRulesEngine.cs`**.

- `AccountingRuleFilter` (DB entity) is the **config** — a bag of match predicates (aircraft type, immatriculation list, start/landing locations, flight type codes, crew types, member numbers, homebase, time ranges) paired with an action target (article number, recipient, accounting unit).
- A runtime `Rule` object is instantiated per flight from a filter. Rule types: `DoNotInvoiceFlight`, `Recipient`, `FlightTime`, `EngineTime`, `InstructorFee`, `LandingTax`, `StartTax`, `VsfFee`, `AdditionalFuelFee`, `NoLandingTax`.
- The pipeline runs in stages on a shared `RuleBasedDeliveryDetails` accumulator (`DeliveryService.cs:256+`):
  1. `IgnoreFlightRulesEngine` — `DoNotInvoiceFlight` rules. If any match, skip the flight.
  2. `RecipientRulesEngine` — `Recipient` rules. Sets the invoice recipient.
  3. `DeliveryItemRulesEngine` — the loop:
     - **FlightTime loop**: apply matching FlightTime rules, each one **decrements `ActiveFlightTime`** on the accumulator and emits a `DeliveryItem`. Repeat until no rule matches. This is how tiered/chunked billing works — first 30 min at rate A, next 30 at rate B — without any explicit tier logic.
     - **EngineTime loop**: same pattern.
     - InstructorFee, AdditionalFuelFee, StartTax, LandingTax rules (single-pass).
     - For glider flights, recurse into the linked TowFlight.
- `DeliveryCreationTest` (entity + controller) is a **regression harness**: store the expected `DeliveryItems` for a flight, re-run rules after a config change, diff. Use it before touching production rule filters.

`InvoiceRuleFilters.xlsx` at the repo root is a seed/reference spreadsheet — likely the source for default rule filters. `Invoice-Rule-Editor-Form-Design.vsdx/png` in `doc/` is the design mock for the in-client rule editor.

### 4. Multi-tenancy is convention, not framework

**There is no EF global filter for `ClubId`.** Every service inherits `BaseService` (`FLS.Server.Service/BaseService.cs`) and is expected to call:

- `CurrentAuthenticatedFLSUserClubId` (`:43`) — the current user's club.
- `IsCurrentUserInClub(clubId)` (`:56`) — boolean check.
- `IsOwner(record)` (`:73`) — ownership check (some records are user-owned, some club-owned; `User.cs:105-107` defines `OwnerId` + `OwnershipType`).
- `IsCurrentUserInRoleClubAdministrator` (`:100`).

Per-request, `UserInitActionFilter` (`FLS.Server.Web/ActionFilters/UserInitActionFilter.cs:17-34`) hydrates `IdentityService.CurrentAuthenticatedFLSUser` from the bearer-token principal.

**Implication when modifying code:** every new query, every new service method is a silent tenancy-bug risk if you forget the `ClubId` filter. Pattern to copy: grep for `CurrentAuthenticatedFLSUserClubId` in any existing service and mirror the surrounding code.

### 5. User / Person split — the data model trap

Two distinct concepts that get conflated by name:

- **`User`** — a login principal. Has username/password, email, `ClubId` (scoped to **exactly one** club), `AccountState`, application roles. Carries `PersonId?` if the login is tied to a human.
- **`Person`** — a human in the system. May exist without a User (passengers, guests, deceased members). Has firstname, lastname, communication email, licenses, medical certificates.
- **`PersonClub`** (N:M) — per-club membership facts: `MemberNumber`, `MemberState`, per-club **flight role flags** (`IsPilot`, `IsInstructor`, `IsTrainee`, `IsPAX`), notification preferences. A Person may belong to multiple clubs with different roles in each.
- **`Role` / `UserRole`** — *application* roles (separate concept from flight roles). Three predefined in `FLS.Data.WebApi/Resources/RoleApplicationKeyStrings.cs`:
  - `SystemAdministrator` — superuser, no club scoping.
  - `ClubAdministrator` — per-club admin; actively gated in services.
  - `FlightOperator` — referenced but not extensively gated.

```
Login domain                          Human/membership domain
─────────────                         ────────────────────────
User ─────── ClubId ─────> Club <───── PersonClub ─────> Person
 │                                       │
 └─ UserRole ─> Role                     ├─ MemberNumber, MemberState
                                         ├─ IsPilot, IsInstructor, IsTrainee, IsPAX
                                         └─ Notification flags
```

A User can only act inside their one `ClubId`. A Person can fly at any club they're a member of via `PersonClub` — that's what shows up in flight crew dropdowns.

## Auth flow

- `POST /Token` with `grant_type=password, username, password` returns a bearer token.
- `Startup.Auth.cs:28` sets `AccessTokenExpireTimeSpan` to **14 days**.
- `FLSOAuthAuthorizationServerProvider`:
  - Requires confirmed email (`:69`).
  - Honors account-locked state (`:78`).
- `IdentityUserManager`:
  - Password min length 8, no special-char requirement (`:31-38`).
  - 5 failed attempts → 10-minute lockout (`:42-44`).
  - Email must be unique (`:27`).
- All API endpoints rely on `[Authorize]` + the bearer filter; default host auth is suppressed in `WebApiConfig`. CORS is fully open (`origins: "*"`).

## Job catalog — one-liners

All under `FLS.Server.Service/Jobs/`:

| Job                          | Purpose                                                                                       |
| ---------------------------- | --------------------------------------------------------------------------------------------- |
| `DailyFlightValidationJob`   | Validates `NotProcessed`/`Invalid` flights → `Valid`/`Invalid`; locks `Valid` ≥2 days old.    |
| `DailyReportJob`             | Emails per-pilot/per-instructor daily flight reports.                                         |
| `AircraftStatisticReportJob` | Monthly aircraft usage report to club/owner; supports manual `year/month` override.           |
| `PlanningDayNotificationJob` | Emails tomorrow's planning-day status + 7-day-ahead reminders to assigned instructors/pilots. |
| `LicenceNotificationJob`     | Emails expiry warnings for medical certs and instructor licenses (60-day window).             |
| `DeliveryCreationJob`        | Runs the rules engine on locked flights → creates Deliveries → transitions to `DeliveryPrepared` (or `DeliveryPreparationError`). |
| `DeliveryMailExportJob`      | Bundles pending Deliveries into per-recipient Excel files, zips, emails the club; marks `IsFurtherProcessed`. |
| `AircraftDatabaseSyncJob`    | Pulls aircraft metadata (FLARM ID, model, competition sign) from the OGN aircraft DB.         |

Excel export uses **EPPlus** (license note: EPPlus changed from LGPL to Polyform Noncommercial after v4.5 — check the pinned version before relying on it commercially) and **Ionic.Zip**.

## What's NOT in this repo (don't waste time looking)

- **OGN flight auto-import** — handled by the separate [`OGNAnalyser`](https://github.com/sgacond/OGNAnalyser) project that writes flights straight into this DB. `FlightService` only sees them after insertion.
- **Proffix invoice sync** — `FLS.Server.ProffixInvoiceService/` is a stub (only `Properties/AssemblyInfo.cs` and `packages.config` are committed). The real adapter lives in [`PROFFIX-FLS-Sync`](https://github.com/arminstutz/PROFFIX-FLS-Sync) and pulls Deliveries via the public API.
- **Aircraft database** for the `AircraftDatabaseSyncJob` — fetched from the public OGN DDB.

## Where to start when changing things

| Goal                                          | Start here                                                                                                              |
| --------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| Change validation rules                       | `FlightService.cs` → `ValidateFlightBasics` and type-specific validators (search for `ProcessStateId = ... Invalid`).   |
| Change billing tiers / add an invoice rule    | `Accounting/DeliveryItemRulesEngine.cs` + add a filter type in `FLS.Data.WebApi/Accounting/RuleFilters/`. Verify with a `DeliveryCreationTest`. |
| Add a scheduled job                           | New class in `Service/Jobs/` implementing `IJob`; wire it into `WorkflowService.Run()` and add an endpoint on `WorkflowsController`; register the service in `UnityConfig`. |
| Add a new tenant-scoped endpoint              | New controller in `FLS.Server.Web/Controllers/` + new service in `FLS.Server.Service/`. **Inherit `BaseService` and use `CurrentAuthenticatedFLSUserClubId` in every query** — there is no global tenant filter. |
| Add a new entity                              | Entity in `FLS.Server.Data/DbEntities/` + fluent mapping in `FLS.Server.Data/Mapping/` + DTO in `FLS.Data.WebApi/<Domain>/` + service + controller + Unity registration + SQL update script in `database/FLS/Updates/DBUpdate_v<next>.sql`. |
| Trigger a workflow manually                   | `curl -H "Authorization: Bearer $TOKEN" {host}/api/v1/workflows/<name>`. The `FLS.Workflow.Activator` console app does exactly this. |
| Understand a state transition                 | `FlightService.cs:1380-1440` (transition matrix) + `FlightProcessState` enum in `FLS.Server.Data/Enums/`.               |
| Onboard a new club                            | DB inserts via the `database/FLSTest/3 insert/` reference scripts; create `Club`, seed `AccountingRuleFilter`s, create a `ClubAdministrator` `User`. |

## Knowledge gaps worth verifying with a running build

- Exact rule-evaluation order when multiple `Recipient` rules match (first-match-wins? last-write-wins?).
- The current state of `FLS.Server.ProffixInvoiceService` — is it actually wired anywhere, or pure dead code?
- Whether `DeliveryCreationTest` is invoked by any job or only on demand via the controller.
- Whether EPPlus/Ionic.Zip version pins are still under licenses that permit your intended use.
