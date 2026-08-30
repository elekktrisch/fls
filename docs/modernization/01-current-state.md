# 01 — Current State

Authoritative snapshot of the legacy system. Sacred cows and strategic anchors live in
[`00-seed.md`](00-seed.md); this document supplies the facts the seed assumes.

> **Read the seed with care.** `00-seed.md` mixes two kinds of content. Its sacred cows and
> strategic anchors are authoritative. Its stack references — `alpenflight/database/` with Flyway
> migrations at [`00-seed.md:26`](00-seed.md), Postgres, Keycloak — are **rebuild-1 decisions**.
> Treat those as archive. `bmad-architecture` re-decides them.

> **Rebuild 2 status.** This is the load-bearing input to the rewrite. Every epic derives from the
> feature inventory in §2, and every architecture decision answers a risk in §7. Rebuild 1's
> planning artifacts are archived in [`../attempt-1/`](../attempt-1/).

**Verification stamp**

| Field | Value |
| --- | --- |
| Last verified | 2026-08-24 |
| Repository commit | `1aff2b604` |
| `flsserver/` last changed at | `51894c316` |
| `flsweb/` last changed at | `9da543854` |
| Verified by | `bmad-review` (adversarial, edge-case, structure, prose lenses) |

Every line range in this document (for example `FlightService.cs:1380-1444`) is correct **as of the
commits above**. `CLAUDE.md` permits fixes to legacy source, so line numbers drift. Re-verify a
range before you act on it.

**How to refresh this document.** The generating skill `/modernize-discover` is retired. Refresh it
by hand: re-run the counts in §1, §5, and §6 against the repository, regenerate the §2 Spec column
from `find e2e/tests -name "*.spec.ts"`, then re-run `bmad-review` over the result and update the
stamp above.

## 1. Executive snapshot

**What it is.** The Flight Logging System (FLS) is a multi-tenant SaaS. Swiss glider clubs use it
to run flight operations: aircraft reservations, flight logging, planning days, accounting, and
invoice export to external systems.

**The two codebases.** Two independently-versioned codebases sit side-by-side in this repository.
`flsserver/` is an ASP.NET Web API 2 backend on .NET Framework 4.5, with EF6 Code First, Unity DI,
OWIN OAuth2 bearer authentication, and SQL Server. It holds 47 controllers in
`FLS.Server.Web/Controllers/`. `flsweb/` is an AngularJS 1.4 SPA built with Webpack 1, Babel
ES2015, and tested with Karma and Jasmine on a Node 8 toolchain.

**The two external integrations.** One integration sits at each end of the system. The
[OGNAnalyser](https://github.com/sgacond/OGNAnalyser) project writes inbound flights directly to
the database. The [PROFFIX-FLS-Sync](https://github.com/arminstutz/PROFFIX-FLS-Sync) project reads
outbound deliveries through the public API.

**Lifecycle stage: mature legacy.** Clubs use the system in production today. The full toolchain is
end-of-life: Webpack 1, AngularJS 1.4, .NET Framework 4.5, and Node 8.

> **The e2e suite proves breadth, not depth.** The suite holds 43 specs in 12 categories under
> `e2e/tests/`. It is the most reliable feature inventory **at breadth** — it confirms a feature
> exists and is reachable. It does not prove that the business logic is correct. Read the depth
> callout under [Flight operations](#flight-operations) and R14 in §7 before you treat the suite as
> a parity oracle.

## 2. Feature inventory

Rows: feature → primary code paths → user persona → e2e spec. The tables group the features by
domain.

**How to read these tables.**

1. One row is one epic candidate. `bmad-create-epics-and-stories` derives from this column set.
2. The Spec column holds paths relative to [`e2e/tests/`](../../e2e/tests/). Example:
   `flights/create.spec.ts` is `e2e/tests/flights/create.spec.ts`.
3. `(no direct spec)` means no spec targets this feature. It is the only token used for that case.
4. A spec named `*-parity-J*.spec.ts` or `*-fanout-J*.spec.ts` is a **rebuild-1 journey artifact**.
   These capture legacy screens on video and assert lightly. Do not count them as behavior
   coverage. `flights/flights-parity-J2.spec.ts` carries the tag `@quarantine-legacy` and does not
   run in a default pass.
5. A feature appears in exactly one table. Where a second domain also uses it, that table
   cross-references the owner instead of repeating the row.

### Identity, auth, and tenancy
| Feature | Code | Persona | Spec |
|---|---|---|---|
| Bearer login (`/Token`) | `flsserver/src/FLS.Server.Web/App_Start/Startup.Auth.cs`, `IdentityUserManager`, `core/AuthService.js` | all | `auth/login.spec.ts`, `auth/authenticated-routes-smoke.spec.ts`, `api/contract.spec.ts` |
| Password reset (lost password) | `flsweb/src/lostpassword/`, `RegistrationService` | end-user | `public/registration-flows.spec.ts`, `email/notifications.spec.ts`, `auth/lostpassword-parity-J19.spec.ts` |
| Email confirmation | `flsweb/src/confirm/`, `RegistrationService` | end-user | `public/registration-flows.spec.ts`, `auth/lostpassword-parity-J19.spec.ts` |
| User CRUD | `UsersController`, `UserService`, `flsweb/src/masterdata/users/` | club admin | `masterdata/users-crud.spec.ts` |
| Roles & user-role assignment | `UserRolesController`, `Role` enum, `RoleApplicationKeyStrings.cs` | system admin | `api/contract.spec.ts` (list shape only) |
| Profile self-edit | `flsweb/src/profile/`, `UsersController.UpdateMy` | end-user | `profile/edit.spec.ts`, `profile/profile-parity-J4.spec.ts` |
| Multi-tenant isolation | `BaseService.CurrentAuthenticatedFLSUserClubId` (convention) | system | `multi-tenant/isolation.spec.ts` |

### Master data (CRUD-shaped admin surface)
| Feature | Code | Persona | Spec |
|---|---|---|---|
| Clubs | `ClubsController`, `ClubService`, `flsweb/src/masterdata/clubs/` | system / club admin | `masterdata/clubs-crud.spec.ts`, `api/contract.spec.ts` |
| Aircraft | `AircraftsController`, `AircraftService`, `flsweb/src/masterdata/aircrafts/` | club admin | `masterdata/aircrafts-crud.spec.ts`, `masterdata/aircrafts-parity-J1.spec.ts` |
| Locations | `LocationsController`, `LocationService`, `flsweb/src/masterdata/locations/` | club admin | `masterdata/locations-crud.spec.ts`, `masterdata/locations-fanout-J0c.spec.ts` |
| Flight types | `FlightTypesController`, `flsweb/src/masterdata/flightTypes/` | club admin | `masterdata/flight-types-crud.spec.ts` |
| Member states | `MemberStatesController`, `flsweb/src/masterdata/memberStates/` | club admin | `masterdata/member-states-crud.spec.ts` |
| Person categories | `PersonCategoriesController`, `flsweb/src/masterdata/personCategories/` | club admin | `masterdata/person-categories-crud.spec.ts` |
| Persons (+ add-person modal) | `PersonsController`, `PersonService`, `flsweb/src/masterdata/persons/` | club admin | `masterdata/persons-add-modal.spec.ts` |
| Articles | `ArticlesController`, `ArticleService` | club admin | (no direct spec) |
| Email templates | `EmailTemplatesController`, `TemplateService` | club admin | (no direct spec) |
| Language translations | `LanguageTranslationsController`, `LanguageService` | system admin | (no direct spec) |
| System data | `SystemDatasController`, `SystemService` | system admin | (no direct spec) |
| System logs | `SystemLogsController`, `flsweb/src/system/logs/` | system admin | (no direct spec) |
| Reference tables (countries, lengths, elevations, counter units, …) | `CountriesController`, `ElevationUnitTypesController`, `LengthUnitTypesController`, `CounterUnitTypesController`, … | system | (no direct spec) |
| Master-data list + form render smoke | (spans the controllers above) | club admin | `masterdata/screenshot-smoke.spec.ts` |

### Flight operations

> **Test coverage depth — mostly happy path.** Most specs in this section exercise the primary flow
> with valid inputs and assert the surface response shape. R14 in §7 lists exactly what the suite
> probes and what it does not, and states what the rewrite must do about it. Read R14 before you
> treat any row below as covered.

| Feature | Code | Persona | Spec |
|---|---|---|---|
| Glider/tow flight create | `FlightsController.Post`, `FlightService`, `flsweb/src/flights/` | pilot / operator | `flights/create.spec.ts`, `flights/flights-parity-J2.spec.ts` |
| Flight edit | `FlightsController.Put`, `FlightService` | pilot / operator | `flights/edit.spec.ts` |
| Flight state transitions | `FlightService.cs:1380-1444`, `FlightProcessState` / `FlightAirState` enums | scheduled job + admin | `flights/state-transitions.spec.ts`, `flights/locking-workflow.spec.ts` |
| Flight types per aircraft type | `FlightCostBalanceTypesController`, `FlightTypesController` | system | (no direct spec) |
| Air movements (motor aircraft) | `flsweb/src/flights/airmovements/`, same `FlightsController` | club admin | `flights/airmovements-crud.spec.ts` |
| Flight reports | `FlightReportsController`, `FlightReportService`, `flsweb/src/reporting/` | club admin | `reporting/flight-reports.spec.ts`, `reporting/custom-builder.spec.ts`, `reporting/reporting-parity-J7.spec.ts` |
| API contract surface | (covers all `FlightOverview` / `FlightDetails` DTOs) | system | `api/contract.spec.ts` |
| Audit log | `AuditLogsController`, `AuditLogService` (client) | club admin | `flights/audit-logs.spec.ts` |

### Reservations & planning
| Feature | Code | Persona | Spec |
|---|---|---|---|
| Reservation CRUD | `AircraftReservationsController`, `AircraftReservationService`, `flsweb/src/reservations/` | pilot | `reservations/crud.spec.ts`, `reservations/reservations-parity-J5.spec.ts` |
| Reservation scheduler (calendar view) | `flsweb/src/reservation-scheduler/` | pilot | `reservations/scheduler.spec.ts` |
| Planning day CRUD | `PlanningDaysController`, `PlanningDayService`, `flsweb/src/planning/` | club admin | `planning/day-crud.spec.ts`, `planning/planning-parity-J6.spec.ts` |
| Planning setup wizard | `flsweb/src/planning/` (setup screen) | club admin | `planning/setup-wizard.spec.ts` |
| Planning-day notifications | `PlanningDayNotificationJob` | scheduled job | `email/notifications.spec.ts` |

### Accounting & invoicing pipeline

> **Sacred cow.** See [seed §Sacred cows](00-seed.md#sacred-cows-must-survive-the-rewrite). The
> rewrite must reproduce this pipeline exactly.
| Feature | Code | Persona | Spec |
|---|---|---|---|
| Accounting rule filter CRUD | `AccountingRuleFiltersController`, `AccountingRuleFilterTypesController`, `AccountingUnitTypesController`, `flsweb/src/masterdata/accountingRules/` | club admin | `accounting/rules-edit.spec.ts`, `accounting/accounting-parity-J8.spec.ts` |
| Delivery creation (rules engine) | `Accounting/RuleEngines/DeliveryItemRulesEngine.cs`, `DeliveryService`, `RulesEngine/RulesEngine.cs`, `Jobs/DeliveryCreationJob.cs` | scheduled job | `accounting/delivery-creation-workflow.spec.ts`, `accounting/rules-engine-per-type.spec.ts` |
| Delivery view / edit / delete | `DeliveriesController`, `flsweb/src/masterdata/deliveries/` | club admin | `accounting/delivery-creation-workflow.spec.ts` |
| Delivery creation test (regression harness) | `DeliveryCreationTestsController`, `flsweb/src/masterdata/deliveryCreationTests/` | club admin | `accounting/delivery-creation-test.spec.ts` |
| Delivery mail export | `Jobs/DeliveryMailExportJob.cs`, `Exporting/` (EPPlus, Ionic.Zip) | scheduled job | (no direct spec — needs SMTP) |

This table owns the two delivery features. The scheduled-job table in
[Email & scheduled jobs](#email--scheduled-jobs) lists the jobs that trigger them and points back
here.

### Public (no-auth) flows
| Feature | Code | Persona | Spec |
|---|---|---|---|
| Trial-flight registration | `TrialFlightsRegistrationsController`, `flsweb/src/tryflight/` | public | `public/registration-flows.spec.ts`, `email/notifications.spec.ts` |
| Passenger-flight registration | `PassengerFlightsRegistrationsController`, `flsweb/src/passengerflight/` | public | `public/registration-flows.spec.ts`, `email/notifications.spec.ts` |
| Landing page | `flsweb/src/main/` | public | `public/landing.spec.ts`, `public/screenshot-smoke.spec.ts` |

### Email & scheduled jobs
| Feature | Code | Persona | Spec |
|---|---|---|---|
| Daily flight validation | `Jobs/DailyFlightValidationJob.cs` | scheduled | `flights/locking-workflow.spec.ts` |
| Daily report email | `Jobs/DailyReportJob.cs`, `Email/` | scheduled | `email/notifications.spec.ts` (mailpit) |
| Monthly aircraft statistic report | `Jobs/AircraftStatisticReportJob.cs` | scheduled | (no direct spec) |
| Planning-day notification email | `Jobs/PlanningDayNotificationJob.cs` | scheduled | `email/notifications.spec.ts` |
| Licence-expiry notification email | `Jobs/LicenceNotificationJob.cs` | scheduled | `email/notifications.spec.ts` |
| Aircraft DB sync (OGN) | `Jobs/AircraftDatabaseSyncJob.cs` | scheduled | (no direct spec) |
| Workflow dispatcher | `WorkflowsController`, `WorkflowService.Run()` | external cron | `flights/locking-workflow.spec.ts`, `accounting/delivery-creation-workflow.spec.ts` (both drive it) |

Two more jobs run on this dispatcher: `Jobs/DeliveryCreationJob.cs` and
`Jobs/DeliveryMailExportJob.cs`. The
[Accounting & invoicing pipeline](#accounting--invoicing-pipeline)
table owns both rows.

### Dashboard
| Feature | Code | Persona | Spec |
|---|---|---|---|
| Dashboard | `DashboardsController`, `DashboardService`, `flsweb/src/main/dashboard/` | all | (no direct spec) |

**Coverage callouts.**

*Breadth gaps.* No spec targets the monthly aircraft statistic report, the aircraft DB sync, the
delivery mail export, articles CRUD, email-template CRUD, language-translation CRUD, system-data
CRUD, system logs, or the dashboard. R13 in §7 carries the scheduled-job part of this gap.

*Depth gaps.* R14 in §7 states what the suite probes and what it does not.

Both gaps are the highest parity risks if the rewrite cuts over before the suite grows.

## 3. Architecture digest

This section lists the load-bearing patterns. Detail lives in
[`../legacy/server.md`](../legacy/server.md) and [`../legacy/web.md`](../legacy/web.md). This
document does not repeat it.

- **Workflow ≠ Quartz.** Background jobs are HTTP endpoints on `WorkflowsController`. External OS-level cron hits them via the `FLS.Workflow.Activator` console app. `WorkflowService.Run()` dispatches by UTC hour. The `job_scheduling_data_2_0.xsd` in the service tree is a **dead schema** — no in-process scheduler.
- **Two flight-state fields.** `FlightAirState` is *computed*, never stored. `FlightProcessState`
  is *stored* and workflow-driven. Time gates: ≥ 2 days to lock, ≥ 3 more days to bill. See
  [seed §Sacred cows](00-seed.md#sacred-cows-must-survive-the-rewrite). Both enumerations are
  given in full below — do not model the state machine from the summary bullet alone.
- **Single-entity flight model.** Glider, tow, and motor flights are all one `Flight` row, discriminated by `FlightAircraftType`. Tow plane references its towed glider via `TowFlightId`. Validation recurses through the link. The recursion has no stated termination guard — see Q-B5 in §9.
- **Accounting rules engine is a decrement loop.** `DeliveryItemRulesEngine.cs` repeatedly applies
  matching `FlightTime` rules until none match. Each iteration decrements `ActiveFlightTime` on a
  shared accumulator. This is the mechanism for tiered and chunked billing. Clubs configure it, and
  the rewrite must reproduce the result exactly. Two properties the loop depends on are not written
  down anywhere — rule ordering and the minimum decrement. See Q-B6 and Q-B7 in §9.
- **Multi-tenancy is convention, not framework.** No EF global filter for `ClubId`. Every service is expected to call `CurrentAuthenticatedFLSUserClubId` on every query. Seed marks structural enforcement as **non-negotiable** for the rewrite.
- **User vs. Person split.** `User` is a login principal scoped to one `ClubId`. `Person` is a human, can be in multiple clubs via `PersonClub`. Collapsing them breaks multi-club pilot rosters.
- **OAuth bearer, 14-day, no refresh.** Tokens sit on `$http.defaults` in the SPA; sessionStorage only; no global 401 interceptor; recovery depends on next route change re-running `userAuth` resolve guard.
- **Per-action cache invalidation.** Client-side `$resource` services attach response interceptors
  that clear specific `$http` cache entries on mutating calls. There is no global cache strategy, so
  a mutating endpoint added without an interceptor serves stale reads. See Q-B12 in §9.
- **i18n is server-loaded.** `angular-translate` URL loader against `/api/v1/translations`. Translations live in DB (`LanguageTranslation` table). Changes don't require a client rebuild.
- **DB schema is hand-rolled.** `flsserver/database/FLS/Updates/DBUpdate_v*.sql` is the production schema source of truth. EF Code First migrations exist in `FLS.Server.Data/Migrations/` but are not the runtime driver.

#### `FlightProcessState` — the stored state machine

Source: `flsserver/src/FLS.Data.WebApi/Flight/FlightProcessState.cs`. Transition rules come from the
XML remarks on that enum and from `FlightService.cs:1375-1444`.

| Value | Id | Meaning | Reachable from |
| --- | --- | --- | --- |
| `NotProcessed` | 0 | Created, not yet validated | (initial) |
| `Invalid` | 28 | Validation failed | `NotProcessed` |
| `Valid` | 30 | Validation passed | `NotProcessed`, `Invalid` (after a user edit and the next validation job) |
| `Locked` | 40 | Age ≥ 2 days; eligible for delivery | `Valid`, `DeliveryPrepared` (reset — the delivery is deleted), `ExcludedFromDeliveryProcess` |
| `DeliveryPreparationError` | 45 | Delivery creation ran and matched no rule | `Locked` |
| `DeliveryPrepared` | 50 | Delivery created for the finance system | `Locked` |
| `DeliveryBooked` | 60 | Finance system booked it; the flight is frozen | `DeliveryPrepared` |
| `ExcludedFromDeliveryProcess` | 99 | Held out of delivery creation | `Valid`, `Locked`, any delivery state except `DeliveryBooked` |

Rules the rewrite must preserve:

- `DeliveryBooked` is terminal. A flight in that state can no longer be edited.
- `DeliveryPrepared` is reversible to `Locked`. The reset deletes the delivery, so the club can
  redefine accounting rule filters and re-run creation.
- `ExcludedFromDeliveryProcess` returns only to `Locked`.
- `Invalid` is not a dead end. A user edits the flight, and the next validation job may set `Valid`.

> **Correction to earlier drafts.** Before 2026-08-24 this document described a four-state linear
> machine (`NotProcessed → Valid → Locked → DeliveryPrepared`). The enum carries **eight** values.
> `DeliveryPreparationError`, `DeliveryBooked`, and `ExcludedFromDeliveryProcess` were missing.

#### `FlightAirState` — the computed state

Source: `flsserver/src/FLS.Data.WebApi/Flight/FlightAirState.cs`. Seven values:
`New` (0), `FlightPlanOpen` (5), `MightBeStarted` (8), `Started` (10), `MightBeLandedOrInAir` (15),
`Landed` (20), `FlightPlanClosed` (25).

The `MightBe…` values are deliberate ambiguity markers. They encode "the timestamps do not settle
this". The rewrite must decide whether to keep that three-way logic or resolve it to a definite
state.

The SPA does **not** carry these seven values. `FlightStateMapper` in
`flsweb/src/flights/FlightsServices.js:117-199` collapses them into three UI buckets —
`ready` / `inAir` / `landed`. That collapse is lossy and hand-written. See R5 in §7.

## 4. Integration map

### Inbound
| Caller | Contract | Auth | Owning repo |
|---|---|---|---|
| `flsweb` SPA | REST under `/api/v1/*` (47 controllers), bearer token | OAuth2 password grant, 14-day token | this repo |
| OGNAnalyser | **Direct SQL writes** to `Flight` + related tables | DB credentials | [sgacond/OGNAnalyser](https://github.com/sgacond/OGNAnalyser) |
| External cron (`FLS.Workflow.Activator`) | `GET /api/v1/workflows/*` | OAuth2 bearer (admin user) | this repo (`flsserver/src/FLS.Workflow.Activator`) |
| Public registration forms | `POST /api/v1/trialflightregistrations`, `POST /api/v1/passengerflightregistrations` | none | this repo (frontend embeds) |

### Outbound
| Target | Contract | Auth | Owning repo |
|---|---|---|---|
| SMTP server | RFC 5322 messages via `System.Net.Mail.SmtpClient` | smtp creds | n/a |
| Proffix accounting system | Polled by external sync via `/api/v1/deliveries/*` GETs | OAuth2 bearer | [arminstutz/PROFFIX-FLS-Sync](https://github.com/arminstutz/PROFFIX-FLS-Sync) |
| OGN aircraft DB | HTTP GET (public) — `AircraftDatabaseSyncJob` | none | OGN DDB |

**Note:** `FLS.Server.ProffixInvoiceService/` in this repo is a stub (only `Properties/AssemblyInfo.cs` and `packages.config` committed). All Proffix logic lives in the external sync repo.

## 5. Data model summary

EF Code First with **56** entity classes in `FLS.Server.Data/DbEntities/`, over **59** tables. These
two counts match [`legacy-migration-plan.md`](legacy-migration-plan.md), which is the exhaustive
per-table reference.

Hand-written SQL scripts drive the schema: `database/FLS/Updates/DBUpdate_v1.8.0.sql` through
`v1.8.11.sql` — **12** scripts — on top of a 40-table base in
`database/FLSTest/2 alter/2 Alter Database.sql`. The same folder also holds
`Data-Cleaning after v1.8.9.sql`, which is a **data** script, not a schema script. R7 must account
for it separately.

Clusters and central entities:

- **Identity & access.** `User`, `Role`, `UserRole`, `Person`, `PersonClub`, `Club`, `ClubExtension`, `ClubState`. The `User`/`Person`/`PersonClub` triad is the load-bearing shape — see seed sacred cows.
- **Aircraft & resources.** `Aircraft`, `AircraftType`, `AircraftState`, `AircraftAircraftState`, `AircraftOperatingCounter`, `Article`. ~15 aircraft seeded in `FLSTest`.
- **Flight operations.** `Flight`, `FlightCrew`, `FlightCrewType`, `FlightType`, `FlightCostBalanceType`, `FlightAirState`, `FlightProcessState`, `InOutboundPoint`, `Location`, `LocationType`. `Flight` is the largest table; one row per glider/tow/motor flight.
- **Reservations & planning.** `AircraftReservation`, `AircraftReservationType`, `PlanningDay`,
  `PlanningDayAssignment`, `PlanningDayAssignmentType`. Planning-day data persists per
  `(location, date)`, and `PlanningDayAssignment` carries the crew references.
- **Accounting.** `AccountingRuleFilter`, `AccountingRuleFilterType`, `AccountingUnitType`, `Delivery`, `DeliveryItem`, `DeliveryCreationTest`. Rule filter config drives `DeliveryItemRulesEngine`; deliveries flow `Prepared → Booked`.
- **Reference / dropdown data.** `Country`, `CounterUnitType`, `LengthUnitType`, `ElevationUnitType`, `MemberState`, `PersonCategory`, `LocationType`, `Language`, `LanguageTranslation`, `StartType`, `EmailTemplate`, `ExtensionType`, `ExtensionValue`, `SettingsEntity`-likes. Mostly small static tables; `LanguageTranslation` is the largest (one row per (locale, key)).

Cross-cluster constraints worth noting for any schema reshape:
- `Flight.TowFlightId → Flight.FlightId` self-reference (glider ↔ tow link). The delete semantics
  on this self-reference are not documented. See Q-B4 in §9.
- `Flight.GliderPilotPersonId / FlightInstructorPersonId / TowPilotPersonId → Person`, indirectly
  through `PersonClub` for tenancy. **A flight crew member can be a Person from a club other than
  the operating club** — splitting the schema by `ClubId` is not simple. The legacy rule that
  decides which tenant filter admits such a row is not written down. See Q-B8 in §9.
- `Delivery.RecipientPersonId → Person`, same cross-tenant possibility, same undocumented rule. See
  Q-B9 in §9.
- `AccountingRuleFilter` carries per-club config but references master data (`Aircraft`, `FlightType`) that is also per-club — clean per-tenant export requires walking these graphs.

## 6. Build, test, and ops surface

### Server
- **Build:** MSBuild + NuGet on Windows; `xbuild` on Linux/Mono (see [`TESTING.md`](../../TESTING.md) for the Linux playbook). Target: .NET Framework 4.5.
- **Runtime:** IIS in production; Mono 6.12 console host (`FLS.Server.Console`) on Linux for the demo path.
- **DI:** Unity (`UnityConfig.cs`).
- **EF:** version 6.2.0, `EntityFramework.SqlServer.dll` must be copied next to bin under Mono.
- **Vendored libs:** `Alpinely.TownCrier` (email templating), `Foundation.ObjectHydrator` (test data) — both checked into source rather than NuGet.
- **Logging:** NLog (`NLog.config` per host).
- **Tests:** MSTest in `FLS.Server.Tests`. Test settings file at `src/FLS.Server.Tests.runsettings`. Coverage is uneven — service tests dominate, controller tests sparse.

### Client
- **Build:** Yarn 1 + Webpack 1.12 + Babel `preset-es2015`. Node **8.17.0** (pinned via the Yarn ecosystem; never been ported forward).
- **Tests:** Karma 0.13 + Jasmine 2.4, headless Chrome. Single spec gating done by editing `src/index.spec.js`.
- **Dependencies:** AngularJS 1.4.1, `angular-ui-bootstrap` 0.13, `ng-table`, `selectize`,
  `pikaday`, `highcharts` 0.0.11, `moment` ^2.22, `lodash` 3.10. Many packages are pre-1.0.

### Database
- **Engine:** SQL Server 2022 Developer for the demo and the e2e suite. The production engine is
  not recorded anywhere in this repository. Confirm it with the operator before the database ADR.
- **Migration:** hand-rolled SQL scripts in `database/FLS/Updates/` (versioned), plus the
  `database/FLSTest/{1 create, 2 alter, 3 insert}/` reference tree. 12 schema update scripts
  (`v1.8.0` … `v1.8.11`) sit on top of the base alter file, and one data script
  (`Data-Cleaning after v1.8.9.sql`) sits beside them. Seed data lives in `3 insert/`.
- **E2E fixture:** `database/FLSTest/3 insert/_test-fixture.sql` anchors timestamps to a fixed `2026-01-01` base so time-gated states are reachable without clock manipulation.

### CI / dev infra
- **CI: none on this branch.** No `.github/` directory exists. Rebuild 1's 11 workflows are archived
  under [`../attempt-1/`](../attempt-1/). `CLAUDE.md` states the same. The new stack brings its own
  CI, and the architecture step must not assume an e2e gate that runs today.
- **Docker:** `docker-compose.yml` brings up SQL Server and Mailpit for the e2e suite. The server and
  the client still start by hand, outside compose. Read the comments in `docker-compose.yml`.
- **E2E infrastructure:** `bash e2e/scripts/dev-up.sh` starts it; `e2e/scripts/dev-down.sh` stops it;
  `e2e/scripts/seed.sh` loads the fixture. `e2e/playwright.config.ts` sets `baseURL` to
  `http://localhost:3000` and the API to `http://localhost:25567`.

### Acceptance smoke
The "T3 sequence" in [`TESTING.md`](../../TESTING.md) is the minimum bar for "the system runs":
POST `/Token`, GET `/users/my`, GET and PUT a flight, then re-read to confirm persistence. If T3
passes, the server, the database, authentication, and EF are all wired. The first parity check for
the new system must be a T3 equivalent.

## 7. Risk hotspots

Each risk carries three fields. **Where** is the file and line that proves the risk. **Answered
when** is the evidence that closes it — `bmad-architecture` must produce that evidence, not an
assertion. **Class** is one of:

- **Blocking** — the rewrite cannot start an epic that touches this area until the risk is answered.
- **Carry forward** — record a decision, then fix it in the normal course of the build.

| Risk | Class | One line |
| --- | --- | --- |
| R1 Multi-tenancy by convention | Blocking | The largest correctness risk in the rewrite |
| R2 Time-gated state machine | Blocking | Gates make a fresh database look broken |
| R3 Accounting rules engine | Blocking | Must reproduce a stateful loop exactly |
| R4 EPPlus license | Carry forward | Lower than earlier drafts stated — see below |
| R5 Client enum duplication | Carry forward | Silent UI drift |
| R6 CORS wide open | Carry forward | Cheap fix, needs a recorded policy |
| R7 Hand-rolled SQL baseline | Blocking | Every migration tool needs this baseline |
| R8 Stubbed Proffix integration | Carry forward | Needs a compatibility ADR |
| R9 OGN writes direct SQL | Blocking | Constrains any schema reshape |
| R10 Bearer token, no refresh | Carry forward | Auth ADR must address it |
| R11 Vendored email lib | Carry forward | License now confirmed — see below |
| R12 Nav-bar tautology bug | Carry forward | Cosmetic, one line |
| R13 Job coverage breadth gap | Carry forward | Add tests before a paying tenant |
| R14 Suite depth gap | Blocking | The suite is not a parity oracle as it stands |

### R1 — Multi-tenancy enforced by convention
**Where:** `flsserver/src/FLS.Server.Service/BaseService.cs` (`CurrentAuthenticatedFLSUserClubId`);
every service that queries.

Every query goes through a service that *should* filter by `CurrentAuthenticatedFLSUserClubId`.
Nothing structural prevents a forgotten filter from leaking another club's data. The spec
`multi-tenant/isolation.spec.ts` is the only line of defense, and it covers a sampled subset. The
seed mandates structural enforcement in the new system. This is a sacred cow, restated here because
it is the single largest correctness risk in the rewrite.

**Answered when:** a test proves that a query which omits the tenant filter fails — at compile time,
or at the database. A code-review convention does not close this risk.

### R2 — Time-gated state machine, and the time unit is undocumented
**Where:** `flsserver/src/FLS.Server.Service/FlightService.cs`;
`flsserver/src/FLS.Server.Service/Jobs/DailyFlightValidationJob.cs`.

`FlightProcessState.Locked` requires ≥ 2 days of age. `DeliveryPrepared` requires ≥ 3 days past
lock. On a fresh database no flight reaches `Locked`, so delivery creation produces nothing and the
system looks broken. The e2e fixture works around this with backdated seed data
(`_test-fixture.sql` anchors timestamps to 2026-01-01). The new system must preserve the gates
**and** provide a way to test them without clock manipulation.

The unit and the boundary are not documented. See Q-B2 and Q-B3 in §9.

**Answered when:** the architecture names the time unit and the boundary rule, and a test drives a
flight across each gate at the boundary without changing the system clock.

### R3 — Accounting rules engine: parity-critical, customer-configurable
**Where:** `flsserver/src/FLS.Server.Service/Accounting/RuleEngines/DeliveryItemRulesEngine.cs`;
`flsserver/src/FLS.Server.Service/RulesEngine/RulesEngine.cs`.

`DeliveryItemRulesEngine` runs a stateful decrement loop over rule objects built from per-club
database configuration. Re-implementing this in a new language is the highest-risk item in the
rewrite. `DeliveryCreationTest` is the regression harness. The rewrite needs a much larger corpus in
CI than the repository commits today.

Rule ordering and the minimum decrement are undocumented, and the loop result depends on both. See
Q-B6 and Q-B7 in §9.

**Answered when:** a corpus of recorded legacy inputs and outputs runs against the new engine in CI,
and every case matches to the cent.

### R4 — EPPlus license boundary
**Where:** `flsserver/src/FLS.Server.Service/packages.config:9` and
`flsserver/src/FLS.Server.Tests/packages.config:7`.

**Verified 2026-08-24: the pinned version is EPPlus 4.5.3.1, which is LGPL.** The Polyform
Noncommercial license starts at EPPlus **5.0.0**, not "post v4.5". The legacy system is therefore
on a free version today, and the license risk is an accidental major upgrade, not the current state.
This risk is lower than earlier drafts of this document stated.

The Excel exporter ADR must still pick a library for the rewrite (EPPlus ≤ 4.5.3.1, OpenXML SDK, or
ClosedXML). Output-format compatibility with the Proffix sync is the parity constraint, and it is
the part that still needs an answer.

**Answered when:** the ADR names the library and its license, and a test compares an exported file
against a legacy-produced file for the fields the Proffix sync reads.

### R5 — `FlightStateMapper` enum duplication
**Where:** `flsweb/src/flights/FlightsServices.js:117-199` (`FlightStateMapper`).

`FlightStateMapper` hardcodes server enum values as JavaScript strings. It also **collapses** the
seven `FlightAirState` values into three UI buckets (`ready` / `inAir` / `landed`), and that mapping
is hand-written. Any drift between the two sides is a silent UI bug. The new system must derive
client-side state names from a single source: code generation from OpenAPI, shared TypeScript types,
or an equivalent.

**Answered when:** the client state names are generated from the server definition, and the build
fails if the two go out of step.

### R6 — CORS wide open
**Where:** `flsserver/src/FLS.Server.Web/App_Start/WebApiConfig.cs:25` —
`config.EnableCors(new EnableCorsAttribute(origins: "*", headers: "*", methods: "*"))`.

The new system must scope CORS to the real deployment origins. The fix is low cost. An ADR must
record the policy.

**Answered when:** an ADR states the allowed origins per environment, and the deployed configuration
matches it.

### R7 — Hand-rolled SQL migration baseline
**Where:** `flsserver/database/FLS/Updates/` (12 schema scripts, `v1.8.0` … `v1.8.11`, plus
`Data-Cleaning after v1.8.9.sql`); `flsserver/database/FLSTest/2 alter/2 Alter Database.sql`
(40-table base).

Any new migration tool (EF Core, FluentMigrator, DbUp, Flyway, Liquibase) needs a parity baseline
derived from the existing schema. The baseline snapshot is its own story. The data-cleaning script
is not a schema script, and the baseline must not fold it in.

**Answered when:** the baseline reproduces the production schema exactly, proven by a structural
diff against a database built from the 13 legacy scripts.

### R8 — Stubbed Proffix integration in-repo
**Where:** `flsserver/src/FLS.Server.ProffixInvoiceService/` — three files only
(`FLS.Server.ProffixInvoiceService.csproj`, `packages.config`, `Properties/AssemblyInfo.cs`).

The project holds assembly metadata and nothing else. The real sync is the external
`PROFFIX-FLS-Sync` repository. The new system must serve a Proffix-compatible API, or coordinate a
rebuild on the sync side. The seed puts the sync itself out of scope, but a compatibility ADR is
required.

**Answered when:** an ADR states which `/api/v1/deliveries/*` responses stay contract-stable, and the
sync maintainer has confirmed the list.

### R9 — OGN inbound contract is direct DB writes
**Where:** external — [sgacond/OGNAnalyser](https://github.com/sgacond/OGNAnalyser). No code in
this repository defines the contract; the schema itself is the contract.

OGNAnalyser writes flights straight to the SQL schema. Replacing the schema (R7) means either
preserving the schema for OGN, or providing a new ingestion endpoint and updating OGNAnalyser. A
different person and repository own the second option.

Direct writes also bypass every application invariant. See Q-B10 and Q-B11 in §9.

**Answered when:** either the OGN-facing tables keep their shape under a documented compatibility
contract, or the OGNAnalyser maintainer has agreed to an ingestion endpoint in writing.

### R10 — OAuth bearer with no refresh, no 401 interceptor
**Where:** `flsserver/src/FLS.Server.Web/Providers/FLSOAuthAuthorizationServerProvider.cs`;
`flsweb/src/core/AuthService.js`.

Tokens last 14 days, live in sessionStorage only, and never refresh. The client has no global 401
handler. Recovery depends on the next route change re-running the `userAuth` resolve guard, so a
401 on a write with no following route change loses the user's input silently. See Q-B13 in §9.

The new auth scheme (OIDC, sliding refresh, or short-lived JWT plus refresh token) must address this
in the auth ADR.

**Answered when:** the ADR defines the token lifetime, the refresh path, and the 401 handling — and a
test proves an expired token during a write produces a recoverable state, not a lost write.

### R11 — Vendored email-templating lib (`Alpinely.TownCrier`)
**Where:** `flsserver/src/Alpinely.TownCrier/`.

**Verified 2026-08-24: the vendored tree carries `COPYING.txt` and `COPYING.LESSER.txt` — it is
LGPL.** The license question is closed. The upstream origin and the local delta against it are still
unrecorded, and that is the part that remains open.

Replace the library with a maintained equivalent during the rewrite, or extract the templates first
to make the email rewrite tractable.

**Answered when:** the email templating library for the new system is chosen and its license
recorded, and every legacy template is extracted into a format the new library reads.

### R12 — `||` tautology bug in nav-bar visibility (`index.js:50`)
**Where:** `flsweb/src/index.js:50` —
`AuthService.setShowNavBar($location.path() !== '/tryflight' || $location.path() !== '/passengerflight')`.
The two conditions can never both be false, so the expression is always `true`.

The bug is cosmetic but real. The new system must hide the nav bar on public routes by a real
mechanism — a route flag, a layout slot, or an equivalent — instead of a boolean expression.

**Answered when:** the public routes render without the nav bar, and a test asserts it per route.

### R13 — Test coverage breadth gaps in scheduled jobs
**Where:** `flsserver/src/FLS.Server.Service/Jobs/DeliveryMailExportJob.cs`,
`AircraftStatisticReportJob.cs`, `AircraftDatabaseSyncJob.cs`.

No spec covers these three jobs. They are the jobs that are hardest to verify once a tenant runs on
the new stack. Add e2e or integration tests before they reach a paying tenant, or the jobs ship
unverified.

**Answered when:** each of the three jobs has a test that drives it end-to-end and asserts its
output.

### R14 — Test coverage depth: the suite is not a parity oracle
**Where:** `e2e/tests/` — 43 specs in 12 categories.

The suite proves that features exist and reach the database. For most features it does not prove
behavior.

**What the suite does probe** (corrected 2026-08-24 — earlier drafts of this document said
"happy-path only", which understated it):

- `flights/state-transitions.spec.ts` asserts `Valid → ExcludedFromDeliveryProcess → Valid` and
  `Invalid → Valid` through `/api/v1/flights/validate`.
- `flights/locking-workflow.spec.ts` drives `Valid → Locked` through
  `/workflows/flightvalidation`.
- `api/contract.spec.ts` asserts response shapes across the API, and includes at least one rejection
  path (`POST /Token` with bad credentials returns 400).
- `accounting/rules-engine-per-type.spec.ts` asserts per-rule-type behavior, including
  `DoNotInvoiceFlight` short-circuiting the pipeline.
- `email/notifications.spec.ts` asserts eight distinct emails against Mailpit.

**What the suite does not probe:**

- **Validation rejection paths on flights.** Invalid crew composition, missing required fields,
  out-of-range timestamps, conflicting glider and tow assignments. These return 400 in production.
  No spec asserts them.
- **Illegal state transitions.** The matrix at `FlightService.cs:1375-1444` rejects most pairs. No
  spec asserts a rejection. `DeliveryPreparationError`, `DeliveryBooked`, and the reset from
  `DeliveryPrepared` back to `Locked` are untouched.
- **Time-gate boundaries.** The fixture backdates timestamps so the gates are reachable. No spec
  probes one second before or after a gate.
- **Glider ↔ tow link integrity.** Partial updates on a glider flight that has a `TowFlightId` are
  untested, and the cascade behavior on the tow row is unverified.
- **Permission boundaries.** Pilot, flight operator, club admin, and unauthenticated are not
  asserted per endpoint. `multi-tenant/isolation.spec.ts` covers `ClubId` leakage on a sampled
  subset only.
- **Concurrent edits.** Two clients writing the same `Flight` inside the EF6 round-trip window.
- **Rules-engine combinations.** Decrement-loop interactions, multi-stage rule chains, recipient
  overrides, and recursion through `TowFlightId` — the parity-critical parts per R3.

**A further caveat.** Nine of the 43 specs (`*-parity-J*.spec.ts`, `*-fanout-J*.spec.ts`) are
rebuild-1 journey artifacts that capture legacy screens on video and assert lightly.
`flights/flights-parity-J2.spec.ts` carries `@quarantine-legacy` and does not run in a default pass.
The effective behavior-coverage count is therefore below 43.

**Implication for the rewrite.** The suite cannot serve as a parity oracle as it stands. Either
expand it *before* cutting over — preferred, because the existing specs are cheap to extend — or
validate parity another way: manual UAT, traffic replay, or a dual run with a diff. The expansion is
a story in its own right, and the backlog must sequence it early.

**Answered when:** every item in the "does not probe" list above has a spec, or the PRD records an
explicit decision to validate that item by another named method.

## 8. Open product questions

*(Historical. These questions drove the rebuild-1 vision elicitation in May 2026. Rebuild 1's
answers are archived in
[`../attempt-1/02-vision-and-constraints.md`](../attempt-1/02-vision-and-constraints.md). **No
answer in this section binds rebuild 2.** Rebuild 2 re-answers every question in its own PRD. The
list stays here as a record of what the discovery phase surfaced.)*

These are **product and strategy** questions. §9 holds the **behavioral** questions — gaps in the
legacy specification that the rewrite must close before it writes domain code.

- **Performance targets.** What page-load and request-latency budgets must the new system hit? (Current system has none recorded.)
- **Availability target.** What uptime SLO are we committing to? What's the current observed availability?
- **Migration / onboarding window.** What is the acceptable per-tenant migration time? (Rebuild 1
  answered this on 2026-05-17: per-tenant self-service, ≤ 30 min for a typical legacy database, no
  centralized cutover. **That answer is not binding on rebuild 2.** Rebuild 2 re-answers it in its
  own PRD, like every other question in this list.)
- **Hosting target.** Production today is presumably IIS on Windows; is the new system constrained to Windows, allowed on Linux, or required to run in a specific cloud?
- **Budget / team shape.** Solo developer? Team? What languages does the team know — does that pin the backend ADR?
- **Compliance.** Any regulatory obligation (GDPR retention, Swiss data residency, FOCA aviation rules) the new system must demonstrably satisfy?
- **Internationalization.** Server-loaded translations stay, or move to client-bundled? Add languages?
- **Reporting / Excel parity.** Are existing Excel exports byte-for-byte parity-required (e.g., because Proffix sync parses them), or just feature-equivalent?
- **OGN integration.** Keep direct-DB ingestion, or are we willing to negotiate an ingestion API with the OGNAnalyser maintainer?
- **DB scope.** Seed makes the DB conditionally in scope. Confirm: is reshape allowed if we have a migration path, or must the new system run against the existing schema unchanged for a parallel period?
- **Auth migration.** Are existing user sessions / tokens expected to survive the move to the new stack, or are users re-prompted to log in on first visit?
- **Test-corpus expansion for the rules engine.** What's the budget (time / data) for building the `DeliveryCreationTest` corpus?
- **Out-of-scope features.** Are there features in §2 that we're explicitly **not** porting (deprecating)?

---

## 9. Open behavioral questions

§8 holds product and strategy questions. This section holds the **behavioral** gaps: places where
the legacy system has a rule, but no document in this repository states it. Every one blocks a
domain decision. Answer each with the `legacy-oracle` agent against `flsserver/` before the epic that
touches it starts.

| Id | Question | Blocks |
| --- | --- | --- |
| Q-B1 | Which validation failure sets `FlightProcessState.Invalid`, and which leaves the flight `NotProcessed`? | Flight state machine |
| Q-B2 | Is the ≥ 2-day lock gate counted in calendar days, business days, or 48 hours? | Flight state machine, R2 |
| Q-B3 | Is a gate boundary inclusive, and in which timezone is it evaluated? `WorkflowService.Run()` dispatches by UTC hour, but the gates are in days. | Flight state machine, R2 |
| Q-B4 | What happens to a tow row when its glider flight is deleted — restrict, cascade, or null-out? | Data model, R7 |
| Q-B5 | What stops `TowFlightId` validation recursion on a self-reference or a cycle? | Flight validation |
| Q-B6 | When two accounting rules match the same `ActiveFlightTime`, which one applies first? | Rules engine, R3 |
| Q-B7 | Can a rule decrement `ActiveFlightTime` by zero, and what stops the loop if it does? | Rules engine, R3 |
| Q-B8 | Which club's tenant filter admits a flight whose crew member belongs to a different club? | Multi-tenancy, R1 |
| Q-B9 | Same question for `Delivery.RecipientPersonId`. | Multi-tenancy, R1 |
| Q-B10 | What validates an OGN row that breaks a state-machine or tenancy invariant, given OGN writes direct SQL? | Ingestion, R9 |
| Q-B11 | What resolves a conflict when OGN and a user write the same `Flight` at the same time? | Ingestion, R9 |
| Q-B12 | What is the default when a mutating endpoint has no cache-invalidation interceptor? | Client caching |
| Q-B13 | What should happen on a 401 during a write, when no route change follows? | Auth, R10 |
| Q-B14 | What does a club see before it has any location or aircraft? Planning days key on `(location, date)`. | Onboarding |
| Q-B15 | What renders when a `LanguageTranslation` row is missing, or `/api/v1/translations` fails? | i18n |
| Q-B16 | What rate limit or abuse control protects the two unauthenticated registration endpoints? | Public flows |

---

## Document inventory

| Item | Count |
| --- | --- |
| Features inventoried | 52 rows across 8 domains |
| Integrations | 4 inbound, 3 outbound |
| Risk hotspots | 14 (R1–R14); 5 blocking, 9 carry-forward |
| Open product questions (§8) | 13 |
| Open behavioral questions (§9) | 16 |
| E2E specs | 43 in 12 categories; 9 are rebuild-1 journey artifacts |

**Next step:** `bmad-prd`. This document passed `bmad-review` on 2026-08-24.
