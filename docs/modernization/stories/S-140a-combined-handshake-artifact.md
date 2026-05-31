---
id: S-140a
title: Combined handshake artifact — SPA download carries uploadId + public key for the export jar
epic: E-15
status: todo
depends_on: [S-140]
integration_base: integration/migration
origin: refinement-followup
origin_story: S-139
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
