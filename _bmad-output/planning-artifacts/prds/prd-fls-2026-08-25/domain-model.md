---
title: "AlpenFlight — Domain Model and Glossary"
status: draft
created: 2026-08-29
updated: 2026-08-29
---

# AlpenFlight — Domain Model and Glossary

The ubiquitous language for AlpenFlight. Target state, plus the mapping needed to read the legacy
system. Every document, screen, and code identifier uses these terms. A synonym is a defect.

## 1. Naming rules

1. One word, one meaning. §2 is the list of permitted words.
2. `Status` for a lookup, `State` for a state machine. A flight has an air state and a process state.
   A club, a user, an aircraft, and a membership have a status.
3. No abbreviation in an identifier. Write `Landing`, not `Ldg`. Keep only what aviation uses as a
   word: `ICAO`, `MTOM`, `FLARM`, `TMG`, `METAR`.
4. Use the English aviation term. Write `registration`, not `immatriculation`.
5. Name a relationship for what it means, not for the table. A person linked to a club is a
   `ClubMembership`.
6. `Launch`, `Takeoff`, and `BlockStart` are three different things. See §2.3.
7. Name a field for its meaning, not its storage. `SortOrder`, not `SortIndicator`.

## 2. Glossary

### 2.1 Tenancy and people

| Term | Identifier | Definition |
| --- | --- | --- |
| Club | `Club` | The tenant. Every user belongs to exactly one. Every domain record carries the club it belongs to. |
| Person | `Person` | A human record: name, address, licences, medical expiry. Belongs to any number of clubs. Exists with no user. |
| User | `User` | A login principal. Belongs to exactly one club, links to at most one person. Never merged with a person. |
| Club membership | `ClubMembership` | The link between a person and a club. Carries the member number, the membership status, and the roles the person holds in that club. |
| Membership status | `MembershipStatus` | Club-defined: active member, passive member, junior. |
| Person category | `PersonCategory` | A club-defined grouping of persons, in a hierarchy. Charging rules match on it. |
| Role | `Role` | What a user may do in the application. Distinct from a club role, which lives on the club membership. |
| Duty flight leader | `DutyFlightLeader` | The person in charge of gliding operations for the day. Logs the flights. |
| Tow pilot | `TowPilot` | The pilot of the tug. |
| Winch driver | `WinchDriver` | The person who operates the winch. |
| Instructor | `Instructor` | A flight instructor. |
| Supervising pilot | `SupervisingPilot` | The pilot or instructor who supervises a flight without being the instructor of record. |
| Supplier | — | The person who builds, hosts, and supports AlpenFlight. Never a code term. Never called "operator". |

### 2.2 Aircraft and airfields

| Term | Identifier | Definition |
| --- | --- | --- |
| Aircraft | `Aircraft` | One airframe: glider, tug, powered aircraft, or motor glider. |
| Registration | `Registration` | The national registration marking, such as `HB-3215`. |
| Competition ID | `CompetitionId` | The short competition marking on a glider's fin. |
| Aircraft category | `AircraftCategory` | Glider, glider with engine, motor glider, powered aircraft, multi-engine, jet, helicopter. |
| Aircraft status | `AircraftStatus` | Serviceable, information, attention, malfunction, maintenance, uninsured, withdrawn. Each says whether the aircraft may fly. |
| Operating counter | `OperatingCounter` | A recorded meter reading: flight time, engine time, launch totals. |
| Counter unit | `CounterUnit` | Minutes, or hours to two decimals. |
| Airfield | `Airfield` | A place an aircraft takes off from or lands at. |
| Location | `Location` | The wider record an airfield is one kind of. Also waypoints, mountain tops, and outlanding fields. Every airfield is a location; not every location is an airfield. |
| Home airfield | `HomeAirfield` | The club's base, and the default airfield of an aircraft. |
| Route point | `RoutePoint` | A named reporting point for arrival or departure. |
| Outbound route | `OutboundRoute` | The departure route the flight used. |
| Inbound route | `InboundRoute` | The arrival route the flight used. |

### 2.3 The flight

| Term | Identifier | Definition |
| --- | --- | --- |
| Launch | `Launch` | How the aircraft got airborne. A launch fee is charged for it. |
| Takeoff | `Takeoff` | The moment the wheels leave the ground. |
| Block start | `BlockStart` | The moment the flight begins for billing. Stamped. |
| Launch method | `LaunchMethod` | Aerotow, winch launch, self-launch, external launch, powered takeoff. |
| Flight | `Flight` | One record covering a glider flight, a tow flight, or a powered flight. |
| Flight category | `FlightCategory` | Glider flight, tow flight, or powered flight. The discriminator on the flight record. |
| Flight type | `FlightType` | The club-defined purpose: training dual, training solo, check flight, passenger flight, trial flight. Carries a code the charging rules match on. |
| Tow flight | `TowFlight` | The flight of the tug that launched a glider. A flight record in its own right, linked from the glider flight. |
| Crew assignment | `CrewAssignment` | One person in one role on one flight, with the times they held it. |
| Crew role | `CrewRole` | Pilot, co-pilot, instructor, passenger, winch driver, supervising pilot, invoice recipient. |
| Landing count | `LandingCount` | The number of landings the flight made. |
| Air state | `AirState` | The physical state, computed from the timestamps. Never the stored authority. |
| Process state | `ProcessState` | The stored administrative state. Decides billing eligibility. |
| Open flight | — | A flight on the server that is not complete. The whole club sees it. Not a private draft. |
| Stamp | `Stamp` | An atomic write of one time field. One press, no confirmation. |
| Hold | `Hold` | The claim one user takes on the flight edit form. Expires, and can be taken over. Not a lock. |
| Lock | — | The process state the time gate sets. A locked flight is read-only. Not a hold. |
| Time gate | `TimeGate` | The elapsed-time rule that moves a flight to the next process state. Two exist: the lock gate and the billing gate. |
| Aircraft movement | `AircraftMovement` | One takeoff or one landing, counted for airfield statistics. |

### 2.4 Licences

| Term | Identifier | Definition |
| --- | --- | --- |
| Licence | `Licence` | A pilot licence. Always spelled `Licence`. |
| Rating | `Rating` | A qualification carried on a licence, such as the passenger rating. |
| Endorsement | `Endorsement` | A clearance for a launch method: aerotow, winch, or self-launch. |
| Medical | `Medical` | A medical certificate with an expiry date. Class 1, Class 2, or LAPL. |
| Handicap index | `HandicapIndex` | The performance handicap of a glider. |
| Part-M approval | `PartMApproval` | A continuing-airworthiness approval. Not a licence. |

### 2.5 Planning

| Term | Identifier | Definition |
| --- | --- | --- |
| Reservation | `Reservation` | A pilot's claim on an aircraft for a timeslot at an airfield. |
| Reservation type | `ReservationType` | The club-defined kind, including maintenance. |
| Roster day | `RosterDay` | One flying day at one airfield, with the people assigned to its duties. |
| Duty assignment | `DutyAssignment` | One person assigned to one duty on one roster day. |
| Duty type | `DutyType` | The club-defined duty, and how many people it needs. |
| Season assignment | `SeasonAssignment` | The batch action that fills a duty across a date range in one pass. |

### 2.6 Charging and invoicing

| Term | Identifier | Definition |
| --- | --- | --- |
| Charging rule | `ChargingRule` | A club-configured rule: a WHEN condition, a THEN result, an engine-stop flag. |
| Charging rule kind | `ChargingRuleKind` | Which engine phase the rule runs in. The kind decides the run order. See §3.4. |
| Rules engine | `RulesEngine` | Consumes a flight's active flight time with the charging rules and emits invoice lines. |
| Active flight time | `ActiveFlightTime` | The flight time the engine consumes. Each rule that applies takes part of it. |
| Charging unit | `ChargingUnit` | Per minute, per second, per landing, or per launch or flight. |
| Invoice draft | `InvoiceDraft` | The billing document the engine produces for one recipient. |
| Invoice line | `InvoiceLine` | One line inside an invoice draft: article, text, quantity, unit, discount. |
| Article | `Article` | The accounting product code a line is booked against. |
| Launch fee | `LaunchFee` | The fee charged for getting airborne. |
| Landing fee | `LandingFee` | The fee charged for a landing. |
| Association fee | `AssociationFee` | The levy the Swiss gliding association charges per flight. |
| Cost split | `CostSplit` | How a flight's cost divides: pilot pays all, split equally, tow pilot pays own, no instructor fee, named person pays. |
| Flight time credit | `FlightTimeCredit` | Prepaid or discounted flight time held against a person. |
| Billing expectation | `BillingExpectation` | One real flight, the invoice the club expects it to produce, and the tolerance flags saying which fields may differ. |
| Re-baseline | `Rebaseline` | Replacing a billing expectation with the engine's current output, after a person decided the change was intended. |
| Dry run | `DryRun` | A replay of the engine against one real flight, showing every rule that fires, what it consumes, and what it emits. Changes nothing. |

### 2.7 Platform

| Term | Identifier | Definition |
| --- | --- | --- |
| Master data | `MasterData` | The club-configured lists the rest of the product uses. |
| Catalog | `Catalog` | A list a picker searches. The flight form prefetches thirteen. |
| Record status | `RecordStatus` | Inactive, active, confirmation needed, deleted, system. |
| Stub record | `IsStubRecord` | A record created in place from the flight form, holding only the fields that moment needed. |
| Scheduled work | `ScheduledWork` | A batch job an external timer starts. |
| Audit record | `AuditRecord` | Who changed which record, when, and what changed. |
| Migration | `Migration` | The transfer of one club into AlpenFlight: export, upload, verify, commit. |
| Mismatch | `Mismatch` | A verified invoice line that does not reproduce to the cent during a migration. |
| Record strip | — | The only list treatment. The product has no data table. |

## 3. Domain model

### 3.1 Aggregates

`Club` is the tenant boundary. Everything sits inside exactly one club, except `Person`, which is
shared.

| Aggregate | Holds | Invariants |
| --- | --- | --- |
| `Club` | Settings, defaults, extensions, email targets | The isolation boundary. Every query names it. |
| `Person` | Licences, ratings, endorsements, medicals, contact details | Not owned by a club. Reaches clubs through `ClubMembership`. Exists with no user. |
| `ClubMembership` | Member number, status, club roles, notification preferences | One person, one club, one membership. The member number is unique inside the club. |
| `Aircraft` | Status periods, operating counters, home airfield | The registration identifies it. Owned by a club or a person. |
| `Flight` | Crew assignments, times, counters, routes | See §3.2. |
| `Reservation` | Aircraft, pilot, second crew, timeslot, airfield | |
| `RosterDay` | Duty assignments | Identified by airfield and date. |
| `ChargingRule` | WHEN condition, THEN result, engine-stop flag | Ordered by kind. See §3.4. |
| `InvoiceDraft` | Invoice lines, recipient snapshot | Holds a copy of the recipient's name and address, so a later change to the person does not rewrite history. |
| `Location` | Route points, runway data | Shared across clubs. |

### 3.2 The flight

```
Flight
├── AircraftId                    → Aircraft
├── FlightCategory                  glider · tow · powered   (the discriminator)
├── FlightTypeId                  → FlightType     (club-defined purpose)
├── LaunchMethodId                → LaunchMethod   (aerotow · winch · self · external · powered)
├── TowFlightId                   → Flight         self-reference; delete rule open (Q-B4)
├── TakeoffAirfieldId             → Location
├── LandingAirfieldId             → Location
├── CostSplitId                   → CostSplit
├── AirState                        computed from the times, never the stored authority
├── ProcessState                    stored, decides billing eligibility
└── CrewAssignment[]              → Person + CrewRole + held-from / held-to times
                                     a crew person may belong to another club (Q-B8)
```

Four time pairs, and they are not the same thing.

| Pair | Meaning | Written by |
| --- | --- | --- |
| `TakeoffTime` / `LandingTime` | The physical flight | Stamped, or typed |
| `BlockStart` / `BlockEnd` | The billed period | Stamped with NOW |
| `EngineCounterAtTakeoff` / `EngineCounterAtLanding` | The engine meter | Copied from the last flight, then corrected |
| `CrewAssignment.HeldFrom` / `HeldTo` | When a person held a role | On a crew change |

```
AirState   (computed)    New → FlightPlanOpen → TakeoffPresumed → Started
                             → LandingPresumed → Landed → FlightPlanClosed

ProcessState (stored)    NotProcessed ─┬→ Invalid                    (Q-B1)
                                       └→ Valid
                                            ↓  lock gate             (Q-B2, Q-B3)
                                          Locked
                                            ↓  billing gate
                                          InvoiceDrafted ─→ InvoiceBooked
                                            └→ InvoiceDraftFailed
                                          ExcludedFromInvoicing
```

### 3.3 User, person, and club

```
User ──(0..1)──→ Person ──(0..n)──→ ClubMembership ──(1)──→ Club
 │                                                            ▲
 └────────────────────(1)─────────────────────────────────────┘
```

- A user belongs to exactly one club and links to at most one person.
- A person belongs to any number of clubs, each through a club membership.
- Licences live on the person. Club roles live on the membership. A person holds one glider licence,
  and can be an instructor at one club and an ordinary member at another.
- A person exists with no user. A visiting pilot needs no login.

Collapsing user and person breaks the pilot roster at a site where members fly for more than one
club.

### 3.4 The rules engine

Four engines run in a fixed sequence:

1. Ignore — does this flight get invoiced at all?
2. Recipient — who receives the invoice?
3. Invoice lines — which lines, at what amounts? The decrement loop.
4. Draft details — the header text.

Engine 3 runs nine phases in a fixed order. The kind of a rule decides its phase.

| # | Phase | Charging rule kind | Consumes active flight time |
| --- | --- | --- | --- |
| 1 | No landing fee | `NoLandingFee` | no |
| 2 | Aircraft flight time | `FlightTime` | yes |
| 3 | Aircraft engine time | `EngineTime` | yes |
| 4 | Instructor fee | `InstructorFee` | no |
| 5 | Tow flight aircraft time | `FlightTime` on the tow flight | yes |
| 6 | Extra fuel fee | `ExtraFuelFee` | no |
| 7 | Launch fee | `LaunchFee` | no |
| 8 | Landing fee | `LandingFee`, master flight then tow flight | no |
| 9 | Association fee | `AssociationFee`, master flight then tow flight | no |

The active flight time is set from the flight duration before phase 2. Phases 2, 3, and 5 decrement
it.

Order within one phase is open. See Q-B6.

### 3.5 Charging rule conditions

A charging rule holds nine matching fields. Each pairs with a flag that inverts the match
(`UseRuleForAll…ExceptListed`).

`MatchedAircraft`, `MatchedLaunchMethods`, `MatchedFlightTypeCodes`, `MatchedTakeoffAirfields`,
`MatchedLandingAirfields`, `MatchedClubMemberNumbers`, `MatchedCrewRoles`,
`MatchedAircraftHomeAirfields`, `MatchedMembershipStatuses`, `MatchedPersonCategories`.

In the legacy system every one of these is a delimited string of business keys — registrations,
member numbers, flight-type codes — not a foreign key. `bmad-architecture` decides whether they stay
strings or become relations. Three consequences either way:

1. A rule that references an aircraft by registration stops matching when the registration changes.
2. The migration must carry the values intact, because FR-49 parity depends on them matching exactly
   what they matched before.
3. Nothing stops another club's member number entering the list. It is a second path past the club
   boundary, alongside Q-B8 and Q-B9.

## 4. Legacy to target mapping

For reading the legacy system and for writing the migration. 38 of 56 entities keep their name.

### 4.1 Entities that keep their name

`Club`, `Person`, `User`, `Role`, `UserRole`, `Aircraft`, `Location`, `Country`, `Flight`,
`FlightType`, `Article`, `EmailTemplate`, `Language`, `Setting`, `Extension`, `ExtensionType`,
`ExtensionValue`, `ClubExtension`, `SystemData`, `SystemLog`, `SystemVersion`, `PersonCategory`.

Shortened, with the qualifier implied by the relation:

| Legacy | Target |
| --- | --- |
| `AircraftOperatingCounter` | `OperatingCounter` |
| `AircraftReservation` | `Reservation` |
| `AircraftReservationType` | `ReservationType` |
| `CounterUnitType` | `CounterUnit` |
| `ElevationUnitType` | `ElevationUnit` |
| `LengthUnitType` | `LengthUnit` |
| `LanguageTranslation` | `Translation` |
| `PersonFlightTimeCredit` | `FlightTimeCredit` |
| `PersonFlightTimeCreditTransaction` | `FlightTimeCreditTransaction` |
| `PersonPersonCategory` | `PersonCategoryAssignment` |
| `InOutboundPoint` | `RoutePoint` |
| `AircraftAircraftState` | `AircraftStatusPeriod` |

### 4.2 Entities that rename

| Legacy | Target | Reason |
| --- | --- | --- |
| `AccountingRuleFilter` | `ChargingRule` | It is a rule, not a filter. |
| `AccountingRuleFilterType` | `ChargingRuleKind` | |
| `AccountingUnitType` | `ChargingUnit` | |
| `Delivery` | `InvoiceDraft` | It is a billing document. *(Open question 4.)* |
| `DeliveryItem` | `InvoiceLine` | |
| `DeliveryCreationTest` | `BillingExpectation` | It holds an expected invoice. It creates nothing. |
| `FlightCostBalanceType` | `CostSplit` | It is a split, not a balance. |
| `FlightCrew` | `CrewAssignment` | One person in one role, not the whole crew. |
| `FlightCrewType` | `CrewRole` | The values are roles. |
| `FlightAirState` | `AirState` | |
| `FlightProcessState` | `ProcessState` | |
| `StartType` | `LaunchMethod` | Naming rule 6. |
| `MemberState` | `MembershipStatus` | Naming rule 2. |
| `ClubState` | `ClubStatus` | Naming rule 2. |
| `UserAccountState` | `AccountStatus` | Naming rule 2. |
| `AircraftState` | `AircraftStatus` | Naming rule 2. |
| `AircraftType` | `AircraftCategory` | Collides with three other things. See §4.5. |
| `PersonClub` | `ClubMembership` | Naming rule 5. |
| `PlanningDay` | `RosterDay` | The job is a duty roster. |
| `PlanningDayAssignment` | `DutyAssignment` | |
| `PlanningDayAssignmentType` | `DutyType` | |

### 4.3 Field renames

Only where the legacy name is wrong or ambiguous. Everything else keeps its name.

| Legacy | Target |
| --- | --- |
| `Flight.StartDateTime` | `TakeoffTime` |
| `Flight.LdgDateTime` | `LandingTime` |
| `Flight.StartLocationId` / `LdgLocationId` | `TakeoffAirfieldId` / `LandingAirfieldId` |
| `Flight.StartRunway` / `LdgRunway` | `TakeoffRunway` / `LandingRunway` |
| `Flight.NrOfLdgs` | `LandingCount` |
| `Flight.NrOfLdgsOnStartLocation` | `LandingCountAtTakeoffAirfield` |
| `Flight.NoStartTimeInformation` / `NoLdgTimeInformation` | `TakeoffTimeUnknown` / `LandingTimeUnknown` |
| `Flight.StartTypeId` | `LaunchMethodId` |
| `Flight.StartPosition` | `LaunchQueuePosition` *(meaning unconfirmed — open question 11)* |
| `Flight.FlightAircraftType` | `FlightCategory` |
| `Flight.EngineStartOperatingCounterInSeconds` | `EngineCounterAtTakeoff` |
| `Flight.EngineEndOperatingCounterInSeconds` | `EngineCounterAtLanding` |
| `Aircraft.Immatriculation` | `Registration` |
| `Aircraft.CompetitionSign` | `CompetitionId` |
| `Aircraft.DaecIndex` | `HandicapIndex` |
| `Aircraft.HomebaseId` | `HomeAirfieldId` |
| `Aircraft.IsTowingstartAllowed` | `IsAerotowAllowed` |
| `Aircraft.IsWinchstartAllowed` | `IsWinchLaunchAllowed` |
| `Aircraft.MTOM` | `MaximumTakeoffMass` |
| `Person.HasGliderPAXLicence` | `HasPassengerRating` |
| `Person.HasPartMLicence` | `HasPartMApproval` |
| `Person.Has*StartPermission` | `Has*LaunchEndorsement` |
| `Person.IsFastEntryRecord` | `IsStubRecord` |
| `Person.Lastname` / `Firstname` / `Midname` | `LastName` / `FirstName` / `MiddleName` |
| `Club.Clubname` | `ClubName` |
| `*.SortIndicator` | `SortOrder` |
| `AccountingRuleFilter.RuleFilterName` | `ChargingRule.RuleName` |
| `AccountingRuleFilter.NoLandingTaxFor*` | `ChargingRule.NoLandingFeeFor*` |
| `Delivery.DeliveredOn` | `InvoiceDraft.IssuedOn` |
| `DeliveryItem.Position` | `InvoiceLine.LineNumber` |
| `DeliveryItem.ItemText` | `InvoiceLine.LineText` |

### 4.4 Enum members

| Legacy | Target |
| --- | --- |
| `AircraftStartType.TowingByAircraft` | `LaunchMethod.Aerotow` |
| `AircraftStartType.WinchLaunch` | `LaunchMethod.WinchLaunch` |
| `AircraftStartType.SelfStart` | `LaunchMethod.SelfLaunch` |
| `AircraftStartType.ExternalStart` | `LaunchMethod.ExternalLaunch` |
| `AircraftStartType.MotorFlightStart` | `LaunchMethod.PoweredTakeoff` |
| `AccountingUnitType.Min` / `Sec` / `Ldgs` / `StartOrFlight` | `ChargingUnit.PerMinute` / `PerSecond` / `PerLanding` / `PerLaunchOrFlight` |
| `…StartTaxAccountingRuleFilter` | `ChargingRuleKind.LaunchFee` |
| `…LandingTaxAccountingRuleFilter` | `ChargingRuleKind.LandingFee` |
| `…NoLandingTaxAccountingRuleFilter` | `ChargingRuleKind.NoLandingFee` |
| `…VsfFeeAccountingRuleFilter` | `ChargingRuleKind.AssociationFee` |
| `…DoNotInvoiceFlightRuleFilter` | `ChargingRuleKind.DoNotInvoice` |
| `…RecipientAccountingRuleFilter` | `ChargingRuleKind.Recipient` |
| `…FlightTime` / `EngineTime` / `InstructorFee` / `AdditionalFuelFee` | `ChargingRuleKind.FlightTime` / `EngineTime` / `InstructorFee` / `ExtraFuelFee` |
| `FlightCrewType.PilotOrStudent` | `CrewRole.Pilot` *(open question 12)* |
| `FlightCrewType.Observer` | `CrewRole.SupervisingPilot` |
| `FlightCrewType.FlightCostInvoiceRecipient` | `CrewRole.InvoiceRecipient` |
| `FlightCostBalanceType.PilotPaysAllCosts` | `CostSplit.PilotPaysAll` |
| `FlightCostBalanceType.HalfHalfPayment` | `CostSplit.SplitEqually` |
| `FlightCostBalanceType.TowPilotTakesHisCosts` | `CostSplit.TowPilotPaysOwn` |
| `FlightCostBalanceType.CostsPaidByPerson` | `CostSplit.NamedPersonPays` |
| `AircraftType.MotorAircraft` | `AircraftCategory.PoweredAircraft` *(open question 13)* |
| `AircraftType.MotorGlider` | `AircraftCategory.MotorGlider` — unchanged |
| `FlightCategory.MotorFlight` | `FlightCategory.PoweredFlight` |
| `FlightAirState.MightBeStarted` | `AirState.TakeoffPresumed` |
| `FlightAirState.MightBeLandedOrInAir` | `AirState.LandingPresumed` |
| `FlightProcessState.DeliveryPrepared` / `DeliveryBooked` / `DeliveryPreparationError` | `ProcessState.InvoiceDrafted` / `InvoiceBooked` / `InvoiceDraftFailed` |
| `AircraftStateKey.OK` | `AircraftStatus.Serviceable` |
| `AircraftStateKey.EndOfLife` | `AircraftStatus.Withdrawn` |
| `EntityRecordState` | `RecordStatus` |

### 4.5 Duplicates in the legacy that collapse to one definition

| Legacy | Target |
| --- | --- |
| `AircraftType` entity, `AircraftType` enum | One `AircraftCategory` |
| `Flight.FlightAircraftType`, `FlightAircraftTypeValue`, `FlightCategory` | One `FlightCategory` |
| `LocationType` (17 members), `AirfieldLocationType` (6 members, same ids, different names) | One `LocationType` |
| `StartType` entity, `AircraftStartType` enum | One `LaunchMethod` |

### 4.6 Defects not to carry forward

| Where | Defect |
| --- | --- |
| `AirfieldLocationType.GrasRunway` | Misspelled. `GrassRunway`. |
| `LocationType.CoolTower` | Misspelled. `CoolingTower`. |
| `translations.json` key `AIRBORN` | Misspelled. |
| `translations.json` key `INVOICE_RECEIPIENT` | Misspelled. |
| `DaecIndex` in code, `DEAC_INDEX` in the interface | Two spellings of one thing. |
| `Licence` and `License` | Both spellings appear. Use `Licence`. |
| `AccountingRuleFilterFactory.cs:27-30` | Four real club member numbers with real first names, hardcoded in a public repository. |
| `flsweb/src/index.js:50` | A tautology makes the navigation bar show on the public pages. See FR-64. |

## 5. Open naming questions

Numbering continues [`prd.md`](prd.md) §12.

| # | Question | Owner |
| --- | --- | --- |
| 2 | Rename "flight operator" to "duty flight leader"? Applied here and in the PRD. Not yet in `EXPERIENCE.md` or `DESIGN.md`. | Supplier |
| 4 | Rename `Delivery` to `InvoiceDraft` in the domain, keeping `/deliveries` on the wire? | Supplier, then `bmad-architecture` |
| 11 | What is `Flight.StartPosition`? Read here as the launch queue position. | `legacy-oracle` |
| 12 | Is `CrewRole.PilotOrStudent` one role or two? The flight type already carries the training case. | `legacy-oracle` |
| 13 | "Powered" or "motor"? `MotorAircraft` → `PoweredAircraft` is correct English aviation and touches many identifiers. The German interface keeps *Motorflug* either way. | Supplier |
| 14 | Which German words does the interface use for the renamed terms? | Supplier, at translation time |
