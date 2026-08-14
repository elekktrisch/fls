import type {
  FlightDetail,
  FlightLastContextResponse,
  FlightTemplateResponse,
} from '@api/generated/model';

import {
  flightDetailToFormSnapshot,
  templateToFormSnapshot,
  type CrewSnapshot,
  type FlightFormSnapshot,
} from './flight-form.model';
import type { FlightPrefs } from './flight-prefs.service';

export function buildDefaultsForNew(
  template: FlightTemplateResponse,
  lastContext: FlightLastContextResponse | null,
  prefs: FlightPrefs,
): FlightFormSnapshot {
  const base = templateToFormSnapshot(template);
  return applyLastContextThenPrefs(base, lastContext, prefs);
}

export function buildDefaultsForCopy(
  template: FlightTemplateResponse,
  prefs: FlightPrefs,
): FlightFormSnapshot {
  const base = templateToFormSnapshot(template);
  return applyPrefsOverlay(base, prefs);
}

export function buildDefaultsForEdit(
  glider: FlightDetail,
  tow: FlightDetail | undefined,
): FlightFormSnapshot {
  return flightDetailToFormSnapshot(glider, tow);
}

type CrewStringKey = {
  [K in keyof CrewSnapshot]: CrewSnapshot[K] extends string | null ? K : never;
}[keyof CrewSnapshot];

function coalesceCrew<S extends Record<string, unknown>>(
  base: CrewSnapshot,
  source: S,
  pairs: readonly (readonly [CrewStringKey, keyof S])[],
): CrewSnapshot {
  const merged: CrewSnapshot = { ...base };
  for (const [snapKey, sourceKey] of pairs) {
    merged[snapKey] = base[snapKey] ?? (source[sourceKey] as string) ?? null;
  }
  return merged;
}

const GLIDER_FIELDS_BORROWED_FROM_LAST_CONTEXT = [
  ['flightTypeId', 'flightTypeId'],
  ['pilotPersonId', 'pilotPersonId'],
  ['invoiceRecipientPersonId', 'invoiceRecipientPersonId'],
  ['startLocationId', 'startLocationId'],
  ['ldgLocationId', 'ldgLocationId'],
  ['outboundRoute', 'outboundRoute'],
  ['inboundRoute', 'inboundRoute'],
  ['flightCostBalanceTypeId', 'flightCostBalanceTypeId'],
] as const satisfies readonly (readonly [CrewStringKey, keyof FlightLastContextResponse])[];

const TOW_FIELDS_BORROWED_FROM_LAST_CONTEXT = [
  ['aircraftId', 'aircraftId'],
  ['flightTypeId', 'flightTypeId'],
  ['pilotPersonId', 'pilotPersonId'],
  ['ldgLocationId', 'ldgLocationId'],
] as const satisfies readonly (readonly [
  CrewStringKey,
  keyof NonNullable<FlightLastContextResponse['tow']>,
])[];

function applyLastContextThenPrefs(
  base: FlightFormSnapshot,
  ctx: FlightLastContextResponse | null,
  prefs: FlightPrefs,
): FlightFormSnapshot {
  if (!ctx) return applyPrefsOverlay(base, prefs);
  const merged: FlightFormSnapshot = {
    ...base,
    startTypeId: base.startTypeId ?? ctx.startTypeId ?? null,
    glider: coalesceCrew(base.glider, ctx, GLIDER_FIELDS_BORROWED_FROM_LAST_CONTEXT),
    tow: ctx.tow
      ? coalesceCrew(base.tow, ctx.tow, TOW_FIELDS_BORROWED_FROM_LAST_CONTEXT)
      : base.tow,
  };
  return applyPrefsOverlay(merged, prefs);
}

function applyPrefsOverlay(base: FlightFormSnapshot, prefs: FlightPrefs): FlightFormSnapshot {
  const startLoc = prefs.lastStartLocation;
  if (!startLoc) return base;
  return {
    ...base,
    glider: {
      ...base.glider,
      startLocationId: base.glider.startLocationId ?? startLoc,
      ldgLocationId: base.glider.ldgLocationId ?? startLoc,
    },
    tow: {
      ...base.tow,
      startLocationId: base.tow.startLocationId ?? startLoc,
      ldgLocationId: base.tow.ldgLocationId ?? startLoc,
    },
  };
}
