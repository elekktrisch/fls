import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import type { EmailTemplateListItem } from '@api/generated/model';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { EmailTemplatesStore } from '../email-templates.store';

/** Stable testid/route suffix for the `(templateKey, languageLocale)` identity. */
function rowId(t: Pick<EmailTemplateListItem, 'templateKey' | 'languageLocale'>): string {
  return `${t.templateKey}-${t.languageLocale}`;
}

@Component({
  selector: 'af-email-templates-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfDataTableComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    RouterLink,
  ],
  template: `
    <af-page>
      <af-page-header
        title="Email templates"
        description="Customize transactional-email defaults for this club."
      />

      <af-page-error
        [message]="store.loadError()"
        (retry)="store.load()"
        data-testid="email-templates-error"
      />

      <af-data-table
        data-testid="email-templates-table"
        [items]="store.items()"
        [loading]="store.isLoading()"
      >
        <ng-template #primary let-t>
          <a
            class="text-slate-900 font-medium no-underline hover:text-brand-700"
            [routerLink]="['/email-templates', t.templateKey, t.languageLocale]"
            [attr.data-testid]="'email-template-row-' + rowId(t)"
          >
            <span>{{ t.templateKey }}</span>
            <span> · </span>
            <span class="uppercase">{{ t.languageLocale }}</span>
          </a>
        </ng-template>
        <ng-template #secondary let-t>
          @if (t.subject) {
            <span class="text-slate-600">{{ t.subject }}</span>
          }
        </ng-template>
        <ng-template #meta let-t>
          @if (isOverride(t)) {
            <span
              class="inline-block text-xs px-2 py-0.5 bg-brand-50 text-brand-700 border border-brand-200"
              [attr.data-testid]="'email-template-override-' + rowId(t)"
            >
              Customized
            </span>
          } @else {
            <span
              class="inline-block text-xs px-2 py-0.5 bg-slate-50 text-slate-600 border border-slate-200"
            >
              Default
            </span>
          }
        </ng-template>
      </af-data-table>
    </af-page>
  `,
})
export class EmailTemplatesListPage {
  protected readonly store = inject(EmailTemplatesStore);
  protected readonly rowId = rowId;

  protected isOverride(t: EmailTemplateListItem): boolean {
    return t.source === 'CLUB_OVERRIDE';
  }
}
