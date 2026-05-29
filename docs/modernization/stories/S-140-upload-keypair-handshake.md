---
id: S-140
title: Per-upload keypair handshake + public-key surface
epic: E-15
status: todo
depends_on: [S-134]
integration_base: integration/migration
acceptance:
  - `POST /api/v1/migrations/handshake` (authenticated, email-verified) issues a fresh RSA-4096 keypair per call, persists the private key encrypted-at-rest in a `migration_upload` row with state `awaiting_upload`, returns `{ uploadId, publicKeyPem, expiresAt }` (24 h TTL).
  - `GET /api/v1/migrations/handshake/current` returns the caller's current `awaiting_upload` row (sans private key) for SPA reload resilience, or 404.
  - The SPA surface at `/migrate/start` displays the public-key PEM in a copy-friendly textarea + a "Download public-key file" button (saves `alpenflight-public-key-<uploadId>.pem`). Both options pre-populate the same key. **Page mount restores the existing `awaiting_upload` via `GET .../current`; a fresh `POST .../handshake` only fires after the user clicks "Regenerate" AND confirms the modal warning that the prior key (if pasted into the JAR) will become invalid.**
  - A "Show me the export tool" panel below the key links to S-139's JAR download (placeholder constant until S-139's CI artifact URL exists).
  - Each user can have at most one `awaiting_upload` migration at a time, **enforced structurally by partial UNIQUE `(user_id) WHERE state = 'awaiting_upload'`**. Calling handshake again wipes the previous row's private key (column → NULL), flips state to `superseded`, emits `migration.handshake_superseded`.
  - Private-key-at-rest encryption: AES-256-GCM via **Google Tink** `AEAD` primitive under a keyset-rotation-ready master keyset (env-loaded JSON; single key in V1; KMS envelope upgrade is a drop-in via Tink's `KmsEnvelopeAead`).
  - On TTL expiry, a scheduled cleanup job (`MigrationHandshakeExpiryJob`, hourly, idempotent) flips expired rows to `expired` and wipes the private key. System-actor audit row.
  - Funnel-telemetry events: `migration.handshake_issued`, `migration.handshake_superseded`, `migration.handshake_expired`.
estimate: M
adr_refs: [0019]
parity_test: tests/migration/handshake.spec.ts (new)
refined: true
refined_at: 2026-05-29
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer]
context7_last_checked: 2026-05-29
github_issue: 165
---

## Context
Vision C28 specifies an "on-the-fly generated public-key for the encryption" per upload. This is the server side of that handshake: generate a fresh keypair, retain the private key encrypted-at-rest until ingest consumes it (S-141), surface the public key for the user to feed to the JAR (S-139).

Per-upload (not per-user, not global): each ingest stands on a fresh keypair. Blast radius of a leaked private key is bounded to a single upload's bundle.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] V16 Flyway: `t_migration_upload` (one in-flight row per user via partial UNIQUE).
- [ ] Tink dependency + `MigrationCryptoConfig` (env-keyset bootstrap, fail-fast).
- [ ] `MigrationUpload` aggregate + tight state machine.
- [ ] `MigrationHandshakeService.issue / current / superseded-on-issue` + endpoints.
- [ ] `MigrationHandshakeExpiryJob` (`@Scheduled` hourly, unscoped, idempotent).
- [ ] SPA `/migrate/start` page (mount-restore via `GET .../current`; Regenerate flow with modal-confirm).
- [ ] Audit-log entries: `MIGRATION_HANDSHAKE_{ISSUED, SUPERSEDED, EXPIRED}`.

<!-- modernize-refine: start -->

## Design notes

- **Module placement.** New top-level package `ch.alpenflight.migrations` with `{domain, application, web, infra}` per ADR 0023. S-140 lands the shared substrate (the `MigrationUpload` aggregate, the crypto primitives, the `/handshake` endpoints, the expiry job + Spring Modulith `@ApplicationModule` boundary). S-141 extends `domain` (adds `MigrationRun` next to `MigrationUpload`) and adds its own `application` / `web` slices.
- **Cryptographic substrate — Google Tink.** Wrap-once-with-AEAD is the entire crypto surface; do NOT hand-roll AES-GCM envelopes. Maven: `com.google.crypto.tink:tink:1.15+`. Three primitives in scope here:
  - **`AEAD` (AES-256-GCM)** wraps `private_key_ciphertext` (the PKCS#8 DER bytes of the per-upload RSA private key) under the server master keyset; the `uploadId` UUID bytes are passed as `associatedData` so a ciphertext from upload A cannot be unwrapped against upload B's metadata.
  - **`KeysetHandle`** = the master keyset. Serialized as Tink's standard JSON; sourced from the env var `ALPENFLIGHT_MIGRATION_MASTER_KEYSET` (base64-encoded JSON, or `file:/path/to/keyset.json` URL — pick at boot per `ALPENFLIGHT_MIGRATION_MASTER_KEYSET_SOURCE`). V1 ships a single-key keyset; `KeysetManager.add(...) + setPrimary(...) + disable(...)` is the rotation path — old keys still decrypt historical rows because `key_id` is intrinsic to Tink's envelope.
  - **`StreamingAead`** (S-141 will adopt for the bundle decrypt — `AES256_GCM_HKDF_4KB`). Notable here: Tink's `newDecryptingStream` validates auth tags chunk-by-chunk + at close, eliminating the JDK `CipherInputStream` deferred-tag footgun S-141's design currently calls out as the highest-risk gotcha. **Pickup note for S-141:** consider re-refining to adopt `StreamingAead` instead of raw `Cipher`+`CipherInputStream`; same security guarantee, lower implementer risk.
- **Master-keyset bootstrap.** `MigrationCryptoConfig` `@PostConstruct` reads the env var, parses the Tink JSON keyset via `TinkJsonProtoKeysetFormat.parseKeyset`, instantiates `Aead` from the handle, holds in a `@Bean` for app lifetime. **Fail-fast on missing / malformed env** (`BeanCreationException` from `@PostConstruct`). No fallback default. Operator runbook: generate a fresh keyset locally via `tinkey create-keyset --key-template AES256_GCM` then base64-encode the JSON. Reused by S-141 via the same bean.
- **`MigrationUpload` aggregate — tight state enum: `awaiting_upload | superseded | expired | failed | consumed`.** Aggregate methods: `static issue(userId, keypair, clock, ttl)`, `supersedeBy(newUploadId, clock)`, `markExpired(clock)`, `markFailed(errorCode, clock)`, `markConsumed(deploymentId, clock)`. Last three wipe `privateKeyCiphertext = null`. Guards reject illegal transitions via `IllegalUploadStateException`. **Per ADR 0022 D2: no DB CHECK on `state` — the FSM lives on the aggregate.** The double-POST-from-S-141 guard is `state = awaiting_upload AND no row EXISTS in t_migration_run WHERE upload_id = ? AND state NOT IN ('failed')` — S-141 owns the join; S-140's surface stops at the simpler `state = awaiting_upload`.
- **Boundary contract with S-141.** S-140 owns the `awaiting_upload → superseded / expired` transitions. S-141 owns `awaiting_upload → consumed / failed` and the `t_migration_run` parent row. The enum is defined here once; S-141 imports it.
- **`uploadId` external form — NO prefix.** `MigrationUpload` is a 24h-lifetime credential row, not a cross-bounded-context aggregate like `Flight`/`Aircraft`. `MigrationUploadId` typed-ID record exists per convention but `toExternal()` returns the raw canonical UUID (`019e30c3-…`). ADR 0019 prefix registry untouched. Reversible later if cross-context surfaces emerge.
- **PEM public-key surface.** Standard X.509 SubjectPublicKeyInfo, PEM-wrapped (`-----BEGIN PUBLIC KEY-----` + base64(SPKI) + 64-char lines). Encode via JDK `publicKey.getEncoded()` + `Base64.getMimeEncoder(64, "\n")` — no BouncyCastle. Response JSON: `{uploadId, publicKeyPem, expiresAt}`. Use a DTO record, NEVER the JPA entity — `private_key_ciphertext` must never serialize back.
- **Endpoints.**
  - `POST /api/v1/migrations/handshake` — `@PreAuthorize("isAuthenticated() and principal.claims['email_verified'] == true")`. No request body (reject body with 400). Returns 200 `{uploadId, publicKeyPem, expiresAt}`. Silent supersede on prior `awaiting_upload`.
  - `GET /api/v1/migrations/handshake/current` — same authz. Returns 200 with current `awaiting_upload` row (sans private key) OR 404.
- **SPA `/migrate/start`.** Move the page out of `@features/signup/signup.routes` into its own module `@features/migrate-handshake/` (`migrate-handshake.page.ts`, `migrate-handshake.store.ts`, `migrate-handshake.service.ts`). Update `app.routes.ts` `migrate` lazy-load target; the existing `signup/migrate-start.component.ts` placeholder is superseded. Signal Store: `migrateHandshakeStore` with `currentUpload`, `publicKeyPem`, `expiresAt`, `loading`, `error`. **Page-mount flow:** call `GET /handshake/current` → if 200, restore state; if 404, call `POST /handshake`. **Regenerate flow:** explicit "Regenerate" button → modal-confirm warning `"A new key invalidates the previous one. If you already started the export with the old key, abandon it and start over."` → on confirm, call `POST /handshake`. Touch targets ≥ 44 × 44 px (Vision §2 NFR — gloves rationale). Route guard: authenticated + `email_verified=true`; unverified → `/verify-email-pending`.
- **`MigrationHandshakeExpiryJob`.** `@Scheduled(cron = "0 0 * * * *")` in `migrations.application`. Single UPDATE `state='expired', private_key_ciphertext=NULL WHERE state='awaiting_upload' AND expires_at < now() RETURNING id, user_id`. Runs unscoped (handshake rows are pre-tenant) — follow the pattern at `alpenflight/server/src/main/java/ch/alpenflight/deployments/application/LifecycleStateFilter.java`. For each returned row: emit `migration.handshake_expired` funnel event + `MutationAuditEvent` with `systemActor=true`. Idempotent by the predicate. **Terminal-row sweep** (deleting `superseded`/`expired`/`failed` rows older than N days) is **out of scope** — operator-deferred to a future ops story; file a stub at finalize.
- **Audit integration.** Three actions: `MIGRATION_HANDSHAKE_ISSUED` (actor = user, before=null, after={state, expires_at, private_key_ciphertext='<bytes:NNN>'}), `MIGRATION_HANDSHAKE_SUPERSEDED` (actor = user, both sides set), `MIGRATION_HANDSHAKE_EXPIRED` (`systemActor=true`). **NEVER include the raw ciphertext in the audit payload — only its length.** Funnel telemetry stream is dual: `{uploadId, occurredAt}` only, no `userId`, no PEM.
- **Pre-tenant authn note.** `migration_upload` has no `club_id` and no `@TenantId` — pre-tenant by design (signup → handshake fires before any Deployment exists). The principal → `t_user.id` lookup CANNOT use `UserPrincipalLookup` (it requires the JIT path with a club claim, see `JitUserMaterializationFilter.shouldMaterialise`). Add a sibling lookup keyed on `keycloak_sub` only — `SELECT id FROM t_user WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL`. **S-024 cross-tenant leakage CI must allowlist `migration_upload`** (hand-off line).
- **Flyway V16.** `t_migration_upload`: `id uuid PK`, `user_id uuid NOT NULL REFERENCES t_user(id)`, `state varchar(32) NOT NULL`, `public_key_pem text NOT NULL`, `private_key_ciphertext bytea NULL`, `created_at timestamptz NOT NULL`, `updated_at timestamptz NOT NULL`, `expires_at timestamptz NOT NULL` (= `created_at + interval '24 hours'`, stored), `consumed_at timestamptz NULL`. Partial UNIQUE `ux_migration_upload_user_awaiting (user_id) WHERE state = 'awaiting_upload'` — the structural enforcer for AC §5. No CHECK on state.

## Edge cases & hidden requirements

- **Two-tab simultaneous /handshake** — partial UNIQUE rejects the loser's INSERT with a constraint violation; service catches, retries `(supersede prior + insert new)` in a single txn. Loser races converge to "one wins, the other is itself the supersede".
- **Re-handshake orphans the prior PEM the user pasted into the JAR** — solved by the Regenerate-modal-confirm flow + page-mount restore-from-current (operator-picked). The user has to actively choose to invalidate; accidental refreshes restore the existing key.
- **Two browser sessions (laptop + phone)** — second `/handshake` silently supersedes the first; the laptop's stored PEM becomes orphan. The SPA on the laptop sees the supersession only on next mount (404 → fresh /handshake, modal-suppress for the page-mount case since there's no key to invalidate from this session's POV). Acceptable as designed.
- **TTL clock anchor:** `expires_at` is stored at INSERT (`created_at + 24h`) — predicate `expires_at < now()` is monotonic across clock skew.
- **Upload arrives at `expires_at + epsilon`** — distinct error code: S-141 returns `BUNDLE_HANDSHAKE_EXPIRED` (separate from decrypt failure codes). Hand-off line for S-141's error taxonomy.
- **App crash between row-commit and HTTP-response** — orphan row exists; SPA never received PEM. SPA's mount-restore via `GET /handshake/current` covers the recovery: on next visit the row is restored intact.
- **Master-keyset env var missing / malformed at boot** — `BeanCreationException`, app refuses to start. Operator-confirmed.
- **Master-keyset rotation** — Tink keyset gives intrinsic `key_id`; rotation is `KeysetManager.add(new) → setPrimary(new) → disable(old)`. Old `awaiting_upload` rows decrypt against the disabled key, new issues use the primary. Drain unnecessary.
- **Concurrent expiry-job execution** (Spring `@Scheduled` overlap on slow JVM) — predicate filters `state='awaiting_upload'`, so the second tick is a no-op on already-expired rows.
- **Audit-actor for expiry job** — `ActorResolver` resolves no Spring Security context → `systemActor=true`. Confirm the job's transaction setup leaves the security context empty (no leaked principal from a prior request thread).
- **Supersede audit-actor is the user** — the user's `POST /handshake` triggered the supersede; `systemActor=false`.

## Security plan

- **Authz.** `@PreAuthorize("isAuthenticated() and principal.claims['email_verified'] == true")` on both endpoints. Principal → `t_user.id` via a pre-Club sibling lookup (NOT `UserPrincipalLookup` which requires the JIT path). 401 on anonymous; 403 on unverified email.
- **Per-upload private key.** PKCS#8 DER bytes wrapped by Tink AEAD using `uploadId.bytes` as `associatedData` (binds wrapping to row identity — a ciphertext from row A cannot be unwrapped against row B's metadata). Zeroize the unwrapped plaintext byte[] in a `finally` after Tink's `encrypt` returns. Store wrapped bytes in `bytea`.
- **Master keyset.** Single `KeysetHandle` cached as a `@Bean` from `@PostConstruct`. Env var: `ALPENFLIGHT_MIGRATION_MASTER_KEYSET` (base64 of Tink JSON keyset). Source mode env var: `ALPENFLIGHT_MIGRATION_MASTER_KEYSET_SOURCE = inline | file`. Fail-fast at boot on missing / malformed.
- **PEM substitution defense.** HTTPS-only transport (Caddy per ADR 0010) + HSTS already in place. The public key is non-secret but a substituted public key allows the substituting party to decrypt the upload — covered by transport invariant.
- **Response DTO.** JSON-serializable record `HandshakeResponse(UUID uploadId, String publicKeyPem, Instant expiresAt)`. Never the JPA entity. Integration test asserts the response body contains exactly those three fields.
- **Audit events.** Three actions (above). `before/after` snapshots use `<bytes:NNN>` placeholder for `private_key_ciphertext` — never the raw bytes. Funnel telemetry payload pinned to `{uploadId, occurredAt}`.
- **Rate-limiting.** Per-user thrash on `/handshake` is a CPU DoS amplifier (RSA-4096 keygen ~100-500ms each). Per-endpoint rate-limit is **deferred to a future story (S-041 rate-limit infra)** — note as `[improvement]` here. Caddy-layer per-IP cap can serve as the interim mitigation.
- **OWASP delta:** A02 covered by Tink (AES-256-GCM + random IV per wrap + 128-bit tag). A05 covered by fail-fast boot validation. A08 covered by AEAD auth tag.

## Test plan

- **Parity strategy.** Greenfield — no legacy oracle. `tests/migration/handshake.spec.ts` is a happy-path Playwright spec validating SPA-side; backend correctness lives at integration.
- **Unit (~5):** `MigrationUpload` state-machine transitions; Tink keyset round-trip (parse env → encrypt → decrypt → match); RSA-4096 modulus bit-length assertion; PEM round-trip through `KeyFactory.generatePublic`; expiry-policy clock math.
- **Integration (`@SpringBootTest` against `SharedPostgresContainer`):**
  - Handshake happy path: 201 + body shape + row in `awaiting_upload` + private key Tink-decryptable + `MIGRATION_HANDSHAKE_ISSUED` audit event.
  - `GET /handshake/current` returns 200 with row OR 404.
  - Anonymous → 401; unverified email → 403.
  - **Supersession** (headline): two sequential POSTs → first row `superseded`, key NULL; second row fresh; events fire in order; partial UNIQUE structurally enforces.
  - **TTL expiry-job happy path + idempotency** (twice on same row → no duplicate events).
  - **Concurrent /handshake race** — `CompletableFuture.allOf(...)` against the same principal; assert the invariant (one survivor in `awaiting_upload`), not which one wins.
  - **Master-keyset env across app restart** — seed + `@DirtiesContext` + reload + unwrap-match. `@Tag("slow")`.
  - **Fail-fast on empty master-keyset** — separate Spring context with `ALPENFLIGHT_MIGRATION_MASTER_KEYSET=` empty; assert `ApplicationContextException`.
- **E2E (1 Playwright spec):** sign-in via S-134 mock, land on `/migrate/start`, assert PEM textarea + download blob + JAR-link panel; assert touch targets ≥ 44 × 44 px at mobile viewport. NO supersede / expiry coverage here.
- **Fixtures (shared with S-141):** `MasterKeyTestFixture` (`@TestConfiguration` + `@DynamicPropertySource` for a deterministic Tink keyset); `MigrationHandshakeTestFixture` (`seedAwaitingUpload(userId, expiresAt) → (row, plaintextPrivateKey)`); `static final KeyPair TEST_KEYPAIR` cache (1 keygen across the entire suite — 4096-bit keygen × 30 tests would tax 9s otherwise). `RecordingTelemetrySink` confirmed reusable from S-138 / S-027. SPA OIDC mock from `alpenflight/web/e2e/helpers/signin-as.test-helper.ts` (S-134).
- **Out of scope for S-140 tests:** the bundle decryption / ingest flow (S-141); JAR-side encryption (S-139); KMS-backed master key (deferred); audit-log read-back of `LEGACY_MIGRATED` actor (S-183).

## Performance plan

(N/A — M-scoped story; the only measurable cost is RSA-4096 keygen (~100-500ms per call) on the request thread, well inside the Vision O8 30-min and §2 NFR `signup→trial-ready p95 < 10s` budgets. Pre-warm `SecureRandom.getInstanceStrong()` at boot. No keypair pool — defeats per-upload uniqueness, throughput need is order-of-magnitude one handshake per user per day. Per-endpoint rate-limit deferred to S-041.)

<!-- modernize-refine: end -->
