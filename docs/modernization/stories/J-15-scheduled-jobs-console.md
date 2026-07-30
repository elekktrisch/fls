---
id: J-15
title: Scheduled-jobs admin console (/system/jobs)
epic: E-10
status: in_progress
started_at: 2026-07-23
journey0: false
carved: true
depends_on: [J-1, J-2, J-9, J-10, J-12a]
rolls_up: [S-081, S-082, S-018, S-038, S-083, S-084, S-085, S-088, S-089, S-090]
acceptance:
  - "[happy] A SYSTEM_ADMINISTRATOR opens /system/jobs and sees every registered business job with its name + last-run status (never-run / running / completed / failed + duration)."
  - "[happy] Clicking Run now on Daily Flight Validation runs the job and the console shows a completed run result (started/finished timestamps + summary counts)."
  - "[happy] After that run, a seeded NOT_PROCESSED glider flight is VALID and a VALID flight ≥2 days old is LOCKED (asserted by a tenant-scoped re-read as club-admin)."
  - "[happy] Run now on Daily Report lands a per-pilot report email in Mailpit."
  - "[happy] Run now on Aircraft DB Sync against a recorded OGN DDB fixture updates a matched aircraft's model/competition-sign; an unmatched FLARM id is logged, not auto-created."
  - "[happy] Run now on Delivery Creation turns an eligible LOCKED flight into a persisted Delivery (state DELIVERY_PREPARED); a no-rules-match flight goes DELIVERY_PREPARATION_ERROR, not aborting the run."
  - "[key-error] A CLUB_ADMINISTRATOR (non-sysadmin) is redirected away from /system/jobs and POST /api/v1/admin/jobs/{name}/run returns 403."
  - "[edge] Every run emits started + completed (or failed) events visible as the job's last-run status; a job whose body throws is reported failed without crashing the scheduler or the console."
screen: /system/jobs
headless_pulled_in: "Spring @Scheduled jobs (S-083/084/085/088/089/090) + existing PlanningDayNotificationJob → the /system/jobs admin console (real admin screen; JobRegistry lists them, Run now triggers runOnce)"
migration: "N/A — greenfield admin screen; jobs operate on already-migrated flights/deliveries/persons (no new entity mapper). Gate exercises jobs over seeded + (where feasible) migrated flights."
parity_test: alpenflight/web/e2e/tests/real-idp/jobs-console-parity.spec.ts (new); mock inner-loop alpenflight/web/e2e/tests/jobs-console/jobs-console.spec.ts (new)
adr_refs: [0009, 0013, 0008, 0011]
---

## Context
Legacy runs ~8 nightly jobs triggered by OS cron → `FLS.Workflow.Activator` → bearer-token `GET /api/v1/workflows/<name>` (`flsserver/.../WorkflowsController.cs`, `[Authorize]`, cross-tenant). ADR 0009 replaces that stack with in-process Spring `@Scheduled` + a thin admin "run now" endpoint. This journey ships the operator-facing surface for it: a `/system/jobs` console that lists the registered jobs, shows each one's last-run outcome, and lets a system admin trigger a run on demand (backfill / rules-engine debugging). It's the screen-home for all scheduled work — six jobs land through it end-to-end, proving the console triggers real cross-tenant transitions, email, and an external sync.

## Spec must assert
The single green run drives the **`sysadmin` real-idp principal** (`SYSTEM_ADMINISTRATOR`, exists in `alpenflight/auth/realm-export.json`) and proves:

1. **Console lists jobs.** `GET /api/v1/admin/jobs` returns the registry; `/system/jobs` renders each business job (name, cron, last-run status). Registry collects `@MeasuredJob`-annotated jobs — includes the already-shipped `PlanningDayNotificationJob`.
2. **Run now → real transition.** `POST /api/v1/admin/jobs/daily-flight-validation/run` runs `DailyFlightValidationJob`; console shows a completed `JobRun` (started/finished + counts). Parity (ground in `flsserver/.../Jobs/DailyFlightValidationJob.cs`): `NOT_PROCESSED`/`INVALID` → `VALID` or `INVALID` per `FlightValidator`; `VALID` ≥ 2 days old → `LOCKED`. Iterate clubs unscoped, transition per-club (reuse the existing `@UnscopedScheduledJob` + `LifecycleStateFilter` mechanism). Verify a specific seeded flight's new state by a tenant-scoped club-admin re-read (sysadmin has no tenant).
3. **Run now → email.** `DailyReportJob` runs → Mailpit receives the per-pilot/per-instructor report (Thymeleaf template ported from legacy `Alpinely.TownCrier` under `flsserver/src/FLS.Server.Service/Email/`).
4. **Run now → external sync.** `AircraftDatabaseSyncJob` against a **recorded OGN DDB fixture** (no live network in the gate): a matched aircraft (by `flarm_id`/immat) gets model/competition-sign updated; an unmatched entry is logged, never auto-created (tenancy-safe — no club to own it); a network failure is caught (logged + Sentry), job does not crash.
5. **Run now → delivery.** `DeliveryCreationJob` (thin cron wrapper over the existing `DeliveryCreationService.createFromEligibleFlights()`) turns an eligible `LOCKED` flight into a persisted `Delivery` (`DELIVERY_PREPARED`); a no-rules-match flight → `DELIVERY_PREPARATION_ERROR`; per-flight failures are isolated, not fatal to the run (ground in `flsserver/.../Jobs/DeliveryCreationJob.cs`).
6. **Instrumentation.** Each run emits `started` + `completed`/`failed` (surfaced as the console's last-run status; also the Micrometer `fls_job_duration_seconds{job=...}` histogram). A job whose body throws is reported `failed` without crashing the scheduler.
7. **Authz.** A `CLUB_ADMINISTRATOR` is redirected off `/system/jobs` (sysadmin guard) and `POST /admin/jobs/{name}/run` → 403. This is the load-bearing negative test — the jobs are cross-tenant, so a club admin must not trigger them ([[feedback_safety_claim_needs_negative_test]], [[project_real_idp_real_roles_catches_authz_gaps]]).

## Notes

### Role gating — sysadmin, not club-admin (design decision)
Unlike the tenant-scoped `/system/logs` audit viewer (club-admin, one club's trail), scheduled jobs run **unscoped across all clubs**. So the console is **SYSTEM_ADMINISTRATOR-gated**: route `canActivate: [sysadminGuard]` (`core/session/sysadmin.guard.ts`), exposed in `SYS_ADMIN_SECTIONS` (next to `/clubs`) in `nav-sections.ts` — NOT in `MASTERDATA_CLUB_ADMIN_ITEMS`. Endpoints `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")`. Legacy's `[Authorize]`-any was effectively a service-account (Activator) operation, not a normal user's. Consequence for the spec: the sysadmin has no tenant, so a transition is verified either by the console's run-result counts (directly visible) or a secondary club-admin re-read.

### What already exists (de-risks the build — confirmed by carve-time backend map)
- `@EnableScheduling` (`AlpenFlightApplication.java`); the **`@UnscopedScheduledJob` marker + `LifecycleStateFilter`/`LifecycleStateFilterAspect`** already implement per-club iteration — S-083/S-089 reuse it, don't reinvent `runUnscoped`.
- `DeliveryCreationService.createFromEligibleFlights()` already persists deliveries from `LOCKED` flights (eligibility LOCKED + billable + ≤3 days) → **S-089 is a thin `@Scheduled`/runOnce wrapper**, not a rebuild.
- Email fully wired: `SmtpMailSender` + Thymeleaf + Mailpit + `templates/email/` (S-082 satisfied via J-12) → S-084/S-085 = port template + wire + expiry query.
- Flight state machine (`FlightProcessState`: NOT_PROCESSED/INVALID/VALID/LOCKED/…) + `FlightValidator` (smoke depth — S-083 likely deepens it toward legacy parity) + `FlightTransitionMatrix`.
- `Aircraft.flarm_id` + `Aircraft.competition_sign` columns exist (S-088 update targets).
- POI/Excel infra (`ExcelExportSupport`, `poi-ooxml:5.5.1`) — but **`ZipOutputStream` does NOT exist** (S-090's genuinely-new piece).
- Web feature-folder pattern: mirror `features/audit-logs/` → `features/jobs/` (`jobs.routes.ts` + `jobs.store.ts` + `list/jobs-list.page.ts`).

### Greenfield build (the real work)
- **S-081** JobRegistry (Spring component collecting `@MeasuredJob`s) + `GET /api/v1/admin/jobs` + `POST /api/v1/admin/jobs/{name}/run` (idempotent runOnce). Retrofit existing jobs (`PlanningDayNotificationJob`) with `@MeasuredJob` so they appear. *(seam: platform/scheduling JobRegistry + JobsAdminController)*
- **S-038** `@MeasuredJob` annotation/AOP → started/completed/failed events + `fls_job_duration_seconds` histogram + a persisted `JobRun` last-run record the console reads. Grafana panel is deferred (observability platform; no Grafana in the e2e proof — directive 1). *(seam: platform/scheduling @MeasuredJob advice + JobRun store)*
- **S-018** ShedLock **stub** (inert): `V55__shedlock.sql` + `net.javacrumbs.shedlock:{spring,provider-jdbc-template}:7.7.0` on classpath (NOT `@EnableSchedulerLock`) + `shedlock` SYSTEM_GLOBAL in `tenant-rules.yaml` + a `ShedLockNotActivatedTest` reflection guard. Latest migration is **V54** → this ships as **V55**. Do NOT activate (single-instance). *(seam: db/migration V55 + build.gradle.kts + tenant-rules.yaml)*
- **S-088** OGN DDB HTTP client + parse + match-by-flarm/immat + update-only + recorded fixture. *(seam: aircraft/application AircraftDatabaseSyncJob + OGN DDB client)*
- **S-090** DeliveryMailExportJob — group `DELIVERY_PREPARED` by recipient → SXSSF per recipient → **`ZipOutputStream`** → email attachment → mark `IsFurtherProcessed`. **SPLIT-CANDIDATE:** the only L / genuinely-new-infra job; if `/do-ship`'s gate shows J-15 is over-budget, split S-090 to a **J-15b (Deliveries mail-export)** follow-up that adds a `Run now` + zip-email assertion on the SAME console screen (a visible console result → a legit screen-touching follow-up, not a headless-only journey). Default: rides J-15.
- **S-083** DailyFlightValidationJob, **S-084** DailyReportJob, **S-085** LicenceNotificationJob (confirm the licence-expiry window vs legacy — S-085 guesses 60 days; a `legacy-oracle` pass at ship time on `flsserver/.../Jobs/LicenceNotificationJob.cs` pins it), **S-089** DeliveryCreationJob. *(seam: one job class per aggregate's application package)*

### Boyscout riders to fold (the ≤40% infra slot — /do-ship sizes + clears from `_BOYSCOUT.md` on ship)
- **S-092** decommission `FLS.Workflow.Activator` + `Alpinely.TownCrier` refs — J-15 is the last-job-ported home (`_ORDER.md` platform riders).
- **[WORKFLOW-SLIM]** extract repeated per-journey CI YAML into `.github/actions/` composites (the still-pending half).
- **[MAINTAINABILITY-TOOLING]** commit the real Qodana baseline over the placeholder (J-15 touches CI with new job specs).
- **[HISTORY→GIT]** this journey file is already contract-only; keep new story/journey prose that way.
- **[COMMENT-STRIP]** per-touch only if J-15 edits `MapperLegacyBindings.java` / the fan-out fixture (unlikely — note, don't force).
- New ITs use production-code seeding per-touch (ADR 0027 §3); new job control endpoints get `@Operation(operationId=…)` so orval emits named methods, not positional `getN` (the J-3 orval rider).

### Not a migration journey
Migration = N/A (no new entity mapper), so the **⚠ BLOCKS-next-MIGRATION-journey** riders in `_BOYSCOUT.md` (J-9 article-5001, J-8 filter predicate, J-0c Location render) do **not** gate J-15. The jobs are still proven over `LOCKED`/`NOT_PROCESSED` flights the gate seeds (and, where the real-idp fanout makes it cheap, over the migrated club's flights).

### Assumptions made
1. `sysadmin`-gated (see decision above) rather than club-admin — the cross-tenant nature of the jobs makes club-admin unsafe; the negative test in AC7 proves it.
2. All six jobs home on the one console screen (roadmap intent + "headless work never gets its own journey"); S-090 is the sanctioned split-to-J-15b escape hatch, not a default split.
3. The Grafana dashboard panel (S-038 AC) is deferred to the observability platform — the Micrometer histogram + persisted `JobRun` last-run record (which the console reads) are the in-scope proof.
4. The OGN sync uses a recorded DDB fixture in the gate (no live external network in CI) — matches S-088's own "recorded OGN response" acceptance.
5. Licence-expiry window (S-085) confirmed against legacy at ship time via `legacy-oracle`, not carve time.

## Ship-time scope decision (2026-07-23)
**S-090 (DeliveryMailExport / zip) splits to a follow-up J-15b** — the carve's sanctioned escape hatch. It has no J-15 AC (the ACs + "spec must assert" cover only PlanningDayNotification + DailyFlightValidation + DailyReport + AircraftDBSync + DeliveryCreation), it's the only L/new-infra job (`ZipOutputStream` + SXSSF-per-recipient), and it carries a legacy billing-surface bug to reconcile (`DeliveryNumber="Workflow {time}"` — non-unique, locale-dependent — do NOT reproduce). J-15 ships the console + 5 jobs (incl. the cheap grounded S-085).

## Oracle-pinned parity anchors (legacy_oracle, ship time)
- **S-083 LOCK**: `Valid && CreatedOn(date-truncated) ≤ today−2d` → LOCKED (on **CreatedOn**, not FlightDate/ValidatedOn). Validate set: `NotProcessed OR (Invalid && ModifiedOn ≥ ValidatedOn)`.
- **S-089 delivery eligibility**: `Locked && (Glider|Motor) && CreatedOn ≤ today−3d`; no-items OR no-recipient → `DELIVERY_PREPARATION_ERROR`; DoNotInvoice → `EXCLUDED_FROM_DELIVERY_PROCESS`; per-flight failures isolated.
- **S-084 report**: recipients = pilot+copilot+instructor, gated on person's `ReceiveFlightReports==true`; marks report-sent on send; per-person/club isolated.
- **S-085 licence**: **60-day window** (confirmed); 6 types (MedicalLapl/Class1/Class2, Glider/Motor instructor, PartM); NOT club-scoped; skip blank comm-email. Ship the corrected **≤60-day inclusive** window (legacy's exact-day-equality is a fragile quirk — documented divergence).
- **S-088 OGN sync**: source `ddb.glidernet.org/download?j=1` (JSON); **match by immatriculation ONLY** (normalize: strip `-`, upper); update-only (FLARMId, model, competition-sign), never create; network/parse errors caught → job survives. Recorded fixture in the gate (freeze the DDB JSON shape: `OgnDevices[]` with `DeviceId`, `Registration`, `Cn`, `IsTracked`, `IsIdentified`).

## Tasks
- [x] **T-01** — Mock inner-loop spec stub + gallery scaffold. `e2e/tests/jobs-console/jobs-console.spec.ts` (thin: renders list, Run-now button, mocked `/api/v1/admin/jobs` + run response) + scaffold the per-journey proof-gallery page for J-15 + link from the persistent index. *(seam: e2e mock spec + gallery page)*
- [x] **T-02** — Scope the per-push gate to J-15 real-idp only; move prior journeys to mock-IdP (full real-idp regression → nightly + §4 gate). *(seam: ci.yml / proof workflow gate-scoping)*
- [x] **T-03** — S-038 `@MeasuredJob` AOP instrumentation: annotation + `@Around` advice emitting started/completed/failed + `fls_job_duration_seconds` Micrometer histogram + persisted `JobRun` last-run entity/repo + migration `V55` (job_run table). *(seam: platform/scheduling @MeasuredJob advice + JobRun store)*
- [x] **T-04** — S-081 `JobRegistry` (collects `@MeasuredJob` beans) + `JobsAdminController`: `GET /api/v1/admin/jobs` (list + last-run) + `POST /api/v1/admin/jobs/{name}/run` (idempotent runOnce), both `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")` + `@Operation(operationId=…)`; retrofit `PlanningDayNotificationJob` with `@MeasuredJob`; IT incl. the **club-admin 403** negative (AC7). *(seam: platform/scheduling JobRegistry + JobsAdminController)*
- [x] **T-05** — Web `features/jobs/` console (mirror `features/audit-logs/`): `jobs.routes.ts` (`sysadminGuard`) + `jobs.store.ts` + `list/jobs-list.page.ts` (list + last-run status + Run-now) + nav entry in `SYS_ADMIN_SECTIONS` + orval regen. Mock spec (T-01) goes green here. *(seam: web features/jobs component-route)*
- [ ] **T-06** — S-083 `DailyFlightValidationJob` (`@UnscopedScheduledJob` + `@MeasuredJob`): per-club validate→lock per the oracle anchor; reuse `FlightValidator` + `LifecycleStateFilter`. IT proving VALID + LOCKED transitions. *(seam: flights/application DailyFlightValidationJob)*
- [ ] **T-07** — S-089 `DeliveryCreationJob` (thin `@MeasuredJob` cron wrapper over `DeliveryCreationService.createFromEligibleFlights()`). IT proving DELIVERY_PREPARED + DELIVERY_PREPARATION_ERROR isolation. *(seam: accounting/application DeliveryCreationJob)*
- [ ] **T-08** — S-084 `DailyReportJob` (per-pilot/instructor report email; `ReceiveFlightReports` gate; Thymeleaf template port to `templates/email/`). IT proving Mailpit receipt. *(seam: reporting/application DailyReportJob + email template)*
- [ ] **T-09** — S-085 `LicenceNotificationJob` (≤60-day inclusive window, 6 licence types, per-person email, skip blank email). IT proving one email per expiring licence. *(seam: person/application LicenceNotificationJob + template)*
- [ ] **T-10** — S-088 `AircraftDatabaseSyncJob` + OGN DDB client (HTTP + JSON parse + match-by-immat + update-only + recorded fixture; network-fail caught). IT proving matched update + unmatched-skip + no auto-create. *(seam: aircraft/application AircraftDatabaseSyncJob + OGN DDB client)*
- [ ] **T-11** — S-018 ShedLock **stub** (inert): migration `V56__shedlock.sql` + `net.javacrumbs.shedlock:{spring,provider-jdbc-template}:7.7.0` on classpath (NOT `@EnableSchedulerLock`) + config entry (`application.yml`/tenant-rules surface — no `tenant-rules.yaml` exists) + `ShedLockNotActivatedTest` reflection guard. *(seam: db/migration V56 + build.gradle.kts + config)*
- [ ] **T-12** — Thicken the real-idp parity spec `e2e/tests/real-idp/jobs-console-parity.spec.ts` to full assertions (drive `sysadmin`; open console; Run-now each AC'd job; verify transitions via a tenant-scoped **club-admin re-read**; Mailpit; OGN fixture; 403) + author its clean seed floor (production-code seeding, ADR 0027 §3). *(seam: real-idp spec + seed)*
- [ ] **T-13** — Rider: S-092 decommission dead `FLS.Workflow.Activator` + `Alpinely.TownCrier` references (alpenflight-side + `_ORDER.md`/docs — J-15 is the last-job-ported home; legacy files stay read-only). *(seam: dead-ref cleanup)*

**Deferred riders (NOT folded — sized out of J-15):** `[WORKFLOW-SLIM]` composite-action CI refactor stays in `_BOYSCOUT.md` (too big for this already-large journey). `[MAINTAINABILITY-TOOLING]` Qodana baseline backfill handled at the §4 gate (needs the CI `qodana-scan` artifact first).
