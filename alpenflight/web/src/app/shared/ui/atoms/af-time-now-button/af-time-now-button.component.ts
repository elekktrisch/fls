import { ChangeDetectionStrategy, Component, output } from '@angular/core';

import { AfButtonComponent } from '../af-button';

function nowAsHhMm(): string {
  const d = new Date();
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

@Component({
  selector: 'af-time-now-button',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfButtonComponent],
  template: `
    <af-button (clicked)="emit()" ariaLabel="Set to now">
      <span>now</span>
    </af-button>
  `,
})
export class AfTimeNowButtonComponent {
  readonly nowSet = output<string>();

  protected emit(): void {
    this.nowSet.emit(nowAsHhMm());
  }
}
