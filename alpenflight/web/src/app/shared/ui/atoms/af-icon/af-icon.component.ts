import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  isDevMode,
} from '@angular/core';
import { LUCIDE_ICONS, LucideDynamicIcon } from '@lucide/angular';

@Component({
  selector: 'af-icon',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideDynamicIcon],
  template: `
    @if (label()) {
      <svg
        [lucideIcon]="name()"
        [size]="size()"
        [strokeWidth]="strokeWidth()"
        role="img"
        [attr.aria-label]="label()"
      ></svg>
    } @else {
      <svg
        [lucideIcon]="name()"
        [size]="size()"
        [strokeWidth]="strokeWidth()"
        aria-hidden="true"
      ></svg>
    }
  `,
})
export class AfIconComponent {
  readonly #icons = inject(LUCIDE_ICONS);

  readonly name = input.required<string>();
  readonly size = input<number>(24);
  readonly strokeWidth = input<number>(1.5);
  readonly label = input<string | undefined>(undefined);

  constructor() {
    if (!isDevMode()) return;
    effect(() => {
      const n = this.name();
      if (!(n in this.#icons)) {
        console.error(
          `[af-icon] "${n}" is not registered. Add a named Lucide import to alpenflight/web/src/app/core/icons/icon-registry.ts.`,
        );
      }
    });
  }
}
