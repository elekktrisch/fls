//---
title: "Product Brief Addendum: AlpenFlight"
status: draft
created: 2026-08-24
updated: 2026-08-24
---

# Product Brief Addendum: AlpenFlight

This addendum holds the depth that supports [`brief.md`](brief.md) but does not belong in it.
`bmad-prd` and `bmad-architecture` read it. It does not summarise the brief.

---

## 1. Competitive landscape

This section uses published competitor material from 2026-08-24. Nobody tested the products.

### FlyLink — the closest competitor

[flylink.ch](https://flylink.ch/en/). Swiss owned and operated. Hosted in Europe. It markets itself
to glider clubs and general-aviation groups, and states that it suits alpine regions.

**Published features.** A digital logbook with search over times, pilots, aircraft, and landings.
Aircraft reservations in calendar, timeline, and grid views, with drag-and-drop. Tow plane linking
for glider operations. Member management with licence and medical expiry dates. Maintenance and
technical log. Weather (METAR and TAF). Reports and statistics. Higher tiers add digital instructor
signatures, recency tracking, document management, custom reports, API access, and multi-club
federation with cross-club member synchronisation.

**Published prices.**

| Plan | Price | Fleet size |
| --- | --- | --- |
| Starter | Free | 1–2 aircraft |
| Club | CHF 12 per month | 3–6 aircraft |
| Fleet | CHF 9 per month | 7 or more aircraft |
| Federation | On request | Unlimited |

Add-ons: OGN live tracking at CHF 5, Swiss regulator (FOCA) compliance at CHF 3, advanced analytics
at CHF 29.

**Two observations shaped the brief.**

1. Rebuild 1 specified a free tier of one club, two aircraft, and five users. FlyLink's free tier is
   one to two aircraft. Two suppliers reached the same shape on their own. That shape is therefore
   obvious, and price offers no room to compete.
2. FlyLink already sells OGN tracking and Swiss regulator integration. Both appear on rebuild 1's
   list of new features. AlpenFlight follows there. It does not lead.

### Other products found

| Product | Market | Note |
| --- | --- | --- |
| [Vereinsflieger.de](https://vereinsflieger.de/) | Germany | The established club system. Reservations, duty rosters, billing and accounting, fuel, maintenance, defect logs, licence expiry warnings. Billing posts flight fees and membership fees to assigned accounts. |
| [Startkladde](https://startkladde.sourceforge.net/) | Germany | Open source and free. It sets the lowest price for basic flight logging. |
| [Air-Software](https://air-software.eu/vereinsmanagement.html) | Germany | Automatic flight log management. It determines flight fees for billing. |
| [Orgazee](https://orgazee.com/loesungen/flugverein) | Germany | Club management. It allocates finances to members. |
| GliderLog | Austria | Austria only. Glider specific. |
| OpenFlyers, AircraftClubs, FlightCircle, Pilot-Next | International | General flying-club and flight-school software. |

### The open competitive question

Two of AlpenFlight's claimed advantages are unconfirmed against the competitors, and one test
settles both.

**First, the accounting rules.** No competitor's published material states whether a club can define
**rules that consume flight time and produce invoice lines**. They describe billing, fee
determination, and account posting. That language fits a rate card. It also fits a rule engine.

**Second, the speed of the flight form.** Section 2 records what the FLS form does. No screenshot on
a competitor's site settles whether their form matches it.

FLS runs a decrement loop. Rules match against the remaining flight time, consume part of it, emit
an invoice line, and repeat until no rule matches. Each club configures this.

**How to settle both.** Register a free FlyLink account and a Vereinsflieger trial. Then do two
things. Express one real accounting rule from the operator's own club. Log one glider flight with a
tow, and count the clicks and the keystrokes against the same flight in FLS. If a competitor passes both,
the advantage narrows to migration alone, and the brief needs a correction. This test is cheap. Run
it before the PRD locks the positioning.

---

## 2. The flight-form efficiency

The brief names this as the second advantage. This section records what FLS actually does, so the
rewrite reproduces the behaviour and not a screenshot. Every claim below cites legacy code, verified
on 2026-08-24.

**The form hides what the selection makes irrelevant.** The three flight-edit templates carry 88
conditional directives (`ng-if`, `ng-show`, `ng-hide`, `ng-disabled`, `ng-required`) —
`flight-edit-glider-form.html` holds 55 of them on its own. The operator sees the fields for the
flight they are logging, and no others.

**The form creates master data in place.** `FlightsController.js` imports `AddPersonController` and
`AddAircraftController` directly. A missing pilot or a visiting aircraft does not send the operator
to the master-data screens and back. **This matters most at the moment it happens: a busy flying
day, with an unfamiliar aircraft on the field.**

**The form copies from the last flight.** `copyTowingFromLast`, `copyRouteFromLast`, and
`copyLastCounterToStartOperatingCounter` fill the tow aircraft, the outbound and inbound routes, and
the engine counter. Values persist in `localStorage` between sessions. A separate `/copy` route
clones a whole flight.

**The form guesses well when it has nothing to copy.** Start and landing locations fall back through
`lastStartLocation`, then the club's home base.

**Every dropdown searches as the operator types.** The three flight-edit templates configure 21
typeahead pickers over 13 catalogs: persons, glider pilots, tow pilots, instructors, observers,
winch operators, glider aircraft, tow aircraft, locations, start types, glider flight types, tow
flight types, and cost balance types. **This is every picker in the form, not a favoured few.**
Person pickers search first name, last name, and city together, so an operator finds a pilot by the
field they happen to remember.

**The form is ready before the operator searches it.** The controller prefetches all 13 catalogs
into scope before the form opens. The typeahead therefore filters a local list. It does not wait on
the network — which is what makes it usable at an airfield with poor coverage.

**Dates accept the mouse and the keyboard equally.** `fls-date-picker`
(`core/directives/datePicker/DatePickerInputDirective.js`) renders one `<input type="text">` bound to
a Pikaday calendar. The operator types `24.08.2026` against the pattern `([0-9]{2}\.){2}[0-9]{4}`,
or clicks the calendar. Both paths write the same model, and a clear button empties it. **Neither
path is the fallback for the other.**

> **Warning: rebuild 1 decided to drop this, and that decision is now reversed.** Its soft
> preferences read "Native input types over custom controls" and "Legacy uses text inputs with
> format-on-blur — we should not carry that forward". The operator reversed this on 2026-08-24. A
> native `<input type="date">` is slower to type into on desktop, and typing is the airfield hot
> path. `bmad-ux` must not restore the rebuild-1 preference without raising it again.

**What FLS does not have.** No keyboard shortcut handler exists anywhere in `flsweb/src/flights/`.
No `keydown`, no `keypress`, no hotkey binding. **Keyboard completion is a new capability, not a
port.** Rebuild 1 reached the same conclusion and filed it as a target, not as parity.

**Why this belongs in the brief.** A rewrite loses this by accident. Each behaviour above is a small
decision that no feature list records, and a team that ports the field set without porting the
behaviour ships a form that is complete and slower.

**Measure the cost in clicks and keystrokes, not in seconds.** A stopwatch measures the operator's
familiarity and the network. A click count and a keystroke count measure the interface itself. They
are deterministic, they do not need a practised user, and a Playwright test counts both by listening
for mouse and key events. **Record the two counts for one glider flight with a tow on the legacy
form. That pair of numbers is the target the rewrite must not exceed.**

---

## 3. The cost-recovery model

The brief states the conclusion. This section shows the calculation.

**The rule:** monthly income must exceed monthly running cost.

```
clubs_needed  =  monthly_running_cost  /  (price_per_club  ×  (1 − payment_fee))
```

Running cost has these components. The operator supplies the real figures.

| Component | Note |
| --- | --- |
| Application and database hosting | One virtual server is probably enough at this scale |
| Backups and off-site retention | Swiss or European storage, per the data-residency duty |
| Domain and certificates | Small and fixed |
| Error tracking and log storage | Free tiers may cover this at this scale |
| Payment fee | A percentage of income, not a fixed cost |
| Email delivery | Transactional volume is low |

**A worked example.** Every figure below is a placeholder.

- Running cost: CHF 100 per month
- Price per club: CHF 25 per month
- Payment fee: 3 percent

```
clubs_needed = 100 / (25 × 0.97) ≈ 4.1  →  5 clubs
```

**The consequence is the useful part.** Cost recovery needs about five paying clubs, not fifty. That
is reachable. The price decision also matters less than it looks: a doubled price halves the club
count, and both figures stay small. The PRD must not spend effort on price. It must spend effort on
a migration that succeeds.

**What the model excludes.** The operator's time. Any payment for the build. Support effort per
club. **Support effort is the figure most likely to make this model wrong.** If each club costs
several hours a month, the limit becomes the operator's time, not money.

---

## 4. Rebuild-1 constraint inheritance

Rebuild 1 recorded 34 hard constraints in
[`docs/attempt-1/02-vision-and-constraints.md`](../../../../docs/attempt-1/02-vision-and-constraints.md).
`CLAUDE.md` classes that folder as history, not authority. This triage tells the PRD which
constraints the brief decides, and which stay open.

> **Warning: the rebuild-1 constraints name specific technologies.** They name PostgreSQL, Keycloak,
> Flyway, Spring Boot, Angular, and Stripe. `docs/modernization/01-current-state.md` states that
> these are rebuild-1 decisions, and that `bmad-architecture` decides them again. Do not carry them
> into the PRD as given.

### This brief decides these

| Constraint | Verdict |
| --- | --- |
| C10 — port every feature, no deprecations | **Kept.** It is the product promise. |
| C17 — six new features before the first release | **Cut in full.** |
| C19 — per-club branding | **Cut** with C17. |
| C30 — freemium tier shape | **Superseded.** A free tier attracts unknown users, and this product has none. Use one flat price per club and a time-limited trial. |
| C27 — anonymous sandbox with nightly reset | **Doubtful.** It exists to convert unknown users. Weigh its build cost against a demonstration by the operator. |
| C23, C24 — the airfield hot path, and copy-from-last | **Raised.** The brief promotes flight-form efficiency to a stated advantage. These stop being preferences. Section 2 defines what they mean. |

### The PRD owns these

C3 structural tenant isolation. C4 Swiss or European data residency. C5 data-subject rights under
Swiss and European data protection law. C6 migration completes in one self-service session. C7 the
legacy invariants survive: tenant isolation, the flight state machine, the time gates, the user and
person split, the accounting engine, and the two external integrations. C9 database reshape needs a
validated mapping. C11 accounting parity, proven by a test corpus. C12 an audit record on every
change. C13 refresh-token authentication. C14 no legacy password migrates. C16 spreadsheet export is
feature-equivalent, not byte-equivalent. C20 email stays the primary notification channel. C21 and
C22 mobile-first design and a dense desktop variant. C25 multi-tenant service with self-onboarding. C29 the trial expires. C31 subscription
lifecycle states. C32 paid and unpaid clubs get identical isolation and audit guarantees. C33 hosted
checkout, with no card data held.

### `bmad-architecture` decides these

C1 runs on Linux. C2 the backend language. C8 the inbound integration contract. C15 where
translations live. C26 the identity provider and the sign-up federation. C28 the export tool's form.
C34 whether a lifecycle entity groups the clubs.

---

## 5. Alternatives considered and rejected

This section records them so a later reader does not open them again without new evidence.

**Compete directly as the better glider club system.** Rejected. AlpenFlight follows the established
competitors on their strengths — push notifications, waiting lists, regulator integration, and live
tracking. It has no advantage that a club without FLS history notices. That contest needs market
share the arithmetic cannot fund.

**Keep security as the main reason to buy.** Rejected as the main message. The security risk is real
and it stays in the problem statement. It does not differentiate, because FlyLink already offers
Swiss ownership and European hosting. Security explains why a club must leave FLS. It does not
explain why the club chooses AlpenFlight.

**Sell the accounting engine as the product, with gliding as its first market.** Parked. The room
did not reject it. It depends entirely on open question 2 in the brief. If the established
competitors cannot express a rule engine, this opportunity exceeds the migration. Revisit it after
the test in section 1.

**Sell to the national federation instead of to clubs.** Parked. FlyLink prices a federation tier,
which suggests a buyer exists there. One federation agreement beats many club subscriptions, and it
simplifies collection. It stays out of reach until the product exists and one club runs on it.

**Port only the features the first club uses.** Rejected. It reaches a shipped product fastest, and
it breaks the promise that a club's configuration survives. That promise is the product.

**Run rebuild 1 and rebuild 2 at the same time.** The operator ended this on 2026-08-24. Rebuild 2
is the only track. Rebuild 1 closes once this approach proves itself.

---

## 6. Persona depth

The brief names the three personas. This section adds only what the brief omits.

**The club system carrier.** They sometimes inherited the accounting rules from a predecessor who
has left, so they may not know why a rule exists. They answer the questions when a member disputes
an invoice, which is how billing errors reach them. The brief records their true objection: the risk
that billing changes. **Every migration message must answer that objection first.** A message that
leads with security, or with a modern interface, does not reach this person.

**The flight operator.** Interruptions are constant during a flying day. They measure the product by
the number of taps per flight, and by whether the entry survives a lost connection. **They do not
care about any feature in the cut list.** They are the reason offline logging stayed in scope.

**The pilot.** They use the system a few times a month. FLS serves them well today, so the rewrite
risk is low. No design decision needs to optimise for them.

**The operator (Roman).** He builds, hosts, and supports the product alone. His capacity limits
every commitment in the brief. Support effort per club is the figure most likely to break the cost
model. See section 3.

---

## 7. The hard engineering risk

The brief names three risks. `docs/modernization/01-current-state.md` §7 already records the detail
behind them. The PRD must not derive it again.

- **R3, the accounting engine.** A stateful decrement loop over per-club configuration. Two
  properties it depends on appear in no document: the order in which rules apply, and the minimum
  decrement. Both are open behavioural questions (Q-B6, Q-B7). Resolve them with the `legacy-oracle`
  agent before the accounting epic starts.
- **R14, the test suite depth.** The 43-spec suite proves features exist. It does not prove
  behaviour. Validation rejection paths, illegal state transitions, time-gate boundaries, permission
  boundaries, and rule-engine combinations all lack assertions. Nine specs are rebuild-1 artefacts
  that assert lightly. **Nobody can claim parity on this suite as it stands.** Sequence its
  expansion early in the backlog.
- **R1, tenant isolation.** The brief promises structural enforcement. The success criterion is a
  test that fails an unfiltered query. A review convention does not satisfy it.
- **R2, the time gates.** A flight locks after two days and bills three days later. On a fresh
  database nothing reaches those states, so the system looks broken. The unit and the boundary
  appear in no document (Q-B2, Q-B3). The new system needs a way to test the gates without a change
  to the clock.
