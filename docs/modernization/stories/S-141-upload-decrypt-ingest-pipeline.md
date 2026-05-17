---
id: S-141
title: Encrypted-bundle upload + streaming decrypt + ingest pipeline
epic: E-15
status: todo
depends_on: [S-016, S-138, S-140]
acceptance:
  - `POST /api/v1/migrations/{uploadId}/bundle` (authenticated, `Content-Type: application/octet-stream`, streaming upload) accepts an encrypted bundle in the format defined by ADR 0019. Max body size 2 GB (vision §2 NFR). 413 on oversize.
  - Endpoint streams the upload directly into the decrypt pipeline: header parse → unwrap session key with the per-upload private key (S-140) → AES-GCM-decrypt the archive stream → tar-extract per-entity NDJSON streams → call S-016's schema-mapping ingest function per entity stream → commit-on-success.
  - **No plaintext touches disk.** Decryption happens in-memory in fixed-size chunks; the only persisted output is the resulting Postgres rows. Plaintext-leak test fixture plants a unique marker in a synthetic bundle and asserts the marker never appears in the post-ingest disk + tmpfs greps. (Vision §2 NFR.)
  - The bundle manifest declares N Clubs (the legacy FLS install may host 1..N). The pipeline provisions a fresh Deployment (S-138) plus one Club per manifest entry, hangs each entity stream off its declared Club, and commits the whole thing in a single Postgres transaction.
  - On success: `migration_upload.consumed_at = now`; private key wiped; response carries the new `deploymentId` + the list of provisioned `clubIds`.
  - On failure (decrypt mismatch / corrupt bundle / schema-mismatch / partial ingest): transaction rolls back; no Deployment is provisioned; `migration_upload` flipped to `failed` with error code; private key wiped (failed upload requires a fresh handshake to retry).
  - Progress reporting: SPA polls `GET /api/v1/migrations/{uploadId}/status` and shows a per-phase progress bar. States: `awaiting_upload`, `decrypting`, `ingesting <entity-name> <club-name>`, `provisioning`, `complete`, `failed`.
  - Funnel-telemetry events: `migration.upload_started`, `migration.ingest_started`, `migration.ingest_completed` (with `club_count`), `migration.ingest_failed`.
  - Concurrency: one upload per user at a time (enforced by `migration_upload` state machine from S-140).
estimate: L
adr_refs: [0018, 0019]
parity_test: tests/migration/upload-and-ingest.spec.ts (new; round-trip from S-139 JAR through this pipeline)
---

## Context
Vision C28 + C32 + §2 NFR (plaintext-at-rest exposure) define the security posture. C34 specifies the data model: one upload → one Deployment containing 1..N Clubs.

This story owns the server-side pipeline; S-139 owns the client-side write; S-016 owns the schema-mapping logic shared between them.

The streaming requirement is load-bearing: a 2 GB plaintext bundle decrypted to a temp file is both a memory-class risk on a single-VPS deployment AND a security risk. Streaming hybrid decrypt avoids both.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Endpoint scaffold (Spring `@PostMapping` with `InputStream` body).
- [ ] Decrypt pipeline: header parse, RSA unwrap, AES-GCM stream-decipher, tar inflate.
- [ ] Manifest reader: enumerate Clubs in bundle, hand off to provisioning service (S-138).
- [ ] Per-entity ingest dispatch using the shared `next/migration-bundle/` library (S-016).
- [ ] Transactional boundary: full ingest is one Postgres transaction.
- [ ] Status-polling endpoint + progress state.
- [ ] Plaintext-leak test fixture + assertion.
- [ ] Idempotency: re-uploading after `failed` requires a fresh handshake.

## Notes
- A 2 GB bundle in one transaction is a lot; refine whether to split per-Club or per-entity with explicit rollback (operator preference + measurement against the prod-class VPS).
- Decrypt pipeline holds the private key in memory only during the upload window. Refine: secure-byte-array implementation that zeroes on close.
- Per memory `[[feedback-re-runnable-over-frozen-docs]]`: parity test reads from a seeded legacy SQL Server (via S-139's JAR) and writes to a fresh Deployment — re-runnable in CI, not a committed bundle.
