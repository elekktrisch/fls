# Modernization Seed — AlpenFlight (FLS → rewrite)

Project-specific context that every phase of the modernization workflow reads first. Generic skills + this seed = an AlpenFlight-tailored plan.

## What we're modernizing

The Flight Logging System (FLS), a multi-tenant SaaS for glider and motor flight operations: reservations, flight logging, planning, accounting/invoicing exports, email workflows. Two codebases:

- **`flsserver/`** — ASP.NET Web API on .NET Framework 4.5, EF6 Code First, OWIN bearer-token auth, Unity DI, SQL Server. Business logic concentrated in `FLS.Server.Service`.
- **`flsweb/`** — AngularJS 1.4 SPA, Webpack 1, Babel ES2015, Node 8-era toolchain, Karma + Jasmine. Bootstrapped manually from `index.js`.

The existing docs at the repo root are authoritative reading before any phase runs:

- [`CLAUDE.md`](../../CLAUDE.md) — repo overview, build commands, project layout.
- [`../legacy/server.md`](../legacy/server.md) — server mental model: workflow-via-HTTP+cron, two-dim flight state machine, rules engine, multi-tenancy convention, user/person split.
- [`../legacy/web.md`](../legacy/web.md) — client mental model: ngRoute resolve-guard auth, `$http.defaults` token attachment, per-action cache invalidation, feature module map.
- [`TESTING.md`](../../TESTING.md) — Playwright e2e harness; the e2e suite is the most reliable feature inventory available.

## Strategic anchors (do not re-litigate)

- **Strategy:** greenfield rewrite. Not strangler-fig, not in-place upgrade. New code is written from scratch in sibling folders.
- **Coexistence model:** AlpenFlight is a self-service SaaS — each legacy FLS deployment onboards independently via the export-JAR + UI-upload flow (epic E-15), on its own schedule. There is no centralized cutover event. Old and new run side-by-side per-tenant until that tenant uploads and is provisioned. No reverse-proxy bridge, no shared session, no shared DB writes.
- **Repo layout:** new code lives under a single top-level subtree, working slug `next/` (rename to `alpenflight/` tracked by S-152), with sub-folders:
  - `next/server/` — Spring Boot service
  - `next/web/` — Angular frontend
  - `next/database/` — Flyway migrations, seed data, test fixtures
  - `next/auth/` — Keycloak realm exports + IdP config artifacts
  - `next/ops/` — `docker-compose.yml`, Caddyfile, deploy scripts
  - `next/migration-bundle/` — schema-mapping library shared by the export JAR and the server ingest pipeline
  - `next/migration-tool/` — the legacy export JAR (single-file Java fat-jar)
  
  The subtree sits sibling to the existing `flsserver/`/`flsweb/` folders inside this repo.
- **Database:** in scope **only** if a clean data-migration path exists. If reshape is required, the ADR for it must propose the mapping tooling and how to validate parity (the parity oracle in CI is the live mechanism).
- **Artifacts:** markdown only for now. The workflow does not push to GitHub Issues until stories exist that we want to track.

## Decisions the workflow *will* make (ADR candidates)

Each of these is an ADR, not an anchor. The workflow surfaces tradeoffs and the user picks.

- Backend language and framework.
- Frontend framework + build tool + state management.
- Database engine and migration tooling.
- Auth scheme (current is OAuth2 bearer with 14-day tokens; replacement might be OIDC, sliding refresh, etc.).
- Hosting + deployment model (current is presumably IIS on Windows).
- Observability (logging, metrics, tracing).
- Internationalization (current is server-loaded `angular-translate` via `/api/v1/translations` against a DB table).
- Reporting / Excel export (current uses EPPlus + Ionic.Zip — EPPlus license is now Polyform Noncommercial past v4.5).
- API shape (REST as today vs. GraphQL vs. tRPC-style).
- Background-job mechanism (current is OS cron → HTTP endpoint → in-process dispatch via `WorkflowService.Run()`).

## Sacred cows (must survive the rewrite)

If any of these break, the rewrite is a failed rewrite. The workflow treats them as constraints that ADRs must respect.

- **Multi-tenancy.** Every domain entity carries a `ClubId`. Tenant isolation is currently enforced by convention (`CurrentAuthenticatedFLSUserClubId` on every query). The new system must enforce this *structurally* (row-level security, schema-per-tenant, query-level guard, etc.) — convention-only is no longer acceptable.
- **The flight state machine.** Two-dimensional: `FlightAirState` (computed from timestamps) × `FlightProcessState` (workflow-driven, transitions per `../legacy/server.md` §2). The new system must preserve the process states and their time-gating semantics (≥2 days to lock, ≥3 days to bill).
- **User / Person separation.** Login principal (`User`, one `ClubId`) is distinct from human (`Person`, can be member of multiple clubs via `PersonClub`). The new system must keep this split — collapsing them breaks pilot rosters at multi-club sites.
- **Accounting rules engine.** The decrement-loop pipeline described in `../legacy/server.md` §3 (`FlightTime` rules iteratively consume `ActiveFlightTime` and emit `DeliveryItem`s). Customers configure this; behavior must be bit-exact or migration breaks every invoice. `DeliveryCreationTest` (the regression harness) is how parity gets validated.
- **OGN integration.** Inbound flights from the [OGNAnalyser](https://github.com/sgacond/OGNAnalyser) project are written directly to the DB. The new system must accept the same inbound contract or the OGN side must be replaced too.
- **Proffix integration.** Outbound deliveries are consumed by [PROFFIX-FLS-Sync](https://github.com/arminstutz/PROFFIX-FLS-Sync) via the public API. Either the new system serves a compatible API or Proffix sync gets rebuilt.

## Out of scope

- Modifying OGNAnalyser or PROFFIX-FLS-Sync. They are separate projects with separate ownership; per-tenant handoff coordinates with their maintainers (S-149, S-150).
- Rewriting feature behavior. The rewrite is faithful to current functionality unless an ADR explicitly opts in to a behavior change.
- Mobile clients. None exist today; none in scope (PWA per ADR 0014 covers the mobile-grade UX directive).
- Coordinated decommissioning of legacy `flsserver/` / `flsweb/`. Each tenant turns off its own legacy server when ready post-migration — it's an operator concern per tenant, not a modernization-workflow story.

## Domain glossary

| Term | Meaning |
|---|---|
| Club | Tenant. Every user belongs to exactly one. |
| Person | Human record. May belong to multiple clubs via `PersonClub`. |
| Flight | Single domain entity covering glider, tow, and motor flights (discriminated by `FlightAircraftType`). |
| Planning Day | (location, date) pair with assigned instructor / tow pilot / flight operator and the day's reservations. |
| Delivery | Invoice draft produced by the rules engine. Transitions: `Prepared` → `Booked` (booked = terminal). |
| Accounting Rule Filter | DB-stored config that the rules engine instantiates at runtime as `Rule` objects. |
| Workflow | Scheduled batch job. Triggered by OS cron hitting `/api/v1/workflows/<name>`, **not** by an in-process scheduler. |
| FlightAirState | Computed physical state of a flight (in-air / landed / ...). Never authoritatively stored. |
| FlightProcessState | Stored administrative state of a flight. Drives billing eligibility. |

## Known integration & risk hotspots

The workflow's discovery phase should treat these as known, not re-derive them:

- **EPPlus license change.** v4.5+ is Polyform Noncommercial. If we're commercial, the new Excel export needs a different lib (OpenXML SDK, ClosedXML, etc.) — this is its own ADR.
- **`FlightStateMapper`** in `flights/FlightsServices.js:117-199` duplicates the server's `FlightProcessState` enum as JS strings. Drift here is a silent UI bug. New system must derive these from a single source.
- **`||` tautology bug** in `index.js:50` (`setShowNavBar(p !== '/tryflight' || p !== '/passengerflight')` is always true). Worth flagging as test coverage when the new public flows are built.
- **CORS is fully open** (`origins: "*"`) in `WebApiConfig`. The new system must scope this properly.
- **Hand-rolled SQL update scripts.** Production schema is driven by `database/FLS/Updates/DBUpdate_v*.sql`, not by EF migrations. Any new migration tool needs a parity baseline from these scripts.
- **`FLS.Server.ProffixInvoiceService`** is a stub directory. Live integration lives in a separate repo.
- **OAuth bearer with 14-day lifetime, no refresh.** Client stores in sessionStorage; no proactive refresh; no global 401 interceptor. Drift between client cache and server token expiry is invisible until next route change.
- **Vendored libs.** `Alpinely.TownCrier` (email templating) and `ObjectHydrator-master` (test data) are checked-in copies, not NuGet refs. Replace with packaged equivalents.
