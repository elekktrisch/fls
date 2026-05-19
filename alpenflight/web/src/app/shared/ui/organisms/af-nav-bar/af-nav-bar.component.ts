import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { NzDrawerModule } from 'ng-zorro-antd/drawer';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';

import { AfIconComponent } from '../../atoms/af-icon';
import { ViewportService } from '../../viewport';

export interface NavItem {
  readonly path: string;
  readonly label: string;
  readonly icon?: string;
}

export interface UserSummary {
  readonly displayName: string;
  readonly initials: string;
}

export type Locale = 'de' | 'fr' | 'it' | 'en';

const LOCALE_LABELS: Record<Locale, string> = {
  de: 'Deutsch',
  fr: 'Français',
  it: 'Italiano',
  en: 'English',
};

/**
 * Top-bar primary nav (ADR 0024 §Decision).
 *
 *   ┌────────────────────────────────────────────────────────────────┐
 *   │ [✈ AlpenFlight]  Clubs  Flights  Members         [👤 user ▾]   │
 *   └────────────────────────────────────────────────────────────────┘
 *
 * Single-layer, 56px tall. Below md, sections collapse to a hamburger
 * drawer; the bar shows hamburger + brand + user avatar.
 *
 * Active section indicator: brand-500 underline.
 */
@Component({
  selector: 'af-nav-bar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzDrawerModule, NzDropDownModule, RouterLink, RouterLinkActive, AfIconComponent],
  host: { class: 'block' },
  template: `
    <header
      role="banner"
      class="sticky top-0 z-50 flex items-center gap-3 h-14 px-4 bg-white border-b border-slate-200 md:gap-6 md:px-6 lg:px-8 xl:px-12"
    >
      <!-- Below-md hamburger -->
      @if (!isWide()) {
        <button
          type="button"
          class="bg-transparent border-0 p-0 w-11 h-11 inline-flex items-center justify-center cursor-pointer text-slate-900"
          [attr.aria-label]="'Open navigation'"
          data-testid="af-nav-burger"
          (click)="openDrawer()"
        >
          <af-icon name="menu" [size]="20" />
        </button>
      }

      <!-- Brand -->
      <a
        [routerLink]="brandHref()"
        class="inline-flex items-center gap-2 flex-none text-slate-900 no-underline font-medium"
        data-testid="af-nav-brand"
      >
        <af-icon name="plane" [size]="22" class="text-brand-500" />
        <span class="text-lg tracking-tight">{{ title() }}</span>
      </a>

      <!-- Section tabs (above md only) -->
      @if (isWide()) {
        <nav class="flex items-stretch gap-1 h-full ml-2" aria-label="Primary">
          @for (item of items(); track item.path) {
            <a
              [routerLink]="item.path"
              routerLinkActive="!text-slate-900 !border-brand-500"
              class="inline-flex items-center px-3.5 text-[15px] text-slate-600 no-underline border-b-2 border-transparent -mb-px hover:text-slate-900"
              [attr.data-testid]="'af-nav-section-' + item.path"
            >
              {{ item.label }}
            </a>
          }
        </nav>
      }

      <span class="flex-1"></span>

      <!-- User menu (right) -->
      @if (user(); as u) {
        <button
          type="button"
          class="inline-flex items-center gap-2 bg-transparent border-0 px-2 py-1 cursor-pointer text-slate-900 min-h-10 hover:bg-slate-50"
          nz-dropdown
          [nzDropdownMenu]="userMenu"
          nzTrigger="click"
          nzPlacement="bottomRight"
          data-testid="af-nav-user"
          [attr.aria-label]="'Account menu for ' + u.displayName"
        >
          <span
            class="inline-flex items-center justify-center w-8 h-8 rounded-full bg-brand-100 text-brand-700 text-[13px] font-medium"
            aria-hidden="true"
            >{{ u.initials }}</span
          >
          @if (isWide()) {
            <af-icon name="chevron-down" [size]="16" />
          }
        </button>
        <nz-dropdown-menu #userMenu="nzDropdownMenu">
          <ul
            class="list-none m-0 p-1 min-w-[12.5rem] bg-white border border-slate-200"
            role="menu"
          >
            <li role="presentation" class="px-3 py-2 text-sm text-slate-500 font-medium">
              {{ u.displayName }}
            </li>
            <li role="presentation" class="h-px bg-slate-200 my-1" aria-hidden="true"></li>
            <li role="none">
              <a
                role="menuitem"
                class="flex items-center gap-2.5 w-full px-3 py-2 text-[15px] text-slate-900 no-underline cursor-pointer text-left hover:bg-slate-50"
                routerLink="/profile"
              >
                <af-icon name="user" [size]="16" />
                <span>Profile</span>
              </a>
            </li>
            <li role="none">
              <a
                role="menuitem"
                class="flex items-center gap-2.5 w-full px-3 py-2 text-[15px] text-slate-900 no-underline cursor-pointer text-left hover:bg-slate-50"
                routerLink="/settings"
              >
                <af-icon name="settings" [size]="16" />
                <span>Settings</span>
              </a>
            </li>
            <li role="presentation" class="h-px bg-slate-200 my-1" aria-hidden="true"></li>
            <li
              role="presentation"
              class="flex items-center gap-2 px-3 py-1.5 text-sm text-slate-500"
            >
              <af-icon name="globe" [size]="16" />
              <span>Language</span>
            </li>
            @for (loc of locales; track loc) {
              <li role="none">
                <button
                  type="button"
                  role="menuitem"
                  class="flex items-center justify-between gap-2.5 w-full pl-8 pr-3 py-2 bg-transparent border-0 text-[15px] cursor-pointer text-left hover:bg-slate-50"
                  [class.text-brand-700]="loc === locale()"
                  [class.text-slate-900]="loc !== locale()"
                  (click)="localeChange.emit(loc)"
                >
                  <span>{{ localeLabel(loc) }}</span>
                  @if (loc === locale()) {
                    <af-icon name="check" [size]="14" />
                  }
                </button>
              </li>
            }
            <li role="presentation" class="h-px bg-slate-200 my-1" aria-hidden="true"></li>
            <li role="none">
              <a
                role="menuitem"
                class="flex items-center gap-2.5 w-full px-3 py-2 text-[15px] text-red-600 no-underline cursor-pointer text-left hover:bg-slate-50"
                routerLink="/auth/logout"
                data-testid="af-nav-logout"
              >
                <af-icon name="log-out" [size]="16" />
                <span>Sign out</span>
              </a>
            </li>
          </ul>
        </nz-dropdown-menu>
      }
    </header>

    <!-- Mobile drawer (sections only; user menu stays in the bar) -->
    <nz-drawer
      [nzVisible]="drawerOpen()"
      nzPlacement="left"
      [nzClosable]="true"
      [nzWidth]="280"
      nzTitle="Navigation"
      (nzOnClose)="closeDrawer()"
    >
      <ng-container *nzDrawerContent>
        <nav aria-label="Primary mobile">
          <ul class="list-none m-0 p-0 flex flex-col gap-1">
            @for (item of items(); track item.path) {
              <li>
                <a
                  [routerLink]="item.path"
                  routerLinkActive="!border-brand-500 !text-brand-700 !font-medium"
                  class="flex items-center gap-2.5 py-3 px-2 text-slate-900 no-underline border-l-[3px] border-transparent"
                  (click)="closeDrawer()"
                >
                  @if (item.icon) {
                    <af-icon [name]="item.icon" [size]="18" />
                  }
                  <span>{{ item.label }}</span>
                </a>
              </li>
            }
          </ul>
        </nav>
      </ng-container>
    </nz-drawer>
  `,
})
export class AfNavBarComponent {
  readonly #viewport = inject(ViewportService);
  readonly #atLeastMd = this.#viewport.isAtLeast('md');

  readonly items = input.required<readonly NavItem[]>();
  readonly title = input<string>('AlpenFlight');
  readonly brandHref = input<string>('/');
  readonly user = input<UserSummary | null>(null);
  readonly locale = input<Locale>('de');

  readonly localeChange = output<Locale>();

  protected readonly locales: readonly Locale[] = ['de', 'fr', 'it', 'en'];
  protected localeLabel(loc: Locale): string {
    return LOCALE_LABELS[loc];
  }

  readonly #drawerOpen = signal(false);
  protected readonly drawerOpen = this.#drawerOpen.asReadonly();
  protected readonly isWide = computed(() => this.#atLeastMd());

  protected openDrawer(): void {
    this.#drawerOpen.set(true);
  }
  protected closeDrawer(): void {
    this.#drawerOpen.set(false);
  }
}
