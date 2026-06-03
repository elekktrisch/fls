import {
  FlightCreateRequestFlightAircraftType,
  FlightListItemFlightAircraftType,
} from '@api/generated/model';
import type {
  FlightCreateRequestFlightAircraftType as CreateAcType,
  FlightListItemFlightAircraftType as ListAcType,
} from '@api/generated/model';

import { needsTowplane } from './edit/flight-form.model';

/**
 * The flight screen is one parameterized list + wizard served at two routes
 * (S-064): `/flights` (the default glider/tow/all-types logbook) and
 * `/airmovements` (motor "air movements"). Legacy `flsweb/` duplicated the
 * `flights/` and `flights/airmovements/` AngularJS modules; the rewrite must
 * NOT — the only difference is the MOTOR aircraft-type filter on the list and
 * the suppressed tow step on the wizard. The route carries `data.flightVariant`
 * and the shared components read this config to specialise themselves.
 */
export interface FlightVariant {
  /** Discriminator carried on the route `data` (`'motor'` for /airmovements). */
  readonly id: 'default' | 'motor';
  /** Route prefix the list + wizard navigate within (`/flights` | `/airmovements`). */
  readonly basePath: string;
  /** `<af-page-header>` title → the `<h1>` text. */
  readonly title: string;
  /** Primary "new" button label. */
  readonly newLabel: string;
  /**
   * Aircraft-type the list is pinned to (motor → MOTOR). `null` = no pin; the
   * user drives the aircraft-type dropdown (default `/flights`).
   */
  readonly aircraftTypeFilter: ListAcType | null;
  /** Create-request discriminator forced for this variant (motor → MOTOR). */
  readonly createAircraftType: CreateAcType | null;
  /**
   * `true` when the variant can never carry a tow (motor): the wizard hides the
   * tow step regardless of start-type. `false` defers to the start-type
   * (`needsTowplane` → aerotow). Motor air movements have no tow / no winch
   * operator (legacy `air-movements.html`).
   */
  readonly suppressTow: boolean;
}

export interface FlightVariantData {
  flightVariant?: 'motor';
}

const DEFAULT_VARIANT: FlightVariant = {
  id: 'default',
  basePath: '/flights',
  title: 'Flights',
  newLabel: 'New flight',
  aircraftTypeFilter: null,
  createAircraftType: null,
  suppressTow: false,
};

const MOTOR_VARIANT: FlightVariant = {
  id: 'motor',
  basePath: '/airmovements',
  title: 'Air movements',
  newLabel: 'New air movement',
  aircraftTypeFilter: FlightListItemFlightAircraftType.MOTOR,
  createAircraftType: FlightCreateRequestFlightAircraftType.MOTOR,
  suppressTow: true,
};

export const FLIGHT_VARIANTS = {
  default: DEFAULT_VARIANT,
  motor: MOTOR_VARIANT,
} as const;

/** Resolve the variant from a route's `data` (defaulting to the standard logbook). */
export function resolveFlightVariant(data: FlightVariantData | undefined): FlightVariant {
  return data?.flightVariant === 'motor' ? FLIGHT_VARIANTS.motor : FLIGHT_VARIANTS.default;
}

/** Create-request discriminator forced by the variant (motor → MOTOR, default → null). */
export function variantCreateAircraftType(variant: FlightVariant): CreateAcType | null {
  return variant.createAircraftType;
}

/**
 * Whether the wizard renders the tow step for this variant + start-type. Motor
 * never tows (`suppressTow`); the default variant defers to `needsTowplane`.
 */
export function variantNeedsTow(
  variant: FlightVariant,
  startTypeId: string | null | undefined,
): boolean {
  if (variant.suppressTow) {
    return false;
  }
  return needsTowplane(startTypeId);
}
