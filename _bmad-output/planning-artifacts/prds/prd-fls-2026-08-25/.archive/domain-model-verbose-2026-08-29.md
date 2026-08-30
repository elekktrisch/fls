---
title: "AlpenFlight — Domain Model and Glossary"
status: draft
created: 2026-08-29
updated: 2026-08-29
---

# AlpenFlight — Domain Model and Glossary

This document holds the ubiquitous language for AlpenFlight. [`prd.md`](prd.md) §3 points here
instead of carrying its own glossary.

**Every document, every screen, and every code identifier uses the target term in §3.** A synonym is
a defect. If you need a word this document does not hold, add it here first.

## 1. How this document was built

The legacy Flight Logging System was written by German-speaking authors, and **German is the source
language**. The English identifiers in `flsserver/` are translations of German aviation and
accounting terms. Most are good. Some are false friends, some are Germanisms, and a few are the
wrong aviation term.

Evidence used, all verified on 2026-08-29:

| Source | What it gave |
| --- | --- |
| `flsserver/src/FLS.Server.Data/DbEntities/` | 56 entity classes and every field name |
| `flsserver/src/FLS.Data.WebApi/**/*.cs`, `FLS.Server.Data/Enums/` | 21 enums and their members |
| `flsweb/server/mock-data/translations.json` | **The German source text for 330 interface keys** |
| `flsserver/database/FLSTest/3 insert/` | The seeded domain values, in German |
| `flsserver/src/FLS.Server.Service/Accounting/` | The rules engine and its execution order |

**The German column in §4 is the evidence.** Where the German and the English disagree, the German
wins, because the German is what the club actually says.

## 2. Naming rules

Apply these to every new identifier, every screen label, and every document.

1. **One word, one meaning.** ASD-STE100 rule 4. The glossary in §3 is the list of permitted words.
2. **`Status` for a lookup, `State` for a state machine.** A flight has an *air state* and a
   *process state*, because both are state machines. A club, a user, an aircraft, and a membership
   have a *status*, because those are lookup lists.
3. **No abbreviation in an identifier.** Write `Landing`, not `Ldg`. Write `Number`, not `Nr`.
   Keep only the abbreviations that aviation itself uses as words: `ICAO`, `MTOM`, `FLARM`, `TMG`,
   `METAR`.
4. **Use the English aviation term, not the translated German one.** The reader is a pilot. Write
   `registration`, not `immatriculation`.
5. **A junction table is not a domain concept.** Name the relationship for what it means. A row that
   links a person to a club is a *club membership*, not a `PersonClub`.
6. **`Takeoff`, `Launch`, and `Start` are three different things.** See §3.3. The legacy system uses
   `Start` for all three, and that single collision causes most of the confusion in the flight
   domain.
7. **Never name a field for its storage.** `SortIndicator` describes a column. `SortOrder` describes
   a meaning.

---

## 3. The ubiquitous language

*Target term — the word to write in prose. Identifier — the word to write in code. Where the two
differ, the identifier is the code-safe form of the same concept.*

### 3.1 Tenancy and people

| Target term | Identifier | Definition |
| --- | --- | --- |
| **Club** | `Club` | The tenant. Every user belongs to exactly one club. Every domain record carries the club it belongs to. German: *Verein*. |
| **Person** | `Person` | A human record: name, address, licences, medical expiry. A person can belong to more than one club, and a person can exist with no user. |
| **User** | `User` | A login principal. A user belongs to exactly one club and links to at most one person. A user is not a person, and the two never merge. |
| **Club membership** | `ClubMembership` | The link between a person and a club. It carries the member number, the membership status, and the roles the person holds **in that club**. |
| **Membership status** | `MembershipStatus` | The club-defined status of a membership: active member, passive member, junior, and so on. Club-configured. |
| **Person category** | `PersonCategory` | A club-defined grouping of persons, in a hierarchy. The charging rules match on it. |
| **Role** | `Role` | What a user may do in the application. Distinct from a club role, which lives on the club membership. |
| **Duty flight leader** | `DutyFlightLeader` | The person in charge of gliding operations for the day. They log the flights. German: *Segelflugleiter*. **The PRD currently calls this the "flight operator" — see §7.1.** |
| **Tow pilot** | `TowPilot` | The pilot of the tug. German: *Schlepppilot*. |
| **Winch driver** | `WinchDriver` | The person who operates the winch. German: *Windenführer*. |
| **Instructor** | `Instructor` | A flight instructor. German: *Fluglehrer*. |
| **Supervising pilot** | `SupervisingPilot` | The pilot or instructor who supervises a flight without being the instructor of record. German: *Überwachender Pilot*. **The legacy calls this an "observer", which is a false friend — see §5.** |
| **Supplier** | — | The person who builds, hosts, and supports AlpenFlight. Never a code term. Never called "operator". |

### 3.2 Aircraft and airfields

| Target term | Identifier | Definition |
| --- | --- | --- |
| **Aircraft** | `Aircraft` | One airframe. Glider, tug, powered aircraft, or motor glider. |
| **Registration** | `Registration` | The national registration marking, such as `HB-3215`. German: *Immatrikulation*. |
| **Competition ID** | `CompetitionId` | The short competition marking painted on a glider's fin. |
| **Aircraft category** | `AircraftCategory` | Glider, glider with engine, motor glider, powered aircraft, multi-engine, jet, helicopter. |
| **Aircraft status** | `AircraftStatus` | Serviceability: serviceable, information, attention, malfunction, maintenance, uninsured, withdrawn. Each status says whether the aircraft may fly. |
| **Operating counter** | `OperatingCounter` | The recorded meter reading of an aircraft: flight time, engine time, and launch totals. |
| **Counter unit** | `CounterUnit` | How a counter reads: minutes, or hours to two decimals. |
| **Airfield** | `Airfield` | A place an aircraft takes off from or lands at. German: *Flugplatz*. |
| **Location** | `Location` | The wider record that an airfield is one kind of. It also covers waypoints, mountain tops, and outlanding fields. **Keep both words: every airfield is a location, and not every location is an airfield.** |
| **Home airfield** | `HomeAirfield` | The club's base, and the default airfield of an aircraft. German: *Club Flugplatz*. |
| **Route point** | `RoutePoint` | A named reporting point for arrival at or departure from an airfield. German: *Inbound/Outbound*. |
| **Outbound route** | `OutboundRoute` | The departure route the flight used. |
| **Inbound route** | `InboundRoute` | The arrival route the flight used. |

### 3.3 The flight — and the three words the legacy confuses

**Read this before you name anything in the flight domain.** The legacy system writes `Start` for
three separate ideas, and every downstream confusion begins there.

| Target term | Identifier | Definition |
| --- | --- | --- |
| **Launch** | `Launch` | **How the aircraft got airborne.** Aerotow, winch, self-launch. German: *Start* in *Startart*. A launch fee is charged for this. |
| **Takeoff** | `Takeoff` | **The moment the wheels leave the ground.** German: *Start* in *Startzeit*. |
| **Block start** | `BlockStart` | **The moment the flight begins for billing**, which the duty flight leader stamps. German: *Beginn Blockzeit*. |
| **Launch method** | `LaunchMethod` | Aerotow, winch launch, self-launch, external launch, or powered takeoff. Club-selectable per flight. German: *Startart*. |
| **Flight** | `Flight` | One record covering a glider flight, a tow flight, or a powered flight. |
| **Flight category** | `FlightCategory` | Glider flight, tow flight, or powered flight. It is the discriminator on the flight record. |
| **Flight type** | `FlightType` | The club-defined purpose of a flight: training dual, training solo, check flight, passenger flight, trial flight. It carries a code the charging rules match on. |
| **Tow flight** | `TowFlight` | The flight of the tug that launched a glider. It is a flight record in its own right, linked from the glider flight. |
| **Crew assignment** | `CrewAssignment` | One person in one role on one flight, with the times they held it. |
| **Crew role** | `CrewRole` | Pilot or student, co-pilot, instructor, passenger, winch driver, supervising pilot, or invoice recipient. |
| **Landing count** | `LandingCount` | The number of landings the flight made. |
| **Air state** | `AirState` | The physical state of a flight, computed from its timestamps. Never stored as the authority. |
| **Process state** | `ProcessState` | The stored administrative state of a flight. It decides billing eligibility. |
| **Open flight** | — | A flight that exists on the server and is not complete. The whole club sees it. It is not a private draft. |
| **Stamp** | `Stamp` | An atomic write of one time field. One press, no confirmation. |
| **Hold** | `Hold` | The claim one user takes on the flight edit form. It expires and can be taken over. **A hold is not a lock.** |
| **Lock** | — | The process state that the time gate sets. A locked flight is read-only. **A lock is not a hold.** |
| **Time gate** | `TimeGate` | The elapsed-time rule that moves a flight to the next process state. Two exist: the lock gate and the billing gate. |
| **Aircraft movement** | `AircraftMovement` | One takeoff or one landing, counted for airfield statistics. German: *Luftbewegung*. |

### 3.4 Licences and endorsements

| Target term | Identifier | Definition |
| --- | --- | --- |
| **Licence** | `Licence` | A pilot licence. Spell it `Licence` everywhere; the legacy mixes `Licence` and `License`. |
| **Rating** | `Rating` | A qualification carried on a licence, such as the passenger rating. |
| **Endorsement** | `Endorsement` | A club or authority clearance for a launch method: aerotow, winch, or self-launch. German: *Zulassung*. **The legacy calls this a "permission".** |
| **Medical** | `Medical` | A medical certificate with an expiry date. Class 1, Class 2, or LAPL. |
| **Handicap index** | `HandicapIndex` | The performance handicap of a glider. German: *DAeC-Index*. **The legacy spells it `DaecIndex` in code and `DEAC_INDEX` in the interface — both refer to the DAeC index.** |
| **Part-M approval** | `PartMApproval` | A continuing-airworthiness approval. It is not a licence. |

### 3.5 Planning and reservations

| Target term | Identifier | Definition |
| --- | --- | --- |
| **Reservation** | `Reservation` | A pilot's claim on an aircraft for a timeslot at an airfield. |
| **Reservation type** | `ReservationType` | The club-defined kind of reservation, including maintenance. |
| **Roster day** | `RosterDay` | One flying day at one airfield, with the people assigned to its duties. German: *Planungstag*. |
| **Duty assignment** | `DutyAssignment` | One person assigned to one duty on one roster day. German: *Einteilung*. |
| **Duty type** | `DutyType` | The club-defined duty a person can be assigned to, and how many people it needs. |
| **Season assignment** | `SeasonAssignment` | The batch action that fills a duty across a date range in one pass. **New in AlpenFlight.** |

### 3.6 Charging and invoicing

> **Caution: this is the sacred cow. Read §6 before you rename anything in this area.** The external
> accounting synchroniser reads the wire names.

| Target term | Identifier | Definition |
| --- | --- | --- |
| **Charging rule** | `ChargingRule` | A club-configured rule with a WHEN condition, a THEN result, and an engine-stop flag. German: *Verrechnungsregel*. **The legacy calls it an "accounting rule filter"; the German holds no word for "filter".** |
| **Charging rule kind** | `ChargingRuleKind` | Which phase of the engine the rule runs in: do-not-invoice, recipient, no-landing-fee, flight time, instructor fee, extra fuel fee, launch fee, landing fee, association fee, engine time. **The kind decides the run order — see §6.** |
| **Rules engine** | `RulesEngine` | The process that consumes a flight's active flight time with the charging rules and emits invoice lines. |
| **Active flight time** | `ActiveFlightTime` | The flight time the rules engine consumes. Each rule that applies takes part of it. |
| **Charging unit** | `ChargingUnit` | What a rule charges by: per minute, per second, per landing, or per launch or flight. |
| **Invoice draft** | `InvoiceDraft` | The billing document the rules engine produces for one recipient. **The legacy calls it a "delivery" — see §7.2, which is an open decision, not a settled rename.** |
| **Invoice line** | `InvoiceLine` | One line inside an invoice draft: article, text, quantity, unit, discount. German: *Buchungszeile*. |
| **Article** | `Article` | The accounting product code a line is booked against. German: *Artikel*. Standard in Swiss accounting. |
| **Launch fee** | `LaunchFee` | The fee charged for getting airborne. German: *Starttaxe*. **The legacy calls this a "start tax"; *Taxe* means fee, not tax.** |
| **Landing fee** | `LandingFee` | The fee charged for a landing. German: *Landetaxe*. Same false friend. |
| **Association fee** | `AssociationFee` | The levy the Swiss gliding association charges per flight. The legacy calls it the VSF fee. |
| **Cost split** | `CostSplit` | How the cost of a flight divides between the people on board: pilot pays all, half and half, tow pilot takes their own, no instructor fee, or a named person pays. German: *Kostenverteilung*. **The legacy calls this a "flight cost balance type".** |
| **Flight time credit** | `FlightTimeCredit` | Prepaid or discounted flight time held against a person. |
| **Billing expectation** | `BillingExpectation` | One real flight, plus the invoice the club expects it to produce, plus the tolerance flags that say which fields may differ. Running it compares the current engine output against the expectation and reports pass or fail. **The club re-baselines it on purpose when it changes a rule — see §7.4.** The legacy calls it a "delivery creation test". |
| **Re-baseline** | `Rebaseline` | The action that replaces a billing expectation with the engine's current output, after a person decided the change was intended. |
| **Dry run** | `DryRun` | A replay of the rules engine against one real flight that shows every rule that fires, what it consumes, and what it emits. It changes nothing. |

### 3.7 Platform

| Target term | Identifier | Definition |
| --- | --- | --- |
| **Master data** | `MasterData` | The club-configured lists the rest of the product uses. German: *Stammdaten*. |
| **Catalog** | `Catalog` | A list that a picker searches. The flight form prefetches thirteen of them. |
| **Record status** | `RecordStatus` | The lifecycle of any record: inactive, active, confirmation needed, deleted, system. |
| **Stub record** | `IsStubRecord` | A record created in place from the flight form, with only the fields that moment needed. It waits for somebody to complete it. **The legacy calls this a "fast entry record".** |
| **Scheduled work** | `ScheduledWork` | A batch job an external timer starts. The legacy calls it a workflow. |
| **Audit record** | `AuditRecord` | Who changed which record, when, and what changed. |
| **Migration** | `Migration` | The transfer of one club from the legacy system into AlpenFlight: export, upload, verify, commit. |
| **Mismatch** | `Mismatch` | A verified invoice line that does not reproduce to the cent during a migration. |
| **Record strip** | — | The only list treatment in the product. There is no data table anywhere. |

---

## 4. Legacy to target mapping

All 56 legacy entity classes. **Verdict `keep`** means the legacy name carries forward unchanged.

*Counts: 38 keep, 18 rename. That is 68 percent kept, close to the 70 percent you estimated.*

### 4.1 Entities that keep their name

| Legacy entity | Target | German source | Note |
| --- | --- | --- | --- |
| `Club` | `Club` | *Verein* | Field `Clubname` → `ClubName` (casing). |
| `Person` | `Person` | *Person* | Fields `Lastname`/`Firstname`/`Midname` → `LastName`/`FirstName`/`MiddleName`. |
| `User` | `User` | *Benutzer* | |
| `Role`, `UserRole` | `Role`, `UserRole` | *Rolle* | |
| `Aircraft` | `Aircraft` | *Flugzeug* | Several fields rename. See §4.3. |
| `AircraftOperatingCounter` | `OperatingCounter` | *Zählerstand* | Shortened; the aircraft is implied by the relation. |
| `Location` | `Location` | *Flugplatz* | The German label is narrower than the entity. See §3.2. |
| `Country` | `Country` | *Land* | |
| `Flight` | `Flight` | *Flug* | Many fields rename. See §4.3. |
| `FlightType` | `FlightType` | *Flugtyp* | |
| `Article` | `Article` | *Artikel* | |
| `AircraftReservation` | `Reservation` | *Reservation* | Shortened; `Aircraft` is implied. |
| `AircraftReservationType` | `ReservationType` | *Reservationstyp* | |
| `EmailTemplate` | `EmailTemplate` | — | |
| `Language`, `LanguageTranslation` | `Language`, `Translation` | *Sprache* | |
| `Setting` | `Setting` | *Einstellung* | |
| `Extension`, `ExtensionType`, `ExtensionValue`, `ClubExtension` | unchanged | — | The per-club extension mechanism. |
| `SystemData`, `SystemLog`, `SystemVersion` | unchanged | *System* | |
| `CounterUnitType` | `CounterUnit` | *Zähler Einheit* | `Type` suffix dropped. |
| `ElevationUnitType`, `LengthUnitType` | `ElevationUnit`, `LengthUnit` | *Höheneinheit*, *Längeneinheit* | Same. |
| `PersonCategory`, `PersonPersonCategory` | `PersonCategory`, `PersonCategoryAssignment` | *Kategorie* | |
| `InOutboundPoint` | `RoutePoint` | *Inbound/Outbound* | Reads as a word. |
| `PersonFlightTimeCredit` | `FlightTimeCredit` | — | |
| `PersonFlightTimeCreditTransaction` | `FlightTimeCreditTransaction` | — | |
| `DeliveryItem` | `InvoiceLine` | *Buchungszeile* | Follows the `Delivery` decision in §7.2. |
| `AircraftAircraftState` | `AircraftStatusPeriod` | — | It is a dated status record, not a junction. |

### 4.2 Entities that rename

| Legacy entity | Target | German source | Why it changes |
| --- | --- | --- | --- |
| `AccountingRuleFilter` | `ChargingRule` | *Verrechnungsregel* | **The German holds no word for "filter".** A charging rule is a rule, not a filter. |
| `AccountingRuleFilterType` | `ChargingRuleKind` | *Regel-Typ* | Follows. `Kind` avoids the `Type` suffix pile-up. |
| `AccountingUnitType` | `ChargingUnit` | — | It states what a rule charges by. |
| `Delivery` | `InvoiceDraft` | *Rechnung* | **Open decision — see §7.2.** The interface label is *Rechnungs-Informationen*, which means invoice information. |
| `DeliveryCreationTest` | `BillingExpectation` | — | It holds an expected invoice and compares against it. It does not create a delivery, and **it is not a parity test** — see §7.4. |
| `FlightCostBalanceType` | `CostSplit` | *Kostenverteilung* | *Verteilung* is a split, not a balance. |
| `FlightCrew` | `CrewAssignment` | *Besatzung* | It is one person in one role, not the whole crew. |
| `FlightCrewType` | `CrewRole` | — | The values are roles: pilot, instructor, passenger. |
| `FlightAirState` | `AirState` | — | `Flight` is implied by the relation. |
| `FlightProcessState` | `ProcessState` | — | Same. |
| `StartType` | `LaunchMethod` | *Startart* | **The core naming fix.** See §3.3. |
| `MemberState` | `MembershipStatus` | *Mitgliederstatus* | It is a lookup, not a state machine. Rule 2. |
| `ClubState` | `ClubStatus` | — | Same. |
| `UserAccountState` | `AccountStatus` | *Benutzerkonto Status* | Same. The German already says *Status*. |
| `AircraftState` | `AircraftStatus` | — | Same. |
| `AircraftType` | `AircraftCategory` | *Flugzeug Typ* | **It collides with three other things.** See §5.4. |
| `PersonClub` | `ClubMembership` | *Mitgliedschaft* | A junction name leaking into the domain. Rule 5. |
| `PlanningDay` | `RosterDay` | *Planungstag* | The job is a duty roster. *Einteilung* confirms it. |
| `PlanningDayAssignment` | `DutyAssignment` | *Einteilung* | |
| `PlanningDayAssignmentType` | `DutyType` | — | |

### 4.3 Field renames that matter

Only the fields where the legacy name is wrong or ambiguous. Everything not listed keeps its name.

| Legacy field | Target | Why |
| --- | --- | --- |
| `Flight.StartDateTime` | `TakeoffTime` | German *Startzeit* means takeoff time. |
| `Flight.LdgDateTime` | `LandingTime` | Abbreviation. Rule 3. |
| `Flight.StartLocationId` / `LdgLocationId` | `TakeoffAirfieldId` / `LandingAirfieldId` | Both. |
| `Flight.StartRunway` / `LdgRunway` | `TakeoffRunway` / `LandingRunway` | Both. |
| `Flight.NrOfLdgs` | `LandingCount` | Abbreviation. |
| `Flight.NrOfLdgsOnStartLocation` | `LandingCountAtTakeoffAirfield` | Both. |
| `Flight.NoStartTimeInformation` | `TakeoffTimeUnknown` | States the fact, not the absence of a field. |
| `Flight.NoLdgTimeInformation` | `LandingTimeUnknown` | Same. |
| `Flight.StartTypeId` | `LaunchMethodId` | §3.3. |
| `Flight.StartPosition` | `LaunchQueuePosition` | `[ASSUMPTION]` — confirm the meaning. See §8. |
| `Flight.FlightAircraftType` | `FlightCategory` | It is the flight discriminator, not an aircraft type. §5.4. |
| `Flight.EngineStartOperatingCounterInSeconds` | `EngineCounterAtTakeoff` | German *Beginn Motorzählerstand*. |
| `Flight.EngineEndOperatingCounterInSeconds` | `EngineCounterAtLanding` | Same. |
| `Aircraft.Immatriculation` | `Registration` | Germanism. |
| `Aircraft.CompetitionSign` | `CompetitionId` | *Zeichen* is a marking, and English gliding says competition ID. |
| `Aircraft.DaecIndex` | `HandicapIndex` | It is the handicap. It also fixes the `DaecIndex` / `DEAC_INDEX` spelling split. |
| `Aircraft.HomebaseId` | `HomeAirfieldId` | Two words, and it names the thing. |
| `Aircraft.IsTowingstartAllowed` | `IsAerotowAllowed` | Casing, and the correct launch word. |
| `Aircraft.IsWinchstartAllowed` | `IsWinchLaunchAllowed` | Same. |
| `Aircraft.MTOM` | `MaximumTakeoffMass` | Spell it out; keep `MTOM` as the screen label. |
| `Person.HasGliderPAXLicence` | `HasPassengerRating` | It is a rating, and `PAX` is an abbreviation. |
| `Person.HasPartMLicence` | `HasPartMApproval` | Part-M is an approval, not a licence. |
| `Person.Has*StartPermission` | `Has*LaunchEndorsement` | *Zulassung* is an endorsement. |
| `Person.IsFastEntryRecord` | `IsStubRecord` | Names what it is, not how it was typed. |
| `*.SortIndicator` | `SortOrder` | Rule 7. |
| `AccountingRuleFilter.RuleFilterName` | `RuleName` | Follows the entity rename. |
| `AccountingRuleFilter.Matched*` | `Matched*` | **Keep the names, but see §5.5 — every one of them is a delimited string, not a relation.** |
| `AccountingRuleFilter.NoLandingTaxFor*` | `NoLandingFeeFor*` | False friend. |
| `Delivery.DeliveredOn` | `IssuedOn` | An invoice is issued, not delivered. |
| `DeliveryItem.Position` | `LineNumber` | `Position` is overloaded. |
| `DeliveryItem.ItemText` | `LineText` | German *Buchungszeile*. |

### 4.4 Enum member renames

| Legacy | Target | Why |
| --- | --- | --- |
| `AircraftStartType.TowingByAircraft` | `LaunchMethod.Aerotow` | German *Flugzeugschlepp*. |
| `AircraftStartType.WinchLaunch` | `LaunchMethod.WinchLaunch` | Keep. |
| `AircraftStartType.SelfStart` | `LaunchMethod.SelfLaunch` | German *Eigenstart*. |
| `AircraftStartType.ExternalStart` | `LaunchMethod.ExternalLaunch` | German *Externer Start*. |
| `AircraftStartType.MotorFlightStart` | `LaunchMethod.PoweredTakeoff` | German *Motorflugstart*. |
| `AccountingUnitType.Min` / `Sec` / `Ldgs` / `StartOrFlight` | `ChargingUnit.PerMinute` / `PerSecond` / `PerLanding` / `PerLaunchOrFlight` | Abbreviations. |
| `AccountingRuleFilterType.StartTaxAccountingRuleFilter` | `ChargingRuleKind.LaunchFee` | False friend, and the suffix repeats the type. |
| `AccountingRuleFilterType.LandingTaxAccountingRuleFilter` | `ChargingRuleKind.LandingFee` | Same. |
| `AccountingRuleFilterType.NoLandingTaxAccountingRuleFilter` | `ChargingRuleKind.NoLandingFee` | Same. |
| `AccountingRuleFilterType.VsfFeeAccountingRuleFilter` | `ChargingRuleKind.AssociationFee` | Names the thing, not the association's initials. |
| `AccountingRuleFilterType.DoNotInvoiceFlightRuleFilter` | `ChargingRuleKind.DoNotInvoice` | |
| `AccountingRuleFilterType.RecipientAccountingRuleFilter` | `ChargingRuleKind.Recipient` | |
| `AccountingRuleFilterType.*AccountingRuleFilter` (rest) | `ChargingRuleKind.FlightTime` / `EngineTime` / `InstructorFee` / `ExtraFuelFee` | |
| `FlightCrewType.PilotOrStudent` | `CrewRole.Pilot` | The student case is the flight type, not the crew role. `[ASSUMPTION]` — confirm. |
| `FlightCrewType.Observer` | `CrewRole.SupervisingPilot` | **False friend.** German *Überwachender Pilot*. In gliding an "observer" is an official observer for badge claims — a different person entirely. |
| `FlightCrewType.FlightCostInvoiceRecipient` | `CrewRole.InvoiceRecipient` | |
| `FlightCostBalanceType.PilotPaysAllCosts` | `CostSplit.PilotPaysAll` | |
| `FlightCostBalanceType.HalfHalfPayment` | `CostSplit.SplitEqually` | |
| `FlightCostBalanceType.TowPilotTakesHisCosts` | `CostSplit.TowPilotPaysOwn` | Also removes the gendered pronoun. |
| `FlightCostBalanceType.CostsPaidByPerson` | `CostSplit.NamedPersonPays` | |
| `AircraftType.MotorAircraft` | `AircraftCategory.PoweredAircraft` | *Motorflugzeug*. English aviation says powered. **`MotorGlider` keeps its name** — that is a real English term. |
| `FlightCategory.MotorFlight` | `FlightCategory.PoweredFlight` | Same. |
| `FlightAirState.MightBeStarted` | `AirState.TakeoffPresumed` | |
| `FlightAirState.MightBeLandedOrInAir` | `AirState.LandingPresumed` | |
| `FlightProcessState.DeliveryPrepared` / `DeliveryBooked` / `DeliveryPreparationError` | `ProcessState.InvoiceDrafted` / `InvoiceBooked` / `InvoiceDraftFailed` | Follows §7.2. |
| `AircraftStateKey.OK` | `AircraftStatus.Serviceable` | Aviation term. |
| `AircraftStateKey.EndOfLife` | `AircraftStatus.Withdrawn` | |
| `AirfieldLocationType.GrasRunway` | `LocationType.GrassRunway` | **Spelling defect.** |
| `LocationType.CoolTower` | `LocationType.CoolingTower` | **Spelling defect.** |
| `EntityRecordState` | `RecordStatus` | Rule 2. |

---

## 5. The mistranslations, with evidence

These are the ones worth knowing about, not the full rename list. Each cites the German.

### 5.1 *Taxe* is a fee, not a tax — the clearest false friend

`StartTax`, `LandingTax`, `NoLandingTax`, `NoLandingTaxForGlider`, and six more identifiers use
"tax". The German is *Starttaxe* and *Landetaxe*. **In German a *Taxe* is a fee or a charge. It is
not a tax.** The club charges it; no authority levies it. Every one of these becomes a fee.

This matters beyond tidiness: a club system carrier reading "landing tax" on an invoice line has a
reasonable question about who the tax goes to, and the answer is nobody.

### 5.2 "Filter" appears nowhere in the German

The entity is `AccountingRuleFilter`. The interface calls it *Verrechnungs-Regeln*, which is
"charging rules". The rule target field is *Verrechnungsregel Ziel*. **There is no *Filter* anywhere
in the German.** Somebody named the class after what it does internally — it filters flights — and
the internal name reached the interface.

The consequence is real. The UX run recorded that the accounting screens are hard to understand, and
this is part of why: the screen calls a rule a filter, so a carrier looking for the rules does not
find them.

### 5.3 "Observer" is the wrong aviation word

`FlightCrewType.Observer`. The German is *Überwachender Pilot / Instruktor* — the supervising pilot
or instructor. In gliding, an **Official Observer** is the person who validates a badge or record
claim. The two are unrelated. A pilot reading "observer" reads the wrong thing.

### 5.4 `AircraftType` names four different concepts

| Where | What it means |
| --- | --- |
| `AircraftType.cs` entity | A lookup of aircraft categories |
| `AircraftType` enum in `FLS.Data.WebApi` | The same categories, duplicated |
| `Flight.FlightAircraftType` (int) | **The flight category**, not an aircraft type at all |
| `FlightAircraftTypeValue` enum | The flight categories, duplicated again |
| `FlightCategory` enum | The flight categories, duplicated a third time |

**Three enums hold the same three flight categories.** This is the same defect class as risk hotspot
R5, which records the flight-state enum duplicated between the client and the server. The target
keeps two concepts and one definition of each: `AircraftCategory` for the airframe, and
`FlightCategory` for the flight.

### 5.5 The rule conditions are delimited strings, not relations

`ChargingRule` holds nine matching fields, and **every one of them is a `string`**:

`MatchedAircraftImmatriculations`, `MatchedStartTypes`, `MatchedFlightTypeCodes`,
`MatchedStartLocations`, `MatchedLdgLocations`, `MatchedClubMemberNumbers`,
`MatchedFlightCrewTypes`, `MatchedAircraftsHomebase`, `MatchedMemberStates`,
`MatchedPersonCategories`.

Each holds a delimited list of business keys — registrations, member numbers, flight-type codes —
not foreign keys. Each is paired with a `UseRuleForAll…ExceptListed` flag that inverts the match.

**This is a domain-model decision for `bmad-architecture`, not a naming one.** It has three
consequences the architecture must weigh:

1. A rule references an aircraft by its registration. Change the registration, and the rule silently
   stops matching.
2. The migration must carry these strings intact, because the parity promise depends on them
   matching exactly what they matched before.
3. Nothing stops a club from putting another club's member number in the list. It is a second path
   past the club boundary, alongside Q-B8 and Q-B9.

### 5.6 Two enums for one location type, with different members

| Id | `LocationType` (17 members) | `AirfieldLocationType` (6 members) |
| --- | --- | --- |
| 2 | `AirfieldGrass` | `GrasRunway` |
| 3 | `Outlanding` | `ExternalField` |
| 4 | `AirfieldGliderOnly` | `GliderAirfield` |
| 5 | `AirfieldSolid` | `ConcreteRunway` |

**The same numbers carry different names in two places**, and one of them is misspelled. The target
keeps one definition.

### 5.7 Small defects worth fixing while renaming

| Where | Defect |
| --- | --- |
| `translations.json` key `AIRBORN` | Misspelling of "airborne". |
| `translations.json` key `INVOICE_RECEIPIENT` | Misspelling of "recipient". |
| `translations.json` value for `MINIMUM_NUMBER_OF_SEATS_REQUIRED` | German misspelling: *Mninimale*. |
| `DaecIndex` in code, `DEAC_INDEX` in the interface | Two spellings of the DAeC index. |
| `Licence` and `License` | Both spellings appear. Use `Licence`. |
| `Club.Clubname`, `Person.Lastname` | Inconsistent casing against every other identifier. |
| `AccountingRuleFilterFactory.cs:27-30` | **Four real club member numbers with real first names are hardcoded in the source.** They belong to one club. The rewrite must not carry them, and they should not be in a public repository. |

---

## 6. The domain model

### 6.1 Aggregates

**Club** is the tenant boundary. Everything below sits inside exactly one club, except `Person`,
which is shared.

| Aggregate | Root | Holds | Invariants |
| --- | --- | --- | --- |
| **Club** | `Club` | Settings, defaults, extensions, email targets | The club is the isolation boundary. Every query names it. |
| **Person** | `Person` | Licences, ratings, endorsements, medicals, contact details | **A person is not owned by a club.** It reaches clubs through `ClubMembership`. A person exists with no user. |
| **Club membership** | `ClubMembership` | Member number, membership status, club roles, notification preferences | One person, one club, one membership. The member number is unique inside the club. |
| **Aircraft** | `Aircraft` | Status periods, operating counters, home airfield | The registration identifies it. Owned by a club or by a person. |
| **Flight** | `Flight` | Crew assignments, times, counters, routes | See §6.2. **The largest aggregate and the hardest one.** |
| **Reservation** | `Reservation` | Aircraft, pilot, second crew, timeslot, airfield | |
| **Roster day** | `RosterDay` | Duty assignments | Identified by airfield and date. |
| **Charging rule** | `ChargingRule` | WHEN condition, THEN result, engine-stop flag | Ordered by kind. See §6.4. |
| **Invoice draft** | `InvoiceDraft` | Invoice lines, recipient snapshot | **Holds a copy of the recipient's name and address**, so a later change to the person does not rewrite history. |
| **Location** | `Location` | Route points, runway data | Shared across clubs. |

### 6.2 The flight aggregate

A flight is the only record several people write, on several devices, over several hours.

```
Flight
├── AircraftId                    → Aircraft
├── FlightCategory                  glider · tow · powered   (the discriminator)
├── FlightTypeId                  → FlightType     (club-defined purpose)
├── LaunchMethodId                → LaunchMethod   (aerotow · winch · self · external · powered)
├── TowFlightId                   → Flight         ⚠ self-reference, delete rule undocumented (Q-B4)
├── TakeoffAirfieldId             → Location
├── LandingAirfieldId             → Location
├── CostSplitId                   → CostSplit
├── AirState                        computed from the times, never the stored authority
├── ProcessState                    stored, decides billing eligibility
└── CrewAssignment[]              → Person + CrewRole + held-from / held-to times
                                     ⚠ a crew person may belong to another club (Q-B8)
```

**Four time pairs, and they are not the same thing.**

| Pair | Meaning | Who writes it |
| --- | --- | --- |
| `TakeoffTime` / `LandingTime` | The physical flight | Stamped, or typed |
| `BlockStart` / `BlockEnd` | The billed period | **Stamped with NOW** |
| `EngineCounterAtTakeoff` / `EngineCounterAtLanding` | The engine meter | Copied from the last flight, then corrected |
| `CrewAssignment.HeldFrom` / `HeldTo` | When a person held a role | Rarely, on a crew change |

**The two state dimensions.**

```
AirState   (computed)    New → FlightPlanOpen → TakeoffPresumed → Started
                             → LandingPresumed → Landed → FlightPlanClosed

ProcessState (stored)    NotProcessed ─┬→ Invalid                    (Q-B1: which failure?)
                                       └→ Valid
                                            ↓  lock gate             (Q-B2, Q-B3: unit? boundary?)
                                          Locked
                                            ↓  billing gate
                                          InvoiceDrafted ─→ InvoiceBooked
                                            └→ InvoiceDraftFailed
                                          ExcludedFromInvoicing
```

### 6.3 The user, person, and club triad

**This is the shape the seed calls a sacred cow, and the one most likely to be collapsed by
accident.**

```
User ──(0..1)──→ Person ──(0..n)──→ ClubMembership ──(1)──→ Club
 │                                                            ▲
 └────────────────────(1)─────────────────────────────────────┘
```

- A **user** belongs to exactly one club and links to at most one person.
- A **person** belongs to any number of clubs, each through a **club membership**.
- **The licences live on the person. The club roles live on the membership.** A person holds one
  glider licence, and can be an instructor at one club and an ordinary member at another.
- A person can exist with no user. A visiting pilot needs no login.

**Collapsing user and person breaks the pilot roster at a site where members fly for more than one
club.** That is the whole reason the split exists.

### 6.4 The rules engine — and what it really does

**This section corrects an assumption in the PRD. Read §7.3.**

The pipeline runs four engines in a fixed sequence:

```
1. IgnoreFlightRulesEngine     — does this flight get invoiced at all?
2. RecipientRulesEngine        — who receives the invoice?
3. DeliveryItemRulesEngine     — which lines, at what amounts?     ← the decrement loop
4. DeliveryDetailsRulesEngine  — the header text of the draft
```

**Engine 3 runs nine phases, and the order of the phases is fixed in the source code**
(`DeliveryItemRulesEngine.Run()`):

| # | Phase | Charging rule kind | Consumes active flight time |
| --- | --- | --- | --- |
| 1 | No landing fee | `NoLandingFee` | no |
| 2 | Aircraft flight time | `FlightTime` | **yes** |
| 3 | Aircraft engine time | `EngineTime` | **yes** |
| 4 | Instructor fee | `InstructorFee` | no |
| 5 | Tow flight aircraft time | `FlightTime` (on the tow flight) | **yes** |
| 6 | Extra fuel fee | `ExtraFuelFee` | no |
| 7 | Launch fee | `LaunchFee` | no |
| 8 | Landing fee | `LandingFee` — master flight, then tow flight | no |
| 9 | Association fee | `AssociationFee` — master flight, then tow flight | no |

The active flight time is set from the flight duration before phase 2, and phases 2, 3, and 5
decrement it.

**Within one phase the engine does not sort.** `RulesEngine.Run()` is a bare
`foreach (var rule in rules)` over the list as the query returned it. `ChargingRule.SortOrder` exists
on the entity, and **the engine never reads it**. The factory that builds the demonstration rule set
assigns it, and nothing consumes it.

---

## 7. Findings that change the PRD

### 7.1 "Flight operator" is a mistranslation, and it is in the PRD glossary

The interface key is `FLIGHT_OPERATOR`, and the German is **`Segelflugleiter`** — the glider flight
leader, the person in charge of gliding operations for the day.

"Operator" is wrong twice. In aviation an *operator* is the organisation that operates aircraft
under an air operator certificate. And the same word is already doing double duty in your documents:
the brief used it for both this person and for you, which is why the PRD split it into "flight
operator" and "supplier".

**Recommended target: `duty flight leader`.** It is the standard English for the role, it carries no
second meaning, and it frees "operator" entirely.

**This is not a cheap change.** The term appears in `prd.md` (§2.1, §3, §4.3, §4.5, and every UJ), in
`EXPERIENCE.md`, and in `DESIGN.md`. I have **not** applied it. It is your call, and §8 records it as
the first open question.

### 7.2 "Delivery" is an invoice draft, and renaming it touches an external contract

The German for the delivery mail export is *Rechnungs-Informationen* — invoice information. The
delivery line text is *Buchungszeile* — a posting line. **A `Delivery` is a billing document.** The
name is almost certainly a literal carry-over of *Lieferung* from the Proffix accounting system,
where a delivery note becomes an invoice.

`InvoiceDraft` is the honest name, and the PRD glossary already explains `Delivery` as "the invoice
draft the rules engine produces".

**The constraint:** FR-56 requires AlpenFlight to serve an interface that the external Proffix
synchroniser reads, and that synchroniser polls `/api/v1/deliveries/*` today. The domain can be
renamed while the wire path stays. That is a normal thing to do, and it should be a deliberate
decision rather than a side effect. §8 records it.

### 7.3 FR-47 assumes a rule order that the legacy does not have

**This is the most important finding in this document.**

FR-47 says the carrier "changes that order", with drag to reorder. The UX run designed an ordered
list, a drag handle, and a reorder guard that shows the invoice before and after. Addendum §3 lists
Q-B6 — *when two rules match the same active flight time, which applies first?* — as unresolved.

**The legacy answer, from the source:** the order is **fixed by rule kind**, in the nine phases in
§6.4, hardcoded in `DeliveryItemRulesEngine.Run()`. A club cannot change it. Within a phase there is
no order at all — the engine iterates the query result, and `SortOrder` is never read.

That means three things:

1. **Q-B6 is partly answered.** Across kinds, the phase sequence decides. Within a kind, the legacy
   has no defined answer, which makes the outcome depend on the database's row order. Confirm the
   within-kind behaviour with the `legacy-oracle` agent — that part still needs the agent.
2. **FR-47 as written is a behaviour change, not a port.** The brief forbids an unrecorded behaviour
   change. Either FR-47 changes to *show* the order rather than let a carrier set it, or it stays and
   gets recorded as a second deliberate change alongside FR-60.
3. **The reorder guard in FR-51 may have nothing to guard.** If the order is fixed, the guard's job
   disappears — but the dry run behind it, FR-50, becomes more valuable, not less. **A carrier cannot
   change the order, so seeing why the number is the number is the only help available.**

My recommendation, for your decision: **keep the ordered list as a read-only display of the nine
phases, keep the dry run, and drop the drag-to-reorder and the reorder guard from v1.** It ports the
real behaviour, it still answers UJ-2, and it removes a feature that would silently change every
club's invoices.

---

### 7.4 The `DeliveryCreationTest` is a re-baselining tool, and "parity" was the wrong word

**This corrects an error in the first draft of this document, and it changes FR-54.**

The first draft named this entity `BillingParityTest` and described it as the mechanism that proves
accounting parity. **That was wrong twice.**

**First, it collided with our own vocabulary.** This project already uses *parity* for one specific
thing: the legacy system and AlpenFlight producing the same invoice line. That is a one-time
migration proof. This entity is about something else entirely — a club's rules against the club's own
recorded expectation, over years. Using one word for both breaks naming rule 1 in the first document
that states it.

**Second, it described the mechanism and missed the job.** Here is what the legacy actually offers,
from `DeliveryService.RunDeliveryCreationTest()` and
`flsweb/src/masterdata/deliveryCreationTests/`:

| Control | What it does |
| --- | --- |
| **Create test delivery** | Runs the engine against the flight now, and **writes the result straight into the expectation field**. One click re-baselines. |
| Expectation field | A free text area holding the expected invoice as JSON. **A person can hand-edit it.** |
| Ignore flags | Nine of them — recipient name, address, person id, member number, information, item text, item positioning, and more. They say which differences do not count. |
| **Run test** | Runs the engine and reports `Success!`, or `Failure` with a message naming each field that did not match. |
| Two tables on screen | The expected invoice lines above, the last run's actual lines below. **Stacked, not diffed.** |
| Matched rules | The rules that fired in the last run, each linking to its rule editor. |
| **Run all** | Runs every test with a progress bar in green and red. |

**That is a golden-master workflow, and your reading of it is the right one.** The daily loop is:
change a rule → run all → read which expectations failed → look at the two tables and decide whether
the difference is what you wanted → if it is, press **Create test delivery** to bless it, and save.
**The difference is the point. It is not a defect to be driven to zero.**

The word "test" also misleads a treasurer. Nothing here is a developer's test. It is a club saying
"this flight should bill like this", and the system telling them when that stops being true.

**One real gap, and it matters for the PRD.** The legacy compares the current output against a
*stored* expectation. It cannot show what a change *would* do before you make it. To see the effect
of a rule edit you must save the edit first, then run, then read failure messages. **There is no
before-and-after view anywhere in the legacy accounting screens.**

That is exactly the gap the dry run (FR-50) and the reorder preview (FR-51) fill, and it is why they
are worth building even though §7.3 removes the reordering itself. The two capabilities are
complementary, not duplicates:

| Capability | Question it answers | When |
| --- | --- | --- |
| **Dry run** (FR-50) | Why is this number what it is? | Any time, on one flight |
| **Billing expectation** (FR-54) | Did anything change that I did not intend? | After a rule edit, across many flights |
| **Change preview** (FR-51, new) | What would this edit do, before I commit it? | While editing |

**The migration link survives, and it gets better.** FR-74 verifies a migration by replaying recorded
legacy invoices. Those recorded invoices are exactly the right seed for a club's first billing
expectations. **Seed the expectations from the migration, and the same suite that proved the move
also guards every rule edit afterwards.** One mechanism, two jobs — the same shape the UX run found
for the dry run.

## 8. Open naming questions

| # | Question | Why it matters | Owner |
| --- | --- | --- | --- |
| 1 | **Do we rename "flight operator" to "duty flight leader"?** | §7.1. It touches the PRD, `EXPERIENCE.md`, and `DESIGN.md`. It is the correct term and it frees the word "operator". | Supplier |
| 2 | **Do we rename `Delivery` to `InvoiceDraft` in the domain, keeping `/deliveries` on the wire?** | §7.2. It touches the external Proffix contract path. | Supplier, then `bmad-architecture` |
| 3 | **Does FR-47 keep drag-to-reorder?** | §7.3. It is a behaviour change against a sacred cow. | Supplier |
| 4 | **What is `Flight.StartPosition`?** | I read it as the launch queue position. Nothing confirms it. | `legacy-oracle` |
| 5 | **Is `CrewRole.PilotOrStudent` one role or two?** | The flight type already carries the training case. If it is one role, the name is just `Pilot`. | `legacy-oracle` |
| 6 | **Do we say "powered" or keep "motor"?** | `MotorAircraft` → `PoweredAircraft` is correct English aviation. It touches many identifiers, and *Motorflug* is what the club says in German. **The German interface keeps *Motorflug* either way.** | Supplier |
| 7 | **Which German words does the interface use for the renamed terms?** | The rename fixes the English. The German interface text needs its own pass, and §5 shows the German was mostly right already. | Supplier, at translation time |

---

## 9. What this document does not do

- **It does not rename anything in `flsserver/` or `flsweb/`.** Legacy is reference-only.
- **It does not decide storage.** Whether `ChargingRule.Matched*` stays a delimited string or becomes
  a relation is an architecture decision. §5.5 states the consequences either way.
- **It does not settle the open behavioural questions.** §6.4 answers part of Q-B6 because the answer
  fell out of the naming work. The other fifteen still need the `legacy-oracle` agent, per
  [`addendum.md`](addendum.md) §3.
