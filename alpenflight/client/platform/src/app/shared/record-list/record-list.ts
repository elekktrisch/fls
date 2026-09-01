import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RecordItem, type RecordItemData } from '../record-item/record-item';
import { ListGroupHeader } from '../list-group-header/list-group-header';

export interface RecordGroup {
  readonly name: string;
  readonly items: readonly RecordItemData[];
}

@Component({
  selector: 'app-record-list',
  imports: [RecordItem, ListGroupHeader],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'record-list' },
  templateUrl: './record-list.html',
  styleUrl: './record-list.css',
})
export class RecordList {
  readonly items = input.required<readonly RecordItemData[]>();
  // Optional: a record with no groupBy renders as one flat list, no group header. When supplied,
  // groups preserve the order each group name first appears in `items` — the caller (e.g.
  // records.ts) is responsible for sorting `items` before RecordList ever sees them.
  readonly groupBy = input<((record: RecordItemData) => string) | undefined>(undefined);

  protected readonly isGrouped = computed(() => this.groupBy() !== undefined);

  protected readonly groups = computed<readonly RecordGroup[]>(() => {
    const groupBy = this.groupBy();
    if (!groupBy) {
      return [];
    }

    const order: string[] = [];
    const itemsByName = new Map<string, RecordItemData[]>();
    for (const item of this.items()) {
      const name = groupBy(item);
      let bucket = itemsByName.get(name);
      if (!bucket) {
        bucket = [];
        itemsByName.set(name, bucket);
        order.push(name);
      }
      bucket.push(item);
    }

    return order.map((name) => ({ name, items: itemsByName.get(name)! }));
  });
}
