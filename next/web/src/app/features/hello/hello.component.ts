import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component } from '@angular/core';

import { helloResource } from '@api/generated/hello/hello.resource';

// TODO(S-020): remove or auth-gate before cutover.
@Component({
  selector: 'af-hello',
  imports: [DatePipe],
  template: `
    @if (hello.isLoading()) {
      <p>Loading…</p>
    } @else if (hello.error()) {
      <p class="text-red-600">Failed: {{ hello.error()?.message }}</p>
    } @else if (hello.value(); as r) {
      <h1 class="text-blue-600 text-3xl font-bold">{{ r.message }}</h1>
      <p>{{ r.timestamp | date: 'medium' }}</p>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HelloComponent {
  protected readonly hello = helloResource();
}
