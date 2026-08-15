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

export function isWinchLaunch(startTypeId: string | null | undefined): boolean {
  return startTypeId === START_TYPE.WINCH_LAUNCH;
}
