import { describe, expect, it } from 'vitest';

import { FlightCreateRequestFlightAircraftType } from '@api/generated/model';

import { flightNeedsTow, isMotorAircraft, primaryAircraftCreateType } from './motor-aircraft';

const GLIDER = { hasEngine: false, isTowingAircraft: false };
const MOTOR = { hasEngine: true, isTowingAircraft: false };
const TOW = { hasEngine: true, isTowingAircraft: true };

const AEROTOW_START_TYPE = '019e2e15-2c00-7fa1-8000-000000000fa1';
const WINCH_START_TYPE = '019e2e15-2c00-7fa0-8000-000000000fa0';

describe('motor-aircraft', () => {
  describe('isMotorAircraft', () => {
    it('is true for a non-towing aircraft with an engine', () => {
      expect(isMotorAircraft(MOTOR)).toBe(true);
    });

    it('is false for a pure glider (no engine)', () => {
      expect(isMotorAircraft(GLIDER)).toBe(false);
    });

    it('is false for a tow plane (fills the tow slot, not the primary)', () => {
      expect(isMotorAircraft(TOW)).toBe(false);
    });

    it('is false when no aircraft is selected', () => {
      expect(isMotorAircraft(null)).toBe(false);
      expect(isMotorAircraft(undefined)).toBe(false);
    });
  });

  describe('primaryAircraftCreateType', () => {
    it('infers MOTOR from a selected motor aircraft', () => {
      expect(primaryAircraftCreateType(MOTOR)).toBe(FlightCreateRequestFlightAircraftType.MOTOR);
    });

    it('defaults to GLIDER for a glider / no selection', () => {
      expect(primaryAircraftCreateType(GLIDER)).toBe(FlightCreateRequestFlightAircraftType.GLIDER);
      expect(primaryAircraftCreateType(null)).toBe(FlightCreateRequestFlightAircraftType.GLIDER);
    });
  });

  describe('flightNeedsTow', () => {
    it('never needs the tow step for a motor aircraft (regardless of start type)', () => {
      expect(flightNeedsTow(MOTOR, AEROTOW_START_TYPE)).toBe(false);
    });

    it('defers to the start type for a glider (aerotow needs tow, winch does not)', () => {
      expect(flightNeedsTow(GLIDER, AEROTOW_START_TYPE)).toBe(true);
      expect(flightNeedsTow(GLIDER, WINCH_START_TYPE)).toBe(false);
    });
  });
});
