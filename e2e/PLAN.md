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
| 04 | `04-flights-create.spec.ts`            | Create new glider flight via form; assert in list                                          | M          | pending  |
| 05 | `05-flights-edit.spec.ts`              | Edit a seeded flight's glider-specific fields; assert DB mutation                          | M          | pending  |
| 06 | `06-flights-state-transitions.spec.ts` | Invalid→Valid revalidation; exclude-from-delivery toggle                                  | L          | pending  |
| 07 | `07-airmovements-crud.spec.ts`         | Motor aircraft CRUD mirror of flights                                                      | M          | pending  |
| 08 | `08-email.spec.ts`                     | Mailpit-based email delivery for workflows + public forms                                  | M          | done     |
| 09 | `09-public-flows.spec.ts`              | Public unauthenticated flow tests (trial/passenger/reset/confirm)                          | M          | done     |
| 10 | `10-reservations-crud.spec.ts`         | Create / edit / delete aircraft reservation                                                | M          | pending  |
| 11 | `11-reservation-scheduler.spec.ts`     | Scheduler grid renders, slots align, no collisions                                         | M          | pending  |
| 12 | `12-masterdata-crud.spec.ts`           | Full CRUD cycle for one entity (locations)                                                 | M          | pending  |
| 13 | `13-persons-add-modal.spec.ts`         | Modal-driven person creation                                                               | S          | pending  |
| 14 | `14-planning-day-crud.spec.ts`         | Create planning day + crew assignment                                                      | M          | pending  |
| 15 | `15-planning-setup-wizard.spec.ts`     | Drive `/planningsetup` wizard end-to-end                                                  | M          | pending  |
| 16 | `16-flight-reports-generation.spec.ts` | Per-pilot report renders with flight data                                                  | M          | pending  |
| 17 | `17-custom-report-builder.spec.ts`     | Build custom report with filters                                                           | L          | pending  |
| 18 | `18-profile-edit.spec.ts`              | Edit own Person fields, save                                                               | S          | pending  |
| 19 | `19-audit-logs.spec.ts`                | Mutate flight, assert audit entries appear                                                 | M          | pending  |
| 20 | `20-delivery-creation-test.spec.ts`    | Run regression harness; assert items match                                                 | M          | pending  |
| 21 | `21-accounting-rules-edit.spec.ts`     | Create / edit recipient + tiered billing rule                                              | L          | pending  |
| 22 | `22-flight-locking-workflow.spec.ts`   | Trigger validation job; assert Valid → Locked                                              | L          | pending  |
| 23 | `23-delivery-creation-workflow.spec.ts`| Trigger delivery job; assert DeliveryPrepared                                              | L          | pending  |
| 25 | `25-multi-tenant-isolation.spec.ts`    | Cross-club visibility check                                                                | M          | pending  |

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
