import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';

import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { AuditLogsStore } from '../audit-logs.store';

@Component({
  selector: 'af-audit-logs-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [AfPageComponent, AfPageHeaderComponent, AfPageErrorComponent],
  template: `
    <af-page>
      <af-page-header title="Audit logs" />

      <af-page-error
        [message]="store.loadError()"
        (retry)="store.loadPage()"
        data-testid="audit-logs-error"
      />

      <div data-testid="audit-logs-table"></div>
    </af-page>
  `,
})
export class AuditLogsListPage implements OnInit {
  protected readonly store = inject(AuditLogsStore);

  ngOnInit(): void {
    this.store.loadPage();
  }
}
