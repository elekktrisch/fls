---
title: "PRD Addendum: AlpenFlight"
status: draft
created: 2026-08-25
updated: 2026-08-25
---

# PRD Addendum: AlpenFlight

This addendum holds the depth that supports [`prd.md`](prd.md) but does not belong in it.
`bmad-architecture`, `bmad-ux`, and `bmad-create-epics-and-stories` read it. It does not summarise
the PRD.

---

## 1. Alternatives considered and rejected

This section records the alternatives, so that a later reader does not open them again without new
evidence. The product brief addendum §5 holds the strategic alternatives. These are the ones this
PRD decided.

### 1.1 Subscription billing in the first release

**Decided 2026-08-25. No billing in v1.**

| Option | Verdict |
| --- | --- |
| No billing at all in v1 | **Chosen.** The first release serves a closed user group. Nobody signs up, so nobody needs a trial that expires, a lifecycle state machine, or a checkout. It removes a whole feature area from a build that one person does. |
| Trial and lifecycle states, no payment provider | Rejected for v1. It builds a state machine that nothing yet drives. |
| Full subscription with hosted checkout | Rejected for v1. The closed user group does not need it, and it consumes the time the migration needs. |

**The condition attached to the choice.** The architecture must still reserve the club lifecycle, so
that the later promotion to a real launch adds states rather than reshapes the club record. Rebuild-1
constraints C29, C31, and C33 come back at that point, unchanged.

### 1.2 The migration failure path

**Decided 2026-08-25. Report, accept with a reason, and keep an open list.**

| Option | Verdict |
| --- | --- |
| Report, accept with a reason, keep the list | **Chosen.** It is self-service, it is honest, and it never needs the supplier. The open list keeps the trail, so a mismatch stays visible instead of disappearing into a migration report. |
| Block the commit until every line reproduces | Rejected. It makes the strongest promise and it forces a support call to the one person who cannot take one. It also strands a club that has one genuinely wrong legacy line. |
| Report and accept, with no list | Rejected. The club loses the trail, and a later billing dispute has no record. |
| Advisory only | Rejected. It weakens the one step that answers the buyer's objection. |

**Why the open list matters more than it looks.** The buyer's objection is the risk that billing
changes. A mismatch that a club accepted and then forgot is exactly that risk, realised quietly. The
list turns it into a known, tracked item. Counter-metric SM-C4 in the PRD guards against the opposite
failure: making acceptance so easy that a club accepts everything.

### 1.3 Feature parity scope

**Decided 2026-08-25. Every feature, no exception, except OGN.**

The facilitator recommended a cut of the three system-administration surfaces — the language
translation CRUD, the system data CRUD, and the system logs — on the argument that a multi-tenant
service owns them differently from a single-tenant one. **The supplier rejected the cut.** C10 stays
literal. FR-14 and FR-15 carry the three surfaces.

### 1.4 The external integrations

**Decided 2026-08-25. The delivery path stays. OGN leaves.**

| Option | Verdict |
| --- | --- |
| Delivery path in v1, OGN deferred | **Chosen.** The delivery path feeds club accounting, which is the product promise. OGN writes to the legacy database with direct SQL, which risk hotspot R9 names as an external writer that bypasses every domain invariant. Deferring it removes the hardest correctness risk from v1. |
| Both, with a proper inbound contract for OGN | Rejected for v1. It needs a negotiation with the maintainer of an independent project, on the supplier's own time, for a capability the closed user group does not need. It is the right answer for v2. |
| Both, with OGN keeping direct database writes | Rejected. It carries R9 into the new system and defeats FR-1. |
| Neither | Rejected. It breaks the accounting promise. |

**A correction to the option wording.** The option offered to the supplier said "the delivery mail
export ports". That is only half of the delivery path. The external Proffix synchroniser does **not**
read the mail export. It polls the deliveries interface — `GET /api/v1/deliveries/*` in the legacy
system, per `docs/modernization/01-current-state.md` §4. Both features stay in v1, and the PRD carries
them as FR-55 (the mail export) and FR-56 (the deliveries interface).

### 1.5 The offline reach

**Decided 2026-08-25. The flight write path plus a read of today.**

The supplier chose the middle option over the smallest one. The consequence is that the client holds
today's flights, the airborne board, and all thirteen catalogs offline. That is more state to keep
correct than the write path alone, and it is what makes the typeahead work at an airfield with no
coverage. A full offline application was rejected: it multiplies the conflict cases across
reservations, member records, and accounting, where no airfield need exists.

### 1.6 The offline conflict rule

**Decided 2026-08-25. Apply when clean, else raise a conflict.** This closes the assumption that the
UX experience spine recorded at Concurrency point 5.

| Option | Verdict |
| --- | --- |
| Apply when clean, else conflict | **Chosen.** It never destroys an entry, and it never blocks another user. |
| Last write wins | Rejected. It silently destroys the other person's entry. |
| Server wins | Rejected. It discards the field entry that offline exists to protect. |
| Per-field merge | Rejected for v1. It gives the best result and it is the hardest to build and to explain. Revisit if the conflict rate proves annoying. |

### 1.7 Data retention

**Decided 2026-08-25. Export at any time, delete on a written request.**

Full self-service data-subject rights were rejected for v1 because the club administrator already
holds the relationship with the member, and because the closed user group is small. The choice
carries one open legal question, recorded as PRD open question 6: whether a flight record survives the
deletion of the person record.

---

## 2. Rebuild-1 constraint disposition, in full

The product brief addendum §4 triaged the 34 rebuild-1 constraints from
[`docs/attempt-1/02-vision-and-constraints.md`](../../../../docs/attempt-1/02-vision-and-constraints.md).
This table closes every constraint the brief assigned to the PRD, and it repeats the brief's own
verdicts for completeness.

> **Warning: the rebuild-1 constraints name specific technologies.** They name PostgreSQL, Keycloak,
> Flyway, Spring Boot, Angular, and Stripe. Do not carry any of them into the architecture as given.
> `bmad-architecture` decides each one again.

| Id | Constraint | Owner | Disposition |
| --- | --- | --- | --- |
| C1 | Runs on Linux | `bmad-architecture` | Open. |
| C2 | The backend language | `bmad-architecture` | Open. |
| C3 | Structural club isolation | PRD | **Kept.** FR-1, SM-4. |
| C4 | Swiss or European data residency | PRD | **Kept.** PRD §8, FR-87. |
| C5 | Data-subject rights | PRD | **Kept, scoped.** FR-84 serves the request through the club administrator. Self-service is deferred. |
| C6 | Migration in one self-service session | PRD | **Kept.** FR-77. |
| C7 | The legacy invariants survive | PRD | **Kept, with one exception.** Club isolation FR-1, the flight state machine FR-27, the time gates FR-30 and FR-32, the user and person split FR-3, the rules engine FR-49. **The OGN integration invariant is broken deliberately.** See §4. |
| C8 | The inbound integration contract | `bmad-architecture` | Open, and smaller now. OGN leaves v1, so only the scheduled-work dispatcher remains. |
| C9 | The database reshape needs a validated mapping | PRD | **Kept.** FR-73 and FR-74 are the validation. |
| C10 | Port every feature, no deprecation | Brief | **Kept literally**, minus OGN. The supplier rejected the proposed cut of the system-administration surfaces. |
| C11 | Accounting parity, proven by a test corpus | PRD | **Kept.** FR-54. |
| C12 | An audit record on every change | PRD | **Kept.** FR-8. |
| C13 | Refresh-token authentication | PRD | **Kept.** FR-4. |
| C14 | No legacy password migrates | PRD | **Kept.** FR-78. |
| C15 | Where the translations live | `bmad-architecture` | Open. FR-14 states the behaviour, not the store. |
| C16 | Spreadsheet export is feature-equivalent | PRD | **Kept.** FR-55, FR-59. It frees the library choice, which risk RK-8 needs. |
| C17 | Six new features before the first release | Brief | **Cut in full.** |
| C18 | *(not triaged in the brief)* | — | Not carried. |
| C19 | Per-club branding | Brief | **Cut** with C17. |
| C20 | Email is the primary notification channel | PRD | **Kept.** PRD §4.10. |
| C21 | Mobile-first design | PRD | **Kept.** PRD §8 and the UX responsive table. |
| C22 | A dense desktop variant | PRD | **Kept.** FR-61 dense mode. |
| C23 | The airfield hot path | Brief | **Raised to a stated advantage.** FR-16 to FR-26. |
| C24 | Copy from the last flight | Brief | **Raised to a stated advantage.** FR-21. |
| C25 | Multi-tenant service with self-onboarding | PRD | **Kept, scoped.** FR-85. Public sign-up waits for the promotion. |
| C26 | The identity provider and the sign-up federation | `bmad-architecture` | Open. |
| C27 | An anonymous sandbox with a nightly reset | Brief | **Cut.** PRD §5. A demonstration by the supplier is cheaper. |
| C28 | The export tool's form | `bmad-architecture` | Open. FR-71 states what it must do, not what it is. |
| C29 | The trial expires | PRD | **Deferred to v2.** PRD §6.2. |
| C30 | The freemium tier shape | Brief | **Superseded.** One flat price per club and a time-limited trial, at the promotion. No free tier ever. |
| C31 | Subscription lifecycle states | PRD | **Deferred to v2.** The architecture reserves the club lifecycle now. |
| C32 | Identical isolation and audit for a paid and an unpaid club | PRD | **Kept.** FR-1, FR-8. Trivially satisfied in v1, because no club pays. |
| C33 | Hosted checkout, no card data held | PRD | **Deferred to v2.** |
| C34 | Whether a lifecycle entity groups the clubs | `bmad-architecture` | Open, and now load-bearing. C31's deferral depends on the architecture reserving this shape. |

---

## 3. The open behavioural questions, mapped to the requirements they block

`docs/modernization/01-current-state.md` §9 records sixteen behavioural questions. Each one is a place
where the legacy system has a rule and no document states it. Answer each with the `legacy-oracle`
agent against `flsserver/` **before the epic that touches it starts**, not before the PRD closes.

| Id | Question | Blocks |
| --- | --- | --- |
| Q-B1 | Which validation failure sets the invalid process state, and which leaves the flight unprocessed? | FR-29 |
| Q-B2 | Is the lock gate counted in calendar days, business days, or hours? | FR-30, FR-31, FR-33 |
| Q-B3 | Is a gate boundary inclusive, and in which timezone is it evaluated? | FR-30, FR-32, FR-33, FR-69 |
| Q-B4 | What happens to a tow flight when its glider flight is deleted? | FR-16, FR-27 |
| Q-B5 | What stops the tow-flight validation recursion on a self-reference or a cycle? | FR-16, FR-29 |
| Q-B6 | When two accounting rules match the same active flight time, which one applies first? | FR-47, FR-49, FR-50 |
| Q-B7 | Can a rule decrement the active flight time by zero, and what stops the loop if it does? | FR-49 |
| Q-B8 | Which club's filter admits a flight whose crew member belongs to a different club? | FR-1, FR-2 |
| Q-B9 | The same question for the recipient of a delivery. | FR-1, FR-2, FR-52 |
| Q-B10 | What validates an OGN row that breaks a state-machine or isolation invariant? | **Not blocking in v1.** OGN is deferred. It returns with the v2 OGN epic. |
| Q-B11 | What resolves a conflict when OGN and a user write the same flight at the same time? | **Not blocking in v1.** Same reason. |
| Q-B12 | What is the default when a mutating endpoint has no cache invalidation? | FR-35 to FR-38 |
| Q-B13 | What should happen on a failed authentication during a write, with no route change? | FR-4, FR-37 |
| Q-B14 | What does a club see before it has any location or aircraft? A planning day is keyed by location and date. | FR-43, FR-85 |
| Q-B15 | What renders when a translation is missing, or the translation service fails? | FR-14 |
| Q-B16 | What rate limit protects the two unauthenticated registration endpoints? | FR-65. **The legacy system has none, so this is a new capability, not a port.** |

**Two questions left the blocking set.** Q-B10 and Q-B11 both concern OGN ingestion, which §6.2 of the
PRD defers. They return unchanged with the v2 OGN epic.

---

## 4. The one broken sacred cow

`docs/modernization/00-seed.md` lists six sacred cows and states: *"If any of these break, the rewrite
is a failed rewrite."* One of them is the OGN integration: *"The new system must accept the same
inbound contract or the OGN side must be replaced too."*

**The supplier broke it deliberately on 2026-08-25.** This section records the break, so that no later
reader treats it as an oversight.

**What breaks.** AlpenFlight publishes no OGN ingestion path in the first release. The legacy aircraft
database synchronisation job also leaves v1.

**Why.** The OGN analyser writes to the legacy database with direct SQL. It therefore bypasses every
domain invariant that FR-1 exists to enforce: club isolation, the flight state machine, and
validation. Risk hotspot R9 names this. Accepting the same contract in the new system would defeat the
product's largest correctness commitment. Building a proper inbound interface instead needs a
negotiation with the maintainer of an independent project, on the time of one person, for a capability
that the closed user group does not need.

**The consequence, stated plainly.** A club that depends on OGN ingestion today cannot move fully onto
AlpenFlight in the first release. It keeps its legacy system for that path, or its flight operator logs
those flights by hand. **The whole flight-form design already serves manual logging well, which is why
this cost is acceptable and not a hole.**

**What must happen at v2.** Reopen Q-B10 and Q-B11, agree an inbound interface with the OGN analyser
maintainer, and restore the aircraft database synchronisation. Rebuild-1 constraint C8 covers the
contract shape.

**The other five sacred cows survive.** Club isolation is strengthened from convention to structure.
The flight state machine, the user and person split, the rules engine, and the delivery path all port
unchanged.

---

## 5. What the PRD deliberately left out

**No traceability matrix.** Requirements reference journeys inline. A matrix would need maintenance
that one person cannot fund, and `bmad-create-epics-and-stories` reads the requirement numbers
directly.

**No technology anywhere.** Every requirement states a capability. The store, the language, the
framework, the identity provider, the job runner, and the spreadsheet library are all
`bmad-architecture` decisions. Rebuild 1 answered them, and `CLAUDE.md` classes those answers as
history.

**No screen specification.** The UX experience spine and design spine own the component behaviour, the
tokens, and the responsive rules. The PRD points at them and states only the requirements that a
screen must satisfy.

**No price.** The brief's cost model shows that about five paying clubs cover the running cost, and
that a doubled price only halves an already small number. The price decision matters less than it
looks. §6.2 of the PRD defers billing entirely, so the first release needs no price at all.

**No support process.** Support effort per club is the figure most likely to break the cost model, and
SM-7 measures it. The response is a product that needs no support, not a documented support process.
Every migration path, including the failure path, is self-service for exactly this reason.

---

## 6. Vocabulary decisions that downstream documents must keep

ASD-STE100 rule 4 requires one word for one meaning. Two terms in the upstream documents broke it, and
this PRD fixed both. Every downstream document must keep the fix.

| Term | Fix |
| --- | --- |
| **operator** | The brief uses it for two different people. The PRD splits them: **flight operator** is the person at the airfield who logs the flying day; **supplier** is the person who builds, hosts, and supports AlpenFlight. Never write a bare "operator". |
| **hold** and **lock** | These are different mechanisms and they never interchange. A **hold** is the concurrency claim on the flight edit form; it expires, and a person can take it over. A **lock** is the process state the time gate sets; it makes the flight read-only. |
| **draft** | Do not use it for a part-entered flight. A part-entered flight is an **open flight**: a server record the whole club sees. The UX run corrected this on 2026-08-25 and the PRD carries the correction. |
| **check**, **guard**, **gate** | Use **time gate** only for the elapsed-time rule that moves a flight forward. Do not write "check" or "guard" for it. |
