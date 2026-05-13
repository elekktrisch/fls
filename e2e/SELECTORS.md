# e2e Selector Contract

The Playwright suite under `e2e/` does not rely on AngularJS-specific CSS
selectors (the old spinner class, ng-repeat/ng-click attribute selectors,
etc.). Those would not survive the planned UI rewrite. Instead, the suite leans on a
tiny set of stable `data-testid` markers that the templates under
`flsweb/src/` carry. **These markers are the e2e contract** — when the UI is
rewritten, the equivalent components in the new UI must carry the same
markers, or the suite needs to be updated in lockstep.

## Canonical testids

There are exactly three. Each names a *role*, not an entity, so a single id
covers all 11+ entity lists.

| `data-testid`     | Where it lives                                                                                       | What it identifies                                                                                  |
| ----------------- | ---------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `busy-indicator`  | `flsweb/src/core/directives/busyIndicator/busy-indicator-directive.html` (`.busy-indicator-backdrop`) | The visible-when-busy backdrop wrapping the spinner. `ng-show="busy"` toggles its visibility.        |
| `row`             | Every list/table template (see table below)                                                          | A data row in a list. Headers, filter rows, pager rows do **not** carry this id.                    |
| `row-edit`        | Pencil-link table templates only (see table below)                                                   | The pencil `<a>` link that opens the row's edit form, used where the row itself is not clickable. |

The conventions:

- **Role, not entity.** One `data-testid="row"` covers aircraft rows, flight
  rows, planning-day rows, etc. The e2e helpers don't need to know what entity
  they're looking at — `tbody [data-testid="row"]` works everywhere.
- **No duplication on row-click rows.** Many templates put `ng-click="editXxx(x)"`
  directly on the `<tr>`, which is itself the click target. Those rows get
  `data-testid="row"` only. Don't add `row-edit` there.
- **`row-edit` is only for the pencil-link pattern.** A few legacy templates
  (`flight-types-table.html`, `member-states-table.html`) use
  `<tr ng-repeat-start>` for the row and a separate pencil `<a>` for editing.
  Those `<a>`s carry `data-testid="row-edit"` so the helper can prefer them
  over clicking somewhere on the row.

## Template inventory

### Row-click pattern (`row` only)

These templates put `ng-click="editXxx(x)"` (or equivalent: `showXxxDetails`)
on the `<tr>`. Click the row anywhere to open the form.

| Template                                                                | testids on the row    |
| ----------------------------------------------------------------------- | --------------------- |
| `flsweb/src/masterdata/aircrafts/aircrafts-table.html`                  | `row`                 |
| `flsweb/src/masterdata/persons/persons-table.html`                      | `row`                 |
| `flsweb/src/masterdata/users/users-table.html`                          | `row`                 |
| `flsweb/src/masterdata/clubs/clubs-table.html`                          | `row`                 |
| `flsweb/src/masterdata/locations/locations-table.html`                  | `row`                 |
| `flsweb/src/masterdata/accountingRules/accountingRuleFilters-table.html` | `row`                |
| `flsweb/src/masterdata/deliveries/deliveries-table.html`                | `row`                 |
| `flsweb/src/masterdata/deliveryCreationTests/deliveryCreationTests-table.html` | `row`          |
| `flsweb/src/flights/flights.html`                                       | `row`                 |
| `flsweb/src/flights/airmovements/air-movements.html`                    | `row`                 |
| `flsweb/src/planning/planning.html`                                     | `row`                 |
| `flsweb/src/reservations/reservations-table.html`                       | `row`                 |

### Pencil-link pattern (`row` + `row-edit`)

These templates use `<tr ng-repeat-start>` for the row and a separate pencil
`<a ng-click="editXxx(x)">` to open the form. The row itself is not clickable.

| Template                                                                | testids                |
| ----------------------------------------------------------------------- | ---------------------- |
| `flsweb/src/masterdata/flightTypes/flight-types-table.html`             | `row` + `row-edit`     |
| `flsweb/src/masterdata/memberStates/member-states-table.html`           | `row` + `row-edit`     |

### Busy indicator

| Template                                                                | testid                 |
| ----------------------------------------------------------------------- | ---------------------- |
| `flsweb/src/core/directives/busyIndicator/busy-indicator-directive.html` | `busy-indicator`      |

## How the suite consumes them

- `e2e/fixtures.ts` → `waitForBusyIndicatorsToClear` polls for every
  `[data-testid="busy-indicator"]` to have zero bounding-rect dimensions (i.e.
  `ng-show="busy"` has hidden it). Called after every `gotoRoute`.
- `e2e/tests/03-masterdata.spec.ts` → `dataRowCount` counts
  `tbody [data-testid="row"]`. `openFirstRowForm` prefers
  `[data-testid="row-edit"]` and falls back to the row itself.

## Note to the rewrite team

These three testids are the **e2e contract**. When you rewrite the templates
(or move to a different framework entirely), please carry the same markers on
the equivalent components in the new UI:

- The new busy indicator's visible-when-busy element gets
  `data-testid="busy-indicator"`.
- Every data row in every list gets `data-testid="row"`.
- If a row is not itself clickable, the explicit "open this row" control
  (button, link, icon) gets `data-testid="row-edit"`.

Anything beyond these three is fair game to rename, restructure, or drop —
the suite doesn't depend on it. If you find yourself wanting to add more
testids to make a test pass, prefer fixing the test to lean on semantic
selectors (`getByRole`, `getByLabel`, `getByText`) before extending this
contract.
