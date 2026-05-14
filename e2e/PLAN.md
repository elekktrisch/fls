# e2e-gap plan

Reconstructed 2026-05-14. Tracks the planned Playwright spec files that
extend the existing coverage in `e2e/tests/`. Existing specs are numbered
`01`, `02`, `03`, `08`, `09` (already in the repo); this plan slots new
specs into the gaps `04`-`07`, `10`-`23`, and `25`. Each row should be
updated as the spec lands.

See `e2e/SELECTORS.md` for the `data-testid` contract that all UI specs
lean on, and `e2e/README.md` for how to bring up the stack.

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
| 26 | `26-aircraft-crud.spec.ts`             | Create / edit / delete aircraft via /masterdata/aircrafts                                  | M          | pending  |
| 27 | `27-user-crud.spec.ts`                 | Create / edit / delete user via /masterdata/users                                          | M          | pending  |
| 28 | `28-club-crud.spec.ts`                 | Edit own club via /masterdata/clubs (create may be SysAdmin-only)                          | M          | pending  |
| 29 | `29-flight-type-crud.spec.ts`          | Create / edit / delete flight type via /masterdata/flightTypes                             | M          | pending  |
| 30 | `30-member-state-crud.spec.ts`         | Create / edit / delete member state via /masterdata/memberStates                           | M          | pending  |
| 31 | `31-person-category-crud.spec.ts`      | Create / edit / delete person category via /masterdata/personCategories                    | M          | pending  |
| 32 | `32-rules-engine-per-type.spec.ts`     | One DeliveryCreationTest case per `AccountingRuleFilter` rule type (10 types)              | L          | pending  |
| 33 | `33-api-contract.spec.ts`              | Hit every `/api/v1/*` endpoint the client uses; assert shape/keys/status                   | L          | pending  |

(Out of the spec-numbering grid:) `.github/workflows/e2e.yml` — CI workflow that brings up the stack and runs the suite on every PR.

## Cross-cutting risks

1. **Time gates (#22, #23).** `LockFlights` requires age ≥ 2 days;
   `CreateDeliveriesFromFlights` requires lock-age ≥ 3 days. The
   `_test-fixture.sql` anchors all timestamps to a 2026-01-01 base, so
   aged states are reachable without clock manipulation — but the
   fixture must seed flights at the right age relative to that anchor.
2. **Modal-driven flows (#13, possibly #04).** There is no `data-testid`
   contract for modals. A new spec that needs to drive a modal must
   either lean on `getByRole` / `getByLabel`, or extend
   `SELECTORS.md` with new contract markers (and patch the template).
3. **Test isolation between mutation specs.** `freshDb` is worker-scoped.
   Mutation specs that share a worker can interfere. Use Playwright's
   `test.describe.serial` blocks where order matters.
4. **Rule precedence (#21, #23).** `SERVER.md` flags as an open question
   how multiple matching `Recipient` rules combine (first-wins?
   last-write-wins?). The spec should pin the observed behavior with an
   assertion.

## Spec-writing conventions (apply to every new spec)

- TypeScript, ESM-style imports, top-of-file shebang-free.
- Reuse fixtures from `e2e/fixtures.ts`: `loggedInPage` (fast,
  session-storage injection) for the default, `uiLoggedInPage` only when
  a spec actually needs the real login flow, `freshDb` for any spec
  that mutates state.
- Lean on the existing `data-testid` contract (`SELECTORS.md`). If a new
  testid is needed, **note it in the spec's leading comment** but do
  not modify shared template files in the parallel batch — those go
  through a follow-up consolidation pass.
- Wait pattern: `gotoRoute()` already polls `.busy-indicator`. Do not
  rely on `networkidle`.
- Use Mailpit helpers from `e2e/mailpit.ts` for any email assertion.
- Output dirs land under `/tmp/fls-e2e-*` per `playwright.config.ts` —
  do not write artifacts into `/c/...`.

## What this plan does not cover

- Performance / load testing.
- Cross-browser matrices.
- Visual regression beyond the existing screenshot smoke.
- The Proffix invoice sync (separate repo, out-of-scope).
- AngularJS Karma unit tests (separate Jasmine suite).
