import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { NzDrawerModule } from 'ng-zorro-antd/drawer';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzLayoutModule } from 'ng-zorro-antd/layout';
import { NzMenuModule } from 'ng-zorro-antd/menu';

import { ViewportService } from '../../viewport';

export interface NavItem {
  readonly path: string;
  readonly label: string;
  readonly icon?: string;
}

/**
 * Hub-and-spoke nav. Above `md` renders a fixed left rail
 * (`nz-layout-sider` + `nz-menu`). Below `md` collapses to a drawer
 * triggered by a top-bar hamburger.
 */
@Component({
  selector: 'af-nav-bar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NzLayoutModule,
    NzMenuModule,
    NzDrawerModule,
    NzIconModule,
    RouterLink,
    RouterLinkActive,
  ],
  template: `
    @if (isWide()) {
      <nz-sider [nzWidth]="220" nzTheme="light">
        <ul nz-menu nzMode="inline">
          @for (item of items(); track item.path) {
            <li nz-menu-item [routerLink]="item.path" routerLinkActive="ant-menu-item-selected">
              @if (item.icon) {
                <nz-icon [nzType]="item.icon" />
              }
              <span>{{ item.label }}</span>
            </li>
          }
        </ul>
      </nz-sider>
    } @else {
      <header class="af-mobile-bar">
        <button
          type="button"
          class="af-burger"
          [attr.aria-label]="'Open navigation'"
          (click)="openDrawer()"
        >
          <nz-icon nzType="menu" />
        </button>
        <strong>{{ title() }}</strong>
      </header>
      <nz-drawer
        [nzVisible]="drawerOpen()"
        nzPlacement="left"
        [nzClosable]="true"
        (nzOnClose)="closeDrawer()"
      >
        <ng-container *nzDrawerContent>
          <ul nz-menu nzMode="inline">
            @for (item of items(); track item.path) {
              <li nz-menu-item routerLinkActive="ant-menu-item-selected">
                <a [routerLink]="item.path" (click)="closeDrawer()">
                  @if (item.icon) {
                    <nz-icon [nzType]="item.icon" />
                  }
                  <span>{{ item.label }}</span>
                </a>
              </li>
            }
          </ul>
        </ng-container>
      </nz-drawer>
    }
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .af-mobile-bar {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        padding: 0.5rem 1rem;
        border-bottom: 1px solid var(--ant-border-color-base, #d9d9d9);
        background: #fff;
      }
      .af-burger {
        background: none;
        border: 0;
        font-size: 1.25rem;
        min-width: 44px;
        min-height: 44px;
        cursor: pointer;
      }
    `,
  ],
})
export class AfNavBarComponent {
  readonly #viewport = inject(ViewportService);
  readonly #atLeastMd = this.#viewport.isAtLeast('md');

  readonly items = input.required<readonly NavItem[]>();
  readonly title = input<string>('AlpenFlight');

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
