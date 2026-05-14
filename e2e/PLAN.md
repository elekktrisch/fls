# e2e-gap plan

Roadmap of numbered Playwright specs. All rows landed by 2026-05-14
(waves 1 + 2 + wave-3 fixes).

For **how to write a new spec correctly**, read `e2e/TEST_WRITING.md`
first — the parallelism, stable-id, and AngularJS rules there are
load-bearing. `e2e/SELECTORS.md` is the `data-testid` contract.
`e2e/README.md` is the stack-up.

## Task table

| #  | Spec file                              | Scope                                                                                       | Complexity | Status   |
|----|----------------------------------------|---------------------------------------------------------------------------------------------|------------|----------|
| 01 | `01-public.spec.ts`                    | Public-route screenshot smoke                                                              | S          | done     |
| 02 | `02-authenticated.spec.ts`             | Authenticated-route screenshot smoke                                                       | S          | done     |
| 03 | `03-masterdata.spec.ts`                | Read-only masterdata list and form open                                                    | M          | done     |
| 04 | `04-flights-create.spec.ts`            | Create new glider flight via form; assert in list                                          | M          | done     |
| 05 | `05-flights-edit.spec.ts`              | Edit a seeded flight's glider-specific fields; assert DB mutation                          | M          | done     |
| 06 | `06-flights-state-transitions.spec.ts` | Invalid→Valid revalidation; exclude-from-delivery toggle                                  | L          | done     |
| 07 | `07-airmovements-crud.spec.ts`         | Motor aircraft CRUD mirror of flights                                                      | M          | done     |
| 08 | `08-email.spec.ts`                     | Mailpit-based email delivery for workflows + public forms                                  | M          | done     |
| 09 | `09-public-flows.spec.ts`              | Public unauthenticated flow tests (trial/passenger/reset/confirm)                          | M          | done     |
| 10 | `10-reservations-crud.spec.ts`         | Create / edit / delete aircraft reservation                                                | M          | done     |
| 11 | `11-reservation-scheduler.spec.ts`     | Scheduler grid renders, slots align, no collisions                                         | M          | done     |
| 12 | `12-masterdata-crud.spec.ts`           | Full CRUD cycle for one entity (locations)                                                 | M          | done     |
| 13 | `13-persons-add-modal.spec.ts`         | Modal-driven person creation                                                               | S          | done     |
| 14 | `14-planning-day-crud.spec.ts`         | Create planning day + crew assignment                                                      | M          | done     |
| 15 | `15-planning-setup-wizard.spec.ts`     | Drive `/planningsetup` wizard end-to-end                                                  | M          | done     |
| 16 | `16-flight-reports-generation.spec.ts` | Per-pilot report renders with flight data                                                  | M          | done     |
| 17 | `17-custom-report-builder.spec.ts`     | Build custom report with filters                                                           | L          | done     |
| 18 | `18-profile-edit.spec.ts`              | Edit own Person fields, save                                                               | S          | done     |
| 19 | `19-audit-logs.spec.ts`                | Mutate flight, assert audit entries appear                                                 | M          | done     |
| 20 | `20-delivery-creation-test.spec.ts`    | Run regression harness; assert items match                                                 | M          | done     |
| 21 | `21-accounting-rules-edit.spec.ts`     | Create / edit recipient + tiered billing rule                                              | L          | done     |
| 22 | `22-flight-locking-workflow.spec.ts`   | Trigger validation job; assert Valid → Locked                                              | L          | done     |
| 23 | `23-delivery-creation-workflow.spec.ts`| Trigger delivery job; assert DeliveryPrepared                                              | L          | done     |
| 25 | `25-multi-tenant-isolation.spec.ts`    | Cross-club visibility check                                                                | M          | done     |
| 26 | `26-aircraft-crud.spec.ts`             | Create / edit / delete aircraft via /masterdata/aircrafts                                  | M          | done     |
| 27 | `27-user-crud.spec.ts`                 | Create / edit / delete user via /masterdata/users                                          | M          | done     |
| 28 | `28-club-crud.spec.ts`                 | Edit own club via /masterdata/clubs (create may be SysAdmin-only)                          | M          | done     |
| 29 | `29-flight-type-crud.spec.ts`          | Create / edit / delete flight type via /masterdata/flightTypes                             | M          | done     |
| 30 | `30-member-state-crud.spec.ts`         | Create / edit / delete member state via /masterdata/memberStates                           | M          | done     |
| 31 | `31-person-category-crud.spec.ts`      | Create / edit / delete person category via /masterdata/personCategories                    | M          | done     |
| 32 | `32-rules-engine-per-type.spec.ts`     | One DeliveryCreationTest case per `AccountingRuleFilter` rule type (10 types)              | L          | done     |
| 33 | `33-api-contract.spec.ts`              | Hit every `/api/v1/*` endpoint the client uses; assert shape/keys/status                   | L          | done     |

(Out of the spec-numbering grid:) `.github/workflows/e2e.yml` — CI workflow that brings up the stack and runs the suite on every PR.

## Cross-cutting risks (still live)

1. **Time gates (#22, #23).** `LockFlights` requires `CreatedOn ≤
   today - 2d`; `CreateDeliveriesFromFlights` requires `LockedOn ≤
   today - 3d`. `ensureGliderFlight({ createdOnDaysAgo: N })` handles
   CreatedOn; backdate `LockedOn` via `withPool` after locking. See
   `TEST_WRITING.md §4`.
2. **Modal-driven flows.** No `data-testid` contract for modals yet.
   Lean on `getByRole` / `getByLabel`, or extend `SELECTORS.md`.
3. **Rule precedence (#21, #23, #32).** `SERVER.md` flags as an open
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
