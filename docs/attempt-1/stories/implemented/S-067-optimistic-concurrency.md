---
id: S-067
title: Flight concurrency + delete-from-list + aerotow round-trip
epic: E-07
status: done
started_at: 2026-05-25
done_at: 2026-05-25
merged: true
merged_at: 2026-05-25
github_issue: 128
github_pr: 127
depends_on: [S-058, S-062c]
acceptance:
  - PUT + DELETE on `/api/v1/flights/{id}` accept `If-Match: <version>`; stale precondition → 412 with `application/problem+json` body carrying `expected` + `serverVersion`. `*` and weak / strong ETag forms accepted per RFC 7232 §3.1.
  - Soft-delete tow-cascade honours the same state gate as the glider — a `DELIVERY_BOOKED` (or other admin-locked) tow rolls back the whole delete via the class-level `@Transactional`.
  - `FlightListItem` projection carries `version` so the list-view delete sends the row's real version, not the wildcard.
  - Aerotow paired-create has e2e coverage at `04c-flights-paired-create.spec.ts` — POST glider → POST tow → PUT-link order, `If-Match: <gliderVersion>` on the link PUT, plus tow-fail rollback variant.
  - Mapper-identity vitest in `flight-form.model.spec.ts` proves every editable form attribute survives `snapshot → request → server-echo → snapshot` byte-identical (the load-bearing "are all attributes saved" guarantee for the surface the wizard doesn't render today).
  - Delete-from-list UI: kebab Delete + `<af-dialog>` confirm with inline error on failure; suppressed on every state `FlightStateGateException` would reject; placeholder names the gating state.
  - Wizard's Glider step has an `isSoloFlight` checkbox; co-pilot selector hides reactively when solo. Server persists the flag as-given (no crew-derive).
  - Invoice recipient round-trips: TS form packs it as a `FLIGHT_COST_INVOICE_RECIPIENT` crew row (parity with legacy `FlightCrews`); server's existing crew model carries it both ways without a schema change.
  - All wizard / list / detail crew-slot UUIDs in TS mirror server seed (`FlightCrewTypeIds.java`) via the new `flight-crew-types.ts` constants — a contract test will lock this once a `/flight-crew-types` reference endpoint lands.
estimate: M
adr_refs: [0005]
parity_test: alpenflight/web/e2e/tests/flights/04c-flights-paired-create.spec.ts
refined: true
refined_at: 2026-05-25
refined_specialists: [synthesized-inline]
---

## Context

R14 (concurrent-edit was untested in legacy) closed for Flight, the highest-frequency editable entity. Plus the aerotow paired-create coverage S-062c left on the table, the delete-from-list affordance the operator asked for, and the filter-bar alignment boyscout the operator surfaced from a manual screenshot during refine — since codified as the screenshots-in-e2e convention.

## Load-bearing decisions

- **Invoice recipient is a crew row, not a column.** Legacy stored it as `FlightCrew.FlightCrewType = FlightCostInvoiceRecipient` (`flsserver/.../Flight.cs:366-368`); we preserve that shape — no `invoice_recipient_person_id` column on `Flight`. The TS form packs and extracts it through the crew array.
- **List-view delete carries `version`.** The `FlightListItem` projection was extended (server DTO + JPQL projection + mapper + OpenAPI snapshot) so the row's current version is on hand when the kebab fires; otherwise the UI would default to `If-Match: *` and silently bypass the gate the story is supposed to add.
- **`isSoloFlight` is UI-validated, not server-derived.** The wizard owns the truth (checkbox + reactive co-pilot hide); server persists as-given. The mapper round-trip spec is the contract.
- **412 body publishes `serverVersion` as the wire-stable name** — no `actual` alias. The S-062h inline-diff dialog reads `serverVersion` directly without an extra GET.
- **af-dialog focus-trap + Esc + i18n deferred to S-062h.** Both cross-cut the dialog primitive + the locale-key surface; folding either in here would balloon scope.

## Deferred follow-ups

- **`af-dialog` → CDK Overlay + FocusTrap + Esc handler.** Current impl is a plain fixed-position div; covered by the consume points in S-062h (draft-restore + 412 inline diff).
- **i18n keys for all new operator-facing copy** (Delete / Delete (locked) / dialog title / error messages / Solo flight / conflict toast). Currently English-literal; lift into `flight.list.delete.*` / `flight.edit.solo.*` keys in `de.ts` + sibling locales as part of the next E-07 pass.
- **`/api/v1/flight-crew-types` reference endpoint** to replace the static `flight-crew-types.ts` UUID map; adds a contract test that fails CI on drift between FE constants and the V3 seed.

## Notes

Apply the same If-Match + ProblemDetail-with-serverVersion pattern to other high-edit entities (Aircraft, Reservation, PlanningDay) in their respective stories when they next touch the controller. Scoped here to Flight.
