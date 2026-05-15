# CLIENT.md — How flsweb Actually Works

Companion to `CLAUDE.md` (surface map) and `SERVER.md` (server-side mental model). This is the AngularJS 1.4 client at `flsweb/`. All file paths are relative to `flsweb/src/` unless noted. Line numbers reflect state at the time of writing; treat as approximate after edits.

## The client in one sentence

A vintage AngularJS 1.4 SPA, built with Webpack 1 + Babel ES2015 and bootstrapped manually from `index.js`, that authenticates against the server's `/Token` endpoint, stores the bearer token in `$sessionStorage`, attaches it once globally to `$http.defaults`, and uses **ngRoute** + per-route `resolve: { user: userAuth }` guards to gate authenticated areas. Feature modules (flights, planning, reservations, masterdata, reporting, etc.) each ship their own routes, controllers, directives, and `$resource`-based API services.

## Five load-bearing insights

### 1. Bootstrap is manual, not via `ng-app`

`index.js:54` calls `angular.bootstrap(document, ['app.starter'])` directly — there's no `ng-app` attribute in `index.html`. The `app.starter` module (`index.js:22-39`) aggregates 14 feature modules plus `angular-translate`. `index.html:85` puts `ng-controller="AppController"` on `<body>` and conditionally renders `<fls-navigation-bar>` based on `showNavBar()` (`index.html:88`).

### 2. ngRoute + per-route resolve guard — no global auth interceptor

The router is **ngRoute** (`$routeProvider`), not ui-router. Each feature module's `*Module.js` defines its own routes in `.config(($routeProvider) => ...)`.

Auth gating is done **per route** with a resolve key:

```js
$routeProvider.when('/flights', {
    resolve: { user: userAuth },   // ← the gate
    titleKey: 'STARTLIST',
    ...
});
```

`userAuth` is exported by `AuthService` (`core/AuthService.js:141-149`, `:192-194`). It runs synchronously on route change, checks `getToken()` and optionally `isClubAdmin()` / `isSystemAdmin()`, and if any check fails it calls `promptLogin(path)` which redirects to `/main` and overlays the login form. There is **no global `$httpInterceptor`** that handles 401s — failed requests show in the message bar via `MessageManager`, but the user isn't auto-logged-out on a 401. Stale-token recovery relies on the next route change re-running `userAuth`.

Public routes either omit `resolve` entirely or set `publicAccess: true`. Public routes confirmed: `/main`, `/trialflight`, `/passengerflight`, `/lostpassword`, `/confirm`.

### 3. Bearer token is attached once globally, not per request

Login flow (`core/AuthService.js`):

1. `POST /Token` with `grant_type=password, username, password` form-encoded (`:63-64`).
2. Response stored in `$sessionStorage.loginResult` (`:5`, `:68`) — **session storage only, no persistence across browser restarts, no refresh-token mechanism**.
3. Three follow-up GETs to hydrate the user: `/api/v1/users/my`, `/api/v1/userroles`, `Clubs.getMyClub()`.
4. Token attached globally via `$http.defaults.headers.common.Authorization = 'Bearer ' + access_token` (`:183`, also re-attached at init `:186`).
5. Redirect to the path that triggered the login, or `/dashboard` (`LoginFormDirective:28`).

Logout (`:100-108`) clears session storage, clears the `$http` cache, redirects to `/main`. Token lifetime is server-controlled (14 days per `SERVER.md` notes); the client has no proactive refresh.

**Gotcha:** because the token sits on `$http.defaults`, anything that bypasses `$http` (raw `fetch`, third-party widgets) won't carry it. Everything in this codebase goes through `$http`/`$resource`, so this is fine in practice.

### 4. Mixed `$http` + `$resource` API access, with per-action cache invalidation

There is no single "ApiService" wrapper. Two patterns coexist:

- **`$resource`** for declarative REST endpoints. Example: `Aircrafts` (`flights/AircraftsServices.js:34-60`) wraps `/api/v1/aircrafts/listitems/:dest` with `getGliders()`, `getTowingPlanes()` etc., all cached. Mutating actions (POST/DELETE) attach a **per-action `interceptor: { response: () => invalidate(...) }`** (`AircraftsServices.js:83-87`) that clears the relevant `$http` cache entries. There is no global cache-bust strategy — each service does its own.
- **Raw `$http`** for paginated POST queries that take filter/sorting payloads. Example: `PagedFlights.getGliderFlights()` does `$http.post('/api/v1/flights/gliderflights/page/:start/:size', {filter, sorting})` (`flights/FlightsServices.js:9-17`).

Error handling pattern: chain `.catch(_.partial(MessageManager.raiseError, 'load', 'flights list'))`. `MessageManager` (`core/MessageManager.js`) holds an observable list of messages, rendered by a message-bar directive, and clears itself on `$routeChangeStart` (`:47-50`).

### 5. i18n is server-loaded, not bundled

`angular-translate` is configured with the URL loader against `GLOBALS.BASE_URL + '/api/v1/translations'` (`index.js:42-44`). Default language is German (`'de'`). `useLoaderCache: true` means translations are fetched once per session. `useSanitizeValueStrategy(null)` is set — translations may contain HTML.

Routes contribute their `titleKey` in `resolve` locals (e.g. `STARTLIST`), which `NavigationBarDirective` reads on `$routeChangeSuccess` and pipes through `$translate` to set the page title.

Implication: translation changes don't require a client rebuild — they live in the server DB and are served by `TranslationsController`.

## App shell map

```
index.html
└─ <body ng-controller="AppController">
   ├─ <fls-navigation-bar ng-if="showNavBar()">   ← LoginFormDirective lives inside
   └─ <ng-view>                                     ← route-rendered feature
```

- `AppController.js` — trivial; just binds `$scope.showNavBar` to `AuthService.showNavBar`.
- `core/CoreModule.js` — assembles the cross-cutting services (see catalog below) and depends on `ngRoute`, `ngResource`, `ngCookies`, `ngStorage`, `pascalprecht.translate`.
- `main/MainController.js` + `main/main.html` — the landing page (public). `/main` route.
- `main/dashboard/` — the post-login dashboard. `/dashboard` route, auth-gated.

## Core services catalog (`core/`)

Frequently-used (touch most features):

| Service                       | Purpose                                                                                            |
| ----------------------------- | -------------------------------------------------------------------------------------------------- |
| `AuthService`                 | Token lifecycle, login/logout, `userAuth` resolve guard, `isClubAdmin`/`isSystemAdmin`, `showNavBar` toggle. |
| `MessageManager`              | Error and toast queue, cleared on every route change.                                              |
| `TimeService`                 | HH:mm ⇄ decimal ⇄ seconds conversions for flight durations.                                        |
| `TimerSet`                    | Start/end time + duration coordination on flight edit forms.                                       |
| `TableSettingsCacheFactory`   | Per-table filter/sort/page persistence in local storage.                                           |
| `DropdownItemsRenderService`  | Selectize.js item templates for person/starttype/flighttype dropdowns.                             |

Niche / dropdown master data (all cached `$resource`s):

| Service                  | Endpoint                                            |
| ------------------------ | --------------------------------------------------- |
| `Countries`              | `/api/v1/countries/listitems`                       |
| `StartTypes`             | `/api/v1/starttypes/listitems`                      |
| `CounterUnitTypes`       | `/api/v1/counterunittypes/listitems`                |

Utilities:

| Service                | Purpose                                                  |
| ---------------------- | -------------------------------------------------------- |
| `StringUtils`          | Case-insensitive substring search.                       |
| `NavigationCache`      | Stores "cancelling location" between route transitions.  |
| `AuditLogService`      | Loads audit/history records for an entity.               |

`core/directives/` — ~14 reusable directives including the login form, nav bar, message bar, date/time pickers, data table, history viewer, searchbar, tree.

`core/tableFilters/` — reusable filter dropdowns used by ng-table columns.

## Feature module map

Each feature folder follows the same shape: a `*Module.js` (Angular module + `$routeProvider.when()` calls), one or more controllers, one or more services (paginated `$http` + `$resource`-based detail/CRUD), edit directives, and `.html` templates.

### `flights/` + `flights/airmovements/` — the heart

| File                                              | Role                                                                                     |
| ------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| `FlightsController.js`                            | Orchestrates list view + form for glider/tow flights.                                    |
| `FlightsServices.js`                              | `Flights` ($resource), `PagedFlights` ($http POST to `/flights/gliderflights/page`), `FlightStateMapper` (`:117-199`). |
| `GliderFormDirective.js`, `TowFormDirective.js`   | Form fragments — same `Flight` entity, different fields rendered.                        |
| `AirStateFilterDropdownDirective.js`              | List-view filter on `FlightAirState` (computed: ready/inAir/landed).                     |
| `ProcessStateFilterDropdownDirective.js`          | List-view filter on `FlightProcessState` — maps the server's state machine to UI labels. |

State constants are duplicated client-side as strings: `NOT_PROCESSED / INVALID / VALID / LOCKED / DELIVERYPREPARED / DELIVERYBOOKED / EXCLUDEDFROMDELIVERYPROCESS` (`FlightsServices.js:106-113`). When server-side state enum changes, this client mapping must be updated in lockstep.

Routes:
- `/flights` — paged list with ng-table, settings persisted via `TableSettingsCache`.
- `/flights/:id` — edit.
- `/flights/copy/:id` — duplicate.

`airmovements/AirMovementsModule.js` is a parallel module for motor aircraft movements — same structure as `flights/` but a separate audit/log surface. (Not glider/tow-specific.)

**Load-bearing detail:** glider and tow forms split into separate directives but share one `Flight` row on save. The towed glider ↔ tow plane link (`TowFlightId`) is established here. `HighChartsNG` is imported but charting use is minor.

### `planning/` — planning days

`PlanningDaysController` (list) + `PlanningDayEditController` (edit).
Services: `PagedPlanningDays` (`/api/v1/planningdays/page`), `PlanningDayReader` / `PlanningDaysUpdater` (`/api/v1/planningdays/:id`), `ReservationsByPlanningDay`, `PlanningDaysRuleBased` (name suggests rule-driven assignment but actual behavior unverified — worth checking before relying on it).

A planning day = (location, date, remarks) + assignments for flight instructor, tow pilot, and flight operator. The edit view also lists that day's reservations inline.

Routes: `/planning` (list), `/planning/:id/:mode` (edit/view), `/planningsetup` (initial setup).

### `reservations/` and `reservation-scheduler/`

Two views on the same data:

- **`reservations/`** — flat list / form CRUD. `ReservationsController` + `PagedReservations` ($http POST `/api/v1/aircraftreservations/page`), `ReservationInserter`, `ReservationUpdater`, `ReservationDeleter`, `ReservationValidator`. `ReservationTypes` is a cached `$resource`.
  Routes: `/reservations`, `/reservations/:id/:mode`.

- **`reservation-scheduler/`** — calendar/timeline view. `ReservationSchedulerController` pulls in both `aircrafts` and `reservations` modules. Visual aircraft × time-slot grid.
  Route: `/reservation-scheduler`.

### `masterdata/` — CRUD for everything else

Standard pattern across nine subfolders; the load-bearing ones first:

**The rules-engine triad (correspond to `SERVER.md` §3):**

| Folder                       | Highlights                                                                                                                                                                                                  |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `accountingRules/`           | `PagedAccountingRuleFilters` ($http POST `/accountingrulefilters/page`), `AccountingRuleFilterTypesService`, `AccountingUnitTypesService`, `FlightCrewTypesService`. UI for the `AccountingRuleFilter` DB entity that drives `DeliveryItemRulesEngine` server-side. |
| `deliveries/`                | `DeliveryService` (`/api/v1/deliveries/:id`). Deliveries are the invoice drafts produced by `DeliveryCreationJob`. **Deleting a delivery resets affected flights' process states** (`DeliveriesServices.js:58+`) — destructive, mirrors `DeleteDeliveriesAndUpdateProcessStatesOfFlight` on the server. |
| `deliveryCreationTests/`     | `DeliveryCreationTestService` + `PagedDeliveryCreationTests`. Critical for rule debugging: `generateExampleDelivery(flightId)` previews invoice output for a flight without committing; `runTest(id)` runs a stored regression test. This is the UI for the dry-run harness described in `SERVER.md` §3. |

**Standard CRUD (one controller, one services file, one edit form per folder):**

- `aircrafts/` — `Aircrafts`/`Aircraft` $resource; modal `AddAircraftController`. Glider vs. tow distinction in dropdowns.
- `persons/` — `Persons`, `PersonsV2`, `PassengerPersister`, `PersonPersister`. Modal `AddPersonController`. `PersonCategoryFilterDropdownDirective` for list filtering.
- `users/`, `clubs/`, `locations/`, `flightTypes/`, `memberStates/`, `personCategories/` — vanilla CRUD, smaller surface.

### `reporting/` — flight reports

`FlightReportsController` + `FlightReports` service ($http POST `/api/v1/flightreports/page`). HighCharts-ng for visualization. Routes:
- `/flightreports` — report picker.
- `/flightreports/:category/:type` — pre-canned reports.
- `/flightreports/custom/:category/:filter/...` — custom report builder.

### `profile/` — current-user profile

Route `/profile`, auth-gated. `ProfileController` depends on `PersonsModule` — effectively, the logged-in user editing their own `Person` record.

### `system/` — admin

Currently just the `logs/` submodule at `/system/logs`. `LogsDirective` renders application logs. Designed as an extensibility point for future admin panels; mostly empty today.

### Public (no auth) flows

| Folder              | Route               | Purpose                                                                |
| ------------------- | ------------------- | ---------------------------------------------------------------------- |
| `lostpassword/`     | `/lostpassword`     | Request password reset email.                                          |
| `confirm/`          | `/confirm`          | Email-confirmation landing page (token in query string).               |
| `tryflight/`        | `/trialflight`      | Public trial-flight booking. Hides nav bar.                            |
| `passengerflight/`  | `/passengerflight`  | Public passenger-flight registration. Hides nav bar.                   |

The nav-bar-hiding logic in `index.js:50`:

```js
AuthService.setShowNavBar($location.path() !== '/tryflight' || $location.path() !== '/passengerflight');
```

This expression is **always `true`** (the De Morgan trap: `≠ A || ≠ B` is a tautology when A ≠ B). The nav bar gets re-shown on these routes by this handler; whatever actually hides it lives elsewhere (likely the individual route templates omit `fls-navigation-bar` rendering, or controllers call `setShowNavBar(false)` directly). Worth grepping `setShowNavBar(false)` before relying on this behavior.

## API call sites — quick index

When you need to find "what calls endpoint X":

| Endpoint family                            | Likely callers                                                          |
| ------------------------------------------ | ----------------------------------------------------------------------- |
| `/Token`                                   | `core/AuthService.js`                                                   |
| `/api/v1/users/my`, `/userroles`           | `core/AuthService.js` (post-login hydration)                            |
| `/api/v1/translations`                     | `angular-translate` URL loader (`index.js:42`)                          |
| `/api/v1/flights/...`                      | `flights/FlightsServices.js`                                            |
| `/api/v1/aircraftreservations/...`         | `reservations/ReservationsServices.js`                                  |
| `/api/v1/planningdays/...`                 | `planning/PlanningService.js`                                           |
| `/api/v1/accountingrulefilters/...`        | `masterdata/accountingRules/`                                           |
| `/api/v1/deliveries/...`                   | `masterdata/deliveries/`                                                |
| `/api/v1/deliverycreationtests/...`        | `masterdata/deliveryCreationTests/`                                     |
| `/api/v1/aircrafts/listitems/...`          | `flights/AircraftsServices.js`, `masterdata/aircrafts/`                 |
| `/api/v1/persons/...`                      | `masterdata/persons/`                                                   |

## Mock server for offline dev

`flsweb/server/index.js` is an Express app that serves JSON fixtures from `flsweb/server/mock-data/` against the same `/api/v1/*` paths. Run `node flsweb/server/index.js` (or `start-mock-server.cmd`). To point the client at it, you'd need to add a proxy target or use `webpack-dev-server`'s proxy against `localhost:<mock-port>` — currently the dev-server proxies to `localhost:25567` (real backend) by default. This is useful when the C# side isn't running, but the mock only covers a small subset of endpoints (locations, reservations, accountingrulefilters, aircrafts overview, translations, current user, userroles).

## Knowledge gaps worth verifying

- **The `||` bug in `setShowNavBar`** (`index.js:50`) — is the nav bar actually hidden on `/tryflight`/`/passengerflight`, and if so, where does the real hiding happen?
- **`PlanningDaysRuleBased`** — name implies rule-driven crew assignment, but actual behavior wasn't verified. Could be just naming.
- **401 handling** — currently no global interceptor. Test: with an expired token, does the next mutating request just fail silently with a message-bar error and leave the user stuck on a "logged-in" UI?
- **`airmovements/` vs `flights/`** — they look near-identical structurally. Confirm whether they share any code or are full copies (relevant for maintenance).
- **Client state-string drift** — `FlightStateMapper` duplicates the server's `FlightProcessState` enum as strings. Any divergence is a silent UI bug.

## Where to start when changing things

| Goal                                                  | Start here                                                                                                          |
| ----------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| Add a new authenticated page                          | New feature folder under `src/<name>/` with a `*Module.js`; in `.config(($routeProvider))` add `resolve: { user: userAuth }`; import the module into `index.js`'s `app.starter`. |
| Add a public page                                     | Same as above but omit the resolve (or set `publicAccess: true`); confirm nav bar behavior.                         |
| Add a column to the flights list                      | `flights/flights-list.html` (the ng-table) + add the field to the server's `FlightOverview` DTO + map in `FlightsServices.js`. |
| Add a new flight-state transition button              | `flights/FlightsController.js` action + corresponding endpoint on the server's `FlightsController`. Update `FlightStateMapper` if a new state is involved. |
| Add a new accounting-rule type                        | `masterdata/accountingRules/` form + add the rule-type enum value in `FLS.Data.WebApi/Accounting/RuleFilters/` server-side. Add a `DeliveryCreationTest` to verify before pushing. |
| Add a new translation key                             | Add the key in the server DB (`LanguageTranslation` table); use `{{ 'KEY' | translate }}` in templates. No client rebuild needed. |
| Change how the bearer token is attached               | `core/AuthService.js` (`:183`, `:186`). Note: there's no interceptor — it's a one-shot `$http.defaults` mutation.   |
| Add a new dropdown of server master data              | New `$resource` service in `core/` (mirror `StartTypes.js` / `Countries.js`) with `cache: true`.                    |
| Debug "rules don't produce the invoice I expect"      | `masterdata/deliveryCreationTests/` → `generateExampleDelivery(flightId)` is the preview action. Server-side flow in `SERVER.md` §3. |
