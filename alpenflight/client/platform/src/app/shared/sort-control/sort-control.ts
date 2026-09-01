import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

export type SortDirection = 'asc' | 'desc';

export interface SortField {
  readonly key: string;
  readonly label: string;
}

export interface SortState {
  readonly key: string;
  readonly direction: SortDirection;
}

// Hand-written — ng-zorro-antd has no matching primitive, and an item has no column header to
// carry sorting, so it lives here instead (DESIGN.md). Judgment call, flagged in the story's
// Design Notes: one button per sortable field. Clicking a different field switches to it,
// ascending. Clicking the already-active field flips its direction.
@Component({
  selector: 'app-sort-control',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'inline-block' },
  templateUrl: './sort-control.html',
})
export class SortControl {
  readonly fields = input.required<readonly SortField[]>();
  readonly sort = model.required<SortState>();

  protected select(field: SortField): void {
    const current = this.sort();
    this.sort.set(
      current.key === field.key
        ? { key: field.key, direction: current.direction === 'asc' ? 'desc' : 'asc' }
        : { key: field.key, direction: 'asc' },
    );
  }
}
