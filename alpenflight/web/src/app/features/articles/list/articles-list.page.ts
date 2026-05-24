import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { SessionStore } from '../../../core/session/session.store';
import type { ArticleItem } from '../articles.store';
import { ArticlesStore } from '../articles.store';

@Component({
  selector: 'af-articles-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfDataTableComponent,
    AfIconComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    NzDropDownModule,
    RouterLink,
  ],
  template: `
    <af-page>
      <af-page-header title="Articles">
        @if (canMutate()) {
          <af-button
            type="primary"
            htmlType="button"
            (clicked)="router.navigateByUrl('/articles/new')"
            data-testid="articles-new-button"
          >
            New article
          </af-button>
        }
      </af-page-header>

      <af-page-error
        [message]="store.loadError()"
        (retry)="store.loadAll()"
        data-testid="articles-error"
      />

      <div class="mb-3 flex items-center gap-2">
        <label class="flex items-center gap-2 cursor-pointer select-none text-sm text-slate-700">
          <input
            type="checkbox"
            class="w-4 h-4 accent-brand-500 cursor-pointer"
            [checked]="store.includeInactive()"
            (change)="onToggleIncludeInactive($event)"
            data-testid="articles-include-inactive"
          />
          <span>Show inactive articles</span>
        </label>
      </div>

      <af-data-table
        data-testid="articles-table"
        [items]="store.entities()"
        [loading]="store.isLoading()"
      >
        <ng-template #primary let-a>
          <a
            class="text-slate-900 font-medium no-underline hover:text-brand-700"
            [routerLink]="['/articles', a.id, 'edit']"
            [attr.data-testid]="'articles-row-' + a.id"
          >
            <span class="tabular">{{ a.articleNumber }}</span>
            <span> · </span>
            <span>{{ a.articleName }}</span>
          </a>
        </ng-template>
        <ng-template #secondary let-a>
          @if (a.articleInfo) {
            <span>{{ a.articleInfo }}</span>
          }
          @if (!a.isActive) {
            <span
              class="inline-block ml-2 text-xs px-2 py-0.5 bg-slate-50 text-slate-500"
              [attr.data-testid]="'articles-badge-inactive-' + a.id"
            >
              Inactive
            </span>
          }
        </ng-template>
        <ng-template #meta let-a>
          @if (canMutate()) {
            <button
              type="button"
              class="w-8 h-8 inline-flex items-center justify-center bg-transparent border-0 text-slate-500 cursor-pointer hover:text-slate-900 hover:bg-slate-50"
              nz-dropdown
              [nzDropdownMenu]="rowMenu"
              nzTrigger="click"
              nzPlacement="bottomRight"
              [attr.aria-label]="'Actions for ' + a.articleNumber"
              [attr.data-testid]="'articles-kebab-' + a.id"
              (click)="$event.stopPropagation()"
            >
              <af-icon name="more-vertical" [size]="18" />
            </button>
            <nz-dropdown-menu #rowMenu="nzDropdownMenu">
              <ul
                class="list-none m-0 p-1 min-w-[10rem] bg-white border border-slate-200"
                role="menu"
              >
                <li role="none">
                  <a
                    role="menuitem"
                    class="flex items-center gap-2 w-full py-1.5 px-2.5 text-[15px] text-slate-900 no-underline cursor-pointer text-left hover:bg-slate-50"
                    [routerLink]="['/articles', a.id, 'edit']"
                  >
                    <af-icon name="pencil" [size]="14" />
                    <span>Edit</span>
                  </a>
                </li>
                <li role="none">
                  <button
                    type="button"
                    role="menuitem"
                    class="flex items-center gap-2 w-full py-1.5 px-2.5 bg-transparent border-0 text-[15px] text-red-600 cursor-pointer text-left hover:bg-slate-50"
                    (click)="confirmDelete(a)"
                    [attr.data-testid]="'articles-delete-' + a.id"
                  >
                    <af-icon name="trash-2" [size]="14" />
                    <span>Delete</span>
                  </button>
                </li>
              </ul>
            </nz-dropdown-menu>
          }
        </ng-template>
      </af-data-table>
    </af-page>
  `,
})
export class ArticlesListPage {
  protected readonly store = inject(ArticlesStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  protected readonly canMutate = this.session.isClubAdmin;

  protected onToggleIncludeInactive(event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.store.setIncludeInactive(checked);
  }

  protected confirmDelete(a: ArticleItem): void {
    if (typeof window === 'undefined' || !a.id) return;
    if (window.confirm(`Delete article "${a.articleNumber}"? This cannot be undone.`)) {
      this.store.delete(a.id);
    }
  }
}
