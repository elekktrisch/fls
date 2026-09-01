import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzDatePickerModule } from 'ng-zorro-antd/date-picker';

// Story 1.5 deferred spike (see deferred-work.md, source_spec spec-1-5): prices the cost of
// overriding ng-zorro-antd's dark theme against DESIGN.md's exact palette and zero-radius corners,
// before Epic 3 commits the typeahead and the date field to this library. Mounted only at
// /dev/component-spike (app.routes.ts) — never a real screen, never real data.
@Component({
  selector: 'app-component-spike',
  imports: [FormsModule, NzSelectModule, NzDatePickerModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './component-spike.html',
  styleUrl: './component-spike.css',
})
export class ComponentSpike {
  protected readonly aircraft = signal<string | null>(null);
  protected readonly flightDate = signal<Date | null>(null);

  protected readonly aircraftOptions = [
    { value: 'HB-3215', label: 'HB-3215' },
    { value: 'HB-2101', label: 'HB-2101' },
    { value: 'HB-1944', label: 'HB-1944' },
  ];
}
