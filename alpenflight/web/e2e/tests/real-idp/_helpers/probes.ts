import { mailpitInfo } from './mailpit-client';
import { findUserByEmail, _testing } from './keycloak-admin';

/**
 * Four HTTP probes that gate the real-idp suite. Each surface — Keycloak
 * realm discovery, Mailpit REST, backend `/actuator/health`, KC Admin
 * REST + seed user — must be reachable before any spec runs.
 *
 * Bail-out diagnostic names which probe failed so an operator can fix
 * the right thing without paging through the full stack.
 */

const BACKEND_HEALTH =
  process.env['E2E_BACKEND_HEALTH'] ?? 'http://localhost:8080/actuator/health';

export interface ProbeResult {
  ok: boolean;
  detail?: string;
}

export async function probeKeycloakDiscovery(): Promise<ProbeResult> {
  try {
    const res = await fetch(`${_testing.ISSUER}/.well-known/openid-configuration`);
    if (!res.ok) return { ok: false, detail: `HTTP ${res.status}` };
    const doc = (await res.json()) as { issuer?: string };
    if (doc.issuer !== _testing.ISSUER) {
      return { ok: false, detail: `iss mismatch: doc='${doc.issuer}', expected='${_testing.ISSUER}'` };
    }
    return { ok: true };
  } catch (err) {
    return { ok: false, detail: (err as Error).message };
  }
}

export async function probeMailpit(): Promise<ProbeResult> {
  try {
    await mailpitInfo();
    return { ok: true };
  } catch (err) {
    return { ok: false, detail: (err as Error).message };
  }
}

export async function probeBackendHealth(): Promise<ProbeResult> {
  try {
    const res = await fetch(BACKEND_HEALTH);
    if (!res.ok) return { ok: false, detail: `HTTP ${res.status}` };
    return { ok: true };
  } catch (err) {
    return { ok: false, detail: (err as Error).message };
  }
}

export async function probeSeedUser(): Promise<ProbeResult> {
  try {
    const user = await findUserByEmail('pilot1@example.com');
    if (!user) return { ok: false, detail: 'seed user pilot1@example.com not found in realm' };
    return { ok: true };
  } catch (err) {
    return { ok: false, detail: (err as Error).message };
  }
}

export async function runProbes(): Promise<{ ok: boolean; failures: string[] }> {
  const probes: Array<[string, () => Promise<ProbeResult>]> = [
    ['keycloak discovery', probeKeycloakDiscovery],
    ['mailpit /api/v1/info', probeMailpit],
    ['backend /actuator/health', probeBackendHealth],
    ['admin API + seed pilot1', probeSeedUser],
  ];
  const failures: string[] = [];
  for (const [name, run] of probes) {
    const result = await run();
    if (!result.ok) failures.push(`  ✗ ${name}: ${result.detail}`);
  }
  return { ok: failures.length === 0, failures };
}
