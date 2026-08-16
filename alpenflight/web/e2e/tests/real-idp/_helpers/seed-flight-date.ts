export const SEEDED_FLIGHT_DAYS_AGO_INSIDE_90_DAY_LIST_WINDOW_AND_PAST_3_DAY_BILL_GATE = 30;

export function daysAgo(days: number): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() - days);
  return day.toISOString().slice(0, 10);
}

export function seededFlightDateInsideListWindowAndPastBillGate(): string {
  return daysAgo(SEEDED_FLIGHT_DAYS_AGO_INSIDE_90_DAY_LIST_WINDOW_AND_PAST_3_DAY_BILL_GATE);
}
