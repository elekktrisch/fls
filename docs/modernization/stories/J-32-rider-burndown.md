---
id: J-32
title: Rider burndown — drain every S1 + S2 boyscout rider (hardening)
epic: E-13
status: todo
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
migration: N/A — hardening
parity_test: alpenflight/web/e2e/tests/real-idp/audit-log-two-club.spec.ts
adr_refs: [0008, 0022, 0026]
---

## Context

`_BOYSCOUT.md` holds 39 S1 and S2 riders. Neither oldest-first nor severity-first drained it: a
per-journey 40% slot cannot keep up with the discovery rate, and suppressing the filing would be
worse, because the riders come from real `gap-hunter` and worker findings. The operator chose a
dedicated hardening journey.

The journey has a centre, not only a list. Four of the ten S1 riders exist because the J-31 comment
sweep deleted the only record of an invariant and put nothing in its place. Each of those invariants
guards tenancy or the audit trail, and each is enforced today by an absence: nothing fails when
somebody breaks it. `/system/logs` is the screen where those invariants become visible, so the
journey reuses it for the proof instead of re-running an unrelated screen.

## Spec must assert

The proof runs against the built audit-trail screen with a real IdP and two clubs.

1. **Attribution.** After a write, the row for that write names the actor. An anonymous public
   registration and a scheduled job are distinguishable on the screen. Today both render
   `system_actor=true` with two null actor ids (`PublicRegistrationTxWriter.java:147`;
   `AnonymousActorProjectionIT:143` pins that `actor_kind` does not separate them).
2. **Tenant isolation.** A club-1 principal reads club-1 rows only. The existing two-club spec
   proves the screen; this journey adds the allow-list assertion, because
   `ManifestTenantBypassAllowListTest.java:23` holds twelve entries where the deleted comment said
   eleven, and `AUDIT_LOG` is the entry nobody can explain.
3. **Redaction.** A denied field renders `[redacted]`. The application refuses to start when a
   configured field name resolves to no field, so a rename cannot silently empty the audit trail
   (`PiiRedactor` ↔ `application.yml`).
4. **Bundle envelope.** `ClubSpec` carries no deployment-scoped component, proven by an arch test
   or an IT, not by a comment.

Plant a violation for each of 2, 3 and 4, and score the OLD code, per
[[feedback_gate_must_prove_a_red_per_input_class]].

## Rider inventory — 10 S1 + 29 S2

`/do-ship` sizes the task list from these. The waves are a sequence, not a task split.

**Wave 1 — lost invariants become machine guards (4 S1).** `[CLUBSPEC-MUST-NOT-CARRY-DEPLOYMENT-ID]`,
`[LOST-INVARIANTS-NEED-GUARDS]`, `[MANIFEST-TENANT-BYPASS-COUNT]`,
`[AUDIT-REDACTION-BINDS-FIELD-NAMES-AS-STRINGS]`. These carry the journey's proof.

**Wave 2 — decisions the operator owns, not code (2 S1).**
`[DEAD-BUT-WIRED-IMPERSONATION-INTERCEPTOR]` and `[ANON-WRITE-ATTRIBUTION]`. The second is a privacy
decision under GDPR: whether a client IP belongs in the audit table, with what retention. Ask at the
start of the journey, because both block their tasks.

**Wave 3 — proof honesty (1 S1, 3 S2).** `[MONEY-PROOF-CAPTION-OVERCLAIMS]`, `[SPEC-TITLES-OVERCLAIM]`,
`[VACUOUS-NARROWING-ASSERTIONS]`, `[REAL-IDP-SPECS-MUST-NOT-page.route]`. The gallery caption claims a
balance-equality proof the spec never made, on an accounting surface.

**Wave 4 — migration and parity reds (3 S1, 3 S2).** Producer dedupe is soft-delete-blind, J-9
article-5001, J-8 AccountingRuleFilter, `[MAPPER-VS-SCHEMA-TEST-RED-SINCE-J-13]`, J-0c Location
migrated render, fanout has no reporting spec over migrated data.

**Wave 5 — gate coverage holes (8 S2).** `[COMMENT-GATE-DOES-NOT-COVER-GITHUB-DIR]`,
`[WEB-SCRIPTS-ARE-TYPECHECKED-BY-NOTHING]`, `[INLINE-ANGULAR-TEMPLATES-ARE-NOT-TYPECHECKED]`,
`[E2E-TSCONFIG-NODE10-REJECTED-BY-TS6]`, `[ABSOLUTE-DATE-GUARD-READS-THREE-FIELDS-ONLY]`,
`[THEME-GUARD-MISSES-PROTOCOL-RELATIVE-URLS]`, `[CHECK-THEME-LOAD-IS-ROTTEN-AND-UNWIRED]`,
`[FANOUT-RED-IS-INVISIBLE]`.

**Wave 6 — audit and observability on the proof screen (3 S2).** `[AUDIT-ACTOR-CELL]`,
`[REQUEST-ID-NEVER-LOGGED]`, `[TENANT-ISOLATION-IT-PREFIX-COLLISION]`.

**Wave 7 — remainder (12 S2).** `[PERSONS-DETAIL-ROUTE-MAY-BE-SHADOWED]`, `[RESERVATIONS-EVICTED-BODY]`,
`[FORM-FIRST-PAINT-RED]`, `[MOCK-CLUB-ID-SHAPE]`, `[LEGACY-J2-READINESS]`, `[SUITE-ISOLATION]`,
`[GH-PAGES-HISTORY-IS-UNBOUNDED]`, `[BARE-SIGNUP-JOIN-FUNNEL-UNCOVERED]`, un-mask the
migration-ingest constraint in dev/test, op-field-mutate test coverage, JIT-username robustness,
orval positional `getN` naming.

**Excluded, with the reason.** `[MIGRATE-HANDSHAKE-403-FOR-CLUBLESS-REGISTRANT]` [S1] stays filed.
The rider homes it to J-21, which owns the migrate surface, and the operator decided on 2026-08-16
that J-19 files the defect and does not fix the backend.

## Notes

**No design reference exists for this screen.** `docs/modernization/design-reference/` holds no audit
or system screen, so the journey keeps the built screen's shape and changes only what a rider names.

**The gallery bookmark trap.** `audit-log-two-club.spec.ts` tags its `proofVideo` entries
`journey: 'J-30'` and `journey: 'J-13'`. The clean-seed gallery guard reds while the current-journey
page holds 0 videos, so re-tag at least one `proofVideo` to `journey: 'J-32'` in the first task, not
at the gate ([[project_clean_seed_proof_gallery_journey_tag]]).

**Convert as you delete, never delete then file.** Wave 1 exists because J-31 deleted comments and
filed riders. Every task in this journey that removes a comment must leave a name or a machine guard
in its place. The `.github/` comment sweep is filed as a Wave 5 rider and must follow the same rule:
`ci.yml` comments record why `!cancelled()` is load-bearing and why the seed profiles are separate.

**Size, stated plainly.** 39 riders is large for one journey. The done-bar is the operator's filed
intent, so the carve keeps it. The waves let `/do-ship` land value in order if the journey runs long.
Wave 1 alone satisfies the journey bar of one provable screen result plus a green gate.

**Seams for `/do-ship`.** `PiiRedactor` ↔ the redaction config; the manifest tenant-bypass allow-list
and its test; `ClubSpec` plus the bundle-envelope mapper; `PublicRegistrationTxWriter` plus the actor
projection; the three "deliberately NOT `@Transactional`" methods named in
`[LOST-INVARIANTS-NEED-GUARDS]`.

## Assumptions made

- The proof screen is `/system/logs` because the S1 cluster lands on it. The roadmap said only "a
  built screen".
- The waves are my grouping, not the operator's. `/do-ship` may re-order them.
- `[GH-PAGES-HISTORY-IS-UNBOUNDED]` sits at S2. It was filed S1 on 2026-08-19 and lowered when
  `filter: blob:none` removed the broken-build symptom. The operator may raise it again.
- S3 riders (24) stay out of scope, per the roadmap's S1+S2 done-bar.
