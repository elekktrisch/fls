import type { HandshakeResponse } from './migrate-handshake.service';

export const HANDSHAKE_ARTIFACT_FORMAT = 'alpenflight-migration-handshake';
export const HANDSHAKE_ARTIFACT_SCHEMA_VERSION = 1;

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

export function handshakeArtifactDownload(handshake: HandshakeResponse): HandshakeDownload {
  const artifact = buildHandshakeArtifact(handshake);
  return {
    filename: `alpenflight-handshake-${handshake.uploadId}.json`,
    mimeType: 'application/json',
    body: `${JSON.stringify(artifact, null, 2)}\n`,
  };
}
