import { AuditEventRowActorKind } from '@api/generated/model';
import type { AuditEventRow, UserListItem } from '@api/generated/model';

const USER_ID_EXTERNAL_PREFIX = 'usr-';

export type ActorUsernamesByUserId = Readonly<Record<string, string>>;

const LABEL_KEY_PER_ACTOR_KIND: Readonly<Record<string, string>> = {
  [AuditEventRowActorKind.ANONYMOUS_PUBLIC]: 'actor.anonymousPublic',
  [AuditEventRowActorKind.SYSTEM]: 'actor.system',
  [AuditEventRowActorKind.LEGACY_MIGRATED]: 'actor.legacyMigrated',
};

const NO_NAME_RESOLVES_LABEL_KEY = 'actor.unknown';

export function actorUsernamesByUserId(users: readonly UserListItem[]): ActorUsernamesByUserId {
  const usernames: Record<string, string> = {};
  for (const user of users) {
    usernames[withoutExternalPrefix(user.id)] = user.username || user.friendlyName;
  }
  return usernames;
}

export function auditActorCellText(
  row: AuditEventRow,
  usernames: ActorUsernamesByUserId,
  translateAuditLogsKey: (key: string) => string,
): string {
  const labelKey = LABEL_KEY_PER_ACTOR_KIND[actorKindOf(row)];
  if (labelKey) {
    return translateAuditLogsKey(labelKey);
  }
  const username = row.actorUserId ? usernames[row.actorUserId] : undefined;
  return username ?? row.actorKeycloakSub ?? translateAuditLogsKey(NO_NAME_RESOLVES_LABEL_KEY);
}

function actorKindOf(row: AuditEventRow): string {
  if (row.actorKind) {
    return row.actorKind;
  }
  return row.systemActor ? AuditEventRowActorKind.SYSTEM : AuditEventRowActorKind.NORMAL;
}

function withoutExternalPrefix(userId: string): string {
  return userId.startsWith(USER_ID_EXTERNAL_PREFIX)
    ? userId.slice(USER_ID_EXTERNAL_PREFIX.length)
    : userId;
}
