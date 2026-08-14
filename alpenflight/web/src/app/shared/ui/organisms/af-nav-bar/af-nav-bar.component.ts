import {
  ChangeDetectionStrategy,
  Component,
  type Signal,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NgTemplateOutlet } from '@angular/common';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { NzDrawerModule } from 'ng-zorro-antd/drawer';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';
import { filter, map, startWith } from 'rxjs';

import { AfIconComponent } from '../../atoms/af-icon';
import { AfWordmarkComponent } from '../../atoms/af-wordmark';
import { AfLangPickerComponent } from '../../molecules/af-lang-picker';
import { ViewportService } from '../../viewport';

export interface NavItem {
  readonly path?: string;
  readonly label: string;
  readonly icon?: string;
  readonly children?: readonly NavItem[];
  readonly badge?: Signal<number>;
  readonly badgeTestId?: string;
  readonly testId?: string;
}

export interface UserSummary {
  readonly displayName: string;
  readonly initials: string;
}

@Component({
  selector: 'af-nav-bar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgTemplateOutlet,
    NzDrawerModule,
    NzDropDownModule,
    RouterLink,
    RouterLinkActive,
    AfIconComponent,
    AfWordmarkComponent,
    AfLangPickerComponent,
  ],
  host: { class: 'block' },
  template: `
    <header
      role="banner"
      class="sticky top-0 z-50 flex items-center gap-3 h-14 px-4 bg-white border-b border-slate-200 md:gap-6 md:px-6 lg:px-8 xl:px-12"
    >
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

      <a
        [routerLink]="brandHref()"
        class="inline-flex items-center gap-2 flex-none text-slate-900 no-underline font-medium"
        data-testid="af-nav-brand"
      >
        <af-wordmark [label]="title()" />
      </a>

      @if (isWide()) {
        <nav class="flex items-stretch gap-1 h-full ml-2" aria-label="Primary">
          @for (item of items(); track item.label) {
            @if (item.children; as children) {
              <button
                type="button"
                class="inline-flex items-center gap-1 px-3.5 text-[15px] text-slate-600 bg-transparent border-0 border-b-2 border-transparent -mb-px cursor-pointer hover:text-slate-900"
                [class.!text-slate-900]="groupActive(children)"
                [class.!border-brand-500]="groupActive(children)"
                nz-dropdown
                [nzDropdownMenu]="groupMenu"
                nzTrigger="click"
                nzPlacement="bottomLeft"
                [attr.data-testid]="'af-nav-group-' + slug(item.label)"
              >
                {{ item.label }}
                @if (firstBadgedChild(item); as badged) {
                  <ng-container
                    *ngTemplateOutlet="badgePill; context: { $implicit: badged }"
                  ></ng-container>
                }
                <af-icon name="chevron-down" [size]="16" />
              </button>
              <nz-dropdown-menu #groupMenu="nzDropdownMenu">
                <ul
                  class="list-none m-0 p-1 min-w-[12.5rem] bg-white border border-slate-200"
                  role="menu"
                >
                  @for (child of children; track child.path) {
                    <li role="none">
                      <a
                        role="menuitem"
                        [routerLink]="child.path"
                        routerLinkActive="!text-brand-700 !bg-slate-50"
                        class="flex items-center gap-2.5 w-full px-3 py-2 text-[15px] text-slate-900 no-underline cursor-pointer text-left hover:bg-slate-50"
                        [attr.data-testid]="sectionTestId(child)"
                      >
                        @if (child.icon) {
                          <af-icon [name]="child.icon" [size]="16" />
                        }
                        <span>{{ child.label }}</span>
                        @if (child.badge) {
                          <ng-container
                            *ngTemplateOutlet="badgePill; context: { $implicit: child }"
                          ></ng-container>
                        }
                      </a>
                    </li>
                  }
                </ul>
              </nz-dropdown-menu>
            } @else {
              <a
                [routerLink]="item.path"
                routerLinkActive="!text-slate-900 !border-brand-500"
                class="inline-flex items-center px-3.5 text-[15px] text-slate-600 no-underline border-b-2 border-transparent -mb-px hover:text-slate-900"
                [attr.data-testid]="sectionTestId(item)"
              >
                {{ item.label }}
                @if (item.badge) {
                  <ng-container
                    *ngTemplateOutlet="badgePill; context: { $implicit: item }"
                  ></ng-container>
                }
              </a>
            }
          }
        </nav>
      }

      <span class="flex-1"></span>

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
            <li role="presentation" class="px-3 py-2">
              <af-lang-picker ariaLabel="Language" />
            </li>
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

    <nz-drawer
      [nzVisible]="drawerOpen()"
      nzPlacement="left"
      [nzClosable]="true"
      [nzWidth]="280"
      nzTitle="Navigation"
      (nzOnClose)="closeDrawer()"
    >
      <ng-container *nzDrawerContent>
        @if (!isWide()) {
          <nav aria-label="Primary mobile">
            <ul class="list-none m-0 p-0 flex flex-col gap-1">
              @for (item of items(); track item.label) {
                <li>
                  @if (item.children; as children) {
                    <button
                      type="button"
                      class="flex items-center gap-2.5 w-full py-3 px-2 text-slate-900 bg-transparent border-0 border-l-[3px] border-transparent cursor-pointer text-left"
                      [class.!text-brand-700]="groupActive(children)"
                      [class.!border-brand-500]="groupActive(children)"
                      [attr.aria-expanded]="isGroupOpen(item.label)"
                      [attr.data-testid]="'af-nav-group-' + slug(item.label)"
                      (click)="toggleGroup(item.label)"
                    >
                      @if (item.icon) {
                        <af-icon [name]="item.icon" [size]="18" />
                      }
                      <span class="flex-1">{{ item.label }}</span>
                      @if (firstBadgedChild(item); as badged) {
                        <ng-container
                          *ngTemplateOutlet="badgePill; context: { $implicit: badged }"
                        ></ng-container>
                      }
                      <af-icon name="chevron-down" [size]="16" />
                    </button>
                    @if (isGroupOpen(item.label)) {
                      <ul class="list-none m-0 p-0 flex flex-col gap-1 ml-5">
                        @for (child of children; track child.path) {
                          <li>
                            <a
                              [routerLink]="child.path"
                              routerLinkActive="!border-brand-500 !text-brand-700 !font-medium"
                              class="flex items-center gap-2.5 py-3 px-2 text-slate-900 no-underline border-l-[3px] border-transparent"
                              [attr.data-testid]="sectionTestId(child)"
                              (click)="closeDrawer()"
                            >
                              @if (child.icon) {
                                <af-icon [name]="child.icon" [size]="18" />
                              }
                              <span>{{ child.label }}</span>
                              @if (child.badge) {
                                <ng-container
                                  *ngTemplateOutlet="badgePill; context: { $implicit: child }"
                                ></ng-container>
                              }
                            </a>
                          </li>
                        }
                      </ul>
                    }
                  } @else {
                    <a
                      [routerLink]="item.path"
                      routerLinkActive="!border-brand-500 !text-brand-700 !font-medium"
                      class="flex items-center gap-2.5 py-3 px-2 text-slate-900 no-underline border-l-[3px] border-transparent"
                      [attr.data-testid]="sectionTestId(item)"
                      (click)="closeDrawer()"
                    >
                      @if (item.icon) {
                        <af-icon [name]="item.icon" [size]="18" />
                      }
                      <span>{{ item.label }}</span>
                      @if (item.badge) {
                        <ng-container
                          *ngTemplateOutlet="badgePill; context: { $implicit: item }"
                        ></ng-container>
                      }
                    </a>
                  }
                </li>
              }
            </ul>
          </nav>
        }
        <div class="h-px bg-slate-200 my-3" aria-hidden="true"></div>
        <af-lang-picker ariaLabel="Language" />
      </ng-container>
    </nz-drawer>

    <ng-template #badgePill let-item>
      @if (item.badge(); as count) {
        <span
          class="ml-1.5 inline-flex items-center justify-center min-w-5 h-5 px-1.5 rounded-full bg-brand-500 text-white text-xs font-medium tabular"
          [attr.data-testid]="item.badgeTestId"
          [attr.aria-label]="count + ' ' + item.label"
          >{{ count }}</span
        >
      }
    </ng-template>
  `,
})
export class AfNavBarComponent {
  readonly #viewport = inject(ViewportService);
  readonly #atLeastMd = this.#viewport.isAtLeast('md');
  readonly #router = inject(Router);

  readonly items = input.required<readonly NavItem[]>();
  readonly title = input<string>('AlpenFlight');
  readonly brandHref = input<string>('/');
  readonly user = input<UserSummary | null>(null);

  readonly #drawerOpen = signal(false);
  protected readonly drawerOpen = this.#drawerOpen.asReadonly();
  protected readonly isWide = computed(() => this.#atLeastMd());

  readonly #openGroups = signal<ReadonlySet<string>>(new Set());

  readonly #url = toSignal(
    this.#router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
      startWith(this.#router.url),
    ),
    { initialValue: this.#router.url },
  );

  protected firstBadgedChild(item: NavItem): NavItem | null {
    return item.children?.find((c) => c.badge) ?? null;
  }

  protected groupActive(children: readonly NavItem[]): boolean {
    const url = this.#url();
    return children.some((c) => !!c.path && (url === c.path || url.startsWith(`${c.path}/`)));
  }

  protected sectionTestId(item: NavItem): string {
    return item.testId ?? `af-nav-section-${item.path}`;
  }

  protected slug(label: string): string {
    return label.toLowerCase().replace(/\s+/g, '-');
  }

  protected isGroupOpen(label: string): boolean {
    return this.#openGroups().has(label);
  }
  protected toggleGroup(label: string): void {
    const next = new Set(this.#openGroups());
    if (next.has(label)) {
      next.delete(label);
    } else {
      next.add(label);
    }
    this.#openGroups.set(next);
  }

  protected openDrawer(): void {
    this.#drawerOpen.set(true);
  }
  protected closeDrawer(): void {
    this.#drawerOpen.set(false);
  }
}
