import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { LucideDynamicIcon } from '@lucide/angular';

/*
 * Wrap @lucide/angular's dynamic-name directive so feature code stays decoupled
 * from the underlying package. Name is a kebab-case string resolved against
 * the registry in core/icons/icon-registry.ts (ADR 0024).
 *
 * Note: the Lucide directive class is `LucideDynamicIcon`, not `LucideIcon` —
 * `LucideIcon` is a type-only export (one of the bound icon component types).
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
  readonly name = input.required<string>();
  readonly size = input<number>(24);
  readonly strokeWidth = input<number>(1.5);
  readonly label = input<string | null>(null);
}
