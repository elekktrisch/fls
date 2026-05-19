import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  isDevMode,
} from '@angular/core';
import { LUCIDE_ICONS, LucideDynamicIcon } from '@lucide/angular';

/*
 * Thin wrapper over Lucide's dynamic-name directive. `name` is a kebab-case
 * string resolved against the registry in core/icons/icon-registry.ts. The
 * underlying directive class is `LucideDynamicIcon`; `LucideIcon` is a
 * type-only export in v1.16.0.
 *
 * Decorative by default (aria-hidden); pass `label` for a standalone
 * informational icon (role="img" + aria-label). When the icon sits inside a
 * labelled control (icon-button etc.), leave `label` unset and let the parent
 * own the accessible name.
 */
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
    // Dev-only guard: an unregistered icon name silently renders an empty SVG,
    // hiding typos. Surface the miss with a path to the fix.
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
