import { ChangeDetectionStrategy, Component, input } from '@angular/core';

// No dedicated stylesheet: Tailwind utilities in the template carry layout/color, and the
// `.micro` role class from styles.css carries type — same treatment as filter-chip/sort-control.
@Component({
  selector: 'app-list-group-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  templateUrl: './list-group-header.html',
})
export class ListGroupHeader {
  readonly name = input.required<string>();
  readonly count = input.required<number>();
}
