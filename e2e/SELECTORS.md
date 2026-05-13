# e2e Selector Contract

The Playwright suite under `e2e/` does not rely on AngularJS-specific CSS
selectors (the old spinner class, ng-repeat/ng-click attribute selectors,
etc.). Those would not survive the planned UI rewrite. Instead, the suite leans on a
small set of stable `data-testid` markers that the templates under
`flsweb/src/` carry. **These markers are the e2e contract** — when the UI is
rewritten, the equivalent components in the new UI must carry the same
markers, or the suite needs to be updated in lockstep.

## Canonical testids

Each id names a *role*, not an entity, so a single id can cover all 11+
entity lists, or the single auth surface, or all four public forms.

### Lists / spinner (3)

| `data-testid`     | Where it lives                                                                                       | What it identifies                                                                                  |
| ----------------- | ---------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `busy-indicator`  | `flsweb/src/core/directives/busyIndicator/busy-indicator-directive.html` (`.busy-indicator-backdrop`) | The visible-when-busy backdrop wrapping the spinner. `ng-show="busy"` toggles its visibility.        |
| `row`             | Every list/table template (see table below)                                                          | A data row in a list. Headers, filter rows, pager rows do **not** carry this id.                    |
| `row-edit`        | Pencil-link table templates only (see table below)                                                   | The pencil `<a>` link that opens the row's edit form, used where the row itself is not clickable. |

### Auth (6)

The login-form directive (`fls-login-form`) is rendered in **two** places:
inside the navbar on desktop layouts, and inline on `/main` for the mobile
layout. Both share the same template, so any `id="username"` selector matches
two elements. Tests must disambiguate by visibility — e.g. fill the `:visible`
input only.

| `data-testid`      | Where it lives                                                                              | What it identifies                                                                            |
| ------------------ | ------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `login-toggle`     | `flsweb/src/core/directives/navigationBar/navigation-bar-directive.html` (desktop `<a>` only) | The "Login" anchor in the navbar that calls `showLoginForm()` to reveal the desktop login form. |
| `login-form`       | `flsweb/src/core/directives/loginForm/login-form-directive.html` (`<form>`)                | The login form root. The `<form>` has a zero bounding box, so prefer `:visible` on its inputs. |
| `username-input`   | Same template (username `<input>`)                                                          | Username text input.                                                                          |
| `password-input`   | Same template (password `<input>`)                                                          | Password input.                                                                               |
| `login-submit`     | Same template (`<button type="submit">`)                                                    | Submit button.                                                                                |
| `login-error`      | Same template (`.alert.alert-danger`, visible when `loginError` is truthy)                  | Server-returned error message (wrong password, unknown user, locked account, …).              |

### Public-form vocabulary (6)

The four unauthenticated forms (`/trialflight`, `/passengerflight`,
`/lostpassword`, `/confirm`) each carry the same three markers so a test can
locate the form root, submit it, and read its post-submit success state
without depending on translated labels.

| `data-testid`              | Where it lives                                              | What it identifies                                                  |
| -------------------------- | ----------------------------------------------------------- | ------------------------------------------------------------------- |
| `trial-flight-form`        | `flsweb/src/tryflight/tryflight.html` `<form>`              | The `<form>` root of the trial-flight registration form.            |
| `passenger-flight-form`    | `flsweb/src/passengerflight/passengerflight.html` `<form>`  | The `<form>` root of the passenger-flight registration form.        |
| `lostpassword-form`        | `flsweb/src/lostpassword/lostpassword.html` `<form>`        | The `<form>` root of the lost-password request form.                |
| `confirm-email-form`       | `flsweb/src/confirm/confirm-email.html` `<form>`            | The "choose new password" form shown after a valid reset link.      |
| `submit`                   | Inside each public form                                     | The primary submit `<button>` of that form.                         |
| `success-message`          | Inside each public form template                            | The "your request was received" `<div>` (toggled by `ng-show="ctrl.success"`). For `/confirm` the password form itself stands in for a success message — the post-success branch is "the password form is rendered". |

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

### Auth surface

| Template                                                                                       | testids                                                                       |
| ---------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| `flsweb/src/core/directives/navigationBar/navigation-bar-directive.html`                       | `login-toggle`                                                                |
| `flsweb/src/core/directives/loginForm/login-form-directive.html`                               | `login-form`, `username-input`, `password-input`, `login-submit`, `login-error` |

### Public forms

| Template                                                  | testids                                                                                       |
| --------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `flsweb/src/tryflight/tryflight.html`                     | `trial-flight-form`, `submit`, `success-message`                                              |
| `flsweb/src/passengerflight/passengerflight.html`         | `passenger-flight-form`, `submit`, `success-message`                                          |
| `flsweb/src/lostpassword/lostpassword.html`               | `lostpassword-form`, `submit`, `success-message`                                              |
| `flsweb/src/confirm/confirm-email.html`                   | `confirm-email-form`, `submit`                                                                |

### Busy indicator

| Template                                                                | testid                 |
| ----------------------------------------------------------------------- | ---------------------- |
| `flsweb/src/core/directives/busyIndicator/busy-indicator-directive.html` | `busy-indicator`      |

## How the suite consumes them

- `e2e/fixtures.ts` → `waitForBusyIndicatorsToClear` polls for every
  `[data-testid="busy-indicator"]` to have zero bounding-rect dimensions (i.e.
  `ng-show="busy"` has hidden it). Called after every `gotoRoute`.
- `e2e/fixtures.ts` → `loginViaUi(page, username, password)` clicks
  `[data-testid="login-toggle"]` to reveal the desktop login form, then fills
  the `:visible` `username-input` / `password-input` and clicks
  `login-submit`. Used by `auth.spec.ts` and the `uiLoggedInPage` fixture.
- `e2e/tests/03-masterdata.spec.ts` → `dataRowCount` counts
  `tbody [data-testid="row"]`. `openFirstRowForm` prefers
  `[data-testid="row-edit"]` and falls back to the row itself.
- `e2e/tests/auth.spec.ts` → asserts `[data-testid="login-error"]` becomes
  visible after a bad-credentials attempt.
- `e2e/tests/09-public-flows.spec.ts` → fills the four public forms by their
  `[data-testid="<form-name>-form"]` root, submits via `[data-testid="submit"]`,
  asserts `[data-testid="success-message"]` becomes visible.

## Note to the rewrite team

These testids are the **e2e contract**. When you rewrite the templates (or
move to a different framework entirely), please carry the same markers on
the equivalent components in the new UI:

- The new busy indicator's visible-when-busy element gets `data-testid="busy-indicator"`.
- Every data row in every list gets `data-testid="row"`.
- If a row is not itself clickable, the explicit "open this row" control
  (button, link, icon) gets `data-testid="row-edit"`.
- The login surface keeps `login-toggle`, `login-form`, `username-input`,
  `password-input`, `login-submit`, `login-error`.
- Each public form keeps its `<form-name>-form`, `submit`, `success-message`
  triad.

Anything beyond these is fair game to rename, restructure, or drop —
the suite doesn't depend on it. If you find yourself wanting to add more
testids to make a test pass, prefer fixing the test to lean on semantic
selectors (`getByRole`, `getByLabel`, `getByText`) before extending this
contract.
