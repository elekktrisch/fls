import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

// The closed set DESIGN.md's state-marker component defines. One word, one meaning — a marker
// never carries a label outside this set.
export type RecordMarkerTone = 'open' | 'airborne' | 'locked' | 'billed' | 'unsent';

export interface RecordMarker {
  readonly label: string;
  readonly tone: RecordMarkerTone;
}

// DESIGN.md's record-item anatomy: identity, meta, metric, marker. A consumer supplies already
// display-formatted strings — RecordItem carries no business logic, it only lays the four zones
// out and colors them.
export interface RecordItemData {
  readonly id: string;
  readonly identity: string;
  readonly meta: readonly string[];
  readonly metric: string | null;
  readonly metricLive?: boolean;
  readonly marker?: RecordMarker;
  readonly settled?: boolean;
}

@Component({
  selector: 'app-record-item',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'record-item',
    '[class.record-item--settled]': 'record().settled',
  },
  templateUrl: './record-item.html',
  styleUrl: './record-item.css',
})
export class RecordItem {
  readonly record = input.required<RecordItemData>();

  protected readonly meta = computed(() => this.record().meta.join(' · '));
  protected readonly hasMetric = computed(() => this.record().metric !== null);
  protected readonly metricText = computed(() => this.record().metric ?? 'not set');
}
