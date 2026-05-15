# e2e-gap plan

Roadmap of numbered Playwright specs. All rows landed by 2026-05-14
(waves 1 + 2 + wave-3 fixes).

For **how to write a new spec correctly**, read `e2e/TEST_WRITING.md`
first — the parallelism, stable-id, and AngularJS rules there are
load-bearing. `e2e/SELECTORS.md` is the `data-testid` contract.
`e2e/README.md` is the stack-up.

## Task table

The original spec roadmap was numbered 01..33 (the numbers were the
authoring order, not anything load-bearing). 2026-05-15 we moved specs
into per-feature category folders under `tests/<category>/` and
dropped the numeric prefix. Historical task numbers are kept in this
table for traceability — if you're looking for "spec #23" in the
codebase, the new path is in the right column.

| #  | Spec path                                                | Category       | Scope                                                                                  | Status |
|----|----------------------------------------------------------|----------------|----------------------------------------------------------------------------------------|--------|
| 01 | `tests/public/screenshot-smoke.spec.ts`                  | public         | Public-route screenshot smoke                                                          | done   |
| 02 | `tests/auth/authenticated-routes-smoke.spec.ts`          | auth           | Authenticated-route screenshot smoke                                                   | done   |
| 03 | `tests/masterdata/screenshot-smoke.spec.ts`              | masterdata     | Read-only masterdata list and form open                                                | done   |
| 04 | `tests/flights/create.spec.ts`                           | flights        | Create new glider flight via form; assert in list                                      | done   |
| 05 | `tests/flights/edit.spec.ts`                             | flights        | Edit a seeded flight's glider-specific fields; assert DB mutation                      | done   |
| 06 | `tests/flights/state-transitions.spec.ts`                | flights        | Invalid→Valid revalidation; exclude-from-delivery toggle                              | done   |
| 07 | `tests/flights/airmovements-crud.spec.ts`                | flights        | Motor aircraft CRUD mirror of flights                                                  | done   |
| 08 | `tests/email/notifications.spec.ts`                      | email          | Mailpit-based email delivery for workflows + public forms                              | done   |
| 09 | `tests/public/registration-flows.spec.ts`                | public         | Public unauthenticated flow tests (trial/passenger/reset/confirm)                      | done   |
| 10 | `tests/reservations/crud.spec.ts`                        | reservations   | Create / edit / delete aircraft reservation                                            | done   |
| 11 | `tests/reservations/scheduler.spec.ts`                   | reservations   | Scheduler grid renders, slots align, no collisions                                     | done   |
| 12 | `tests/masterdata/locations-crud.spec.ts`                | masterdata     | Full CRUD cycle for one entity (locations)                                             | done   |
| 13 | `tests/masterdata/persons-add-modal.spec.ts`             | masterdata     | Modal-driven person creation                                                           | done   |
| 14 | `tests/planning/day-crud.spec.ts`                        | planning       | Create planning day + crew assignment                                                  | done   |
| 15 | `tests/planning/setup-wizard.spec.ts`                    | planning       | Drive `/planningsetup` wizard end-to-end                                              | done   |
| 16 | `tests/reporting/flight-reports.spec.ts`                 | reporting      | Per-pilot report renders with flight data                                              | done   |
| 17 | `tests/reporting/custom-builder.spec.ts`                 | reporting      | Build custom report with filters                                                       | done   |
| 18 | `tests/profile/edit.spec.ts`                             | profile        | Edit own Person fields, save                                                           | done   |
| 19 | `tests/flights/audit-logs.spec.ts`                       | flights        | Mutate flight, assert audit entries appear                                             | done   |
| 20 | `tests/accounting/delivery-creation-test.spec.ts`        | accounting     | Run regression harness; assert items match                                             | done   |
| 21 | `tests/accounting/rules-edit.spec.ts`                    | accounting     | Create / edit recipient + tiered billing rule                                          | done   |
| 22 | `tests/flights/locking-workflow.spec.ts`                 | flights        | Trigger validation job; assert Valid → Locked                                          | done   |
| 23 | `tests/accounting/delivery-creation-workflow.spec.ts`    | accounting     | Trigger delivery job; assert DeliveryPrepared                                          | done   |
| 25 | `tests/multi-tenant/isolation.spec.ts`                   | multi-tenant   | Cross-club visibility check                                                            | done   |
| 26 | `tests/masterdata/aircrafts-crud.spec.ts`                | masterdata     | Create / edit / delete aircraft via /masterdata/aircrafts                              | done   |
| 27 | `tests/masterdata/users-crud.spec.ts`                    | masterdata     | Create / edit / delete user via /masterdata/users                                      | done   |
| 28 | `tests/masterdata/clubs-crud.spec.ts`                    | masterdata     | Edit own club via /masterdata/clubs (create may be SysAdmin-only)                      | done   |
| 29 | `tests/masterdata/flight-types-crud.spec.ts`             | masterdata     | Create / edit / delete flight type via /masterdata/flightTypes                         | done   |
| 30 | `tests/masterdata/member-states-crud.spec.ts`            | masterdata     | Create / edit / delete member state via /masterdata/memberStates                       | done   |
| 31 | `tests/masterdata/person-categories-crud.spec.ts`        | masterdata     | Create / edit / delete person category via /masterdata/personCategories                | done   |
| 32 | `tests/accounting/rules-engine-per-type.spec.ts`         | accounting     | One DeliveryCreationTest case per `AccountingRuleFilter` rule type (10 types)          | done   |
| 33 | `tests/api/contract.spec.ts`                             | api            | Hit every `/api/v1/*` endpoint the client uses; assert shape/keys/status               | done   |
| —  | `tests/auth/login.spec.ts`                               | auth           | UI-driven login (success + wrong password + unknown user + logout)                     | done   |
| —  | `tests/public/landing.spec.ts`                           | public         | Landing page renders, title assertion                                                  | done   |

(Out of the spec-numbering grid:) `.github/workflows/e2e.yml` — CI workflow that brings up the stack and runs the suite on every PR.

## Cross-cutting risks (still live)

1. **Time gates (#22, #23).** `LockFlights` requires `CreatedOn ≤
   today - 2d`; `CreateDeliveriesFromFlights` requires `LockedOn ≤
   today - 3d`. `ensureGliderFlight({ createdOnDaysAgo: N })` handles
   CreatedOn; backdate `LockedOn` via `withPool` after locking. See
   `TEST_WRITING.md §4`.
2. **Modal-driven flows.** No `data-testid` contract for modals yet.
   Lean on `getByRole` / `getByLabel`, or extend `SELECTORS.md`.
3. **Rule precedence (#21, #23, #32).** `../docs/legacy/server.md` flags as an open
   question how multiple matching `Recipient` rules combine
   (first-wins? last-wins?). Specs should pin the observed behavior.
4. **Mono + SQL Server ceiling.** `workers: 6` is the empirical max
   before back-end timeouts cascade. Don't increase without verifying
   on CI.

## What this plan does not cover

- Performance / load testing.
- Cross-browser matrices.
- Visual regression beyond the existing screenshot smoke.
- The Proffix invoice sync (separate repo, out-of-scope).
- AngularJS Karma unit tests (separate Jasmine suite).
