import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzInputModule } from 'ng-zorro-antd/input';

let nextId = 0;

// A themed wrapper over `nz-input` — the only ng-zorro-antd module this story imports beyond the
// deferred spike's select/date-picker probe. `ng-zorro-antd.dark.css` ships literal colors, not
// CSS variables (see styles.css's bridge comment), so this component overrides `.ant-input`
// against the `--ant-*` bridge itself, the way the ownership rule requires: a raw `ant-*` class
// never leaks outside its own wrapper component.
@Component({
  selector: 'app-search-field',
  imports: [FormsModule, NzInputModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  templateUrl: './search-field.html',
  styles: [
    `
      :host ::ng-deep .ant-input {
        width: 100%;
        height: 32px;
        font-family: var(--font-family-body);
        background-color: var(--ant-color-bg-container);
        border-color: var(--ant-color-border);
        border-radius: var(--ant-border-radius);
        color: var(--ant-color-text);
      }

      :host ::ng-deep .ant-input::placeholder {
        color: var(--ant-color-text-disabled);
      }

      :host ::ng-deep .ant-input:hover {
        border-color: var(--ant-color-border);
      }

      /* ng-zorro-antd.dark.css sets \`outline: 0\` in its own :focus rule, which would otherwise
         suppress the app-wide focus ring (styles.css's :focus-visible). Restated here so this
         control keeps the same ring as every other focusable element. */
      :host ::ng-deep .ant-input:focus {
        border-color: var(--ant-color-primary);
        box-shadow: none;
        outline: 2px solid var(--color-live);
        outline-offset: 1px;
      }
    `,
  ],
})
export class SearchField {
  readonly label = input('Search');
  readonly query = model('');

  protected readonly inputId = `search-field-${nextId++}`;
}
