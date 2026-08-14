
export interface FunnelEvent {
  event_id: string;
  actor_id?: string;
  timestamp: string;
  properties: Record<string, string>;
}

export function emitFunnelEvent(event: FunnelEvent): void {
  console.info('[funnel]', JSON.stringify(event));
}
