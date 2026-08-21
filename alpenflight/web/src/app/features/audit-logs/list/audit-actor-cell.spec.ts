import { describe, expect, it } from 'vitest';

import { AuditEventRowActorKind } from '@api/generated/model';
import type { AuditEventRow, UserListItem } from '@api/generated/model';

import { actorUsernamesByUserId, auditActorCellText } from './audit-actor-cell';
import de from '../../../../i18n/de';

const ACTOR_USER_ID = '019e30c3-2c00-7100-8000-000000000001';
const ACTOR_KEYCLOAK_SUB = '2b3f1d84-9c11-4c1e-9a5d-6f0f1a2b3c4d';

function shippedGermanLabel(dottedKey: string): string {
  let node: unknown = de.auditLogs;
  for (const segment of dottedKey.split('.')) {
    node = (node as Record<string, unknown>)[segment];
  }
  if (typeof node !== 'string') {
    throw new Error(`de.auditLogs.${dottedKey} is not a shipped label`);
  }
  return node;
}

function rowWith(overrides: Partial<AuditEventRow>): AuditEventRow {
  return {
    id: 'aud-019e30c3-2c00-7200-8000-000000000001',
    occurredAt: '2026-08-20T08:15:00Z',
    action: 'CREATE',
    targetEntityType: 'Location',
    failed: false,
    systemActor: false,
    ...overrides,
  } as AuditEventRow;
}

function userWith(overrides: Partial<UserListItem>): UserListItem {
  return {
    id: `usr-${ACTOR_USER_ID}`,
    username: 'a.meier',
    friendlyName: 'Anna Meier',
    notificationEmail: 'anna.meier@example.test',
    roles: [],
    enabled: true,
    invitePending: false,
    ...overrides,
  } as UserListItem;
}

describe('actorUsernamesByUserId', () => {
  it('keys the username on the raw actor user id, without the external usr- prefix', () => {
    expect(actorUsernamesByUserId([userWith({})])).toEqual({ [ACTOR_USER_ID]: 'a.meier' });
  });

  it('falls back to the friendly name when a user carries no username', () => {
    expect(actorUsernamesByUserId([userWith({ username: '' })])).toEqual({
      [ACTOR_USER_ID]: 'Anna Meier',
    });
  });
});

describe('auditActorCellText', () => {
  const usernames = actorUsernamesByUserId([userWith({})]);

  it('names the authenticated actor by username instead of a raw identifier', () => {
    const cell = auditActorCellText(
      rowWith({
        actorKind: AuditEventRowActorKind.NORMAL,
        actorUserId: ACTOR_USER_ID,
        actorKeycloakSub: ACTOR_KEYCLOAK_SUB,
      }),
      usernames,
      shippedGermanLabel,
    );
    expect(cell).toBe('a.meier');
  });

  it('falls back to the keycloak sub when no username resolves, and never renders an empty cell', () => {
    const cell = auditActorCellText(
      rowWith({
        actorKind: AuditEventRowActorKind.NORMAL,
        actorUserId: 'd1b6b1f0-0000-7100-8000-00000000ffff',
        actorKeycloakSub: ACTOR_KEYCLOAK_SUB,
      }),
      usernames,
      shippedGermanLabel,
    );
    expect(cell).toBe(ACTOR_KEYCLOAK_SUB);
    expect(cell).not.toBe('');
  });

  it('falls back to the unknown label when neither a username nor a keycloak sub is present', () => {
    const cell = auditActorCellText(
      rowWith({ actorKind: AuditEventRowActorKind.NORMAL }),
      usernames,
      shippedGermanLabel,
    );
    expect(cell).toBe(shippedGermanLabel('actor.unknown'));
    expect(cell).not.toBe('');
  });

  it('labels an anonymous public submission, which carries no actor identifier at all', () => {
    const cell = auditActorCellText(
      rowWith({ actorKind: AuditEventRowActorKind.ANONYMOUS_PUBLIC, systemActor: false }),
      usernames,
      shippedGermanLabel,
    );
    expect(cell).toBe(shippedGermanLabel('actor.anonymousPublic'));
  });

  it('labels a scheduled job as the system', () => {
    const cell = auditActorCellText(
      rowWith({ actorKind: AuditEventRowActorKind.SYSTEM, systemActor: true }),
      usernames,
      shippedGermanLabel,
    );
    expect(cell).toBe(shippedGermanLabel('actor.system'));
  });

  it('gives the anonymous submission and the scheduled job different labels', () => {
    const anonymous = auditActorCellText(
      rowWith({ actorKind: AuditEventRowActorKind.ANONYMOUS_PUBLIC, systemActor: false }),
      usernames,
      shippedGermanLabel,
    );
    const system = auditActorCellText(
      rowWith({ actorKind: AuditEventRowActorKind.SYSTEM, systemActor: true }),
      usernames,
      shippedGermanLabel,
    );
    expect(anonymous).not.toBe(system);
  });

  it('labels a row migrated from the legacy audit log', () => {
    const cell = auditActorCellText(
      rowWith({ actorKind: AuditEventRowActorKind.LEGACY_MIGRATED }),
      usernames,
      shippedGermanLabel,
    );
    expect(cell).toBe(shippedGermanLabel('actor.legacyMigrated'));
  });

  it('reads the system flag when a row carries no actor kind', () => {
    const cell = auditActorCellText(rowWith({ systemActor: true }), usernames, shippedGermanLabel);
    expect(cell).toBe(shippedGermanLabel('actor.system'));
  });
});
