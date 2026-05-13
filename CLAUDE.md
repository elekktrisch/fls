# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Layout

This directory bundles two independent git repositories of the Flight Logging System (FLS), a multi-tenant system for managing glider and motor flight operations (reservations, flight logging, planning, accounting/invoicing exports, email workflows):

- `flsserver/` — ASP.NET Web API backend (.NET Framework 4.5, C#). Web API + business services + Entity Framework data layer + SQL Server database scripts.
- `flsweb/` — AngularJS 1.4 single-page client (ES2015, built with Webpack 1, tested with Karma + Jasmine).

The two are versioned and released independently. The client talks to the server over `/api/v1/...` and `/Token` (OAuth bearer).

## flsserver (C# / .NET 4.5)

See @SERVER.md for more details

### Build & Test

Solution: `flsserver/src/FLS.sln`. The build targets full .NET Framework 4.5 on Windows (MSBuild + NuGet), not .NET Core — `dotnet build` will not work for the Web/Service projects (they reference `System.Web`, EF6, OWIN, ASP.NET Web API 2, Unity). Use:

- `nuget restore src/FLS.sln` then `msbuild src/FLS.sln /p:Configuration=Debug` (or `Release` / `Demo` / `Test` — four solution configurations exist).
- Tests use MSTest (`Microsoft.VisualStudio.TestTools.UnitTesting`, `[TestClass]` / `[TestMethod]`). Run via `vstest.console.exe src/FLS.Server.Tests/bin/Debug/FLS.Server.Tests.dll /Settings:src/FLS.Server.Tests.runsettings` or from Visual Studio's Test Explorer. A single test: `vstest.console.exe ... /Tests:FullyQualifiedTestName`.

### Database

Schema is **not** managed by EF migrations alone — versioned SQL update scripts live in `flsserver/database/FLS/Updates/DBUpdate_v<version>.sql` and `flsserver/database/FLSTest/` (with subfolders `1 create`, `2 alter`, `3 insert`). When making schema changes, add a new `DBUpdate_v*.sql` rather than editing existing ones.

Default connection string (in `FLS.Server.Web/Web.config`) points at `.\SQLExpress` / `FLSTest` database with integrated auth — adjust for the local environment.

### Project map (under `flsserver/src/`)

- `FLS.Server.Web` — ASP.NET Web API host. Entry points in `App_Start/` (`WebApiConfig`, `UnityConfig` for DI, `Startup.Auth` for OAuth bearer token auth). Controllers in `Controllers/` are thin and delegate to services. Uses **Unity** for DI; services are wired in `UnityConfig.RegisterTypes`. CORS is open globally (`*`).
- `FLS.Server.Service` — business logic. One service class per domain (`FlightService`, `AircraftService`, `PersonService`, `PlanningDayService`, `WorkflowService`, …). Subnamespaces of note: `RulesEngine/` (accounting/invoice rule evaluation, see also `InvoiceRuleFilters.xlsx`), `Jobs/` (scheduled jobs invoked via the workflow activator: `DailyFlightValidationJob`, `DeliveryCreationJob`, `DeliveryMailExportJob`, planning-day notifications, …), `Email/` (per-domain `*EmailBuildService` + `EmailSendService`), `Exporting/`, `Accounting/`, `Reporting/`, `Identity/`.
- `FLS.Server.Data` — Entity Framework 6 (Code First) layer. `FLSDataEntities.cs` is the `DbContext`. POCO entities live in `DbEntities/`, fluent mappings in `Mapping/`, EF migrations in `Migrations/` (but production schema is driven by the SQL scripts above), enums in `Enums/`.
- `FLS.Data.WebApi` — DTOs / contracts shared with the client. Organized by domain (`Flight/`, `Aircraft/`, `Person/`, `PlanningDay/`, …). Controllers exchange these, not the EF entities directly.
- `FLS.Server.Interfaces` — service interfaces (kept separate so non-Web hosts can reference them).
- `FLS.Common` — utilities (converters, helpers) shared across the solution.
- `FLS.Workflow.Activator` — small console app that POSTs to workflow endpoints on the Web API (`flightvalidation`, `dailyreport`, `monthlyreport`, `planning`, `testmail`, `deliverycreation`, `deliverymailexport`). Triggered by an external scheduler.
- `FLS.Server.ProffixInvoiceService` — adapter for the Proffix accounting system (separate Sync Interface project exists outside this repo).
- `Alpinely.TownCrier` — vendored email/templating helper.
- `ObjectHydrator-master/Foundation.ObjectHydrator` — vendored test-data generator used by `FLS.Server.Tests`.
- `FLS.Server.Tests` — MSTest suite. `BaseTest.cs` is the shared fixture; subfolders: `ServiceTests/`, `WebApiControllerTests/`, `FLSCommonTests/`, `Mocks/`, `TestData/`. Test settings in `FLS.Server.Tests.runsettings`.

### Architectural conventions

- Controller → Service → DataAccessService/DbContext. Don't bypass services from controllers.
- DI is via Unity (`UnityConfig.cs`), not Microsoft.Extensions.DependencyInjection. Register new services there.
- API auth is OAuth2 bearer tokens (`/Token` endpoint, `Startup.Auth.cs`). `SuppressDefaultHostAuthentication` is set, so endpoints rely on the `[Authorize]` filter + bearer.
- Multi-tenancy: most entities carry a `ClubId`; check existing services for the standard "filter by current user's club" pattern before adding new queries.
- DTOs in `FLS.Data.WebApi` and entities in `FLS.Server.Data.DbEntities` are kept separate on purpose — don't leak EF entities through controllers.

## flsweb (AngularJS 1.4 / Webpack 1 / Node)

See @CLIENT.md for more details

### Commands (run from `flsweb/`)

- `yarn install` — install deps (note: pinned to legacy versions; expect a Node 8-era toolchain — `.travis.yml` uses Node 8).
- `yarn start` — Webpack dev server on `http://localhost:3000`, proxying `/Token` and `/api/*` to `http://localhost:25567/` (local flsserver).
- `yarn start-test` — same, but proxy to `https://testapi.glider-fls.ch/`.
- `yarn start-prod` — same, but proxy to `https://api.glider-fls.ch/`.
- `yarn run bundle` — production build into `dist/` (Uglify, no source maps).
- `yarn test` — Karma + Jasmine single run in headless Chrome (`FlsChromeHeadless` launcher with `--no-sandbox --headless --disable-gpu`).
- `yarn run test-dev` — Karma in watch mode, opens a real Chrome window. Single spec: edit `src/index.spec.js` (the Karma entry) to import only the spec(s) you want, or use Jasmine's `fdescribe` / `fit`.
- `node server/index.js` (or `start-mock-server.cmd`) — Express mock API on the side, serves JSON fixtures from `flsweb/server/mock-data/`. Useful when the C# backend isn't running.

### Project map (under `flsweb/src/`)

`index.js` bootstraps the `app.starter` Angular module and pulls in one module per feature area. Each feature lives in its own folder with a `*Module.js` (Angular module + routes) plus controllers/services/templates:

- `core/` — shared services and directives (`AuthService`, `MessageManager`, `TimeService`, `Constants`/`GLOBALS`, `tableFilters/`, `directives/`).
- `main/` — shell + dashboard.
- `masterdata/` — CRUD for aircrafts, clubs, persons, users, locations, flight types, accounting rules, deliveries, member states, person categories.
- `flights/` — flight editing/listing, including `flights/airmovements/`.
- `planning/` — planning day UI (instructor/tow-pilot/operator assignments).
- `reservations/` + `reservation-scheduler/` — aircraft reservations and the calendar/scheduler view.
- `reporting/` — flight reports.
- `tryflight/`, `passengerflight/` — public registration flows (no auth, hide nav bar — see the `$routeChangeSuccess` handler in `index.js`).
- `lostpassword/`, `confirm/`, `profile/`, `system/` — auth + account flows + system admin.
- `vendor/`, `styles/`, `lib/` — vendored libs, less/css, helpers.

### Conventions

- ES2015 modules + `babel-preset-es2015`. `import` / `export default`. Angular registration is done in each `*Module.js`.
- Server URL is injected via the `GLOBALS` constant (`core/CoreModule.js` → `core/Constants.js`, `BASE_URL: '../..'`) and used to prefix API calls and translation loader (`/api/v1/translations`).
- i18n via `angular-translate` with the URL loader against `BASE_URL + '/api/v1/translations'`. Default language `de`.
- Tests are colocated `*.spec.js` files; the global Karma entry is `src/index.spec.js`.

## Cross-cutting

- The web client expects the API at the same origin (it uses relative `../..` as `BASE_URL`) — in dev this works because the Webpack dev server proxies `/api/*` and `/Token`. Don't hardcode absolute server URLs in client code.
- When adding a new domain entity end-to-end you typically need to touch: SQL update script → EF entity + mapping (`FLS.Server.Data`) → DTO (`FLS.Data.WebApi`) → service (`FLS.Server.Service` + interface + Unity registration) → controller (`FLS.Server.Web/Controllers`) → Angular feature module under `flsweb/src/`.
- Architecture diagrams and form-design PDFs are in `flsserver/doc/` (`Application-Architecture.png`, `System-Übersicht.png`, `Flight Process States.vsdx`, `Proffix-Interface-Processes.vsdx`, accounting/invoice rule editor PDFs). Consult these before redesigning a workflow.
