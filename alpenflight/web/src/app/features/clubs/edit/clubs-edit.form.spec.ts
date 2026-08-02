import {
  CLUB_UPDATE_FIELDS,
  CLUB_UPDATE_IS_FULLY_COVERED,
  buildClubCreateRequest,
  buildClubUpdateRequest,
  isOwnClub,
  type ClubEditFormValue,
} from './clubs-edit.form';

const formValue: ClubEditFormValue = {
  name: 'Seed Club',
  slug: 'seed-club-1',
  clubKey: 'SEED',
  publicRegistrationEnabled: true,
  countryId: '019e2e15-2c00-74be-8000-0000000004be',
  clubStateId: '019e2e15-2c00-7bb8-8000-000000000bb8',
  discoveryFlightOperatorEmail: 'schnupper@seed.example',
  scenicFlightOperatorEmail: 'mitflug@seed.example',
  discoveryFlightTypeId: 'ft-019e30c3-2c00-7001-8000-0000000000f1',
};

describe('buildClubUpdateRequest', () => {
  /**
   * The club PUT is full-replace, so a field the payload omits is persisted as
   * cleared. `CLUB_UPDATE_FIELDS` stops compiling when `ClubUpdateRequest`
   * grows a field it does not list; this asserts the builder actually emits
   * every field on that list.
   */
  it('emits every field of the full-replace PUT', () => {
    expect(CLUB_UPDATE_IS_FULLY_COVERED).toBe(true);
    expect(Object.keys(buildClubUpdateRequest(formValue)).sort()).toEqual(
      [...CLUB_UPDATE_FIELDS].sort(),
    );
  });

  it('carries the two operator-email recipient lists and the discovery flight type', () => {
    const req = buildClubUpdateRequest(formValue);

    expect(req.discoveryFlightOperatorEmail).toBe('schnupper@seed.example');
    expect(req.scenicFlightOperatorEmail).toBe('mitflug@seed.example');
    expect(req.discoveryFlightTypeId).toBe('ft-019e30c3-2c00-7001-8000-0000000000f1');
  });

  it('sends a blank recipient list as an empty string so the key stays on the wire', () => {
    const req = buildClubUpdateRequest({
      ...formValue,
      discoveryFlightOperatorEmail: '   ',
      scenicFlightOperatorEmail: '',
    });

    expect(req.discoveryFlightOperatorEmail).toBe('');
    expect(req.scenicFlightOperatorEmail).toBe('');
    expect(JSON.parse(JSON.stringify(req))).toHaveProperty('discoveryFlightOperatorEmail');
  });

  it('omits a cleared flight type rather than sending a pattern-violating empty string', () => {
    const req = buildClubUpdateRequest({ ...formValue, discoveryFlightTypeId: null });

    expect(req.discoveryFlightTypeId).toBeUndefined();
    expect(JSON.parse(JSON.stringify(req))).not.toHaveProperty('discoveryFlightTypeId');
  });
});

describe('buildClubCreateRequest', () => {
  it('omits the fields the create endpoint does not accept', () => {
    // The server rejects unknown properties, so the extra edit-only controls
    // must not ride along on the POST.
    expect(Object.keys(buildClubCreateRequest(formValue)).sort()).toEqual(
      ['clubKey', 'clubStateId', 'countryId', 'name', 'publicRegistrationEnabled', 'slug'].sort(),
    );
  });
});

describe('isOwnClub', () => {
  it('is true only for the club the principal is a member of', () => {
    expect(isOwnClub('clb-1', 'clb-1')).toBe(true);
    expect(isOwnClub('clb-1', 'clb-2')).toBe(false);
  });

  it('is false without a resolved club on either side', () => {
    expect(isOwnClub(null, 'clb-1')).toBe(false);
    expect(isOwnClub('clb-1', null)).toBe(false);
    expect(isOwnClub(null, null)).toBe(false);
  });
});
