import { mapClaimsToUser } from './oidc-claims';

describe('mapClaimsToUser', () => {
  const fullClaims = {
    sub: 'b9c0e2a5-1d3f-4a2e-9c6e-22f3a0c0a001',
    preferred_username: 'clubadmin1',
    email: 'clubadmin1@example.com',
    given_name: 'Carla',
    family_name: 'Admin',
    clubId: '019e30c3-2c00-7001-8000-000000000001',
    realm_access: { roles: ['CLUB_ADMINISTRATOR', 'OFFICE_USER', 'default-roles-alpenflight'] },
  };

  it('maps Keycloak claims to a User', () => {
    const user = mapClaimsToUser(fullClaims);

    expect(user).toEqual({
      id: 'b9c0e2a5-1d3f-4a2e-9c6e-22f3a0c0a001',
      username: 'clubadmin1',
      email: 'clubadmin1@example.com',
      firstName: 'Carla',
      lastName: 'Admin',
      clubId: '019e30c3-2c00-7001-8000-000000000001',
      roles: ['CLUB_ADMINISTRATOR', 'OFFICE_USER'],
    });
  });

  it('un-nests realm_access.roles[] (not flat top-level roles)', () => {
    const user = mapClaimsToUser({
      ...fullClaims,
      realm_access: { roles: ['PILOT'] },
    });

    expect(user?.roles).toEqual(['PILOT']);
  });

  it('drops unknown realm roles', () => {
    const user = mapClaimsToUser({
      ...fullClaims,
      realm_access: { roles: ['CLUB_ADMINISTRATOR', 'uma_authorization', 'offline_access'] },
    });

    expect(user?.roles).toEqual(['CLUB_ADMINISTRATOR']);
  });

  it('returns clubId === null when the claim is absent (federated / not-yet-imported user)', () => {
    const { clubId: _strip, ...rest } = fullClaims;
    void _strip;
    const user = mapClaimsToUser(rest);

    expect(user?.clubId).toBeNull();
  });

  it('returns roles === [] when realm_access is absent', () => {
    const { realm_access: _strip, ...rest } = fullClaims;
    void _strip;
    const user = mapClaimsToUser(rest);

    expect(user?.roles).toEqual([]);
  });

  it('returns null when the sub claim is missing (not a valid principal)', () => {
    const { sub: _strip, ...rest } = fullClaims;
    void _strip;

    expect(mapClaimsToUser(rest)).toBeNull();
  });

  it('returns null on unrelated payload shapes', () => {
    expect(mapClaimsToUser(null)).toBeNull();
    expect(mapClaimsToUser(undefined)).toBeNull();
    expect(mapClaimsToUser('not an object')).toBeNull();
    expect(mapClaimsToUser(42)).toBeNull();
  });

  it('coerces missing names to empty strings (Keycloak omits given_name on social IdPs)', () => {
    const { given_name: _g, family_name: _f, ...rest } = fullClaims;
    void _g;
    void _f;
    const user = mapClaimsToUser(rest);

    expect(user?.firstName).toBe('');
    expect(user?.lastName).toBe('');
  });
});
