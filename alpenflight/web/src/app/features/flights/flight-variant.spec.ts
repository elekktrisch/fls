import { describe, expect, it } from 'vitest';

import {
  FlightCreateRequestFlightAircraftType,
  FlightListItemFlightAircraftType,
} from '@api/generated/model';

import {
  FLIGHT_VARIANTS,
  resolveFlightVariant,
  variantCreateAircraftType,
  variantNeedsTow,
} from './flight-variant';

describe('flight-variant', () => {
  describe('resolveFlightVariant', () => {
    it('defaults to the standard flights variant when no route data is present', () => {
      expect(resolveFlightVariant(undefined)).toBe(FLIGHT_VARIANTS.default);
      expect(resolveFlightVariant({})).toBe(FLIGHT_VARIANTS.default);
    });

    it('resolves the motor variant from { flightVariant: "motor" }', () => {
      expect(resolveFlightVariant({ flightVariant: 'motor' })).toBe(FLIGHT_VARIANTS.motor);
    });
  });

  describe('default variant', () => {
    const v = FLIGHT_VARIANTS.default;

    it('routes under /flights and titles "Flights"', () => {
      expect(v.basePath).toBe('/flights');
      expect(v.title).toBe('Flights');
    });

    it('pins no aircraft-type filter (the dropdown is user-driven)', () => {
      expect(v.aircraftTypeFilter).toBeNull();
    });
  });

  describe('motor variant', () => {
    const v = FLIGHT_VARIANTS.motor;

    it('routes under /airmovements and titles "Air movements"', () => {
      expect(v.basePath).toBe('/airmovements');
      expect(v.title).toBe('Air movements');
    });

    it('pre-filters the list to MOTOR flights', () => {
      expect(v.aircraftTypeFilter).toBe(FlightListItemFlightAircraftType.MOTOR);
    });
  });

  describe('variantCreateAircraftType', () => {
    it('forces MOTOR on the create discriminator for the motor variant', () => {
      expect(variantCreateAircraftType(FLIGHT_VARIANTS.motor)).toBe(
        FlightCreateRequestFlightAircraftType.MOTOR,
      );
    });

    it('leaves the glider/tow create discriminator unset for the default variant', () => {
      expect(variantCreateAircraftType(FLIGHT_VARIANTS.default)).toBeNull();
    });
  });

  describe('variantNeedsTow', () => {
    it('never needs the tow step for the motor variant (no aerotow on motor)', () => {
      expect(variantNeedsTow(FLIGHT_VARIANTS.motor, 'any-start-type-id')).toBe(false);
    });

    it('defers to the start-type for the default variant (aerotow needs tow)', () => {
      expect(variantNeedsTow(FLIGHT_VARIANTS.default, '019e2e15-2c00-7fa1-8000-000000000fa1')).toBe(
        true,
      );
      expect(variantNeedsTow(FLIGHT_VARIANTS.default, '019e2e15-2c00-7fa0-8000-000000000fa0')).toBe(
        false,
      );
    });
  });
});
