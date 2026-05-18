# Execution order

Topological sort of stories grouped by phase. Within each phase, listed in dependency-respecting order. Items on the same indent level have no dependency between each other and can run in parallel.

**Format:** `S-NNN — estimate — title`

**Ordering principle (revised 2026-05-17 — walking-skeleton first):** ship the thinnest possible user-visible vertical slice (login → tenant-scoped CRUD → e2e green) as early as possible, then thicken iteratively. Each piece of infrastructure lands **just before the first story that requires it**, not as a separate "complete the foundation" phase.

- **Phase B** is critical-path only — Keycloak, dev compose, server-side auth, SPA auth, tenant resolver, FE toolchain (codegen + Signal Store reference + Reactive Forms + component kit).
- **Phase C** is schema completion (mostly already done) — *no longer* a kitchen-sink "auth + tenancy + audit + machine client + translations" bundle. Items like audit-log infrastructure (S-027), unscoped tenant context (S-023), public-flow tenant resolution (S-025), Proffix machine client (S-029), and translations (S-057) move to **the phase that first needs them**.
- **Phase D** is the **walking skeleton**: one reference entity (slim S-047) + Locations CRUD (S-049) + cross-tenant leakage test (S-024). End-to-end proof of architecture: schema → repo → service → DTO → controller → Signal Store → Angular form → e2e test → tenant isolation. Operator can demo this.
- **Phase E** thickens master data; deferred-from-old-C items land here as their consuming story arrives (S-027 before Clubs, S-057 when first translated screen ships).
- **Phases F+** unchanged in shape but renumbered.

**Production-side infrastructure and production-side observability remain late** (Phase K/L), unchanged from the 2026-05-15 revision.

## Phase A — Foundations (zero-dep, start day 1)

Skeletons + inventory work that informs everything downstream. These have no dependencies and can begin immediately, in parallel.

- S-001 — M — Scaffold next/server/ Spring Boot skeleton ✓ **done**
  - S-123 — S — Lock down springdoc off-state until S-003 wires it (rework follow-up from S-001)
  - S-124 — S — Annotate next/server/.env.example keys with usage notes (rework follow-up from S-001)
  - S-125 — S — Surface open-in-view=false consequence in next/server/README.md (rework follow-up from S-001)
- S-002 — M — Scaffold next/web/ Angular skeleton ✓ **done**
  - S-126 — S — Tighten pnpm-store cache restore-keys in CI (rework follow-up from S-002)
  - S-127 — S — Enforce atomic-design + cross-feature layering via ESLint (rework follow-up from S-002; deps S-008)
- S-010 — M — Extract production-schema parity baseline ✓ **done**
- S-093 — S — Inventory every Excel export
- S-108 — S — Production performance baseline (top 5 routes p95)
- S-120 — S — Product slug + folder rename (can fire any time)
- S-128 — M — Technical rebrand FLS → AlpenFlight ✓ **done**

**External-coordination slots (start early — calendar-bound, not engineering-bound):**

- S-114 — M — OGN maintainer handoff coordination (deps S-066 to *test*, but outreach starts now)
- S-115 — M — Proffix integration verification

**Legacy-targeted depth coverage — runs against legacy first, re-runs against new stack later:**

- S-101 — L — Expand Playwright depth: validation rejection paths
- S-102 — M — Expand Playwright depth: state-machine illegal transitions
- S-103 — M — Expand Playwright depth: time-gate boundaries
- S-104 — L — Expand Playwright depth: permission boundaries per endpoint
- S-105 — M — Expand Playwright depth: glider↔tow link integrity + cascade
- S-106 — L — Expand Playwright depth: multi-tenant isolation per endpoint

## Phase B — Walking-skeleton enablement (critical-path infra only)

Strict minimum to land the first user-visible vertical slice (Phase D). **No item here exists for its own sake — each is on the critical path for "logged-in Locations CRUD in a browser."** Audit log, unscoped tenant context, public-flow tenant resolver, machine client, translations — all deferred to the phase that first needs them.

**Infra services + healthchecks:**

- S-019 — M — Keycloak in docker-compose + realm export (deps S-002 indirectly for SPA client + S-039 for compose service)
- S-153 — S — S-019 rework follow-ups (check-realm-shape + export-realm + misc hardening) — *origin: rework*
- S-039 — M — docker-compose.yml skeleton (dev: backend + Postgres 17 + Keycloak + mailpit) (deps S-001, S-002)
- S-030 — M — Actuator + Micrometer (deps S-001) — *kept for `/actuator/health` healthchecks used by S-039 backend probe*

**API surface (OpenAPI + codegen):**

- S-003 — S — Wire springdoc-openapi (deps S-001)
- S-004 — M — Pick + wire TS codegen (deps S-002, S-003)

**Server-side auth + tenancy plumbing:**

- S-020 — M — Spring Security 7 OAuth2 resource server (deps S-001, S-019)
- S-022 — M — ClubTenantIdentifierResolver + @TenantId (deps S-012, S-015, S-020)
- S-026 — M — Authorization model — roles → @PreAuthorize (deps S-020) — *needed to gate the first endpoint; no later CRUD ships without it*

**Module layering enforcement (ADR 0023 — lands before any new aggregate module):**

- S-155 — L — Module layering template — Spring Modulith + ArchUnit + Clubs reshape (deps S-001, S-022)

**Test infrastructure:**

- S-015 — M — Testcontainers test-DB strategy (deps S-009)

**SPA conventions (pattern stories — every feature copies these):**

- S-006 — M — NgRx Signal Store reference (deps S-002, S-004)
- S-007 — S — Reactive Forms convention (deps S-002, S-004)
- S-008 — M — Component primitives kit (deps S-002) — *first slice consumes atoms + form-field + data-table; rest grows JIT*
- S-021 — L — Angular OIDC client (deps S-002, S-006, S-019)

## Phase C — DB schema completion (mostly done)

Schema foundation. Mostly complete; remaining items are dependency-required by Phase D's walking skeleton.

- S-009 — S — Wire Flyway into Spring Boot ✓ **done**
- S-011 — S — Catalog tenant-scoped entities ✓ **done**
- S-012 — M — V1__baseline part 1: identity + reference data ✓ **done**
- S-013 — L — V1__baseline part 2: flights / aircraft / persons / clubs / locations ✓ **done**
  - S-129 — M — Migrate BOOLEAN columns to string-serialized enums across V2 + V3 (rework follow-up from S-013)
  - S-130 — S — /modernize-refine reconciliation pass (rework-meta follow-up from S-013; workflow improvement)
  - S-131 — S — S-013 deferred review findings (rework follow-up from S-013)
- S-014 — M — V1__baseline part 3: reservations / planning / accounting ✓ **done**
  - S-132 — S — V5 drop business-logic CHECKs ✓ **done**

## Phase D — Walking skeleton (first user-visible vertical slice)

**The first thing an operator can demo.** End-to-end proof of architecture: schema → repo → service → DTO → controller → Signal Store → Angular form → e2e test → tenant isolation. Lands in ~1 sprint after Phase B closes.

- S-047 — M — Reference-data domain (deps S-006, S-007, S-008, S-022) — *first ported domain; establishes the per-domain pattern. **Assumption:** ship `Country` only as the walking-skeleton's reference dropdown, defer remaining reference entities to Phase E to keep Phase D narrow. Note in S-047 `## Tasks` checklist.*
- S-049 — M — Locations CRUD (deps S-047, S-022) — *the user-visible thing: tenant-scoped list + edit form, e2e green.*
- S-024 — M — Cross-tenant leakage CI test (deps S-022, S-011, S-049) — *now there's a real tenant-scoped repository to test against; lands immediately with S-049.*

**Done when:** an operator logs in as a club admin in two different clubs and sees two different Location lists; an e2e test enforces it; CI fails on leakage.

## Phase E — Master data thickening + deferred-from-old-C items

Rest of the master-data CRUD plus the Phase-C items deferred until their first consuming story arrives.

**Deferred-from-old-C items land here as needed:**

- S-027 — L — Audit-log infrastructure (deps S-020, S-022) — *was Phase C; defer until just before the first audit-sensitive mutation. Lands before S-048 Clubs (per S-048 acceptance: "Audit-log entries fire on every mutation").*
- S-057 — M — Translations migrated to bundled JSON (deps S-005) — *was Phase C; defer until first multi-locale screen ships. Lands before S-051 Persons (first parity-translated form).*
- S-005 — S — Pick + wire i18n (deps S-002) — *was Phase B; defer to Phase E since walking-skeleton ships in German only. Block S-057.*

**Master-data CRUD (in dep order):**

- S-047 — (continued) — port remaining reference entities (Language, MemberState, PersonCategory, LengthUnitType, etc.) — *split off the walking-skeleton's `Country`-only slice.*
- S-048 — M — Clubs CRUD (deps S-047, S-026, S-027)
- S-050 — M — Aircraft CRUD (deps S-049)
- S-051 — L — Persons + PersonClub (deps S-048, S-047, S-057)
- S-053 — S — Flight types CRUD (deps S-050)
- S-054 — S — Articles CRUD (deps S-048)
- S-055 — M — Email templates CRUD (deps S-048, S-082 — see Phase G)
- S-052 — L — Users CRUD + role assignment (deps S-051, S-026, S-019, S-020)
- S-056 — M — System data + system-logs view (deps S-027)

## Phase F — Flight operations + reservations + planning

The airfield hot-path (vision C23). Where most user value lands.

- S-058 — M — Flight entity + FlightAircraftType discriminator (deps S-013, S-050, S-051, S-053)
- S-059 — L — FlightProcessState transition matrix (deps S-058)
- S-060 — S — FlightAirState computed state (deps S-058)
- S-061 — M — Time-gate enforcement (deps S-059)
- S-062a — M — Flight CRUD backend + DTOs + validator port (deps S-058, S-059, S-060)
- S-062b — M — Flight list page (deps S-062a, S-006, S-008)
- S-062c — M — Flight create/edit forms + copy flow (deps S-062a, S-062b, S-007)
- S-063 — M — Glider↔Tow link integrity (deps S-062a)
- S-064 — M — Air movements (motor aircraft) (deps S-062a, S-062c)
- S-067 — M — Optimistic-concurrency on Flight (deps S-058)
- S-065 — L — Flight reports + custom report builder (deps S-062a, S-093)
- S-066 — M — OGN ingestion REST endpoint (deps S-058, S-023, S-029) — *S-023 + S-029 deferred from old-C; both land here before OGN.*
- S-068 — M — AircraftReservation CRUD (deps S-050, S-051)
- S-069 — L — Reservation scheduler (deps S-068)
- S-070 — M — PlanningDay CRUD (deps S-068, S-051)
- S-071 — M — Planning-setup wizard (deps S-070)

## Phase G — Scheduled jobs infrastructure + ports

Jobs infrastructure needs mailpit + scheduling baseline. Pulls in the unscoped-tenant-context + ShedLock plumbing deferred from old-C.

**Deferred-from-old-C items land here:**

- S-018 — S — ShedLock stub table (deps S-009) — *was Phase B/C; needed only when scheduled jobs ship.*
- S-023 — M — UnscopedTenantContext mechanism (deps S-022) — *was Phase C; needed only by scheduled jobs (cross-club iteration) + OGN ingest. Lands before S-081.*

**Jobs:**

- S-081 — M — Spring @Scheduled infrastructure (deps S-001, S-026, S-018, S-023)
- S-082 — S — JavaMailSender + Thymeleaf baseline (deps S-001, S-039)
- S-083 — M — Port DailyFlightValidationJob (deps S-081, S-059, S-061)
- S-084 — M — Port DailyReportJob (deps S-082, S-083)
- S-085 — M — Port LicenceNotificationJob (deps S-082, S-051)
- S-086 — M — Port PlanningDayNotificationJob (deps S-082, S-070)
- S-088 — M — Port AircraftDatabaseSyncJob (deps S-081, S-050)
- S-094 — M — ExcelExportSupport helper class (deps S-001, S-093)
- S-095 — M — Port flight-reports Excel export (deps S-065, S-094)

## Phase H — Rules engine + deliveries (the sacred-cow port)

Most subtle behavior in the system. Pulls in the Proffix machine client deferred from old-C.

**Deferred-from-old-C items land here:**

- S-029 — M — Proffix machine client (deps S-019, S-020) — *was Phase C; only consumed by S-080 Proffix verification. Lands before S-080.*

**Rules engine + deliveries:**

- S-072 — L — AccountingRuleFilter CRUD (deps S-014, S-053, S-054)
- S-073 — M — Rules engine: IgnoreFlight + Recipient (deps S-072, S-058)
- S-074 — L — Rules engine: FlightTime decrement loop (deps S-073)
- S-075 — M — Rules engine: EngineTime decrement loop (deps S-074)
- S-076 — M — Rules engine: single-pass rule types (deps S-075)
- S-077 — M — Rules engine: glider→tow recursion (deps S-076, S-063)
- S-078 — L — Delivery CRUD + transitions (deps S-014, S-077)
- S-079 — L — DeliveryCreationTest harness (deps S-077, S-078)
- S-080 — M — Proffix-compatible API verification (deps S-078, S-029, S-115)
- S-089 — M — Port DeliveryCreationJob (deps S-081, S-077, S-078, S-061)
- S-090 — L — Port DeliveryMailExportJob (deps S-082, S-094, S-078)
- S-087 — M — Port AircraftStatisticReportJob (deps S-082, S-094)
- S-096 — M — Excel parity verification harness (deps S-094, S-095)
- S-107 — L — Rules-engine combinatorial corpus (C11) (deps S-079)

## Phase I — Public flows + UI completion

Pulls in the public-flow tenant resolver deferred from old-C.

**Deferred-from-old-C items land here:**

- S-025 — M — Tenant-from-URL for public flows (deps S-022, S-023) — *was Phase C; only consumed by public flows.*

**Public flows:**

- S-097 — S — Landing page port + nav-bar mechanism (deps S-002, S-008)
- S-098 — M — Trial-flight registration (deps S-097, S-025)
- S-099 — M — Passenger-flight registration (deps S-097, S-025)
- S-100 — S — Lost-password + email-confirmation landing pages (deps S-097, S-019)

## Phase J — Legacy schema-mapping library

The shared mapping library that both the export JAR (S-139) and the server ingest pipeline (S-141) depend on. Lands once V1__baseline parts are in (Phase C).

- S-016 — L — Legacy schema-mapping library + parity oracle (deps S-012, S-013, S-014)
- S-028 — M — Bulk-provision tenant users in Keycloak (admin endpoint) (deps S-019, S-026, S-052, S-141)

## Phase O — Self-service migration & freemium SaaS (E-15)

The migration-path feature introduced by vision amendment 2026-05-17c. Reorders late in the sequence because:
- it depends on master-data CRUD (Phase E) + flight operations (Phase F) — there's nothing to migrate *into* before those tenant-scoped entities exist;
- it depends on scheduled-jobs infrastructure (S-081 in Phase G) for the trial-expiry + sandbox-reset crons;
- it consumes the Keycloak realm config (S-019) + the Angular OIDC client (S-021) but extends them with signup-enabled flows;
- the freemium gate annotation (S-143) shapes how feature epics (E-09 deliveries, E-10 jobs, E-11 Excel) wire their controllers — those epics' stories will be re-checked against the gate list during refine.

**ADRs required before story refinement:** 0018 (lifecycle), 0019 (bundle format + encryption), 0020 (feature-gate mechanism), 0021 (billing provider). Recommended sequence below; ADR 0018 unblocks the most stories.

**Deployment + lifecycle scaffolding (start early; unblocks the rest):**

- S-137 — M — Deployment entity + lifecycle state machine + job filter (deps S-048)

**Sandbox demo (parallel branch — can start once S-137 is in):**

- S-135 — M — Sandbox demo Deployment: seed data + nightly reset (deps S-047, S-048, S-049, S-050, S-051, S-058, S-068, S-081, S-137)
- S-136 — M — Anonymous demo-session scoping (deps S-022, S-135, S-137)

**Signup + landing CTAs:**

- S-134 — M — Keycloak self-service signup + Google IdP federation (deps S-019, S-021)
- S-133 — S — Public marketing landing CTAs (deps S-097, S-008)

**Migration transport (the JAR + upload pipeline):**

- S-139 — L — Legacy export JAR (deps S-016)
- S-140 — M — Per-upload keypair handshake (deps S-134)
- S-141 — L — Encrypted-bundle upload + decrypt + ingest pipeline (deps S-016, S-138, S-140)
- S-138 — M — Trial-Deployment provisioning on first successful ingest (deps S-134, S-137, S-141)
- S-142 — M — Trial countdown + 72 h hard-delete cron (deps S-137, S-138, S-141, S-081)

**Freemium + billing:**

- S-143 — M — Feature-gate annotation + 402 contract (deps S-026, S-137)
- S-144 — S — Freemium UI upgrade-prompt (deps S-008, S-143)
- S-145 — L — Subscription billing integration (deps S-137, S-138)
- S-146 — S — Trial-to-paid promotion (deps S-137, S-142, S-145)

**Cross-cutting:**

- S-147 — S — Funnel telemetry events (deps S-031, S-133, S-134, S-136, S-138, S-140, S-141, S-142, S-145, S-146)

**Per-tenant integration handoffs (run any time after the upstream maintainer is contacted):**

- S-149 — M — OGN ingest endpoint — per-tenant handoff with upstream maintainer (deps S-066)
- S-150 — M — Proffix integration — verify live consumer call pattern

## Phase K — Production infrastructure (deployment build-out, deferred until features work)

**All production-side deployment infrastructure lands here**, after features are functional and the schema-mapping library has been exercised end-to-end through CI. Order: production runtime → production hosting → production resilience.

- S-040 — M — Production Dockerfile (deps S-001)
- S-044 — M — VPS provider selection + provisioning
- S-041 — M — Reverse proxy: Caddy vs Traefik (deps S-039, S-044)
- S-046 — M — Helm/Kustomize manifest stub (deps S-039, S-040, S-041)
- S-042 — M — Off-site pg_dump backup (deps S-039, S-044)
- S-043 — M — Restore runbook + dry-run drill (deps S-042)
- S-037 — S — External uptime probe (deps S-030, S-044)
- S-045 — S — K8s-migration trigger criteria (pure documentation, can fire any time once S-044 lands)
- S-091 — M — Production SMTP relay selection (deps S-082, S-044)
- S-151 — M — Production Keycloak deployment (deps S-019, S-041, S-044, S-042)
- S-152 — S — Rename `next/` → `alpenflight/` working subtree (no deps; fires whenever)

## Phase L — Production observability (production-side telemetry)

Observability stack ships at production-deploy time. Application-side hooks (S-030 Actuator) are already in Phase B; structured logging + observability containers + dashboards land here.

- S-031 — M — Structured JSON logging (deps S-001, S-020, S-022)
- S-034 — M — GlitchTip in compose (deps S-001, S-002, S-039)
- S-032 — M — Loki + Grafana in compose (deps S-031, S-039)
- S-033 — M — Prometheus in compose (deps S-030, S-032, S-039)
- S-035 — M — Default Grafana dashboards (deps S-032, S-033)
- S-036 — M — Alert rules as code (deps S-035)
- S-038 — M — Scheduled-job instrumentation pattern (deps S-030, S-081)

## Phase M — Test corpus completion + go-live prep

- S-109 — L — Port full Playwright suite to new stack (deps S-002, S-057, S-097)
- S-110 — S — T3-equivalent smoke (deps S-020, S-021, S-062c)
- S-111 — M — Performance verification (deps S-108, S-109, S-046)
- S-092 — S — Decommission legacy libs from new-stack codebase (deps S-083..S-090)

---

## How to use this

- **Right now** — Phase B is the critical path. Sequence: S-019 + S-039 + S-030 in parallel → S-003 → S-004 → S-020 + S-022 + S-026 (parallel) → **S-155 (layering enforcement; lands before any new aggregate module)** → S-006 + S-007 + S-008 + S-021 (parallel) → **Phase D walking skeleton (S-047 slim + S-049 + S-024)**. S-155 ships the four-package template; every new module (S-047, S-049, S-050, …) is scaffolded into that template from commit one. If S-047 refines before S-155 lands, hold the implement until S-155 is in — the boyscout cost of reshaping reference data later is larger than waiting one story.
- **Walking skeleton is the milestone to optimize toward.** Operator can demo end-to-end behavior the moment Phase D closes. Every story choice before then is "does this block the demo?"
- **Phase E thickens; deferred items land here.** Don't pull S-027 (audit), S-057 (translations), S-005 (i18n picker) earlier "just to be done with them" — they land when their first consuming story arrives.
- **Test corpus expansion (S-101..S-106)** — runs against legacy first, can begin in parallel with any phase.
- **Don't start Phase H (rules engine) until Phase F's flight model is stable** — the parity port has nothing to compare against without it.
- **S-107 (rules-engine corpus) is the long pole of E-13** — schedule explicitly; can take a full week.
- **Production infrastructure (Phase K) intentionally deferred.** Features mature against dev-loop compose; production infra build-out happens only when feature work is largely in place.
- **Phase L observability** can pull forward selectively if a feature epic generates enough debugging pain — S-031 (structured logging) and S-034 (GlitchTip error tracking) are the most likely candidates.
- **Phase O (E-15 self-service migration) lands after Phases E–G** but its ADRs (0018–0021) should be drafted earlier so the affected stories elsewhere (S-016, S-028, S-081, S-097, plus the seven gated feature surfaces — Excel, Proffix, notifications, scheduled-jobs opt-ins) carry the right amendments when they're refined. Recommended ADR sequence: 0018 → 0019 → 0020 → 0021.
- **No centralized cutover phase.** Migration is a self-service product feature (E-15). The S-016 mapping library + CI parity oracle is the rehearsal mechanism; each tenant onboards via the JAR + upload UI on its own schedule.

## What changed in this revision (2026-05-17 — walking-skeleton first)

Previous ordering (2026-05-15) bundled all of auth + tenancy + audit + machine client + translations into Phase C and ran Phase D feature work only after all of it landed. Revised ordering pulls the first user-visible vertical slice (Locations CRUD) to land as early as possible.

**Moved OUT of Phase B/C (defer to first consuming story):**

| Story | Was | Now | Reason |
| --- | --- | --- | --- |
| S-005 (i18n picker) | Phase B | Phase E (before S-057) | Walking skeleton ships in German only; no i18n needed |
| S-018 (ShedLock stub) | Phase B/C | Phase G (before S-081) | Only needed when scheduled jobs ship |
| S-023 (UnscopedTenantContext) | Phase C | Phase G (before S-081) | Only needed by scheduled jobs + OGN ingest |
| S-025 (Tenant-from-URL) | Phase C | Phase I (before S-098/S-099) | Only needed by public flows |
| S-027 (Audit-log infra) | Phase C | Phase E (before S-048 Clubs) | First audit-required mutation is on Clubs |
| S-029 (Proffix machine client) | Phase C | Phase H (before S-080) | Only consumed by Proffix verification |
| S-057 (Bundled JSON translations) | Phase C | Phase E (before S-051 Persons) | Walking skeleton runs single-locale |

**Pulled IN to Phase D (immediately with walking skeleton):**

| Story | Was | Now | Reason |
| --- | --- | --- | --- |
| S-024 (Cross-tenant leakage test) | Phase C | Phase D (after S-049) | Needs a real tenant-scoped repo to test against — meaningless before Locations exists |

**S-047 (Reference-data domain) split assumption:** ship `Country` only in Phase D as the walking skeleton's reference dropdown; remaining reference entities (Language, MemberState, PersonCategory, length/elevation/counter units, StartType) defer to Phase E. Captured as an assumption to flag for operator. If S-047 should be split formally into S-047a / S-047b, propose during `/modernize-refine S-047`.

**Trade-off accepted:** the new ordering creates more "land just-in-time" coupling (S-027 ↔ S-048, S-005+S-057 ↔ S-051, S-023+S-018 ↔ S-081, S-025 ↔ S-098, S-029 ↔ S-080). Risk: a dependency surprise during refine could push back the consuming story. Mitigation: each deferred item is small-to-medium (S/M), so the slip is bounded; and the walking-skeleton ships meaningful value 4–6 stories earlier in calendar time than the previous ordering.

## What changed in the prior revision (2026-05-15)

Previous-previous ordering interleaved production infrastructure (S-039–S-046, S-037, S-091, S-116) and production observability (S-031–S-036, S-038) with feature foundation work in Phases B/C. The 2026-05-15 revision moved them to Phases J/K. The 2026-05-17 revision keeps that move; it only restructures the *pre-feature* phases (B, C, D) for walking-skeleton-first delivery.
