---
id: S-140
title: Per-upload keypair handshake + public-key surface
epic: E-15
status: todo
depends_on: [S-134]
acceptance:
  - `POST /api/v1/migrations/handshake` (authenticated) issues a fresh RSA-4096 keypair per call, persists the private key encrypted-at-rest in a `migration_upload` row with state `awaiting_upload`, returns `{ uploadId, publicKeyPem, expiresAt }` (24 h TTL).
  - The SPA surface at `/migrate/start` displays the public-key PEM in a copy-friendly textarea + a "Download public-key file" button (saves `alpenflight-public-key-<uploadId>.pem`). Both options pre-populate the same key.
  - A "Show me the export tool" panel below the key links to S-139's JAR download (CI artifact URL or hosted location — refine before production go-live).
  - Each user can have at most one `awaiting_upload` migration at a time. Calling handshake again invalidates the previous one (private key wiped, status flipped to `superseded`). Funnel-telemetry captures the supersede event.
  - Private-key-at-rest encryption: AES-256-GCM under a server-side master key (env-loaded; KMS deferred — refine for prod).
  - On TTL expiry, a scheduled cleanup job (`MigrationHandshakeExpiryJob`, hourly) flips expired rows to `expired` and wipes the private key. Idempotent.
  - Funnel-telemetry events: `migration.handshake_issued`, `migration.handshake_expired`.
estimate: M
adr_refs: [0019]
parity_test: tests/migration/handshake.spec.ts (new)
---

## Context
Vision C28 specifies an "on-the-fly generated public-key for the encryption" per upload. This is the server side of that handshake: generate a fresh keypair, retain the private key encrypted-at-rest until ingest consumes it (S-141), surface the public key for the user to feed to the JAR (S-139).

Per-upload (not per-user, not global): each ingest stands on a fresh keypair. Blast radius of a leaked private key is bounded to a single upload's bundle.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] `migration_upload` table (Flyway migration): `id`, `user_id`, `state`, `public_key_pem`, `private_key_ciphertext`, `created_at`, `expires_at`, `consumed_at`.
- [ ] Spring service `MigrationHandshakeService.issue()`.
- [ ] `MigrationHandshakeExpiryJob` (Spring `@Scheduled`).
- [ ] SPA `/migrate/start` page with the key-surface UI.
- [ ] Audit-log entries for handshake-issued + handshake-superseded.

## Notes
- Master-key envelope: a single server-side AES key in env wraps the per-upload private keys. KMS upgrade (e.g. Vault, AWS KMS) is a future hardening — refine if/when production infra firms up.
- An attacker stealing the DB but not the master key cannot decrypt pending uploads. An attacker with both can; that's the cost of holding the private key server-side. Acceptable trade-off because the bundle is in flight for minutes, not days.
- The handshake row + key are wiped after `consumed_at` is set (S-141's ingest does this). Retention zero.
