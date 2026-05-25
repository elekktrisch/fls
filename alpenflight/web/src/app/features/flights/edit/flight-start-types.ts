/**
 * Canonical start-type identifiers seeded by
 * `alpenflight/server/src/main/resources/db/migration/V2__identity_and_reference.sql`.
 *
 * No `/api/v1/start-types` endpoint exists yet, so the wizard hardcodes
 * the seeded UUIDs against the migration. **Follow-up:** add a backend
 * reference-data endpoint + a `StartTypesStore` and consume them from the
 * wizard — tracked alongside the masterdata-field-plumbing rework (the
 * cross-field reactive rules also need richer master-data flags).
 */

export const START_TYPE = {
  WINCH_LAUNCH: '019e2e15-2c00-7fa0-8000-000000000fa0',
  AEROTOW: '019e2e15-2c00-7fa1-8000-000000000fa1',
  SELF_START: '019e2e15-2c00-7fa2-8000-000000000fa2',
  EXTERNAL_START: '019e2e15-2c00-7fa3-8000-000000000fa3',
  MOTOR: '019e2e15-2c00-7fa4-8000-000000000fa4',
} as const;

export interface StartTypeOption {
  readonly id: string;
  readonly label: string;
}

export const START_TYPE_OPTIONS: readonly StartTypeOption[] = [
  { id: START_TYPE.AEROTOW, label: 'Aerotow' },
  { id: START_TYPE.WINCH_LAUNCH, label: 'Winch launch' },
  { id: START_TYPE.SELF_START, label: 'Self start' },
  { id: START_TYPE.EXTERNAL_START, label: 'External start' },
];

export function isAerotow(startTypeId: string | null | undefined): boolean {
  return startTypeId === START_TYPE.AEROTOW;
}
