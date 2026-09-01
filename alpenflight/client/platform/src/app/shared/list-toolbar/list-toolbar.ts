import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { SearchField } from '../search-field/search-field';
import { FilterChip } from '../filter-chip/filter-chip';
import { SortControl, type SortField, type SortState } from '../sort-control/sort-control';

export interface ToolbarChip {
  readonly key: string;
  readonly label: string;
}

// Composes the three atoms in DESIGN.md's fixed order: search field, then filter chips, then the
// sort control. Chips are keyed by `key`; the toolbar owns which keys are active, the host
// (records.ts) owns what each key means.
@Component({
  selector: 'app-list-toolbar',
  imports: [SearchField, FilterChip, SortControl],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  templateUrl: './list-toolbar.html',
  styleUrl: './list-toolbar.css',
})
export class ListToolbar {
  readonly searchLabel = input('Search');
  readonly chips = input.required<readonly ToolbarChip[]>();
  readonly sortFields = input.required<readonly SortField[]>();

  readonly query = model('');
  readonly activeChipKeys = model.required<ReadonlySet<string>>();
  readonly sort = model.required<SortState>();

  protected isActive(chip: ToolbarChip): boolean {
    return this.activeChipKeys().has(chip.key);
  }

  protected onChipActiveChange(chip: ToolbarChip, active: boolean): void {
    const next = new Set(this.activeChipKeys());
    if (active) {
      next.add(chip.key);
    } else {
      next.delete(chip.key);
    }
    this.activeChipKeys.set(next);
  }
}
