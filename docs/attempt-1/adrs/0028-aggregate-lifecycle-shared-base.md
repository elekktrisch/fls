# 0028 — Shared soft-delete lifecycle base; per-aggregate saved-event hook stays

- **Status:** Accepted
- **Date:** 2026-06-13
- **Scope:** AlpenFlight server aggregate roots that carry soft-delete +
  `@DomainEvents`. Closes the recurring "extract a lifecycle
  `@MappedSuperclass`?" question the CPD ratchet
  (`alpenflight/server/config/pmd/cpd-baseline.txt`) re-opened three times
  (J-6 T-02, J-7 RM-1b, J-7 RM-2). Operator directive (J-26 T-21): decide it
  once and write it down so the ratchet stops re-litigating.

## Context

Five aggregates were said to "share a byte-identical lifecycle shape": the
soft-delete fields + `softDelete(userId, clock)` + the `@DomainEvents`
emit-on-save one-liner. The empirical CPD report told a narrower truth — the
actual clone was a **56-token triplet** across `Aircraft`, `Location`,
`FlightType` only:

```java
public void softDelete(@Nullable UUID userId, Clock clock) {
    if (this.deletedOn == null) {
        this.deletedOn = Instant.now(clock);
        this.deletedByUserId = userId;
    }
}
/** ...Spring Data publishes a …Saved event … */
@DomainEvents
Collection<Object> domainEvents() { … }
```

`Flight` (`softDelete(Instant at)`, cascades to crew) and `Person`
(`softDelete(userId, clock, hasOtherTenantMemberships)`, refuses across active
cross-tenant memberships, cascades to `PersonClub`) have genuinely **different**
soft-delete behavior — they were never part of the clone and are not "the shared
shape." So the question was always about the three.

The clone is two parts with opposite extractability:

1. **The soft-delete state + transition** (`deletedOn`, `deletedByUserId`,
   `softDelete(userId, clock)`, `isDeleted()`) references nothing outside
   `java.time` / `java.util`. It is mapped state plus one pure method — the
   textbook `@MappedSuperclass` case.
2. **The `@DomainEvents` saved-event hook** looks identical but each aggregate
   must return its OWN module-local event type (`AircraftSaved`,
   `LocationSaved`, `FlightTypeSaved`, …). The flight-report read-model
   projector and the rename-propagation handlers subscribe to those concrete
   types. Lifting the hook into a shared base forces that base to import every
   bounded context's event class — a dependency inversion across the Spring
   Modulith boundary (the shared kernel would depend on each module). A
   template-method that lets subclasses supply their event puts the per-aggregate
   override back in every class, so it removes no duplication anyway.

The three prior ratchet declines treated (1) and (2) as one indivisible
"the `@MappedSuperclass` this file avoids" — and kept re-opening because (1)
genuinely *should* be extracted while (2) genuinely *should not*.

## Decision

Split the two parts. Execute the extraction for (1); permanently decline (2).

1. **`ch.alpenflight.platform.persistence.SoftDeletableAggregate`** — a
   `@MappedSuperclass` carrying the two soft-delete columns (`deleted_on`,
   `deleted_by_user_id`, the latter marked `@PersistedAuditActor`), the
   idempotent `softDelete(@Nullable UUID, Clock)`, and `isDeleted()`. `Aircraft`,
   `Location`, `FlightType` extend it. Behavior-neutral: same columns, same
   idempotent semantics, proven by the existing domain tests + the FlightReport
   projection / rename ITs passing unchanged.

2. **The `@DomainEvents` saved-event hook stays on each aggregate.** It is NOT
   abstracted — abstracting it would couple the shared kernel to every module's
   event type (ADR 0023 layering + Spring Modulith boundary). Its ~15-token
   residual is below CPD's 50-token window and no longer clones now that the
   `softDelete` block above it is gone.

3. **No tenancy in the base.** `@TenantId` stays per-aggregate (`Location` /
   `FlightType` map it on `club_id` / `operating_club_id`; `Aircraft` is
   cross-tenant with no discriminator, S-058). The arch/leakage introspection
   (`TenantScopedEntityCatalog`) reads the `@TenantId` *declared* field, so it
   must remain on the entity, not a base.

## Consequences

- The 56-token lifecycle clone is gone; cpd-baseline ratchets `4883 → 4827`.
- `Flight` and `Person` keep their own `softDelete` — they do not extend the
  base, by design; their deletion semantics are not shared.
- **The ratchet stops re-litigating.** The cpd-baseline comment now references
  this ADR for the saved-event residual instead of re-deriving "avoid
  `@MappedSuperclass`" each time. The earlier "this codebase deliberately avoids
  `@MappedSuperclass`" blanket stance is superseded: `@MappedSuperclass` is the
  right tool for boundary-clean shared mapped state (soft-delete), and the wrong
  tool for module-coupled behavior (the saved-event hook). The distinction, not
  a blanket ban, is the rule.
