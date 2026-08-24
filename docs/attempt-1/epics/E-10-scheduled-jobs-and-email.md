---
id: E-10
title: Scheduled jobs & email parity
status: todo
adr_refs: [0009, 0013]
---

## Goal
Port the eight scheduled jobs to Spring `@Scheduled` in-process, wire `JavaMailSender` + Thymeleaf for templated email, decommission the `FLS.Workflow.Activator` console app and the `Alpinely.TownCrier` vendored library, and pick a production SMTP relay.

## Scope
- In: Spring `@Scheduled` infrastructure + idempotency conventions + admin-triggered "runOnce" endpoint; ports of all 8 jobs (DailyFlightValidation, DailyReport, AircraftStatisticReport, PlanningDayNotification, LicenceNotification, DeliveryCreation, DeliveryMailExport, AircraftDatabaseSync); JavaMailSender + Thymeleaf base; Mailpit in compose; production SMTP-relay selection; per-job log/metric instrumentation hooks into E-04.
- Out: rules-engine port itself (E-09); the email templates' design polish (port content; iterate later if needed).

## Stories
- [ ] S-081 — Spring `@Scheduled` infrastructure + idempotency + `runOnce` admin endpoint
- [ ] S-082 — JavaMailSender + Thymeleaf baseline + mailpit in compose
- [ ] S-083 — Port `DailyFlightValidationJob` (drives flight `Valid` / `Locked` transitions)
- [ ] S-084 — Port `DailyReportJob` + email template
- [ ] S-085 — Port `LicenceNotificationJob` + email template
- [ ] S-086 — Port `PlanningDayNotificationJob` + email template
- [ ] S-087 — Port `AircraftStatisticReportJob` (uses POI from E-11)
- [ ] S-088 — Port `AircraftDatabaseSyncJob` (OGN aircraft DB)
- [ ] S-089 — Port `DeliveryCreationJob` (invokes rules engine from E-09)
- [ ] S-090 — Port `DeliveryMailExportJob` (uses POI + `ZipOutputStream` from E-11)
- [ ] S-091 — Production SMTP relay selection (self-hosted Postal vs. Postmark/Resend/Brevo EU)
- [ ] S-092 — Decommission `FLS.Workflow.Activator` + `Alpinely.TownCrier` references

## Done when
- All 8 jobs run on configured cron schedules; each emits `started`/`completed`/`failed` events + duration histogram (E-04 instrumentation).
- Each job has a "runOnce" admin endpoint (admin role required) that produces identical output to the scheduled invocation.
- Spec `08` (mailpit) passes; specs `22` and `23` (flight locking + delivery creation workflows) pass when their respective jobs are runOnce'd.
- The `FLS.Workflow.Activator` cron has been removed from the deployment recipe.
