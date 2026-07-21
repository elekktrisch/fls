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

// The seeded TestClub admin; the flights probe scopes to this club's flights.
const FLS_USERNAME = process.env.FLS_USERNAME ?? 'testclubadmin';
const FLS_PASSWORD = process.env.FLS_PASSWORD ?? 's';

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

// A populated /countries proves reference data is hydrated, but the flight rows
// land in a later seed pass — the flights list + flight reports specs default
// their date filter to TODAY, so they race that pass and see an empty list
// unless we hold the suite until a today-scoped flight is queryable.
// `gliderflights/today` computes its window with the server's DateTime.Today
// (midnight), matching the seed's DATEDIFF(dd,0,SYSDATETIME()); it is
// [Authorize]-gated + club-scoped, so we bearer it as the seeded TestClub admin
// whose club owns the seeded flights.
async function waitForSeededFlights(): Promise<void> {
  await pollUntil('seeded flights (gliderflights/today >= 1)', async () => {
    const tokenRes = await fetch(`${API_BASE}/Token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'password',
        username: FLS_USERNAME,
        password: FLS_PASSWORD,
      }),
    });
    if (!tokenRes.ok) return { ok: false, detail: `token HTTP ${tokenRes.status}` };
    const { access_token: accessToken } = (await tokenRes.json()) as {
      access_token?: string;
    };
    if (!accessToken) return { ok: false, detail: 'token missing access_token' };

    const res = await fetch(`${API_BASE}/api/v1/flights/gliderflights/today`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (!res.ok) return { ok: false, detail: `flights HTTP ${res.status}` };
    const body = (await res.json()) as unknown[];
    const count = Array.isArray(body) ? body.length : -1;
    return { ok: count >= 1, detail: `today-flights=${count} (floor 1)` };
  });
}

export default async function globalSetup(): Promise<void> {
  await waitForSeededBackend();
  await waitForMailpit();
  await waitForSeededFlights();
  await clearInbox();
}
