# AlpenFlight design reference (ADR 0024 Option A)

Vendored from a Claude Design handoff bundle dated 2026-05-21. This is the
**canonical pixel-level reference** for the look-and-feel choices ADR 0024
landed on (Swiss-precision + aerospace-pragmatic). When implementing a
new feature screen, find the closest prototype here and match its visual
output — type scale, spacing, button shape, table density, status pills,
empty-state copy.

The prototype is a React / vanilla-CSS sketch; production translates the
same visual choices into Angular + Tailwind v4 `@theme` + ng-zorro
(per ADRs 0004 / 0023 / 0024). Token names match (e.g. `--color-accent`,
`--radius-md`, `--motion-fast`, `--row-h`) so the production stylesheet
can be diff'd against `tokens.css` here as the source of truth.

## What's in here

- `AlpenFlight.html` — entry shell (React + ES modules via Babel-in-browser).
- `tokens.css` — semantic + raw design tokens (the API). 149 LOC.
- `app.css` — component styles (`.af-topbar`, `.af-page`, `.af-btn`,
  `.af-table`, `.af-card`, etc.). 563 LOC. Author-code only; no antd overrides.
- `shell.jsx` — top-bar + nav-drawer + page chrome.
- `screens-home.jsx` — dashboard / next-flight / quick-actions.
- `screens-logbook.jsx` — flight log list (mobile card + desktop table).
- `screens-entry.jsx` — flight log entry form (the "Flight Log Entry Form"
  chat-transcript was specifically about this screen).
- `screens-reservations.jsx` — aircraft reservations calendar + form.
- `screens-public.jsx` — landing + trial-flight / passenger-flight public flows.
- `screens-misc.jsx` — empty / loading / error states; common patterns.
- `icons.jsx` — Lucide-style line icons (the kit `<af-icon>` atom maps to).
- `data.jsx` — fixture data used by the prototypes.
- `app.jsx` — router + tweaks panel wiring (prototype-only; not authoritative).
- `screenshots/` — six representative renders (home, logbook desktop +
  mobile, card view, landing).

## How to use

1. Open `AlpenFlight.html` locally in a browser to interact with the prototype.
2. Compare `tokens.css` against `alpenflight/web/src/styles.css` — any
   semantic-token name in the prototype that's missing in production is a
   gap to close (boyscout into the next story PR per [ADR 0022](../adrs/0022-modernization-primary-directives.md)
   directive 1).
3. For a new feature screen, find the closest prototype, screenshot the
   relevant region, and match it. The prototype is the visual oracle.

## Provenance

Source: Claude Design handoff bundle
(`https://api.anthropic.com/v1/design/h/xn-LN4tflWLiymnt6-d5Xw`),
extracted 2026-05-22. Vendored because the source URL is an ephemeral
artifact (the bundle UUID is one-shot — not a stable docs endpoint).

The chat transcript that produced this bundle (`alpenflight/chats/chat1.md`
in the source) was about the flight-log-entry form specifically, but the
prototypes cover the broader app shell and other primary screens too.
