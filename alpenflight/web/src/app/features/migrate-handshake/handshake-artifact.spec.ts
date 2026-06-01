import { describe, expect, it } from 'vitest';

import {
  HANDSHAKE_ARTIFACT_FORMAT,
  HANDSHAKE_ARTIFACT_SCHEMA_VERSION,
  buildHandshakeArtifact,
  handshakeArtifactDownload,
} from './handshake-artifact';
import type { HandshakeResponse } from './migrate-handshake.service';

const HANDSHAKE: HandshakeResponse = {
  uploadId: '019e30c3-2c00-7001-8000-000000000001',
  publicKeyPem:
    '-----BEGIN PUBLIC KEY-----\nMIICIjANBgkqhkiG9w0BAQEFAAOCAg8A\nABCD==\n-----END PUBLIC KEY-----\n',
  expiresAt: '2026-05-30T10:00:00Z',
};

describe('buildHandshakeArtifact', () => {
  it('carries both the uploadId and the public key plus the self-describing markers', () => {
    const artifact = buildHandshakeArtifact(HANDSHAKE);

    expect(artifact).toEqual({
      format: HANDSHAKE_ARTIFACT_FORMAT,
      schemaVersion: HANDSHAKE_ARTIFACT_SCHEMA_VERSION,
      uploadId: HANDSHAKE.uploadId,
      publicKeyPem: HANDSHAKE.publicKeyPem,
      expiresAt: HANDSHAKE.expiresAt,
    });
  });

  it('pins the schema marker the export jar matches on', () => {
    expect(HANDSHAKE_ARTIFACT_FORMAT).toBe('alpenflight-migration-handshake');
    expect(HANDSHAKE_ARTIFACT_SCHEMA_VERSION).toBe(1);
  });
});

describe('handshakeArtifactDownload', () => {
  it('names the file after the uploadId with a .json extension', () => {
    const { filename, mimeType } = handshakeArtifactDownload(HANDSHAKE);

    expect(filename).toBe(`alpenflight-handshake-${HANDSHAKE.uploadId}.json`);
    expect(mimeType).toBe('application/json');
  });

  it('serializes a body that round-trips the public key byte-for-byte', () => {
    const { body } = handshakeArtifactDownload(HANDSHAKE);
    const parsed = JSON.parse(body) as Record<string, unknown>;

    // The export jar's RSA-OAEP wrap depends on the exact PEM armor + newlines.
    expect(parsed['publicKeyPem']).toBe(HANDSHAKE.publicKeyPem);
    expect(parsed['uploadId']).toBe(HANDSHAKE.uploadId);
    expect(parsed['format']).toBe(HANDSHAKE_ARTIFACT_FORMAT);
    expect(parsed['schemaVersion']).toBe(HANDSHAKE_ARTIFACT_SCHEMA_VERSION);
    expect(parsed['expiresAt']).toBe(HANDSHAKE.expiresAt);
  });

  it('is the single source for download and clipboard so neither can emit a bare key', () => {
    const { body } = handshakeArtifactDownload(HANDSHAKE);

    // A bare PEM would lack the uploadId the server binds as AEAD associated
    // data; the body must always be the combined JSON artifact.
    expect(body).not.toBe(HANDSHAKE.publicKeyPem);
    expect(JSON.parse(body)).toEqual(buildHandshakeArtifact(HANDSHAKE));
  });
});
