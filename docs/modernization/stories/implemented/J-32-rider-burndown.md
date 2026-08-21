---
id: J-32
title: Rider burndown — drain the S1 boyscout riders (hardening)
epic: E-13
status: done
started_at: 2026-08-20
done_at: 2026-08-21
journey0: false
hardening: true
carved: true
depends_on: []
rolls_up: []
acceptance:
  - "[happy] The operator opens /system/logs after a write and sees that write's row, with the actor who made it."
  - "[happy] An anonymous public registration and a scheduled job produce audit rows the screen tells apart."
  - "[key-error] A club-1 operator never sees a club-2 audit row, and the tenant-bypass allow-list holds only its reviewed entries."
  - "[edge] A redacted field renders [redacted], and the application refuses to start when a redaction rule names a field that does not exist."
  - "[edge] The bundle-envelope mapper rejects an inbound deployment_id, so a crafted bundle cannot move a Club to another Deployment."
  - "[happy] The S1 and S2 rider count in _BOYSCOUT.md reaches zero, and each shipped bullet is deleted."
screen: /system/logs — the built audit-trail screen, reused for the proof
headless_pulled_in: audit redaction + tenant-bypass allow-list + actor attribution → /system/logs
migration: mapper-touching — T-12 edits a producer mapper, so `fan-out parity` is a hard merge gate (the carve said N/A)
parity_test: alpenflight/web/e2e/tests/real-idp/audit-log-two-club.spec.ts
adr_refs: [0008, 0022, 0026, 0030]
---

## Context

Four of the ten S1 riders existed because the J-31 comment sweep deleted the only record of an
invariant and put nothing in its place. Each invariant guards tenancy or the audit trail, and each was
enforced by an absence: nothing failed when somebody broke it. `/system/logs` is where those
invariants become visible, so the journey reused that screen for its proof.

## Outcome

The journey shipped every S1 rider and re-filed the S2 tail (operator, 2026-08-21). It converted five
lost invariants into machine guards and found eight live defects that no test was failing on:

- A crafted migration bundle could move a Club into another owner's Deployment. Planting the field in
  production proved it: `clubCountIn(anotherOwnersDeploymentId)` expected 0, was 1 (T-04).
- Four scheduled jobs reached their own `@Transactional` method through `this`, so the annotation was
  inert and a mid-run failure kept partial writes. A fifth site, `MeasuredJobAspect`, caught `Throwable`
  **inside** the transaction advisor, so fixing only the four would have committed the partial write
  anyway (T-46).
- A client IP landed on rows the retention sweep could never reach, and one such row already sat in the
  database. The privacy notice's 90-day promise did not hold for that class (T-49).
- `POST /persons/{id}/clubs` answered 500 on every call. The endpoint had no test (T-45).
- The retention sweep would have thrown Postgres 42501 in production while every test stayed green,
  because tests run as the migrator role and `V54` had revoked `UPDATE` from the app role (T-08b).
- The sweep enumerated active clubs only, so a retired club kept its IP addresses for ever (T-08b).
- A migrated Location whose legacy ICAO did not match the aggregate's pattern could never be saved. A
  `test.skip` had hidden it for months behind a recorded reason that named a slow runner (T-66).
- The audit redaction config was inert for six entity types, because the call sites pass response DTOs
  while the config named entities. The direction was over-redaction, never a leak (T-05, T-45).

**J-32 also created and then removed a false green of its own.** T-02 scoped the per-push lane on the
claim that the nightly covered the two showcase-seed proofs. It did not: the nightly filter yields zero
tests from either spec, and `required` scored a skipped job as success. T-52 restored the only
executing lane, narrowed `skipped`-as-success to three named cases, and put the guard in a graph-root
job. Four defects were later found inside guards this journey shipped (T-59, T-60, T-62, T-63).
T-67 found a fifth. The `fan-out parity` gate read evidence that existed only when a person pressed
a button, and the manager pressed it four times. Its transient read also named an older red run
while a newer run was in flight, so it refused a merge over a defect the commit under test had
already repaired. A git push now arms the fan-out, and the gate waits for the run to answer.

**Four riders were stale.** The article-5001, `AccountingRuleFilter` and J-0c Location defects were
fixed by J-27 on 2026-06-20 and nobody deleted the bullets; the manifest count rider named an innocent
entry. Each was closed by measurement, and each left its spec asserting the thing the rider was about —
two of them had been green because the spec had stopped asserting it.

## Adjudications

**`[DEAD-BUT-WIRED-IMPERSONATION-INTERCEPTOR]` — delete all three (operator, 2026-08-20).** Remove
`AuditTargetTenant`, `AuditTargetTenantInterceptor` and the `TenancyWebMvcConfig` registration, and add
a guard that reds when an impersonation HTTP entry point returns. Evidence: `b72f9c6a0` (S-027) added
the annotation for `/api/v1/admin/locations/{clubId}`; `41e1323ba` (S-159) withdrew that surface the
same day; no forward story needs it; [ADR 0008](../adrs/0008-multi-tenancy-mechanism.md) §Amendment
S-159 states `Tenants.runAs` "is never wired through to an HTTP path". Nothing enforced that sentence,
which is the absence that produced the rider.

**Public-registration intake is a reviewed exception to that guard (operator, 2026-08-20).**
`PublicRegistrationController` takes a club slug from four unauthenticated paths. An anonymous
registrant submits to a club's published intake; the registrant does not act as the club. The club
publishes the slug and can close the surface. It joins `ClubsController` in the allow-list.

**`AUDIT_LOG` stays on the cross-tenant allow-list (T-06).** The rider named the wrong entry.
`AUDIT_LOG` shipped in the file's first commit among exactly eleven entries, with
`AuditLog.actor_user_id` recorded as its reason. J-9b added `PERSON_FLIGHT_TIME_CREDIT` as the twelfth
and left the count word at eleven. The grant is real: the audit row keeps its own `tenant_club_id`
discriminator and only the actor crosses tenants. T-06 replaced the count word with a machine-read
grant table and a negative test.

**`[ANON-WRITE-ATTRIBUTION]` (operator, /do-retro 2026-08-14).** `actor_kind = ANONYMOUS_PUBLIC` with
`system_actor=false`, plus the raw `client_ip` on anonymous public-registration writes only. Retention
90 days: a scheduled job nulls `client_ip` and keeps the row. The privacy notice ships with the journey.
[ADR 0030](../adrs/0030-personal-data-in-the-mutation-audit-trail.md) records it and the operator
accepted it on 2026-08-21.

**Licence and medical dates stay in the audit trail (operator, 2026-08-20).** `PersonLicences`
allow-lists `licenceNumber` and six medical and licence expiry dates, and they land verbatim in
`t_mutation_audit_event.after_state`. Safety-of-flight licence currency and medical currency are a
legitimate audit purpose. The behaviour predates J-32 and does not change here.

**A migrated Location keeps its legacy ICAO (operator, 2026-08-21).** Legacy accepts free text; the
aggregate enforces `^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$`. The stored value is retained and the pattern applies
only when the operator changes the field. New Locations still require a conforming code.

**The privacy notice stays source text (operator, 2026-08-21).** The product publishes no privacy
surface, and the notice says so. Where it publishes stays open.

## What the acceptance criteria genuinely prove

- **AC 1 — proven** on the real chain, entered through the nav.
- **AC 2 — partly proven.** The anonymous half runs on the real chain. No HTTP surface produces a
  `SYSTEM` row, so the anonymous-versus-job split is proven in data by `AnonymousActorProjectionIT` and
  on screen only against a mocked row in the mock lane.
- **AC 3 — proven,** with an adversarial club-B row seeded and its absence asserted.
- **AC 4 — proven,** the refusal by a real `SpringApplication` over the test classpath rather than a
  packaged-jar start. T-64 found the guard had been wired by `@Component` alone, so deleting the
  annotation reddened nothing across 125 tests.
- **AC 5 — proven at IT level,** not on the screen: an arch guard plus a crafted-bundle round trip.
- **AC 6 — NOT met.** Every S1 shipped; 33 S2 tasks were re-filed by operator decision, so the rider
  count did not reach zero.

## Task record

38 tasks shipped: T-01 to T-16 and T-19, T-20, T-45, T-46, T-49 to T-53, T-59, T-60, T-62 to T-68. The
carve budgeted 15. Task growth came from the gate and from two `gap-hunter` rounds, which is the
expected shape of a hardening journey.

- [x] T-67 — a git push arms the fan-out, and the gate waits for the run it armed.
- [x] T-68 — a date-picker spec waits for the overlay panel before it types, so the picker cannot
  throw on the screen under proof.

33 S2 tasks were re-filed to `_BOYSCOUT.md`, including ten defects that J-32 tasks found and did not
fix. No finding was dropped.

## Notes that stay load-bearing

**Riders state symptoms reliably and causes unreliably.** Eight riders in this journey named a cause
their own code did not support — a hard delete that was a soft delete through an EF interceptor, a
`save()` that was three different reasons across five call sites, an unexplainable allow-list entry that
was explained in its own first commit. Every one was caught by a worker who measured instead of read.
Treat a rider's symptom as evidence and its diagnosis as a hypothesis.

**A recorded skip reason is a hypothesis nobody re-tests.** The J-0c skip blamed a loaded runner for
months. The real cause was a disabled Save button over a product defect.

**A guard needs a proven red per input class, including its own justification.** T-02 verified its
aggregation edit correctly and still shipped a false claim about the lane that was supposed to back it
up. The `page.route` ban linted 18 bypass shapes clean.
