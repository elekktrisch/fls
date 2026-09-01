import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

// Hand-written — ng-zorro-antd has no matching primitive. No dedicated stylesheet: Tailwind
// utilities in the template carry the 32px height, border, and the active/inactive palette;
// `.micro` (styles.css) carries type.
@Component({
  selector: 'app-filter-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'inline-block' },
  templateUrl: './filter-chip.html',
})
export class FilterChip {
  readonly label = input.required<string>();
  readonly active = model(false);

  protected toggle(): void {
    this.active.update((active) => !active);
  }

  protected clear(): void {
    this.active.set(false);
  }
}
