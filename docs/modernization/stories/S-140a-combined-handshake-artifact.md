---
id: S-140a
title: Combined handshake artifact — SPA download carries uploadId + public key for the export jar
epic: E-15
status: todo
depends_on: [S-140]
integration_base: integration/migration
origin: refinement-followup
origin_story: S-139
refined: true
refined_at: 2026-05-31
refined_specialists: [requirements, solution, qa]
github_issue: 183
github_pr: 184
acceptance:
  - The S-140 handshake SPA surface (`/migrate/start`) offers a single combined download artifact containing BOTH the `uploadId` and the RSA-4096 `publicKeyPem`, replacing the bare `.pem` download. The format is a self-describing artifact (JSON, or PEM with a structured header) documented as the contract the export jar parses via `--handshake-file`.
  - The copy-to-clipboard control and the download button both yield the combined artifact (uploadId + PEM), never a bare key — so the user cannot feed the jar a key without its matching uploadId.
  - No new server endpoint: the `POST .../handshake` and `GET .../handshake/current` responses already return `{ uploadId, publicKeyPem, expiresAt }` (S-140). This is a client-side packaging change plus the published file-schema the jar consumes.
  - The combined artifact's filename embeds the uploadId (e.g. `alpenflight-handshake-<uploadId>.json`), superseding the old `alpenflight-public-key-<uploadId>.pem` convention.
  - The export jar (S-139) reads the artifact via `--handshake-file`, extracting the public key for the RSA-OAEP session-key wrap and the `uploadId` for the Tink StreamingAead associated data (without the uploadId the server fails the AEAD tag).
estimate: S
adr_refs: [0022]
parity_test: none (client-side packaging + documented file schema; the uploadId↔key binding is exercised by the S-139 / S-139a crypto + e2e tests)
---

## Context

The air-gapped export jar (S-139) binds the handshake `uploadId` as AEAD associated data — the server's `TinkMigrationBundleCipher` passes `uuidBytes(uploadId)` to both the RSA wrap and the StreamingAead body. S-140 currently surfaces only the public-key PEM for download, so the jar would never receive the uploadId and every bundle would fail server-side decrypt (`BUNDLE_DECRYPT_AEAD_TAG_FAILED`). This story packages the `uploadId` and `publicKeyPem` into a single artifact the user downloads and feeds to the jar — one file the two values can't be separated from or mismatched against.

Surfaced by the S-139 refinement (2026-05-31). S-140 is already implemented; this is the minimal client-side follow-up to close the contract.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Define + document the combined-artifact file schema (the `--handshake-file` contract S-139 reads).
- [ ] Update the `/migrate/start` SPA download + copy controls to emit the combined artifact instead of a bare PEM.
- [ ] Update the page's export-tool guidance copy to reference the new file.

## Notes
- Keep the wire/API unchanged — `HandshakeResponse` already carries `uploadId` + `publicKeyPem`; this is packaging only.
- The file is non-secret (public key + an opaque uploadId), so no encryption-at-rest concerns for the download itself.
- Assumption: chose a combined download file over a separate `--upload-id` CLI flag (the S-139 grill option) so the two values cannot be mismatched by the user; revisit only if a flag proves more ergonomic in field use.

<!-- modernize-refine: start -->

## Design notes

- **Artifact = JSON, not PEM-with-header.** Filename `alpenflight-handshake-<uploadId>.json`, mime `application/json`:
  ```json
  { "format": "alpenflight-migration-handshake", "schemaVersion": 1,
    "uploadId": "<uuid>", "publicKeyPem": "<PEM>", "expiresAt": "<iso-8601>" }
  ```
  JSON because the export jar already parses JSON (the bundle manifest) — a PEM-comment header needs a bespoke, normalization-fragile parser. `format` + `schemaVersion` let the jar reject a stray file and evolve the schema. `expiresAt` is non-secret and lets the jar warn before a long export the server would reject. The whole file is non-secret (RSA *public* key + opaque uploadId).
- **download + copy share one pure builder** (`handshake-artifact.ts`: `HandshakeResponse → {filename, mime, body}`) so neither path can emit a bare key. download blobs the JSON; **copy emits the same JSON** (not the bare PEM — that would reinstate the mismatch hazard). The textarea keeps showing `publicKeyPem` (display-only, human-readable).
- **No server change** — `HandshakeResponse` already carries all three fields; store/service untouched. Thin client edit.
- **Cross-story contract (the reason this story exists):** this file IS S-139's `--handshake-file` input — S-139 reads `uploadId` (→ Tink AEAD associated data) + `publicKeyPem` (→ RSA-OAEP wrap) from it. Keep one canonical sample artifact both sides reference.
- ADR 0022: no schema-level business logic (client packaging).

## Edge cases & hidden requirements

- **Copy never emits a bare PEM** — uploadId↔key stay inseparable; copy + download both yield the combined artifact.
- **Stale after regenerate/supersede:** the SPA always reflects the current `awaiting_upload` row, so a *fresh* download carries the new uploadId; a *previously-downloaded* file goes stale. `expiresAt` covers TTL expiry; supersession of a still-unexpired key is only detectable server-side (AEAD tag failure at ingest). The post-regenerate confirmation copy MUST instruct the user to re-download (the old file is dead). Live client-side staleness detection is out of scope (residual gap).
- **Bare-PEM rejection:** the jar accepts ONLY the new format; a bare PEM / old `.pem` → a clear structured error wired into S-139's taxonomy (e.g. `HANDSHAKE_FILE_INVALID`). No transition window — no shipped jar ever read a handshake file.
- **i18n:** new/changed strings (download + copy labels, jar-panel guidance pointing at the `.json`, the regenerate "re-download" copy) via the i18n mechanism, not hard-coded.
- **e2e:** keep the `migrate-handshake-download` testid; update the handshake spec's PEM-value + filename assertions to the combined artifact.

## Security plan

(N/A — the combined artifact is non-secret: an RSA *public* key + an opaque per-upload uploadId, both already disclosed to the authenticated user. No private key, no PII, no new exposure. The one invariant — uploadId and key stay together — is a correctness concern, handled in Design notes, not a confidentiality one.)

## Test plan

- **Extract the builder first** — pull packaging out of the component into a pure `handshake-artifact.ts` so the contract is unit-testable without a DOM render.
- **Unit (the whole logic surface):** body parses to both `uploadId` + `publicKeyPem` (+ `expiresAt` + `format`/`schemaVersion` — assert the literal field names + version, since this IS the S-139 contract); filename `alpenflight-handshake-<uploadId>.json`, mime `application/json`; copy + download derive from the same builder; the PEM round-trips byte-for-byte (armor/newlines preserved — the RSA wrap depends on it).
- **e2e (extend `handshake.spec.ts`, don't fork it):** capture the download (`waitForEvent('download')`, `acceptDownloads`), assert filename + JSON content carries both fields; stub `navigator.clipboard.writeText`, assert copy = the combined artifact; after regenerate, assert the fresh download carries the new uploadId.
- **Cross-story boundary (not here):** the jar consuming the artifact (RSA-OAEP wrap + AEAD AAD) is S-139 / S-139a. This story proves the file is *produced* in the agreed schema; keep one canonical sample fixture both sides share.

## Performance plan

(N/A — trivial client-side packaging; no measurable workload.)

<!-- modernize-refine: end -->
