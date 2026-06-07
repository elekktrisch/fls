# J-6b behavior oracle (legacy-oracle, 2026-06-07) — worker input, pruned at §5

Condensed load-bearing findings for the J-6b tasks. Full legacy cites in `flsweb`/`flsserver`.

## Form validation (T-03/T-04/T-05/T-06/T-07)
- **Legacy has NO server-side/async business validation** on reservation or planning save. The only
  real legacy rules are **client-side `required`**: reservation = Date, Reservation-Type, Pilot,
  Aircraft, Location (+ conditional Second-Crew when type `IsInstructorRequired ||
  ObserverPilotOrInstructorRequired || IsPassengerRequired`, or aircraft `NrOfSeats>1`); planning =
  Date, Location (towing/operator/instructor all OPTIONAL).
- Legacy date-picker pattern: `([0-9]{2}\.){2}[0-9]{4}` (DD.MM.YYYY), `required`.
- **Genuinely server-only rules = (a) reservation aircraft-slot overlap, (b) planning-day
  (club,date,location) uniqueness.** Legacy enforces NEITHER (LEGACY-BUG: silent double-booking +
  duplicate days). **AlpenFlight ALREADY enforces both at save** (J-5 conflict-409, J-6
  `ux_pln_club_date_loc`). So the new `…/validate` endpoint **pre-checks those EXISTING constraints
  inline while editing** — surfacing the same 409/unique earlier, NOT a new business rule. No new
  domain logic; reuse the J-5/J-6 conflict checks behind a non-mutating `validate` path.
- `[Required]`/`GuidNotEmptyValidator` on legacy DTOs are DEAD (never fire). Don't model on them.

## Reservations Day/Week calendar (T-08)
- **No legacy parity** — legacy `/reservations` is a flat NgTable list; `/reservation-scheduler` is a
  fixed 365-day horizontal grid, NO toggle, NO paging, NO range label. The AlpenFlight Day/Week
  calendar is **greenfield UX** → gallery is AlpenFlight-only for the calendar (no legacy pairing).
- Legacy per-column header format is `DD.MM.YYYY`. Period-label format is a product call → use
  `DD.MM.YYYY` (single day in day-view, `DD.MM.YYYY – DD.MM.YYYY` range in week-view) for consistency.

## Date format (T-12/T-13)
- Legacy hardcodes `DD.MM.YYYY` everywhere (per-directive string, no global locale switch). Reproduce
  exactly: `af-date-picker` currently sets no `[nzFormat]` (→ ng-zorro locale default); set
  `dd.MM.yyyy` on single + range pickers; align display pipes to `dd.MM.yyyy`.

## Nav (T-11)
- Legacy shows **Clubs to ALL users** (no role gate) and Users **is** club-admin-gated. So hiding
  Clubs for a club-admin is a **NEW operator decision** (not parity) — implement per operator ask
  (make `/clubs` sysadmin-only). `/reservations` has no legacy nav-visibility issue; just add it.

## Users-list 400 (T-15)
- **NOT a role/authz issue** — the legacy users overview is `[Authorize]`-only and a club-admin is
  authorized. The 400 originates in the request shape / paging payload (or the AlpenFlight backend
  query), NOT the role. Diagnose FE param vs BE; escalate only if it needs a contract change.

## Migration note (informational — J-6b ships no new mapper)
- Real legacy data may carry duplicate planning-days + empty-guid FKs (both legacy-accepted) that
  violate next-schema invariants on ingest — already handled by J-6's mapper dedupe; J-6b adds none.
