# Showcase seed

A **cumulative, deterministic, reusable demo dataset** loaded in **one command**.
Established in **J-3** (the pilot dashboard) because the dashboard variants only
prove their worth against realistic data — empty cards and zero counts demonstrate
nothing.

## What it is (and isn't)

- **On-demand, profile-gated.** It runs only when the `showcase` Spring profile is
  active — never on the IT bootstrap path. ADR 0021 keeps integration tests lean
  and fast; do **not** fatten the always-on Flyway `V__` dev seeds to grow the
  showcase. The lean per-IT seed (`V5`/`V8`/`V26`/`V28`/`V29`: `seed-club-1` +
  clubadmin1-4/pilot1/sysadmin) and the showcase seed are deliberately separate.
- **Distinct from the migrated (fanout) export.** The export is realistic but
  non-curated and only exists in the nightly fanout chain. The showcase seed is
  **curated and dial-able**: you can demand specific variations / edge states, and
  it uses **fixed deterministic UUIDs** so the e2e display spec can assert against
  known rows and dashboard counts are predictable.
- **Coexists with the dev seeds** — it extends them, it does not fight them. Every
  write is an idempotent `ON CONFLICT DO NOTHING` upsert, so re-running the loader
  (or running it after the dev seeds) is a clean no-op.

## How to run it (one command)

```bash
# from alpenflight/server
./gradlew seedShowcase
```

This boots the app with `--spring.profiles.active=dev,showcase`; the
`@Profile("showcase")` `ShowcaseSeedRunner` fires the `ShowcaseSeeder` once, logs
exactly what it loaded, and the process exits. (Equivalent manual form:
`./gradlew bootRun --args='--spring.profiles.active=dev,showcase'`.)

Requires a reachable Postgres (the `DATASOURCE_*` env, same as any JPA-booting
task) that Flyway has already migrated.

## Where it lives

- Loader: `ch.alpenflight.tenancy.showcase.ShowcaseSeeder` (+ `ShowcaseSeedRunner`).
  Homed in the `tenancy` module — its going-in layer is tenancy + principals, and
  it reuses the co-module `provisioning.ReferenceDataSeeder` so a showcase club
  gets exactly the member-state / flight-type defaults a real provisioned club gets.
- This README + any curated SQL fixtures: `src/main/resources/showcase/`.
- Matching Keycloak principals: `alpenflight/auth/realm-export.json` (so the
  real-idp e2e can actually log in as the showcase users).

## What exists today (J-3 T-02 — tenancy + principals)

Two clubs and the three-role principal matrix across both, including a pilot with
**no flights** so the empty-state stays reachable.

| Club | id | reference data |
| --- | --- | --- |
| `seed-club-1` (reused, V5) | `019e30c3-2c00-7001-8000-000000000001` | member_state + flight_type |
| `showcase-club-2` (new)    | `019e30c3-2c00-7001-8000-000000000002` | member_state + flight_type |

| Username | role | club | `t_user` id | Keycloak sub | flights |
| --- | --- | --- | --- | --- | --- |
| `clubadmin1` (reused, V8)   | CLUB_ADMINISTRATOR  | club-1 | `…7100-…001` | `9d08ed9c-699a-4c26-9036-9f0bd378009d` | n/a |
| `pilot1` (reused, V8)       | PILOT               | club-1 | `…7100-…002` | `376317c0-fc0a-439d-a5f7-9af17e5f4178` | HAS (T-03) |
| `pilot-empty1` (new)        | PILOT               | club-1 | `…7100-…020` | `019e30c3-2c00-7200-8000-000000000020` | **NONE** |
| `clubadmin-c2` (new)        | CLUB_ADMINISTRATOR  | club-2 | `…7100-…021` | `019e30c3-2c00-7200-8000-000000000021` | n/a |
| `pilot-c2` (new)            | PILOT               | club-2 | `…7100-…022` | `019e30c3-2c00-7200-8000-000000000022` | HAS (T-03) |
| `sysadmin` (reused)         | SYSTEM_ADMINISTRATOR | (global, no tenant) | — (no `t_user`) | `f1558768-6573-4420-983b-20848972d303` | n/a |

The realm `clubId` attribute is a human label (`club-1` / `club-2`); tenant
resolution actually keys on the `t_user.keycloak_sub` lookup
(`UserPrincipalLookup.resolveTenantFor`), so a showcase principal resolves its
tenant deterministically the moment it authenticates — no JIT race.

## What exists today (J-3 T-03a — locations + aircraft)

Both are built through their **domain aggregate factories** — `Location.create`
and `Aircraft.register` (+ `Aircraft.changeState(OK, …)` for the opening
airworthiness period) — so every ADR 0022 directive-2 business rule (ICAO
shape, immatriculation/competition-sign normalisation, blank-name rejection,
"exactly one open state period") runs over the seed data. Because
`Location`/`Aircraft` own id generation (`@GeneratedValue(UUID)` mints a fresh
random id on persist), the seeder uses the same **validate-via-aggregate, then
JDBC-INSERT-with-a-deterministic-id** pattern the migration ingestor uses for
these exact entities: construct the aggregate (it validates + normalises), read
its getters into an idempotent `ON CONFLICT (id) DO NOTHING` INSERT carrying the
fixed id. Tenant-scoped `Location` writes run inside `Tenants.runAs(clubId, …)`
to honour the effective-tenant write-context contract (T-03b's flights will lean
on the same wrap).

The deterministic ids below are exposed as `public static final UUID` constants
on `ShowcaseSeeder` (`LOCATION_*` / `AIRCRAFT_*`) so **T-03b's flight matrix can
reference them by id**.

**Locations** (`t_location`, `@TenantId`-scoped — same ICAO catalog is private
per club; 3 per club: home glider airfield + two destinations):

| Const | club | ICAO | location_type | id |
| --- | --- | --- | --- | --- |
| `LOCATION_C1_HOME`   | club-1 | LSZX | GLIDER_AIRFIELD | `…7301-…301` |
| `LOCATION_C1_DEST_1` | club-1 | LSGB | CONCRETE_RUNWAY | `…7301-…302` |
| `LOCATION_C1_DEST_2` | club-1 | LSPD | CONCRETE_RUNWAY | `…7301-…303` |
| `LOCATION_C2_HOME`   | club-2 | LSZW | GLIDER_AIRFIELD | `…7301-…304` |
| `LOCATION_C2_DEST_1` | club-2 | LSGT | CONCRETE_RUNWAY | `…7301-…305` |
| `LOCATION_C2_DEST_2` | club-2 | LSPM | CONCRETE_RUNWAY | `…7301-…306` |

**Aircraft** (`t_aircraft`, cross-tenant per S-058 — `managing_club_id` gates
writes; reads are open so club-1 sees the club-2 charter; each gets an opening
`OK` airworthiness period in `t_aircraft_aircraft_state`):

| Const | managing club | immatriculation | aircraft_type | towing | id |
| --- | --- | --- | --- | --- | --- |
| `AIRCRAFT_C1_GLIDER`  | club-1 | HB-3001 | GLIDER          | no  | `…7401-…401` |
| `AIRCRAFT_C1_TOW`     | club-1 | HB-TOW1 | MOTOR_AIRCRAFT  | yes | `…7401-…402` |
| `AIRCRAFT_C1_MOTOR`   | club-1 | HB-MOT1 | MOTOR_GLIDER (TMG) | no | `…7401-…403` |
| `AIRCRAFT_C2_GLIDER`  | club-2 | HB-3002 | GLIDER          | no  | `…7401-…404` |
| `AIRCRAFT_CHARTER_C2` | club-2 | HB-CHTR | MOTOR_AIRCRAFT  | no  | `…7401-…405` |

`AIRCRAFT_CHARTER_C2` is the **cross-club charter** case (J-1): managed by club-2,
referenced read-only by club-1. Each aircraft's opening state row id is the
aircraft id with the `-7401-` band bumped to `-7501-`.

## Per-journey-extension convention (the precedent J-3 sets)

**Each future journey EXTENDS the showcase seed with its entity's realistic
variations** — the same per-journey-contribution pattern as the per-entity legacy
seed + migration mapper. A journey that ships a new entity adds that entity's
showcase rows; it does not rebuild a realistic starting position from scratch.

Rules for an extension:

1. **Append, don't rewrite.** Add new deterministic rows; keep existing ids stable
   so prior e2e assertions don't break.
2. **Deterministic ids only.** Fixed UUIDs, picked off the same suffix scheme
   (`019e30c3-2c00-7XXX-8000-…`) so counts stay predictable and specs can assert
   against known rows.
3. **Idempotent.** Every insert is `ON CONFLICT DO NOTHING`.
4. **Cover the variations the journey's screen needs to prove** — variants ×
   states × dates. The dashboard, e.g., needs flights `today` / within-2d / past
   the lock threshold and across `NotProcessed`/`Valid`/`Invalid`/`Locked`/
   `DeliveryBooked`.
5. **Reuse the provisioning seeders** for tenant-scoped reference data rather than
   hand-rolling inserts.

Planned extensions (non-binding, from the J-3 plan):

| Journey | adds |
| --- | --- |
| J-3 T-03a | locations (3/club) + aircraft (glider / tow / TMG / charter) — done |
| J-3 T-03b | flights across every variation × state × date (references the T-03a ids) |
| J-5 | reservations |
| J-6 | planning days |
| J-10 | deliveries |
| J-11 | articles |
