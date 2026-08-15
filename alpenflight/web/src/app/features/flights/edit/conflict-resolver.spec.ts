import type { FlightDetail, FlightUpdateRequest } from '@api/generated/model';
import { FlightDetailAirState, FlightDetailFlightAircraftType } from '@api/generated/model';

import { buildConflict, computeFieldDiffs } from './conflict-resolver';

function serverDetail(over: Partial<FlightDetail> = {}): FlightDetail {
  return {
    id: 'fl-019e30c3-2c00-7001-8000-000000000001',
    flightAircraftType: FlightDetailFlightAircraftType.GLIDER,
    aircraftId: 'ac-019e30c3-2c00-7001-8000-0000000000a1',
    flightDate: '2026-05-20',
    nrOfLdgs: 1,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    airState: FlightDetailAirState.LANDED,
    processStateId: '019e2e15-2c00-7100-8000-000000007002',
    version: 7,
    crew: [],
    ...over,
  };
}

describe('conflict-resolver', () => {
  it('returns only the fields whose values differ from the server', () => {
    const mine: FlightUpdateRequest = {
      aircraftId: 'ac-019e30c3-2c00-7001-8000-0000000000b2',
      nrOfLdgs: 3,
      comment: 'mine',
    };
    const theirs = serverDetail({
      aircraftId: 'ac-019e30c3-2c00-7001-8000-0000000000a1',
      nrOfLdgs: 1,
      comment: 'theirs',
    });

    const diffs = computeFieldDiffs(mine, theirs);
    const names = diffs.map((d) => d.name);

    expect(names).toEqual(expect.arrayContaining(['aircraftId', 'nrOfLdgs', 'comment']));
    expect(names).not.toContain('flightTypeId');
    const aircraft = diffs.find((d) => d.name === 'aircraftId');
    expect(aircraft).toEqual({
      name: 'aircraftId',
      mine: 'ac-019e30c3-2c00-7001-8000-0000000000b2',
      theirs: 'ac-019e30c3-2c00-7001-8000-0000000000a1',
    });
  });

  it('omits a field the user left identical to the server', () => {
    const mine: FlightUpdateRequest = {
      aircraftId: 'ac-019e30c3-2c00-7001-8000-0000000000a1',
      nrOfLdgs: 1,
      flightDate: '2026-05-20',
    };
    const theirs = serverDetail();

    const diffs = computeFieldDiffs(mine, theirs);

    expect(diffs.map((d) => d.name)).not.toContain('aircraftId');
    expect(diffs.map((d) => d.name)).not.toContain('nrOfLdgs');
    expect(diffs.map((d) => d.name)).not.toContain('flightDate');
  });

  it('buildConflict carries flightId + serverVersion + the diffs', () => {
    const mine: FlightUpdateRequest = { aircraftId: 'ac-019e30c3-2c00-7001-8000-0000000000b2' };
    const theirs = serverDetail();

    const conflict = buildConflict(theirs.id, 9, mine, theirs);

    expect(conflict.flightId).toBe(theirs.id);
    expect(conflict.serverVersion).toBe(9);
    expect(conflict.fields.some((f) => f.name === 'aircraftId')).toBe(true);
  });
});
