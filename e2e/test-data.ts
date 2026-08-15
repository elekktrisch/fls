import type { APIRequestContext, Page } from "@playwright/test";
import sql from "mssql";

export const API_BASE = process.env.FLS_API ?? "http://localhost:25567";

export const MSSQL: sql.config = {
  user: "sa",
  password: "Demo#FLS#2026",
  server: "localhost",
  port: 1433,
  database: "FLSTest",
  options: { trustServerCertificate: true, encrypt: false },
  pool: { max: 2, min: 0, idleTimeoutMillis: 5000 },
};

export async function withPool<T>(
  fn: (pool: sql.ConnectionPool) => Promise<T>,
): Promise<T> {
  const pool = await new sql.ConnectionPool(MSSQL).connect();
  try {
    return await fn(pool);
  } finally {
    await pool.close();
  }
}

export async function getBearerToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem("ngStorage-loginResult");
    if (!raw) return null;
    try {
      return JSON.parse(raw).access_token as string;
    } catch {
      return null;
    }
  });
  if (!token)
    throw new Error(
      "no access_token in sessionStorage (loggedInPage not yet navigated?)",
    );
  return token;
}

export function authHeaders(token: string): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
  };
}


export async function findFlightByComment(
  request: APIRequestContext,
  token: string,
  comment: string,
): Promise<{ FlightId: string } | null> {
  return await withPool(async (pool) => {
    const r = await pool
      .request()
      .input("comment", sql.NVarChar, comment)
      .query("SELECT TOP 1 FlightId FROM Flights WHERE Comment = @comment");
    if (!r.recordset.length) return null;
    return { FlightId: r.recordset[0].FlightId as string };
  });
}

export type EnsureGliderFlightOpts = {
  comment: string;
  flightDate?: Date;
  processStateId?: number;
  createdOnDaysAgo?: number;
};

export async function ensureGliderFlight(
  request: APIRequestContext,
  token: string,
  opts: EnsureGliderFlightOpts,
): Promise<{
  flightId: string;
  aircraftId: string;
  pilotPersonId: string;
  flightTypeId: string;
  startLocationId: string;
}> {
  const headers = authHeaders(token);
  const [gliders, pilots, ftypes, locations, myClub] = await Promise.all([
    request.get(`${API_BASE}/api/v1/aircrafts/listitems/gliders`, { headers }),
    request.get(`${API_BASE}/api/v1/persons/gliderpilots/listitems/true`, {
      headers,
    }),
    request.get(`${API_BASE}/api/v1/flighttypes/gliders`, { headers }),
    request.get(`${API_BASE}/api/v1/locations`, { headers }),
    request.get(`${API_BASE}/api/v1/clubs/my`, { headers }),
  ]);
  for (const [name, r] of [
    ["gliders", gliders],
    ["pilots", pilots],
    ["ftypes", ftypes],
    ["locations", locations],
    ["myClub", myClub],
  ] as const) {
    if (!r.ok()) throw new Error(`${name}: ${r.status()} ${await r.text()}`);
  }
  const gliderList = (await gliders.json()) as Array<{
    AircraftId: string;
    Immatriculation: string;
    NrOfSeats: number;
    HasEngine?: boolean;
  }>;
  const pilotList = (await pilots.json()) as Array<{ PersonId: string }>;
  const ftypeList = (await ftypes.json()) as Array<{
    FlightTypeId: string;
    IsPassengerFlight?: boolean;
    InstructorRequired?: boolean;
  }>;
  const locList = (await locations.json()) as Array<{
    LocationId: string;
    IcaoCode?: string;
  }>;
  const club = (await myClub.json()) as { HomebaseId?: string | null };
  if (!gliderList.length) throw new Error("no seeded glider aircraft");
  if (!pilotList.length) throw new Error("no seeded glider pilot");
  if (!ftypeList.length) throw new Error("no seeded glider flight type");
  if (!locList.length) throw new Error("no seeded location");

  const aircraft =
    gliderList.find((a) => a.Immatriculation === "HB-3407") ??
    gliderList.find((a) => a.NrOfSeats >= 2 && !a.HasEngine) ??
    gliderList[0];
  const pilot = pilotList[0];
  const ftype =
    ftypeList.find((t) => !t.IsPassengerFlight && !t.InstructorRequired) ??
    ftypeList[0];
  const loc =
    locList.find((l) => l.LocationId === club.HomebaseId) ??
    locList.find((l) => l.IcaoCode === "LSZK") ??
    locList[0];

  const existing = await findFlightByComment(request, token, opts.comment);
  let flightId: string;
  if (existing) {
    flightId = existing.FlightId;
  } else {
    const flightDate = opts.flightDate ?? new Date();
    const start = new Date(flightDate.getTime());
    start.setUTCHours(10, 0, 0, 0);
    const landing = new Date(start.getTime() + 30 * 60 * 1000);
    const body = {
      FlightId: "00000000-0000-0000-0000-000000000000",
      FlightDate: flightDate.toISOString().slice(0, 10),
      StartType: 3,
      FlightAircraftType: 1,
      Comment: opts.comment,
      GliderFlightDetailsData: {
        AircraftId: aircraft.AircraftId,
        PilotPersonId: pilot.PersonId,
        FlightTypeId: ftype.FlightTypeId,
        StartLocationId: loc.LocationId,
        LdgLocationId: loc.LocationId,
        StartDateTime: start.toISOString(),
        LdgDateTime: landing.toISOString(),
        NrOfLdgs: 1,
        IsSoloFlight: true,
        FlightComment: opts.comment,
      },
    };
    const createRes = await request.post(`${API_BASE}/api/v1/flights`, {
      headers,
      data: body,
    });
    if (!createRes.ok()) {
      throw new Error(
        `POST /flights -> ${createRes.status()}: ${await createRes.text()}`,
      );
    }
    const created = (await createRes.json()) as { FlightId: string };
    flightId = created.FlightId;
  }

  if (
    opts.processStateId !== undefined ||
    opts.createdOnDaysAgo !== undefined
  ) {
    await withPool(async (pool) => {
      const r = pool.request().input("id", sql.UniqueIdentifier, flightId);
      const sets: string[] = [];
      if (opts.processStateId !== undefined) {
        r.input("state", sql.Int, opts.processStateId);
        sets.push("ProcessStateId = @state");
      }
      if (opts.createdOnDaysAgo !== undefined) {
        r.input("daysAgo", sql.Int, opts.createdOnDaysAgo);
        sets.push("CreatedOn = DATEADD(DAY, -@daysAgo, SYSDATETIME())");
      }
      sets.push(
        "ValidatedOn = COALESCE(ValidatedOn, DATEADD(DAY, -1, SYSDATETIME()))",
      );
      sets.push("ModifiedOn = SYSDATETIME()");
      await r.query(
        `UPDATE Flights SET ${sets.join(", ")} WHERE FlightId = @id`,
      );
    });
  }

  return {
    flightId,
    aircraftId: aircraft.AircraftId,
    pilotPersonId: pilot.PersonId,
    flightTypeId: ftype.FlightTypeId,
    startLocationId: loc.LocationId,
  };
}
