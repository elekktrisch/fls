import { AuditEventRowAction } from '@api/generated/model';

const ACTION_LABELS: Readonly<Record<string, string>> = {
  [AuditEventRowAction.CREATE]: 'Created',
  [AuditEventRowAction.UPDATE]: 'Updated',
  [AuditEventRowAction.DELETE]: 'Deleted',
  [AuditEventRowAction.STATE_TRANSITION]: 'State transition',
  [AuditEventRowAction.BULK_IMPORT]: 'Bulk import',
  [AuditEventRowAction.LOOKUP_HIT]: 'Lookup hit',
  [AuditEventRowAction.LOOKUP_MISS]: 'Lookup miss',
  [AuditEventRowAction.MIGRATION_HANDSHAKE_ISSUED]: 'Handshake issued',
  [AuditEventRowAction.MIGRATION_HANDSHAKE_SUPERSEDED]: 'Handshake superseded',
  [AuditEventRowAction.MIGRATION_HANDSHAKE_EXPIRED]: 'Handshake expired',
  [AuditEventRowAction.MIGRATION_INGEST_STARTED]: 'Ingest started',
  [AuditEventRowAction.MIGRATION_INGEST_COMPLETED]: 'Ingest completed',
  [AuditEventRowAction.MIGRATION_INGEST_FAILED]: 'Ingest failed',
  [AuditEventRowAction.PLANNING_NOTIFICATIONS_RUN]: 'Planning notifications run',
  [AuditEventRowAction.CLUB_JOIN_CODE_ROTATED]: 'Join code rotated',
};

export function actionLabel(action: string | undefined): string {
  if (!action) return '';
  return ACTION_LABELS[action] ?? action;
}

/** Slate tint per action so the forensic list scans by mutation kind at a glance. */
export function actionBadgeClass(action: string | undefined): string {
  switch (action) {
    case AuditEventRowAction.CREATE:
      return 'border-emerald-300 bg-emerald-50 text-emerald-700';
    case AuditEventRowAction.DELETE:
      return 'border-red-300 bg-red-50 text-red-700';
    case AuditEventRowAction.UPDATE:
      return 'border-brand-500 bg-brand-50 text-brand-700';
    default:
      return 'border-slate-300 bg-slate-50 text-slate-700';
  }
}
