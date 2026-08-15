import { clearInbox } from "./mailpit";

const API_BASE = process.env.FLS_API ?? "http://localhost:25567";
const MAILPIT_BASE = process.env.MAILPIT_BASE ?? "http://localhost:8025";

const MIN_COUNTRIES_PROVING_SEED_REPLAY_FINISHED = 100;

const TESTCLUB_ADMIN_USERNAME = process.env.FLS_USERNAME ?? "testclubadmin";
const TESTCLUB_ADMIN_PASSWORD = process.env.FLS_PASSWORD ?? "s";

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
      ok: count >= MIN_COUNTRIES_PROVING_SEED_REPLAY_FINISHED,
      detail: `countries=${count} (floor ${MIN_COUNTRIES_PROVING_SEED_REPLAY_FINISHED})`,
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
      username: TESTCLUB_ADMIN_USERNAME,
      password: TESTCLUB_ADMIN_PASSWORD,
    }),
  });
  if (!tokenRes.ok) throw new Error(`token HTTP ${tokenRes.status}`);
  const { access_token: accessToken } = (await tokenRes.json()) as {
    access_token?: string;
  };
  if (!accessToken) throw new Error("token missing access_token");
  return accessToken;
}

const PAGED_WARMUPS = [
  {
    label: "glider flights",
    path: "flights/gliderflights/page/0/100",
    minRows: 1,
  },
  { label: "motor flights", path: "flights/motorflights/page/0/100" },
  { label: "aircraft reservations", path: "aircraftreservations/page/0/100" },
  {
    label: "flight reports",
    path: "flightreports/page/0/100",
    nestedUnder: "Flights",
  },
  { label: "planning days", path: "planningdays/page/0/100" },
] as const;

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
