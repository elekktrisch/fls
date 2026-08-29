import { ChangeDetectionStrategy, Component } from '@angular/core';
import { httpResource } from '@angular/common/http';
import type { SystemStatusResponse } from '../../platform/src/generated/openapi/model/system-status-response';

@Component({
  selector: 'app-system-status-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section aria-label="System status">
      @if (status.isLoading()) {
        <p>Checking system status.</p>
      } @else if (status.error()) {
        <p>The system status is not available.</p>
      } @else if (status.hasValue()) {
        <p>System status: {{ status.value().status }}</p>
        <p>Server time: {{ status.value().serverTime }}</p>
      }
    </section>
  `,
})
export class SystemStatusCard {
  protected readonly status = httpResource<SystemStatusResponse>(() => '/api/v1/system/status');
}
