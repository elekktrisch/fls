import type { HandshakeResponse } from './migrate-handshake.service';

/**
 * Marks the combined handshake artifact the export jar (S-139) parses via
 * {@code --handshake-file}. Lets the jar reject a stray file and lets a
 * future schema bump stay back-compatible.
 */
export const HANDSHAKE_ARTIFACT_FORMAT = 'alpenflight-migration-handshake';
export const HANDSHAKE_ARTIFACT_SCHEMA_VERSION = 1;

/**
 * The downloaded / copied artifact. Carries the uploadId alongside the
 * public key because the server binds the uploadId as AEAD associated data
 * — a bare PEM would fail the export's decrypt at ingest.
 */
export interface HandshakeArtifact {
  readonly format: typeof HANDSHAKE_ARTIFACT_FORMAT;
  readonly schemaVersion: typeof HANDSHAKE_ARTIFACT_SCHEMA_VERSION;
  readonly uploadId: string;
  readonly publicKeyPem: string;
  readonly expiresAt: string;
}

export interface HandshakeDownload {
  readonly filename: string;
  readonly mimeType: string;
  readonly body: string;
}

export function buildHandshakeArtifact(handshake: HandshakeResponse): HandshakeArtifact {
  return {
    format: HANDSHAKE_ARTIFACT_FORMAT,
    schemaVersion: HANDSHAKE_ARTIFACT_SCHEMA_VERSION,
    uploadId: handshake.uploadId,
    publicKeyPem: handshake.publicKeyPem,
    expiresAt: handshake.expiresAt,
  };
}

/**
 * The single source for both the download blob and the clipboard text, so
 * neither path can emit a bare key without its matching uploadId.
 */
export function handshakeArtifactDownload(handshake: HandshakeResponse): HandshakeDownload {
  const artifact = buildHandshakeArtifact(handshake);
  return {
    filename: `alpenflight-handshake-${handshake.uploadId}.json`,
    mimeType: 'application/json',
    body: `${JSON.stringify(artifact, null, 2)}\n`,
  };
}
