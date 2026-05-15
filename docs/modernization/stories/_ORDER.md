# Execution order

Topological sort of stories grouped by phase. Within each phase, listed in dependency-respecting order. Items on the same indent level have no dependency between each other and can run in parallel.

**Format:** `S-NNN — estimate — title`

## Phase A — Foundations (zero-dep, start day 1)

These have no dependencies and can begin immediately, in parallel, across as many people as the team has. The first three should start the same day.

- S-001 — M — Scaffold next/server/ Spring Boot skeleton
- S-002 — M — Scaffold next/web/ Angular skeleton
- S-010 — M — Extract production-schema parity baseline
- S-019 — M — Keycloak in docker-compose + realm export committed
- S-039 — M — docker-compose.yml skeleton
- S-044 — M — VPS provider selection + provisioning
- S-045 — S — K8s-migration trigger criteria
- S-037 — S — External uptime probe
- S-093 — S — Inventory every Excel export
- S-108 — S — Production performance baseline
- S-114 — M — OGN maintainer handoff coordination
- S-115 — M — Proffix integration verification
- S-120 — S — Product slug + folder rename (can fire any time)

Legacy-targeted depth coverage — runs against legacy first, re-runs against new stack later:

- S-101 — L — Expand Playwright depth: validation rejection paths
- S-102 — M — Expand Playwright depth: state-machine illegal transitions
- S-103 — M — Expand Playwright depth: time-gate boundaries
- S-104 — L — Expand Playwright depth: permission boundaries per endpoint
- S-105 — M — Expand Playwright depth: glider↔tow link integrity + cascade
- S-106 — L — Expand Playwright depth: multi-tenant isolation per endpoint

## Phase B — Project skeleton wiring

Once S-001 and S-002 land:

- S-003 — S — Wire springdoc-openapi (deps S-001)
- S-009 — S — Wire Flyway into Spring Boot (deps S-001)
- S-018 — S — ShedLock stub table (deps S-009)
- S-040 — S — Production Dockerfile (deps S-001)
- S-005 — S — Pick + wire i18n (deps S-002)
- S-008 — M — Component primitives kit (deps S-002)
- S-004 — M — Pick + wire TS codegen (deps S-002, S-003)
- S-006 — M — NgRx Signal Store reference (deps S-002, S-004)
- S-007 — S — Reactive Forms convention (deps S-002, S-004)
- S-011 — S — Catalog tenant-scoped entities (deps S-010)
- S-030 — S — Actuator + Micrometer (deps S-001)

## Phase C — Auth + tenancy + observability + hosting foundations

- S-020 — M — Spring Security 7 OAuth2 resource server (deps S-001, S-019)
- S-021 — L — Angular OIDC client (deps S-002, S-006, S-019)
- S-026 — M — Authorization model — roles → @PreAuthorize (deps S-020)
- S-029 — M — Proffix machine client (deps S-019, S-020)
- S-012 — M — V1__baseline part 1: identity + reference data (deps S-009, S-010, S-011)
- S-013 — L — V1__baseline part 2: flights / aircraft / persons / clubs / locations (deps S-012)
- S-014 — M — V1__baseline part 3: reservations / planning / accounting (deps S-013)
- S-015 — M — Testcontainers test-DB strategy (deps S-009)
- S-022 — M — ClubTenantIdentifierResolver + @TenantId (deps S-012, S-015, S-020)
- S-023 — M — UnscopedTenantContext mechanism (deps S-022)
- S-024 — M — Cross-tenant leakage CI test (deps S-022, S-011)
- S-025 — M — Tenant-from-URL for public flows (deps S-022, S-023)
- S-027 — L — Audit-log infrastructure (deps S-020, S-022)
- S-031 — M — Structured JSON logging (deps S-001, S-020, S-022)
- S-034 — M — GlitchTip in compose (deps S-001, S-002, S-039)
- S-032 — M — Loki + Grafana in compose (deps S-031, S-039)
- S-033 — M — Prometheus in compose (deps S-030, S-032, S-039)
- S-035 — M — Default Grafana dashboards (deps S-032, S-033)
- S-036 — M — Alert rules as code (deps S-035)
- S-041 — M — Reverse proxy: Caddy vs Traefik (deps S-039)
- S-042 — M — Off-site pg_dump backup (deps S-039)
- S-043 — M — Restore runbook + dry-run drill (deps S-042)
- S-046 — M — Helm/Kustomize manifest stub (deps S-039, S-040, S-041)

Migration script + rehearsal (can fire after V1__baseline parts land):

- S-016 — L — One-shot data-migration script (deps S-012, S-013, S-014)
- S-017 — M — Data-migration rehearsal #1 (deps S-016)

Jobs infrastructure (must precede individual job ports):

- S-081 — M — Spring @Scheduled infrastructure (deps S-001, S-026)
- S-082 — S — JavaMailSender + Thymeleaf baseline (deps S-001, S-039)
- S-038 — M — Scheduled-job instrumentation pattern (deps S-030, S-081)
- S-094 — M — ExcelExportSupport helper class (deps S-001, S-093)

## Phase D — Vertical slice: master data parity (proves the architecture)

- S-047 — M — Reference-data domain (deps S-006, S-007, S-008, S-022)
- S-049 — M — Locations CRUD (deps S-047, S-022)
- S-048 — M — Clubs CRUD (deps S-047, S-026)
- S-050 — M — Aircraft CRUD (deps S-049)
- S-051 — L — Persons + PersonClub (deps S-048, S-047)
- S-053 — S — Flight types CRUD (deps S-050)
- S-054 — S — Articles CRUD (deps S-048)
- S-055 — M — Email templates CRUD (deps S-048, S-082)
- S-052 — L — Users CRUD + role assignment (deps S-051, S-026, S-019, S-020)
- S-028 — L — Cutover user export-and-import script (deps S-019, S-012, S-026)
- S-056 — M — System data + system-logs view (deps S-027)
- S-057 — M — Translations migrated to bundled JSON (deps S-005)

## Phase E — Flight operations + reservations + planning

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
- S-066 — M — OGN ingestion REST endpoint (deps S-058, S-023, S-029)
- S-068 — M — AircraftReservation CRUD (deps S-050, S-051)
- S-069 — L — Reservation scheduler (deps S-068)
- S-070 — M — PlanningDay CRUD (deps S-068, S-051)
- S-071 — M — Planning-setup wizard (deps S-070)
- S-095 — M — Port flight-reports Excel export (deps S-065, S-094)

## Phase F — Scheduled jobs (early — most don't depend on rules engine)

- S-083 — M — Port DailyFlightValidationJob (deps S-081, S-059, S-061, S-038)
- S-084 — M — Port DailyReportJob (deps S-082, S-083)
- S-085 — M — Port LicenceNotificationJob (deps S-082, S-051)
- S-086 — M — Port PlanningDayNotificationJob (deps S-082, S-070)
- S-088 — M — Port AircraftDatabaseSyncJob (deps S-081, S-050)

## Phase G — Rules engine + deliveries (the sacred-cow port)

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

## Phase H — Public flows + UI completion

- S-097 — S — Landing page port + nav-bar mechanism (deps S-002, S-008)
- S-098 — M — Trial-flight registration (deps S-097, S-025)
- S-099 — M — Passenger-flight registration (deps S-097, S-025)
- S-100 — S — Lost-password + email-confirmation landing pages (deps S-097, S-019)

## Phase I — Test corpus completion + cutover prep

- S-091 — M — Production SMTP relay selection (deps S-082, S-044)
- S-092 — S — Decommission legacy libs (deps S-083..S-090)
- S-107 — L — Rules-engine combinatorial corpus (C11) (deps S-079)
- S-109 — L — Port full Playwright suite to new stack (deps S-002, S-057, S-097)
- S-110 — S — T3-equivalent smoke (deps S-020, S-021, S-062c)
- S-111 — M — Performance verification (deps S-108, S-109)

## Phase J — Cutover

- S-112 — M — Cutover runbook draft (deps S-017, S-043)
- S-113 — M — Data-migration rehearsal #2 (deps S-017, S-112)
- S-116 — M — Production IdP selection (deps S-019)
- S-117 — S — DNS / reverse-proxy cutover plan (deps S-041, S-044)
- S-118 — M — Rollback plan + snapshot procedure (deps S-117, S-042)
- S-119 — M — Force password-reset email queue (deps S-028, S-116)
- S-121 — L — Cutover-day execution (deps S-112, S-113, S-114, S-115, S-116, S-117, S-118, S-119, S-120, S-107, S-109)
- S-122 — S — Decommission tracker (deps S-121)

---

## How to use this

- **Right now** — start S-001, S-002, S-010 in parallel. S-019 (Keycloak) right after.
- **Whenever there's slack** — Phase A's zero-dep items can run any time; S-114 (OGN handoff) and S-115 (Proffix investigation) especially need to start early for external-coordination reasons.
- **Test corpus expansion (S-101..S-106)** — runs against legacy first, can begin in parallel with any other phase.
- **Don't start Phase G (rules engine) until Phase E's flight model is stable** — the parity port has nothing to compare against without it.
- **S-107 (rules-engine corpus) is the long pole of E-13** — schedule explicitly; it can take a full week of focused work.
