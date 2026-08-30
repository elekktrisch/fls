---
title: "Product Brief: AlpenFlight"
status: draft
created: 2026-08-24
updated: 2026-08-24
---

# Product Brief: AlpenFlight

Depth, evidence, and the constraint triage are in [`addendum.md`](addendum.md).

## Executive summary

Swiss glider clubs run their flight operations on the Flight Logging System (FLS). FLS works. Its
foundation does not. The server runs on .NET Framework 4.5 and the client runs on AngularJS 1.4.
Both reached end of life years ago. No supplier maintains the system, so no supplier fixes a
security defect in it.

The clubs cannot move to a different product cheaply. Competent competitors exist, and one of them
is Swiss. But a club that changes product must enter years of accounting configuration again, by
hand. The club that most needs to leave FLS finds leaving most expensive.

AlpenFlight removes that cost. It rebuilds FLS, and it transfers the club's data and accounting
rules intact. A club exports its legacy database with one tool, uploads the result, and then runs
its flying days on a maintained system. **AlpenFlight sells the migration first and the operations
software second.**

AlpenFlight has a price that pays for the servers. The price does not pay a salary. Every scope
decision below follows from that.

## The problem

A glider club flies at the weekend when the weather permits. It logs every flight, then bills its
members. The billing is not a flat rate. Each club sets its own rules, which consume flight time and
produce invoice lines. Clubs tuned these rules over many years.

FLS does all of this today. The problem is not what FLS does. The problem is what FLS is:

- **No maintainer.** The toolchain is end of life. No supplier ships a security patch for it.
- **A real breach risk.** The system holds member names, addresses, licence data, and medical
  expiry dates. Cross-origin requests are unrestricted. Access tokens last 14 days and never
  refresh. A breach exposes personal data that the club must protect by law.
- **Tenant isolation by convention only.** Every query must filter by club, but nothing structural
  enforces it. One forgotten filter exposes another club's data.

The club knows the risk and stays anyway, because the move looks more expensive than the risk. If a
club gives up on software, it returns to paper. It then loses the flight history, the billing
accuracy, and the licence tracking with it.

## Who this serves

**The buyer is the committee member who carries the system.** Usually a volunteer. They configured
the charging rules. They cannot justify a migration project, because the benefit is an absent
future problem. **Their true objection is not price. It is the risk that billing changes.**

**The daily user is the duty flight leader at the airfield.** They log flights on a phone, next to the
aircraft, with poor mobile coverage. Speed matters more than features.

**The pilot books an aircraft and reads their own flight history.** FLS serves them well today.

The first club is the supplier's own club, which runs FLS now. Addendum §6 holds the detail.

## The solution

AlpenFlight is a multi-tenant service. It replaces FLS feature for feature, and it adds the one
thing FLS never had: a supported way in.

1. **Export.** The club runs one tool against its legacy database. The tool reads the data and
   encrypts it.
2. **Upload.** A club administrator uploads the result through the browser. The supplier does
   nothing.
3. **Verify.** The club inspects its own data before it commits. Flight counts, member records, and
   charging rules must all match.
4. **Operate.** The club runs its flying days on AlpenFlight. Its rules produce the same invoice
   lines, to the cent.

Each club migrates on its own schedule. There is no coordinated switch-over date.

## What makes this different

| | AlpenFlight | FlyLink and comparable competitors |
| --- | --- | --- |
| Flight logging, reservations, member records | Yes | Yes |
| Swiss ownership, European hosting | Yes | Yes |
| Licence and medical expiry tracking | Yes | Yes |
| Charging rules that each club configures | Yes | **Unconfirmed — see open question 2** |
| A flight form built for speed: it reshapes itself, searches every dropdown, and edits master data in place | Yes | **Unconfirmed — see open question 2** |
| Migration from a legacy FLS database | **Yes** | **No published support** |

**The first advantage is the migration, and it stays because of who can build it.** A competitor
must learn a database of 59 tables, a flight state machine with two dimensions, and an accounting
engine that consumes flight time in a loop. They must learn it to win clubs they do not yet have.

**The second advantage is the speed of the flight form.** FLS logs a flight fast because the form
does the work. It hides the fields the selected aircraft does not need. Every dropdown searches as
the duty flight leader types. Every date accepts a typed value or a click, and neither is the fallback. It
creates a missing person or aircraft in place, so the duty flight leader never leaves the form. It copies the
route, the tow aircraft, and the engine counter from the last flight. It prefetches all of it before
the form opens. **This is not a feature list. It is an interaction
design, and a rewrite loses it by accident.**

**Both advantages have a shape, and this brief will not overstate them.** They protect clubs that
run FLS today. They protect nothing else. A club with no FLS history has no reason to prefer
AlpenFlight.

## What this is not

**AlpenFlight is not an income business.** The arithmetic decides this, not preference.

> **Caution: the club count below is a placeholder.** Nobody has counted the live FLS
> installations. Open question 1 names the real figure. Read every row as an illustration.

| Assumption | Yearly gross income |
| --- | --- |
| 80 clubs at CHF 12 per month (a competitor's price) | CHF 11,520 |
| 80 clubs at CHF 30 per month | CHF 28,800 |
| 25 clubs at CHF 30 per month | CHF 9,000 |

The last row is the planning figure. It does not pay a developer. It pays for infrastructure
comfortably. **The goal is therefore cost recovery.** Income must cover the server, the domain, the
backups, the error tracking, and the payment fee. The supplier's time is a contribution.

Two consequences follow, and both are deliberate:

- **Growth is not a success measure.** Enough clubs to cover the running cost is success.
- **The supplier's time is the scarce resource, not the money.** Reconsider any decision that adds
  recurring manual work.

### The three offerings — superseded 2026-08-29

**This supersedes the earlier text, which said a free tier does not fit and named a time-limited
trial.** `bmad-prd` replaced the trial with a free plan. `bmad-architecture` then added a third
offering. The product now carries three:

| Offering | Runs where | Carries | Costs the supplier |
| --- | --- | --- | --- |
| **Paid SaaS** | The supplier's server | Everything | Hosting, covered by the price |
| **Free plan** | The supplier's server | Everything, limited to one club and two aircraft, with an inactivity lifecycle | Hosting, not covered |
| **Community edition** | The club deploys it itself | Core plus the open modules. No invoicing, no migration, no commercial support | **Nothing** |

The community edition answers the buyer's real objection — *what happens when you stop?* — which no
promise can answer. It also answers Startkladde, the free competitor this brief names below.

**The migration is the paid moat, and that is deliberate.** A club that self-deploys cannot bring
its legacy FLS data, because both the migration and the invoicing are pro modules. The community
edition is therefore the destination for a club that never ran FLS, never the escape route from it.

Licence for the public code: **Apache-2.0**. It permits closed pro modules with no argument, and an
inbound contribution arrives under the same terms.

Detail: PRD §5, and
[`ARCHITECTURE-SPINE.md`](../../architecture/architecture-fls-2026-08-29/ARCHITECTURE-SPINE.md) AD-12.

## Scope

**In scope for the first release.**

- Every feature FLS has today. Faithful behaviour, no deprecations. This is the product promise: a
  club's configuration survives the move.
- The migration path: export, upload, verification, and provisioning. All self-service.
- Charging rule parity, proven against recorded legacy results.
- **Flight-form efficiency parity.** The form must hide irrelevant fields, search every dropdown as
  the duty flight leader types, accept a date by keyboard or by click, create master data in place, copy
  values from the last flight, and prefetch its catalogs. Count the clicks and the keystrokes, not
  the features. Add keyboard completion, which FLS does not have today. Addendum §2 records the
  behaviour and cites the code.
- Structural tenant isolation. A query that omits the club filter must fail.
- Offline flight logging. An alpine airfield has no reliable mobile coverage. This is a condition of
  the environment, not an enhancement.
- Short-lived access tokens with refresh.

**Out of scope for the first release.**

- Push notifications, an in-app message inbox, reservation waiting lists, calendar feed export,
  reservation conflict detection, and per-club branding. Rebuild 1 committed to all six. **This
  brief cuts them.** They compete where competitors already lead, and they use the time the
  migration needs.
- Mobile applications. The web client covers mobile use.
- Changes to the two external integration projects. Each club arranges its own handover.
- Any behaviour change to a ported feature, unless a recorded decision permits it.

## Success criteria

| Measure | Target |
| --- | --- |
| Income against infrastructure cost | Income covers cost every month |
| Accounting parity | Every recorded legacy invoice line reproduces to the cent |
| Migration data loss | Zero. Every flight, member, and rule transfers |
| Self-service migration | A club administrator finishes it without the supplier |
| Cross-tenant exposure | Zero. A test fails an unfiltered query |
| Airfield logging | A duty flight leader logs a flight with no network connection |
| Cost to log a flight | No more clicks and no more keystrokes than FLS needs, for the same glider flight with a tow |

## Open questions and risks

Two facts are open. Neither blocks the PRD. Both change what the PRD says.

1. **How many clubs run legacy FLS today?** This is the real addressable list. The count of Swiss
   glider clubs is a poor substitute. The table above uses 80 as a placeholder. If the real figure
   is small, cost recovery needs a higher price or a wider market.
2. **Do the competitors support charging rules that each club configures?** Their published
   material describes billing and exports. It does not settle the question. If they support such
   rules, the advantage narrows to the migration alone, and this brief must say so. Addendum §1
   names a cheap test.

| Risk | Why it matters |
| --- | --- |
| The accounting engine must reproduce a stateful loop exactly | Clubs configured it over years. A wrong result breaks every invoice. |
| The legacy test suite is not a parity oracle | It proves features exist. It does not prove they behave correctly. |
| One person builds and operates this | Capacity limits every commitment above. The scope cuts respect it. |

Addendum §7 holds the engineering detail behind the first two rows.

## Vision

In three years AlpenFlight runs the flying weekend for the clubs that could not otherwise leave FLS.
It is unremarkable software. It starts, it logs flights, it bills correctly, and it gets security
updates. The club committee stops discussing it.

If the migration proves durable, the same approach extends to motor flight groups, and to clubs in
neighbouring countries on comparable end-of-life systems. The first release creates that option.
This brief does not promise it.
