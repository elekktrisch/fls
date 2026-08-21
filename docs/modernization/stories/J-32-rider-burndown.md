---
id: J-32
title: Rider burndown — drain every S1 + S2 boyscout rider (hardening)
epic: E-13
status: in_progress
started_at: 2026-08-20
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
migration: mapper-touching — T-12 and T-14 edit producer mappers, so `fan-out parity` is a hard merge gate (the carve said N/A)
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

**AC 4's `[redacted]` half rides `Person` (T-05 decision).** Every field of `Location` and `Aircraft`
is allow-listed, so those two render no sentinel and an assertion on them passes vacuously (T-01
finding). T-05 kept the shipped `deny-all: Person` instead of inventing a fixture-only denial: name,
birthday, address, phones, both email addresses, licence number and the medical expiry dates are real
PII that the audit trail must not store. `PiiRedactorTest` pins it against the shipped policy plus a
real `Person`, with a `Location` control that proves the redactor does not redact everything.
**T-10 drives it like this:** log in as the club-A administrator, create a Person at `/persons/new`,
then open `/system/logs`, filter `targetEntityType=Person`, and expand the newest row. Every value in
the snapshot table reads `[redacted]`; the row still names the action, the actor and the time.

**AC 6 is measured against the carve-time rider set, not a moving one.** The journey drains the 39
S1 and S2 riders that `_BOYSCOUT.md` held on 2026-08-20. Tasks keep finding new ones — T-02 found a
never-run lane, T-03 found two more. An in-scope finding (a gate hole, or dead code this journey
created) becomes a `T-NN` here. A genuinely out-of-scope finding stays filed and does not block AC 6.
Without this rule AC 6 chases its own tail, and the done-bar stops being honest.

**Seams for `/do-ship`.** `PiiRedactor` ↔ the redaction config; the manifest tenant-bypass allow-list
and its test; `ClubSpec` plus the bundle-envelope mapper; `PublicRegistrationTxWriter` plus the actor
projection; the three "deliberately NOT `@Transactional`" methods named in
`[LOST-INVARIANTS-NEED-GUARDS]`.

## Adjudications

**`[DEAD-BUT-WIRED-IMPERSONATION-INTERCEPTOR]` — delete all three (operator, 2026-08-20).** Remove
`AuditTargetTenant`, `AuditTargetTenantInterceptor` and the `TenancyWebMvcConfig` registration. Add a
guard test that reds when an impersonation HTTP entry point returns. Evidence: `b72f9c6a0` (S-027)
added the annotation for `/api/v1/admin/locations/{clubId}`; `41e1323ba` (S-159) withdrew that surface
the same day; no forward story needs it; [ADR 0008](../adrs/0008-multi-tenancy-mechanism.md) §Amendment
S-159 states `Tenants.runAs` "is never wired through to an HTTP path". The amendment is enforced by
nothing today, which is the absence that produced the rider.

**`AUDIT_LOG` stays on the cross-tenant allow-list (T-06, evidence below).** The rider named the wrong
entry. `AUDIT_LOG` sits on the list since the first commit of the file, where the list held exactly
eleven entries and the shipped text named `AuditLog.actor_user_id` as its reason. J-9b added
`PERSON_FLIGHT_TIME_CREDIT` as the twelfth entry and its reason, but left the count word and the test
method name at eleven. J-31 then deleted the rotted text. The grant is real: the audit row keeps its
own `tenant_club_id` discriminator, and only the actor crosses tenants. T-06 replaces the count word
with a machine-read grant table and a negative test.

**`[ANON-WRITE-ATTRIBUTION]` — adjudicated earlier (operator, /do-retro 2026-08-14).** Build
`actor_kind = ANONYMOUS_PUBLIC` with `system_actor=false`, plus raw `client_ip` on anonymous
public-registration writes only. Retention 90 days: a scheduled job nulls `client_ip` and keeps the
row. The privacy notice ships with the journey, not after it.

**Public-registration intake is a reviewed exception to the impersonation guard (operator,
2026-08-20).** Rule 2 of `ImpersonationHttpEntryPointGuardTest` fires on `PublicRegistrationController`,
which takes a club slug from four unauthenticated paths. An anonymous registrant submits to a club's
published intake; the registrant does not act as the club. The club publishes the slug and can close
the surface. The controller joins `ClubsController` in the allow-list. The operator also kept the
`ADR 0008` paragraph that names the guard and both exceptions.

**Licence and medical dates stay in the audit trail (operator, 2026-08-20).** `PersonLicences`
allow-lists `licenceNumber` and six medical and licence expiry dates. Those values land verbatim in
`t_mutation_audit_event.after_state`, and `MePersonLicencesControllerIT.java:126` pins them
un-redacted. Safety-of-flight licence currency and medical currency are a legitimate audit purpose.
T-08c records the basis and the retention next to the anonymous-write privacy notice. The behaviour
predates J-32 and does not change here.

**The redaction failure direction was over-redaction, never a leak.** `PiiRedactor.java:62` gives an
unmatched type an empty allow-set, so every field renders `[redacted]`. The audit trail was silently
useless for the mismatched types; no personal data escaped. `Person` deny-all was in effect
throughout, because deny-all keys on the string name.

## Tasks

Order: proof-carrying work first (Waves 1-2 plus the actor cell), then one mid-journey `gap-hunter`
round, then the burndown highest-severity-first. Severity tags mirror `_BOYSCOUT.md`.

- [x] T-01 — spec stub for ACs 1-5 + scaffold the J-32 gallery page; re-tag one `proofVideo` to `journey: 'J-32'`
- [x] T-02 — scope the per-push gate: only the J-32 spec runs real-idp, prior journeys run mock-IdP
- [x] T-03 — [S1] delete the impersonation annotation, interceptor and registration; add the no-HTTP-entry-point guard
- [x] T-04 — [S1] assert `ClubSpec` carries no deployment-scoped component; the envelope mapper strips inbound `deployment_id` (AC 5)
- [x] T-05 — [S1] `PiiRedactor` startup guard: every configured redaction field name resolves to a field (AC 4)
- [x] T-06 — [S1] adjudicate the twelfth tenant-bypass allow-list entry (`AUDIT_LOG`); pin the reviewed set (AC 3)
- [x] T-07a — [S1] arch guards for the three "deliberately NOT `@Transactional`" cases
- [x] T-07b — [S1] transaction demarcation structure: `runAs` outside the template, the tx writer bean, the boot-time template
- [x] T-07c — [S1] JPA write-then-read identity: the bound `flush()` name, the operating club from the carrier
- [x] T-07d — [S1] packaged-artifact dependency shape: no production class injects `RestClient.Builder`; fix the OpenAPI snapshot failure message that invites accepting a contract break
  - `OVERFLOW:` three seams (cap is one), six new test files (cap is five), two test layers with four
    ArchUnit classes (cap is three at one layer). The seven remaining invariants split cleanly:
    - **T-07b — transaction demarcation structure** (ArchUnit, pure JVM; extends the T-07a family).
      `FlightReportRebuildService.rebuildForClub:42` keeps `Tenants.runAs` outside the
      `TransactionTemplate`; `JoinRequestTxWriter` stays a separate proxied bean so the
      `@Transactional` boundary nests inside `Tenants.runAs`
      (`JoinRequestsService.java:56,64`); `FlightInitialState.resolveSeeds:28` keeps the
      `TransactionTemplate` inside `@PostConstruct`, because the injected `EntityManager` proxy needs
      a bound JDBC session at boot.
      **Done — the third reason was refuted, and the guard states the measured one instead.**
      Invariants 1 and 2 hold, and `FlightReportRebuildServiceIT` measures the mechanism: the swapped
      order binds the Hibernate session to `NO_TENANT` and reds all three cases. Invariant 3 is a
      different rule than the rider asked for. The stated reason is false: a lookup without the
      `TransactionTemplate`, and a lookup in the constructor body, both start the context green
      against real Postgres, because the Spring shared-`EntityManager` proxy defers the close until
      the terminal query call. The real invariant is the `@PostConstruct` annotation. Remove it and
      the context still starts, `initialProcessStateId` keeps the all-zero sentinel, and five cases
      of `FlightProcessStatePatchIT` get 500 in place of 201. `TransactionDemarcationStructureGuardTest`
      carries all three reasons in its failure messages.
    - **T-07c — JPA write-then-read identity** (Postgres integration tests, second layer).
      `AircraftRepository.flush()` keeps its literal name, because `JpaAircraftRepository` satisfies it
      from `JpaRepository.flush()` by signature (`JpaAircraftRepository.java:13`); a rename makes Spring
      Data read it as a derived query and the repository fails to start.
      `FlightsService.createFlight:87` reads the operating club from `TenantContextCarrier`, not from
      the saved entity.
      **Warning — the rider states a reason that the call sites do not support.** It blames
      `save()` routing through `em.merge`. Two of the five `AircraftsService` call sites
      (`changeAircraftState:245`, `recordAircraftCounter:270`) call no `save()` at all: the parent is
      already managed, and the `@GeneratedValue(strategy = UUID)` child gets its id from the
      cascade at flush. Three further call sites flush to convert a
      `DataIntegrityViolationException` into a domain exception. Establish the real reason per call
      site before you write the guard, as T-06 did for `AUDIT_LOG`.
      **Done — the rider named the wrong mechanism twice, and both guards state the measured one.**
      `merge` is the mechanism at no call site. `changeAircraftState:245` and
      `recordAircraftCounter:270` call no `save()`; the cascaded child gets its id at flush, and
      `AircraftMapper.java:94` reads that id through `Objects.requireNonNull`.
      `changeAircraftState:239` flushes for write ORDER, because Hibernate runs the insert of the
      new period before the update that closes the old one, and `ux_aas_current_state_per_aircraft`
      then reds. `persist:307` and `transferOwnership:217` flush to pull the constraint violation
      inside the try; with no flush it arrives at commit, after the catch. The rename claim is also
      too broad: an unparseable rename fails the context load, but a parseable rename such as
      `deleteByDeletedOnNotNull` starts the application and silently deletes rows. For the second
      invariant, `@TenantId` stamps `operatingClubId` at the insert while `@GeneratedValue` stamps
      the id at persist. A swap to the entity read reds nothing today, because
      `FlightReportProjector.java:41` auto-flushes inside `save()`.
      `FlightOperatingClubComesFromTheCarrierIT` replaces that projector, which removes the
      accident and makes the swap red with `operatingClubId=null`.
    - **T-07d — packaged-artifact dependency shape** (ArchUnit, one rule). No production class injects
      `RestClient.Builder`; `HttpOgnDeviceDatabase:27` builds the client itself. `spring-boot-starter-test`
      auto-configures that builder, so a `@SpringBootTest` passes over a boot jar that dies at startup.
      **Done — the hazard is measured, and the rider named the wrong module.** The builder bean comes
      from `spring-boot-restclient`, which `spring-boot-starter-restclient-test` pulls onto the test
      runtime classpath only; `spring-boot-starter-test` alone does not carry it. The measurements:
      the jar of the current code starts and `/actuator/health` reports UP; the same jar with a
      `RestClient.Builder` constructor parameter prints `APPLICATION FAILED TO START` and
      "No qualifying bean of type `org.springframework.web.client.RestClient$Builder` available";
      `ApplicationContextTest.contextLoads` passes over that same broken code. An
      `ObjectProvider<RestClient.Builder>` is worse, not safer: the jar starts, health reports UP, and
      the deferred `getObject()` throws — and `HttpOgnDeviceDatabase` catches `RuntimeException`, so
      the aircraft sync reports success and writes nothing. `PackagedArtifactDependencyShapeGuardTest`
      carries every measured fact in its failure message.
    - **Already guarded — do not re-file.** `AuditEventDtos.AuditEventRow.beforeState`/`afterState`
      stay `Map<String, Object>`: `OpenApiSnapshotIT.snapshotMatchesLiveSpec` compares the live spec to
      the committed `web/openapi/openapi.json`, which pins
      `{"type": "object", "additionalProperties": {}}` for both fields. A change to `String` flips them
      to `{"type": "string"}` and reds that test. **Done — the message now asks the developer whether
      the contract change was intended, and names regeneration as the deliberate-change action only.**
- [x] T-08a — [S1] `actor_kind = ANONYMOUS_PUBLIC` + `client_ip` column + the anonymous write path (AC 2)
  - **Done — three actor kinds now separate on the projection, and only the anonymous row keeps an IP.**
    `AuditTrailService` classifies every write: a bearer principal reads `NORMAL`, an anonymous public
    submission reads `ANONYMOUS_PUBLIC` with `system_actor=false`, and a write with no principal at all
    reads `SYSTEM` with `system_actor=true`. `MutationAuditEvent.Builder` refuses a `client_ip` on any
    actor kind other than `ANONYMOUS_PUBLIC`, so the privacy boundary is an aggregate rule, not a CHECK.
    `AuditTrailService.recordAnonymousPublicSubmission` drops the IP when a principal IS authenticated,
    which covers the real case: the public form accepts a bearer token because the path is `permitAll`.
    `AnonymousActorProjectionIT` scores that case and asserts the two `PublicFlightRegistration` rows
    differ. Scoring the old classification with the new tests reds 4 cases across 2 classes.
  - **T-09 still needs the actor cell.** `audit-logs-list.page.ts:186` renders
    `row.systemActor ? t('systemActor') : row.actorUserId`. An `ANONYMOUS_PUBLIC` row now takes the
    second branch and prints an empty cell, because both actor ids are null. T-09 must key the cell on
    the new `actorKind` field of `AuditEventRow`, add an operator label for `ANONYMOUS_PUBLIC`, and keep
    `systemActor` for `SYSTEM`. The API carries `actorKind`; it deliberately carries no `client_ip`.
- [x] T-08b — [S1] 90-day `client_ip` null-out job + the on-request redaction path
  - The sweep runs **per tenant**: it opens `Tenants.runAs` for every club, soft-deleted clubs
    included, and redacts inside the `@TenantId` discriminator. It reads no row across a tenant
    boundary. `ClientIpRetentionJob` joins the `Tenants.runAs` allow-list on that basis. The rule
    lives on the aggregate (`MutationAuditEvent.clientIpRetentionHasElapsedAt`), not in the schema.
  - **The boundary: a row exactly 90 days old is redacted.** The window keeps an IP for strictly
    less than 90 days.
  - V60 grants the app role a **column-level** `UPDATE (client_ip)`. The V54 append-only carve-out
    still refuses every other UPDATE and every DELETE on `t_mutation_audit_event`, so the database
    itself enforces "redaction, not deletion".
- [x] T-08c — [S1] privacy-notice entry naming purpose, window and redaction path, plus the licence and medical-date audit basis
  - The notice lives at `docs/modernization/privacy-notice.md`. The repository had no privacy or
    data-protection surface, and a published page is an operator decision, so the notice ships as
    the source text and states that the product does not publish it yet.
  - `PrivacyNoticeMatchesTheShippedBehaviourTest` binds the notice to the code: every day count in
    the notice must equal `MutationAuditEvent.CLIENT_IP_RETENTION`, the notice must name the job
    `client-ip-retention` and the cron must run daily, the erasure path must equal the path
    `AuditAdminController` exposes, the notice must name `ANONYMOUS_PUBLIC`, the projection must
    carry no client IP, and the listed licence fields must equal the non-flag entries of
    `audit.redaction.entities.PersonLicences.allow`. Five planted violations red five of the six
    cases; the sixth guards main code and takes no planted notice.
  - **Open for the operator:** where the product publishes this text, and in which languages. ADR
    0030 is still `PROPOSED` although T-08a and T-08b shipped its decision, and its question 3
    ("is the privacy-notice text in scope for the implementing journey?") is now answered.
- [x] T-09 — [S2] `/system/logs` actor cell renders a username, falling back to `actorKeycloakSub` (AC 1)
  - **Done — the cell keys on `actorKind`, and the three kinds read differently on screen.** An
    authenticated row reads the username (`a.meier`), an `ANONYMOUS_PUBLIC` row reads "Öffentliches
    Formular", a `SYSTEM` row reads "System" and a `LEGACY_MIGRATED` row reads "Migration".
    `audit-actor-cell.ts` holds the whole rule, so `audit-actor-cell.spec.ts` scores the rendered text
    against the shipped German labels of `de.ts`, the fallback case included.
  - **The username comes from the club user projection, and it costs one request per screen.**
    `AuditLogsStore.loadActorUsernames` calls `GET /api/v1/users` once and keys `username` on the raw
    `actorUserId`, because `UserListItem.id` carries the `usr-` prefix and the audit row does not. The
    page fires no per-row lookup. `clubAdminGuard` and the endpoint share the `CLUB_ADMINISTRATOR`
    role, so the caller who reads the screen can also read the projection.
  - **The remaining gap, scoped.** No projection maps an `actorKeycloakSub` to a name. A federated
    principal with no `t_user` row, and an actor from another club (the `AUDIT_LOG` tenant-bypass
    grant), both fall back to the raw sub. To name them, `AuditQueryService.toRow` must carry an
    `actorDisplayName` resolved by one batched `keycloak_sub IN (…)` read. That crosses the
    audit→users Modulith boundary and changes the API shape, so it is a separate task, not this seam.
- [x] T-10 — thicken the spec to full assertions for ACs 1-4; drive it green locally (AC 5 stays IT-proven)
  - **Green on the real chain, on this box: 10 of 10 cases in 2.1 minutes.** Real Keycloak, real
    Mailpit, the backend on the LAN Postgres, both dev servers. No `page.route`, so the spec declares
    no mocked seam.
  - **AC 1.** The row for the write names the administrator by username, which is the Keycloak
    username of the fixture principal. No actor cell on the page is empty. The expanded row carries
    the created name.
  - **AC 2.** A registrant submits the real public scenic-flight endpoint with no token. The club-B
    administrator reads that row as the public-form label; the real API row carries
    `actorKind=ANONYMOUS_PUBLIC`, `systemActor=false` and no actor user id; the public-form label
    differs from the scheduled-job label; and the administrator write on the same screen reads the
    username. **A genuine `SYSTEM` row is reachable from no HTTP surface.**
    `SecurityConfig.java:35` enumerates two anonymous writes and both are public registrations, and
    the jobs-console "Run now" (`JobRegistry.runOnce`) runs inside the caller's security context, so
    it records `NORMAL`. The `SYSTEM` half stays proven by `AnonymousActorProjectionIT` and
    `audit-actor-cell.spec.ts`.
  - **AC 3.** Club B writes a Location, then club A writes its own. On screen the newest
    CREATE/Location row the club-A administrator reads is the club-A write. The real API read of 200
    rows returns rows, carries a club-A row, carries no club-B tenant id, and carries the club-B
    Location name nowhere. **The old row-level assertion was vacuous:** an audit row renders action,
    target, actor, status and time and never the entity name, so
    `rows.filter({ hasText: clubBTargetName }).toHaveCount(0)` could not be non-zero.
  - **AC 4.** A real Person write. Every value of the expanded row reads `[redacted]`, the typed
    first and last name are absent, and the row keeps its action, its actor and its time. The
    control against over-redaction: the Location row of the same trail still shows its name.
  - **AC 5 is not e2e-provable, and the spec claims it nowhere.** The bundle envelope has no screen
    manifestation. T-04 proves it with an arch guard plus a crafted-bundle round-trip.
  - **Real-idp runs on the development box.** The `docker compose` CLI plugin is absent, but the
    `keycloak` and `mailpit` containers already run, and `./gradlew bootRun` against the LAN Postgres
    completes the chain. Correct the "blocked" note before you send another journey to CI for a
    signal the box can give in minutes.
  - **Finding — the nav overlay eats a row click (T-48).** `enterViaNav` leaves the Masterdata
    overlay open. It covers the list, so a row click retries until the test timeout. The trace showed
    the click never completing. `enterAuditLogs` now dismisses the overlay.
  - **Finding — the target filter returned before it narrowed.** The helper asserted the first row's
    target only. The newest row already carried the wanted target, so the helper returned while the
    screen still showed the unfiltered list, and the next assertion scored a row of the old list.
    The helper now waits for the `targetEntityType=` response, and the anonymous case pins the row
    count to what the real read returns. Both reds came from the trace, not from a guess.
- [x] T-49 — [S1 BLOCKER] `MutationAuditEventListener.java:83` writes `client_ip` by raw JDBC with `tenant_club_id` NULL, which bypasses the aggregate rule that `V59` and `V60` cite to justify having no database CHECK; `ClientIpRetentionJob.java:49` sweeps per club, so a null-tenant row keeps its IP for ever
- [x] T-50 — [S1 BLOCKER] the AC 2 proof compares two i18n constants and never renders a `SYSTEM` row (`audit-log-two-club.spec.ts:523`), and the caption claims a scheduled-job label the spec does not assert
  - **Done — the proof asserts what the real screen renders, and the caption claims only that.** The
    old line compared two `de.ts` constants and passed with the feature deleted.
  - **A genuine `SYSTEM` row reaches no HTTP surface, and no bounded proof run.** `SecurityConfig.java:35`
    enumerates two anonymous writes, and both record `ANONYMOUS_PUBLIC`. `JobsAdminController.java:48`
    runs a job inside the caller's security context, so "Run now" records `NORMAL`. Every `@Scheduled`
    cron is daily or hourly, so no job fires inside a 45-second case. One HTTP-reachable `SYSTEM` row
    does exist, and it is the defect T-54 removes: `AuditTrailService.java:85` classifies a FAILED
    anonymous write as `SYSTEM`. A proof built on that row would pin a bug.
  - **Option (b), for that reason.** The real-idp case asserts the rendered anonymous cell: it reads
    the public-form label, it is not empty, it matches no raw identifier shape, and it differs from
    the administrator cell the same screen renders. The `SYSTEM` half stays where it is provable —
    `AnonymousActorProjectionIT.java:119` in the data, `audit-logs-list.spec.ts:196` on the screen,
    and `audit-actor-cell.spec.ts` on the label rule. The mocked-backend case now compares two
    RENDERED cells, so it too has application contact.
  - **Both videos end on the asserted state.** The anonymous case reads the administrator row first
    and the anonymous rows last. The tenant-isolation case takes its bearer directly after the login,
    so the API read no longer navigates the page to `/start` after the assertions.
  - **The scheduled-job fixture names the real producer.** `PlanningDayNotificationJob.java:56` writes
    `PlanningNotificationRun` with the club id as the target id, and `PiiRedactor.java:62` writes
    `[redacted]` for every field of an unlisted entity.
- [x] T-51 — [S1 BLOCKER] the manifest column-level grant check reads `EntityPolicy.tenantBypassFks`, but `ManifestBuilder.java:56,61` hardcodes `Set.of()`, so the T-06 guard never scores a real bundle
  - **Confirmed by measurement, then bound to the producer.** Every one of the twelve granted
    entities declared an empty set. Each mapper now declares the cross-tenant foreign-key columns
    it puts on the wire, `ManifestBuilder` derives `tenantBypassFks` from that registry, and the
    exporter scores its own manifest before it writes a bundle.
  - **Loud where it cannot verify.** An entity that holds a reviewed grant but declares nothing
    fails the export. The message states the residual limit: the check scores only the entities
    `ExportCommand.registeredEntities()` names, so `PERSON_CATEGORY_ASSIGNMENT` and `AUDIT_LOG`
    stay unscored until the exporter registers them.
- [x] T-52 — [S1 BLOCKER] T-02 removed the last executing lane for both showcase-seed proofs and `required` scores `skipped` as success, so two surfaces this journey edits are now ungated (supersedes T-42)
- [x] T-53 — [S1 BLOCKER] `fan-out parity` is schedule and dispatch only and sits outside `required.needs`, so the mapper edits in T-12 and T-14 would land with no gate; ship this before them (supersedes T-27 and `[FANOUT-RED-IS-INVISIBLE]`)
  - **The gate blocks, and the aggregator scores it.** `ci.yml` adds the `fan-out parity` job to
    `required.needs`. The job reads the fan-out workflow's runs through the API and refuses the merge
    unless one real run covers the producer files the branch changes. A run counts when its commit is
    an ancestor of the branch head and no producer file changed after it.
  - **A red now reaches a human on two surfaces.** The job reds the pull request. It also warns on
    every pull request while the newest fan-out on main is not green, and while the daily schedule
    stopped producing runs. That second surface is the rider `[FANOUT-RED-IS-INVISIBLE]`.
  - **The reader tells the classes apart.** `PARITY_DEFECT` means the AlpenFlight parity specs went
    red over a real legacy export. `COULD_NOT_RUN` means the chain broke first, which is where a cold
    NuGet cache lands; it blocks, and it never reads as a parity verdict.
    `NO_RUN_COVERS_THESE_PRODUCER_FILES` means the fan-out did not run. Real historical runs score
    each class: run 32102550688 reads `PARITY_DEFECT`, run 31775231378 reads `COULD_NOT_RUN`, and run
    31123658151 reads `COULD_NOT_RUN` from cancelled legacy builds.
  - **The gate arms on this branch today.** T-51 changed 20 files under `alpenflight/migration-bundle`
    and `alpenflight/migration-tool`, so the journey needs one green fan-out dispatch on
    `integration/J-32` before it merges. T-12 and T-14 change the same trees.
  - **Residual limit, stated in the failure message.** The gate arms only on the two producer trees
    whose legacy SELECT no synthetic bundle exercises. A server-side ingest change, a Flyway change or
    a spec change reaches the real fan-out and does not arm this gate.
- [ ] T-62 — [S1 BLOCKER] `fanout-parity-verdict.py:395` returns `PARITY_PROVEN` when any covering run passed, even when the newest covering run reads `PARITY_DEFECT`, and `gate()` only warns; the selftest never scores that variant, so the gate fails at its stated purpose
- [ ] T-63 — [S1 BLOCKER] the real-idp `page.route` ban misses `page.routeFromHAR`, `page['route']`, a destructured `route`, and every `_helpers/` file, where `console-guard.ts:165` exports `installMockApiStubs` routing `**/api/v1/**`
- [ ] T-64 — [S2] `AuditRedactionConfigStartupGuard.java:29` is wired by `@Component` alone and every test builds it by hand, so deleting the annotation reds nothing
- [ ] T-65 — [S2] confirm whether the migrated-copy rename still skips in the fan-out lane (`fan-out-migration-parity.spec.ts:23`); the T-16 worker and the final review disagree
- [ ] T-54 — [S2] `AuditTrailService.java:83` classifies a failed anonymous write as `SYSTEM` with no IP, so a tripped abuse guard still cannot say who — the case T-08a names as its motivation
- [ ] T-55 — [S2] `audit-logs.store.ts:93` calls `listUsers`, which admits `CLUB_ADMINISTRATOR` only, while `/system/logs` admits `SYSTEM_ADMINISTRATOR` too: a 403 falls back silently to the raw sub
- [ ] T-56 — [S2] gap-hunter nits: the impersonation guard exempts all of `ClubsController`, the retention boundary test asserts 90 days plus epsilon, the scheduled-job fixture names the wrong `targetEntityType`, and two proof videos end on `/start` rather than the asserted state
  - T-50 shipped the last two nits: the fixture names `PlanningNotificationRun`, and both videos end
    on the state their caption describes. The guard exemption and the boundary test remain.
- [x] T-59 — [S1 BLOCKER] the fan-out reds on a locale-brittle assertion this journey added: `public-registration-parity.spec.ts:55` pins the German bundle while `session.store.ts:94` renders the user's `languageCode`; the deployed branch-preview gallery link-check also reds (fan-out run 32456112094; `main` is `PARITY_PROVEN`)
  - **The expected label now comes from the locale the page renders.**
    `e2e/tests/_helpers/rendered-locale.ts` reads `<html lang>`, which `LocaleService.set` writes, and
    picks the matching bundle out of de, en, fr and it. An unknown language throws and names the
    language, so a missing bundle reads as a defect and never as a silent pass. The specs keep the
    full-string equality they had; only the source of the expected string changes.
  - **Five assertions carried the same brittleness; all five now read the rendered locale.** Two are
    the actor cell (`public-registration-parity.spec.ts`, `audit-log-two-club.spec.ts`), one is the
    mock-authenticated actor column (`audit-logs/audit-logs-list.spec.ts`), and two are page titles
    (`reservations-migration-parity.spec.ts`, `planning-migration-parity.spec.ts`). The two audit
    specs also drop their `?lang=de` pin, because the assertion no longer needs one. The proof
    screenshots now show the locale the session really renders, which is English.
  - **The gallery red has its own cause: the fan-out ran no J-32 spec.** The parity step listed
    eleven specs, all tagged J-0c to J-19, so the generator built the J-32 page from zero assets and
    the deploy replaced the good bookmark with a thin page. The list now carries
    `audit-log-two-club.spec.ts`, which the frontmatter already names as the journey parity test. The
    step budget moves to 35 minutes and the job budget to 55 minutes for the added run time.
- [x] T-60 — [S1 BLOCKER] `fanout-parity-verdict.py` misreports a non-producer spec failure as a producer defect, and `:206` returns `COULD_NOT_RUN` when the parity step passed but another step failed, which denies a proven parity
  - **The verdict names the red spec, not the red step.** The reader takes the red spec paths out of
    the run log, which the `['github']` Playwright reporter writes, and it matches them against the
    twelve specs the parity step drives. A red spec that asserts migrated data reads `PARITY_DEFECT`.
    A red spec that asserts none reads `UNRELATED_SPEC_RED` and tells the developer to hunt no
    mapper. A red the reader cannot name reads `PARITY_STEP_RED_SPEC_UNNAMED` and blocks.
  - **A proven parity is never denied.** A green parity step under a red job reads
    `PARITY_PROVEN_RUN_RED_ELSEWHERE`. The verdict states that parity holds, names every red step by
    number and name, and still refuses the merge, so the red reaches a human.
  - **Every verdict is scored against real API data.** `fanout-parity-verdict-fixtures.json` carries
    five runs captured from the Actions API, and `--capture-fixture` re-captures them. The pre-fix
    reader scores run 32456112094 as `PARITY_DEFECT` and run 30756910798 as `COULD_NOT_RUN`; both
    are wrong, which is the measurement that both classes were uncovered.
  - **Residual limit, stated in the failure message.** The reader cannot name a spec whose run log
    GitHub deleted, and Playwright stops a serial suite at its first red test, so the verdict names
    the specs that DID go red and certifies no spec that never ran.
- [ ] T-61 — [S2] the T-45 guard found fifteen more audit call sites passing a snapshot the recorded type does not describe, pinned in `alpenflight/server/config/audit/undecided-audit-snapshot-fields.txt`
- [ ] T-57 — [S2] the bundle ingest maps a cross-tenant FK rejection to `500 INGEST_INTERNAL_ERROR`, so a tenancy rejection reads as a server fault (T-51 finding)
- [ ] T-58 — [S2] `AuditLogMapper` declares no `foreignKeyColumns()`, so `ForeignKeyResolver` looks for `user_id` while the wire field is `actor_user_id`; latent only because `AUDIT_LOG` is unregistered (T-51 finding)
- [x] T-11 — [S1] `[MONEY-PROOF-CAPTION-OVERCLAIMS]` — assert the balance equality or correct the caption
- [x] T-12 — [S1] producer dedupe is soft-delete-blind: scope the dedupe source, extend the dedupe IT
- [x] T-13 — [S1] J-9 article-5001: the rider is stale, and the migrated FlightTime filter has no defect
  - **Legacy genuinely emits the article-5001 line.** `flsserver/database/FLSTest/3 insert/_test-fixture.sql:315`
    defines the "FlightTime: Glider per minute" rule — type 30, glider, every aircraft, 0 to MAX seconds,
    unit Min — with `ArticleTarget {"ArticleNumber":"5001","DeliveryLineText":"Glider flight minutes"}`.
  - **AlpenFlight emits it too, over migrated data.** Mined from fan-out run 32456112094 on this branch:
    `test-results/proof-manifest.json` records the migrated block as passed, and
    `alpenflight-dct-migrated-inputs.png` renders 5001 "HB-3256 Glider flight minutes" 10 Minuten,
    5001 12 Minuten and 6001 "Landegebuehr LSZK" 2 Landung. The values are not derived: they match the
    22-minute HB-3256 glider flight (`6 Insert Test Flights.sql:143`) and the 600-second, 25 %
    `PersonFlightTimeCredit` (`_test-fixture.sql:726`). ADR 0026 D-3 puts the 5001 tier at `min = 0`, so
    the credit split is behaviour-identical to legacy and no money divergence is reachable here.
  - **J-27 T-09 fixed it on 2026-06-20 and nobody deleted the rider.** A malformed recipient fixture made
    the engine answer 500, which suppressed every item. The predicate and shadowing hunt was a red herring,
    so the rider named a cause its own evidence never supported.
  - **The spec now separates the two causes.** `delivery-creation-test-parity.spec.ts:502` records the
    HTTP status and the article numbers the engine returned for each migrated glider flight, and the
    failure message prints them. A 5xx can no longer read as "the filter predicate missed".
  - No producer file changed, so `fan-out parity` needs no fresh run for this task.
- [x] T-14 — [S1] J-8 `AccountingRuleFilter`: the rider is stale, and the spec now asserts the predicate it names
  - **The mapper needs no correction.** J-27 T-01 (PR #228, 2026-06-20) fixed the real cause. A bare
    `JSON_VALUE` over the dirty legacy `ArticleTarget` text aborted the WHOLE producer cursor, so the
    migrated FlightTime filter never arrived. The gated extraction sits at
    `MapperLegacyBindings.java:672-683`. J-27's own fan-out gate recorded the case GREEN over real MSSQL,
    and nobody deleted the rider.
  - **The green did not prove the rider's claim.** The case asserted the article number, the delivery-line
    text and the derived list target. It never asserted a predicate field, and its title claimed "predicate
    config intact". This is a `[SPEC-TITLES-OVERCLAIM]` of the T-19 class.
  - **The spec now asserts the predicate.** `accounting-rules-parity.spec.ts:439` reads the migrated
    `filter_config` and asserts the glider-only scope flags, the 0-second minimum, the 2147483647-second
    maximum, the extend-flight-type-codes flag, and BOTH invert-oriented empty match lists. Every value comes
    from the legacy row in `flsserver/database/FLSTest/3 insert/_test-fixture.sql:312-329`. The case then
    reopens the migrated filter and asserts the glider flag RENDERS checked, so the proof video shows the
    predicate.
  - **`AccountingRuleFilterProducerDedupeIT.java:229` already locks the same round-trip in `check`** over the
    real producer SELECT plus the real mapper, on PostgreSQL 17.
  - No producer file changed.
- [ ] T-15 — [S2] `MapperVsSchemaCompatibilityTest` red since J-13: add the missing Flyway placeholder
- [x] T-16 — [S2] J-0c Location migrated render: the rider is stale, and its neighbour skip now runs
  - **The rider named a case that passes.** Line 167 held "club-B admin sees its OWN copy of the migrated
    Location" before J-27. That case is `fan-out-migration-parity.spec.ts:99` today. J-27 T-03b (PR #228,
    2026-06-20) fixed it fixture-side: the 409-reuse path picked the two clubs in arbitrary Keycloak order
    with no Location-ownership filter, so club B could be a non-owner. Fan-out runs 32456112094 and
    32467009395 pass club-A, club-B and the cross-tenant 404 over the real bundle.
  - **The skip covers a DIFFERENT case, and that case ran nowhere.** J-1 T-18 (PR #202, 2026-06-03) skipped
    "renaming club-A copy leaves club-B copy unchanged" because it timed out on the load-starved fan-out
    runner. The skip was unconditional. The nightly real-idp suite that the skip reason promised never ran
    the case either. The assertion stayed invisible for eleven weeks.
  - **The migrated render is genuinely correct.** A local real-idp run of the spec on the LAN Postgres passes
    club-A at `:78` and club-B at `:99` over the synth fan-out fixture.
  - **The skip is now conditional and visible.** `fan-out-migration-parity.spec.ts:120` skips only when the
    real bundle drives the fan-out runner, prints the reason in the report, and takes a 180-second budget for
    its two real-Keycloak logins. The nightly `alpenflight-e2e-real-idp.yml` full `--project=real-idp` glob
    now runs the case over the synth fan-out fixture.
  - **The rename assertion also moved to a lane that always runs.** The local run of the un-skipped case went
    red on a blank `/locations` page after the third cold `page.goto` of the run, with no console error and no
    4xx in the trace — the reboot-and-renew stall of `project_real_idp_goto_reboot_renew_stall`, under 54 MB
    of free memory. A second local run could not re-test it, because the fan-out fixture takes its
    409-reuse path against an already-ingested database. So the assertion no longer depends on a healthy
    runner: `LocationsTenantIsolationIT.java:98` renames club A's fanned-out copy and asserts club B's copy
    keeps the original name and never carries the new one. It runs in `check` on every push. Teeth verified:
    a planted second rename on club B's copy reds it with an expected-versus-actual, not an error.
- [ ] T-17 — [S2] fanout has no reporting spec over migrated data
- [ ] T-18 — [S2] `[VACUOUS-NARROWING-ASSERTIONS]` — seed the should-be-excluded rows
- [x] T-19 — [S2] `[SPEC-TITLES-OVERCLAIM]` — narrow the two J-13 ACs or strengthen the specs
- [x] T-20 — [S2] ban `page.route` under `e2e/tests/real-idp/` with an eslint override
- [ ] T-21 — [S2] sweep `.github/` comments and add the directory to the comment-gate roots
- [ ] T-22 — [S2] typecheck `web/scripts/**` in CI
- [ ] T-23 — [S2] cover renames inside inline Angular `template:` literals
- [ ] T-24 — [S2] `e2e/tsconfig.json` module resolution + an e2e lint/typecheck lane
- [ ] T-25 — [S2] widen the absolute-date guard to the five unread fields
- [ ] T-26 — [S2] theme guards: protocol-relative URLs + wire or delete `check-theme-load.sh`
- [ ] T-28 — [S2] `RequestIdFilter` MDC key matches the logback pattern
- [ ] T-29 — [S2] give each tenant-isolation IT its own club identity
- [ ] T-30 — [S2] persons detail route may be shadowed by the list glob
- [ ] T-31 — [S2] reservations evicted body: read the id from the 201 `Location` header
- [ ] T-32 — [S2] `liveFieldErrors` gates on touched/dirty
- [ ] T-33 — [S2] `MOCK_CLUB_ID` takes the real `clb-<uuid>` shape
- [ ] T-34 — [S2] legacy J-2 readiness: un-quarantine or re-home the parity coverage
- [ ] T-35 — [S2] suite isolation: non-migration parity specs seed their own data
- [ ] T-36 — [S2] bound the gh-pages history
- [ ] T-37 — [S2] bare `/signup` → `/join` funnel spec
- [ ] T-38 — [S2] un-mask the migration-ingest constraint name on dev/test only
- [ ] T-39 — [S2] op-field-mutate test coverage
- [ ] T-40 — [S2] JIT-username robustness: reject a distinct sub reusing a live username
- [ ] T-41 — [S2] explicit `@Operation` operationIds so orval emits named client methods
- [ ] T-43 — [S2] `RequestTenantHint` lost its only producer when T-03 deleted the interceptor; `RequestAuditFilter.java:80-91` is now unreachable (T-03 finding)
- [ ] T-44 — [S2] `verifyArchUnitFailsOnViolation` and `verifyNullAwayFailsOnViolation` hang off no `check` task, so CI never runs them (T-03 finding)
- [x] T-45 — [S1] three audit call sites pass a snapshot whose class the `entityType` does not name, so the allow-list matches almost nothing and the row is nearly empty: `PersonsService.java:243,262,278` record `PersonClub` with a `Person` / `PersonResponse`; `AircraftsService.java:252,277` record `Aircraft` with `AircraftStateHistoryEntryResponse` / `AircraftOperatingCounterResponse`, so those two config entries are dead (T-05 finding — the startup guard's stated residual limit, made concrete)
- [ ] T-47 — [S2] `HttpOgnDeviceDatabase.java:53` catches `RuntimeException` and reports the aircraft sync as a success, so the job writes nothing and says it worked (T-07d finding)
- [ ] T-48 — [S2] `enterViaNav` (`e2e/tests/_helpers/nav.ts:21`) leaves the Masterdata overlay open, so the next click on the page under it retries until the test timeout instead of failing with a cause; every spec that enters a masterdata screen through the chrome and then clicks carries the hazard. Close the overlay inside the helper and assert it closed (T-10 finding; `audit-log-two-club.spec.ts` works around it locally)
- [x] T-46 — [S1] four scheduled jobs bypass their own `@Transactional` through a self-call, so the run opens no transaction: `AircraftDatabaseSyncJob.java:43` calls `runOnce`, `DailyReportJob.java:86` and `PlanningDayNotificationJob.java:114` call `runForCurrentClub`, `LicenceNotificationJob.java:63` calls `runOnce`. Both `DailyReportJob` and `PlanningDayNotificationJob` already inject an `ObjectProvider` self-proxy for the other entry point, so the `@Scheduled` path is the miss. Fix the four call sites, then widen `TransactionDemarcationStructureGuardTest.noClassCallsATransactionalMethodItDeclaresItself` from this seam to the whole production tree; the repository-wide run reports nine sites, and the other five are an `ObjectProvider` self-proxy that does cross the proxy, a caller that is itself `@Transactional`, and compiler-generated bridge methods that the rule already skips (T-07b finding)

## Assumptions made

- The proof screen is `/system/logs` because the S1 cluster lands on it. The roadmap said only "a
  built screen".
- The waves are my grouping, not the operator's. `/do-ship` may re-order them.
- `[GH-PAGES-HISTORY-IS-UNBOUNDED]` sits at S2. It was filed S1 on 2026-08-19 and lowered when
  `filter: blob:none` removed the broken-build symptom. The operator may raise it again.
- S3 riders (24) stay out of scope, per the roadmap's S1+S2 done-bar.
