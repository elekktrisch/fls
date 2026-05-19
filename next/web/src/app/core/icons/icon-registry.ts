import type { Provider } from '@angular/core';
import {
  LucideCalendar,
  LucideCheck,
  LucideChevronDown,
  LucidePencil,
  LucidePlane,
  LucideSearch,
  LucideTrash2,
  LucideX,
  provideLucideConfig,
  provideLucideIcons,
} from '@lucide/angular';

/*
 * Single registry for Lucide icons consumed via <af-icon name="…">. Add a
 * named import here when a feature needs a new glyph — feature code routes
 * through the atom, never imports from @lucide/angular directly. A future
 * ESLint rule will enforce; until then the convention is the discipline.
 *
 * Global defaults match ADR 0024: 24px, 1.5px stroke, currentColor-driven.
 */
export const ICON_PROVIDERS: Provider[] = [
  provideLucideConfig({
    size: 24,
    strokeWidth: 1.5,
    absoluteStrokeWidth: true,
  }),
  provideLucideIcons(
    LucideCalendar,
    LucideCheck,
    LucideChevronDown,
    LucidePencil,
    LucidePlane,
    LucideSearch,
    LucideTrash2,
    LucideX,
  ),
];
