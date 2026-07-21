import { clearInbox } from './mailpit';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';
const MAILPIT_BASE = process.env.MAILPIT_BASE ?? 'http://localhost:8025';

// The webServer health poll on `/api/v1/countries` only checks for HTTP 200,
// which the EF connection pool answers before the FLSTest seed replay has
// hydrated the reference tables. Registration/reporting/email specs then race a
// half-seeded backend. This gate holds the whole suite until the seed is
// genuinely populated and mailpit is reachable, so a not-ready backend fails
// loudly here instead of as scattered flaky reds.

// The static-data seed inserts ~200 Country rows; a populated response well
// above an empty pool proves the seed replay finished, not merely that EF is up.
const MIN_COUNTRIES = 100;

const READINESS_TIMEOUT_MS = 180_000;
const POLL_INTERVAL_MS = 2_000;

async function pollUntil(
  label: string,
  probe: () => Promise<{ ok: boolean; detail: string }>,
): Promise<void> {
  const deadline = Date.now() + READINESS_TIMEOUT_MS;
  let lastDetail = '(never ran)';
  while (Date.now() < deadline) {
    try {
      const { ok, detail } = await probe();
      lastDetail = detail;
      if (ok) return;
    } catch (err) {
      lastDetail = err instanceof Error ? err.message : String(err);
    }
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  }
  throw new Error(
    `global-setup: ${label} not ready after ${READINESS_TIMEOUT_MS}ms — last: ${lastDetail}`,
  );
}

async function waitForSeededBackend(): Promise<void> {
  await pollUntil('backend seed (/api/v1/countries populated)', async () => {
    const res = await fetch(`${API_BASE}/api/v1/countries`);
    if (!res.ok) return { ok: false, detail: `HTTP ${res.status}` };
    const body = (await res.json()) as unknown[];
    const count = Array.isArray(body) ? body.length : -1;
    return { ok: count >= MIN_COUNTRIES, detail: `countries=${count} (floor ${MIN_COUNTRIES})` };
  });
}

async function waitForMailpit(): Promise<void> {
  await pollUntil('mailpit (/api/v1/info)', async () => {
    const res = await fetch(`${MAILPIT_BASE}/api/v1/info`);
    return { ok: res.ok, detail: `HTTP ${res.status}` };
  });
}

export default async function globalSetup(): Promise<void> {
  await waitForSeededBackend();
  await waitForMailpit();
  await clearInbox();
}
