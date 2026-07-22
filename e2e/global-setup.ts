import { clearInbox } from "./mailpit";

const API_BASE = process.env.FLS_API ?? "http://localhost:25567";
const MAILPIT_BASE = process.env.MAILPIT_BASE ?? "http://localhost:8025";

// The webServer health poll on `/api/v1/countries` only checks for HTTP 200,
// which the EF connection pool answers before the FLSTest seed replay has
// hydrated the reference tables. Registration/reporting/email specs then race a
// half-seeded backend. This gate holds the whole suite until the seed is
// genuinely populated and mailpit is reachable, so a not-ready backend fails
// loudly here instead of as scattered flaky reds.

// The static-data seed inserts ~200 Country rows; a populated response well
// above an empty pool proves the seed replay finished, not merely that EF is up.
const MIN_COUNTRIES = 100;

// The seeded TestClub admin; the warm-up requests scope to this club's data.
const FLS_USERNAME = process.env.FLS_USERNAME ?? "testclubadmin";
const FLS_PASSWORD = process.env.FLS_PASSWORD ?? "s";

const READINESS_TIMEOUT_MS = 180_000;
const POLL_INTERVAL_MS = 2_000;

async function pollUntil(
  label: string,
  probe: () => Promise<{ ok: boolean; detail: string }>,
): Promise<void> {
  const deadline = Date.now() + READINESS_TIMEOUT_MS;
  let lastDetail = "(never ran)";
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
  await pollUntil("backend seed (/api/v1/countries populated)", async () => {
    const res = await fetch(`${API_BASE}/api/v1/countries`);
    if (!res.ok) return { ok: false, detail: `HTTP ${res.status}` };
    const body = (await res.json()) as unknown[];
    const count = Array.isArray(body) ? body.length : -1;
    return {
      ok: count >= MIN_COUNTRIES,
      detail: `countries=${count} (floor ${MIN_COUNTRIES})`,
    };
  });
}

async function waitForMailpit(): Promise<void> {
  await pollUntil("mailpit (/api/v1/info)", async () => {
    const res = await fetch(`${MAILPIT_BASE}/api/v1/info`);
    return { ok: res.ok, detail: `HTTP ${res.status}` };
  });
}

async function bearerToken(): Promise<string> {
  const tokenRes = await fetch(`${API_BASE}/Token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "password",
      username: FLS_USERNAME,
      password: FLS_PASSWORD,
    }),
  });
  if (!tokenRes.ok) throw new Error(`token HTTP ${tokenRes.status}`);
  const { access_token: accessToken } = (await tokenRes.json()) as {
    access_token?: string;
  };
  if (!accessToken) throw new Error("token missing access_token");
  return accessToken;
}

// Each distinct paged read path is a separate EF6 LINQ tree that legacy compiles
// lazily on its FIRST execution against a fresh `CreateDbContext()`. The list
// specs render via `ng-table`'s `getData`, whose promise can resolve before that
// cold compile+connection-warm+security-warm returns, so the first render is
// empty and the spec asserts on nothing. Warming the exact query shape the suite
// reads through — once, up front, authenticated as the club that owns the data —
// makes the first spec-driven call warm. The paged routes take
// `page/{pageStart}/{pageSize}` and a `{ SearchFilter }` body (empty filter =
// unfiltered, which also proves data-readiness for the flight surface).
const PAGED_WARMUPS = [
  {
    label: "glider flights",
    path: "flights/gliderflights/page/0/100",
    minRows: 1,
  },
  { label: "motor flights", path: "flights/motorflights/page/0/100" },
  { label: "aircraft reservations", path: "aircraftreservations/page/0/100" },
  // flightreports returns FlightReportResult, which nests its PagedList under
  // .Flights (the other paths return a top-level PagedList).
  {
    label: "flight reports",
    path: "flightreports/page/0/100",
    nestedUnder: "Flights",
  },
  { label: "planning days", path: "planningdays/page/0/100" },
] as const;

// A `minRows` warm-up polls until the seed is genuinely queryable through this
// exact shape; the rest need only one 200 to compile the tree — but any warm-up
// erroring (4xx/5xx / non-PagedList body) must fail the run loudly, never resolve
// empty and let the spec race a cold query again.
async function warmPagedReadSurface(): Promise<void> {
  const accessToken = await bearerToken();
  for (const warmup of PAGED_WARMUPS) {
    const label = `warm ${warmup.label} (${warmup.path})`;
    await pollUntil(label, async () => {
      const res = await fetch(`${API_BASE}/api/v1/${warmup.path}`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ SearchFilter: {} }),
      });
      if (!res.ok) return { ok: false, detail: `HTTP ${res.status}` };
      const raw = (await res.json()) as Record<string, unknown>;
      const paged = ("nestedUnder" in warmup ? raw[warmup.nestedUnder] : raw) as
        | { Items?: unknown[]; TotalRows?: number }
        | undefined;
      if (!paged || !Array.isArray(paged.Items))
        return { ok: false, detail: "body missing Items[]" };
      const rows =
        typeof paged.TotalRows === "number" ? paged.TotalRows : paged.Items.length;
      const minRows = "minRows" in warmup ? warmup.minRows : 0;
      return { ok: rows >= minRows, detail: `rows=${rows} (floor ${minRows})` };
    });
  }
}

export default async function globalSetup(): Promise<void> {
  await waitForSeededBackend();
  await waitForMailpit();
  await warmPagedReadSurface();
  await clearInbox();
}
