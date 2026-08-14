# 0030 — Personal data in the mutation-audit trail (anonymous-write client IP)

- **Status:** PROPOSED — drafted by `/do-retro` 2026-08-14 from the operator's
  `[ANON-WRITE-ATTRIBUTION]` adjudication. **Not in force until the operator accepts.**
- **Date:** 2026-08-14
- **Scope:** Whether `t_mutation_audit_event` may hold personal data, and under what
  retention/redaction terms. Triggered by anonymous public registration (J-17); written
  to generalise to any future unauthenticated write.

## Context

Every mutation in AlpenFlight writes an audit row via `MutationAuditEventListener`.
Until J-17 every writer was an authenticated principal or a scheduled job, so the row
identified its actor with ids the club already holds — no new personal data.

J-17 shipped the first **unauthenticated write endpoints** (`/discovery-flight/:clubSlug`,
`/scenic-flight/:clubSlug`) plus an abuse guard keyed on client IP × club slug. Two gaps
followed, both recorded in `[ANON-WRITE-ATTRIBUTION]`:

1. An anonymous internet registration is **indistinguishable from a cron job** in
   `/system/logs` — `system_actor=true`, both actor ids null. `AnonymousActorProjectionIT`
   pins that `actor_kind` does not separate them.
2. **No client IP is recorded anywhere.** The guard can throttle a source it cannot name,
   so when it trips the audit trail cannot answer "who".

Closing (2) means deliberately storing personal data (an IP address is personal data under
GDPR) in the audit table for the first time. That is an architecture + legal decision, not
a schema one — which is why J-17 filed it for the operator instead of fixing it in-journey.

## Decision (operator, 2026-08-14)

**Record the raw client IP on anonymous public-registration writes only**, with a bounded
retention window:

- `actor_kind = ANONYMOUS_PUBLIC` — a distinct actor class from a system/cron actor
  (`system_actor = false`). This alone closes gap (1) and carries no personal data.
- `client_ip` — the **raw** address, written **only** on anonymous public-registration
  writes. Never on authenticated mutations, which are already attributable.
- **Retention: 90 days.** A scheduled job nulls `client_ip` on rows older than the window
  and **keeps the audit row**. Redaction, not deletion — the trail survives, the personal
  data does not.
- **Redaction on request** must be possible ahead of the window.
- A **privacy-notice entry** naming the purpose (abuse investigation), the 90-day window,
  and the redaction path ships with it — part of the AC, not a follow-up.

Purpose limitation is what makes the raw address proportionate: it exists to investigate
abuse of an unauthenticated, row-writing, publicly reachable endpoint, and it expires.

### Rejected alternatives

- **Actor class only, no IP.** Zero new personal data, but a tripped guard still cannot
  name its source — the operator judged the forensic gap the larger risk on an endpoint
  that is unauthenticated and writes rows.
- **Hashed or truncated IP** (salted hash, or `/24`). Correlates a burst without holding a
  raw identifier, but is still personal data — it buys a weaker investigation for a
  retention question it does not remove.

## Consequences

- `t_mutation_audit_event` becomes a table with a **retention obligation**. Any future
  column must be assessed the same way; "the audit table is append-only forever" no longer
  holds unqualified.
- A new scheduled redaction job joins the J-15 jobs console, and needs its own proof —
  a job that silently stops running turns a bounded window into an unbounded one.
- `AnonymousActorProjectionIT.actor_kind_does_not_separate_the_two_rows` is the intended
  tripwire and goes red when this ships.
- Extends, does not amend, [ADR 0008](0008-multi-tenancy-mechanism.md) — the anonymous
  write already runs inside an explicit `Tenants.runAs` window, and the audit row stays
  club-scoped.

## Open for the operator

1. Accept, or send back with a different retention window / a hashed variant.
2. Whether the redaction job is club-scoped or global (it reads across tenants by nature).
3. Whether the privacy-notice text is in scope for the implementing journey or handled
   outside the repo.
