---
epic: 1
title: The template — sign in, and see only your club
index: ../epics.md
status: draft
created: 2026-08-29
updated: 2026-08-29
---

## Epic 1: The template — sign in, and see only your club

A user signs in and manages the club fleet. Nobody reaches another club's row, and a query that
forgets the club filter returns zero rows.

This epic is the **template**. The spine states it: *the first epic is the template, not a feature.*
It fixes the id strategy, it builds the client platform, and it ships the nine build gates with the
CI that runs them. Every later story copies a skeleton from here.

| Field | Value |
| --- | --- |
| **Covers** | FR-1, FR-2, FR-3, FR-4, FR-5, FR-6, FR-7, FR-8, FR-12, FR-60, FR-61, FR-81, FR-88 |
| **Slices** | `core/club` deep, `core/aircraft` thin |
| **Governed by** | AD-1, AD-2, AD-3, AD-4, AD-13, AD-17, AD-18, AD-19, AD-21, AD-22 |
| **Oracle first** | Q-B8, Q-B9, Q-B13, Q-B17 |
| **Blocked by** | Question 26, for the migrated audit history only. Story 1.9 ships the new-record part. |

**Sequence.** Stories 1.1 to 1.4 build the substrate, in that order. Story 1.5 needs the shell of
story 1.4. Story 1.6 needs the record list of story 1.5, and it reads the club from a development
source. Story 1.7 deletes that source. Story 1.9 ships before story 1.10 and story 1.12, because
both write an audit record.

### Story 1.1: The build tree and the image

As a supplier,
I want one command to build and test AlpenFlight,
So that every later story starts from a proven tree.

**Acceptance Criteria:**

**Given** a clean checkout
**When** the supplier runs the documented build command
**Then** Gradle builds the multi-module tree of the Structural Seed: `server/platform`,
`server/core`, `server/modules-open`, `server/modules-pro`, `client/platform`, `client/features`,
and `deploy/`
**And** the build produces one container image that serves the API and the built client.

**Given** the remaining dependency versions are unpinned
**When** this story completes
**Then** Gradle, Flyway, the OpenAPI generator, the IndexedDB wrapper, and Keycloak each carry a
pinned version, verified against the published release notes and not recalled from memory.

**Given** the server publishes an OpenAPI specification
**When** the client build runs
**Then** it generates the TypeScript types from that specification
**And** a component uses a generated type as its own type, with no hand-written model.

**Given** `alpenflight/` now holds code
**When** the supplier runs `bmad-project-context` against it
**Then** the run writes `AGENTS.md`.

### Story 1.2: The runtime, the first migration, and the id strategy

As a supplier,
I want one command to start the whole system, and one id type for every table,
So that a developer runs AlpenFlight with no manual step, and no two slices choose a different id.

**Acceptance Criteria:**

**Given** the container image and the Compose file
**When** the supplier runs one command
**Then** PostgreSQL 18.6 and Keycloak start beside the image, Flyway applies every migration, the
Keycloak realm imports from a file held in the repository, and the application answers a health
request
**And** the supplier needs no second command and no manual configuration step. *(AD-20, AD-22)*

**Given** the first Flyway migration
**When** it runs
**Then** it creates the `club` table, the table declares its data kind, and it carries a version
column.

**Given** the id strategy
**When** this story completes
**Then** `platform` declares one id type, and the `club` table uses it
**And** a test fails the build when a table's primary key uses a different type
**And** the spine records the decision, because the spine defers it to this epic.

### Story 1.3: The build gates and CI

As a supplier,
I want CI to run nine gates on every commit, and to prove that each gate fails,
So that the architecture holds without an agent having to remember it.

**Acceptance Criteria:**

**Given** the nine build gates
**When** CI runs on a commit
**Then** every gate runs, and a violation fails the build
**And** the gate set is:

1. A thin slice contains no `application/` package; a deep slice contains all four.
2. Core never references a pro module.
3. Core plus the open modules build and test standalone.
4. Every club-scoped table has a row-level-security policy, and every table declares its data kind.
5. No binary floating-point type appears in a charging, delivery, or invoice type.
6. No cross-slice reach into another slice's internals.
7. A cross-tenant read test proves that a query without a filter returns zero rows.
8. No code calls `now()` directly; the `Clock` is injected, so a test can drive both time gates.
9. Every mutable entity carries a version column.

**Given** each of the nine gates
**When** CI runs it against a deliberate violation
**Then** the build fails, and the failure names the gate
**And** the nine violation cases stay in the repository, so a gate that stops working is found.

> **Note on gate 2 and gate 3.** Both run against an empty `modules-open` and `modules-pro` tree
> until Epic 7 ships the first pro module. Their violation cases are the only proof they work.

### Story 1.4: The application shell and the design tokens

As a user,
I want one shell with four destinations, and one token set behind every screen,
So that every later screen looks and moves the same, on a phone and on a laptop.

**Acceptance Criteria:**

**Given** the application shell
**When** a user opens the application on a phone
**Then** four destinations appear in a bottom bar: Operate, Plan, Records, and Admin
**And** on a pointer device the same four appear in the top bar
**And** every surface sits inside exactly one destination. *(FR-81)*

**Given** the `DESIGN.md` token set
**When** any screen renders
**Then** it uses those token values for colour, type, spacing, and motion
**And** every corner radius is zero
**And** the ground is `surface-base`, with no light mode and no theme control.

### Story 1.5: The record list and the list toolbar

As a user,
I want every list to read as a record item, with one search field and filter chips,
So that I find a record fast on a phone and on a laptop, and no screen shows me a data table.

**Acceptance Criteria:**

**Given** any list of records
**When** it renders
**Then** it uses `RecordList` holding `RecordItem`
**And** the product contains no `<table>` element for a data list.

**Given** one `RecordItem` definition
**When** the viewport is narrow
**Then** the item stacks: identity over meta at full width, metric over marker at the right, with no
fixed zone width
**And** when the viewport is wide it renders four aligned zones: identity 104 pixels, meta flexible,
metric 88 pixels right-aligned, and the marker under the metric. *(FR-61)*

**Given** a record with an absent value
**When** the item renders
**Then** the item keeps its height, and the empty value shows `not set` in `ink-disabled`
**And** no row below it moves.

**Given** a list with groups
**When** it renders
**Then** a group header names the group and its count, and the list restarts under it
**And** the list has one background, with no alternating tone and no rule under a row.

**Given** the list toolbar
**When** it renders
**Then** it shows the search field, then the filter chips, then the sort control, in that order.

**Given** the search field
**When** the user types
**Then** the list filters as they type, across every displayed value, with no submit control. *(FR-60)*

**Given** the filter chips
**When** the user narrows the list
**Then** the chips are the only filter mechanism, and no column carries a filter
**And** an active chip shows its value and a clear control.

**Given** a pointer device
**When** the user turns on dense mode
**Then** the item collapses from 60 pixels to 44 pixels, and the zones keep their widths
**And** the control never appears on a phone.

**Given** the item carries tabular data
**When** assistive technology reads it
**Then** the item carries its table roles, and it labels each field when it renders stacked.

### Story 1.6: See only your own club's aircraft

As a club administrator,
I want the club fleet in one list, and no aircraft of any other club anywhere,
So that a gate proves the isolation before any second slice exists.

> **Note on sequence.** This story proves the boundary before story 1.7 supplies a real signed-in
> club. Until then the tenancy resolver reads the club from a development source. Story 1.7 replaces
> that source with the token, and deletes it. This story stands alone, so it carries no forward
> dependency.

**Acceptance Criteria:**

**Given** the `club_aircraft` table
**When** the Flyway migration creates it
**Then** the same migration creates its row-level-security policy and sets `FORCE ROW LEVEL SECURITY`
**And** the table declares the data kind club-scoped
**And** it carries a `club_id` foreign key, and no `OwnerId` and no `OwnershipType`.

**Given** the application connects to PostgreSQL
**When** it opens a transaction
**Then** it connects as a non-owner role that cannot bypass its own policies
**And** the transaction opener sets the club session variable once, never a controller and never a
query.

**Given** a query that omits the club filter
**When** the cross-tenant read test runs it
**Then** it returns zero rows
**And** the test fails the build when the query returns a row. *(FR-1)*

**Given** a user of club A and a record identifier of club B
**When** the user requests that identifier directly in a URL
**Then** the system answers not found, and it reveals nothing about the record.

**Given** the aircraft slice
**When** a developer reads it
**Then** it holds four files and no `application/` package, because it carries no business rule
**And** the client feature holds no service layer: `httpResource` supplies the value, the loading
state, and the error state in one declaration.

**Given** an aircraft
**When** a club administrator reads it
**Then** it carries a flight counter and an engine counter, each with its counter unit. *(FR-12)*

**Given** the aircraft list
**When** it renders
**Then** it uses the `RecordList` and toolbar from story 1.5.

### Story 1.7: Sign in through the identity provider, and stay signed in

As a user,
I want to sign in once and stay signed in through a flying day,
So that the application never interrupts me while I log a flight.

**Acceptance Criteria:**

**Given** the identity provider holds the credentials
**When** a user signs in
**Then** the client runs the OpenID Connect authorization-code flow with PKCE against the provider
**And** the server validates the resulting token as a resource server
**And** AlpenFlight holds no password and no credential of any kind. *(AD-22)*

**Given** a validated token
**When** a request opens a transaction
**Then** the token supplies the subject only
**And** the club, the roles, and the tenant boundary resolve from `ClubMembership` in AlpenFlight
tables, never from a claim the provider controls
**And** this replaces the development source that story 1.6 used.

**Given** the development tenancy source of story 1.6
**When** this story completes
**Then** the repository holds no development tenancy source
**And** a test fails the build when any code outside the transaction opener sets the club session
variable, because AD-13 permits no second tenancy path. *(AD-13)*

**Given** the realm configuration held in the repository
**When** it is applied
**Then** the access token lives 15 minutes, the refresh token lives 30 days, and the provider rotates
the refresh token on every use
**And** the provider accepts one reuse of the previous refresh token, so a lost response on a weak
network does not sign the user out.

**Given** a signed-in user and a flying day of many hours
**When** the access token expires
**Then** the client refreshes it against the provider with no new sign-in
**And** the user signs in once for the day. *(FR-4)*

**Given** a user who is part way through a write
**When** the request is rejected for an expired token
**Then** the entry stays on screen, the client refreshes, and it repeats the write
**And** the client loses no typed value. *(Q-B13)*

**Given** the `IdentityProvider` port in `platform`
**When** a slice needs an identity operation
**Then** it calls the port, and only the port
**And** the port carries five operations and no more: prove a sign-in, create a principal,
deactivate a principal, start a password reset, and start an email confirmation
**And** the Keycloak adapter is the only implementation in the repository, so a different
Keycloak-compatible provider is a new adapter and a configuration change.

**Given** an unauthenticated request
**When** it reaches any signed-in endpoint
**Then** the system refuses it, and the response reveals nothing about the record.

### Story 1.8: The person, the club membership, and the roles

As a club administrator,
I want a person to exist apart from a login, and to belong to several clubs,
So that a pilot who flies for two clubs keeps one record, and no club can read another club's persons.

**Acceptance Criteria:**

**Given** a person record
**When** it is created
**Then** it exists with no user
**And** a visiting pilot needs no sign-in. *(FR-3)*

**Given** a person who flies for two clubs
**When** each club records a membership
**Then** each `ClubMembership` carries its own member number, membership status, and club roles
**And** the member number is unique inside the club.

**Given** a user
**When** it is created
**Then** it belongs to exactly one club, and it links to at most one person
**And** it links to exactly one principal in the identity provider.

**Given** a club administrator of club A
**When** they search for a person
**Then** the search returns only a person that club A reads through a `ClubMembership` it owns
**And** there is no global person search anywhere in the product. *(AD-4)*

**Given** a crew member who belongs only to club B
**When** club A adds them to a flight
**Then** club A adds them by an explicit cross-club link, never by a typeahead over every person
**And** club A sees its own flight. Club B does not see that flight. *(FR-2, Q-B8)*

**Given** the role set
**When** a user holds a role
**Then** the role names the surfaces and the actions the user reaches
**And** the role lives in an AlpenFlight table, never in the identity provider. *(FR-5, Q-B17, AD-22)*

**Given** a user whose role lacks a permission
**When** they attempt the action
**Then** the system refuses it, and the message names what the user lacks
**And** the refusal happens at the permission layer, so it holds before the surface exists.

### Story 1.9: The audit record

As a club administrator,
I want every change recorded with who, what, when, and which record,
So that I answer a question about a change without asking the supplier.

**Acceptance Criteria:**

**Given** any create, update, or delete of a flight, a person, an aircraft, a charging rule, or an
invoice draft
**When** it commits
**Then** the system writes an audit record naming the actor, the record, the time, and each changed
field with its old value and its new value. *(FR-8)*

**Given** the audit record
**When** the change commits
**Then** the audit record writes in the same transaction as the change, so no change commits without
its audit record
**And** the audit write carries no validation and no business rule, so a defect in the audit code
never stops a user from logging a flight.

**Given** a soft delete, which the spine makes the default for a business record
**When** it commits
**Then** the audit record names the event a delete.

**Given** a change made by scheduled work, and not by a user
**When** it commits
**Then** the audit record names the actor `system`.

**Given** a role change or a club-membership change
**When** it commits
**Then** it writes an audit record
**And** this holds because AD-22 keeps authorization in AlpenFlight tables, where the audit reaches
it.

**Given** an audit record
**When** it is written
**Then** it names its club.

**Given** a club administrator of club A
**When** they read the audit records
**Then** they read only the records of club A
**And** they read no record of club B through any interface.

**Given** an erasure request under FR-84
**When** Epic 13 runs it
**Then** the erasure path covers the audit records that name that person
**And** the audit record keeps its actor, its record, and its time, so the trail stays complete.

**Given** a log line or an error record
**When** the system writes it
**Then** it carries no personal data
**And** this rule holds for the log and for the error record, never for the audit table.

**Given** the legacy audit history carries no club
**When** the migration runs
**Then** it follows the path question 26 decides: derive a club per row, or start the history empty
**And** this story ships the new-record part, which question 26 does not block.

### Story 1.10: Edit my own record and my password

As a user,
I want to correct my own details and change my own password,
So that I do not ask an administrator for a change that I can make myself.

**Acceptance Criteria:**

**Given** a signed-in user
**When** they open their profile
**Then** they read and edit their own person record. *(FR-6)*

**Given** a signed-in user
**When** they change their password
**Then** the change happens at the identity provider, through the port
**And** AlpenFlight never receives the old or the new password.

**Given** a signed-in user
**When** they attempt to change their own role or their own club
**Then** the system refuses it, and the message names what they cannot change.

**Given** a user edits their profile
**When** they save
**Then** the change writes an audit record.

### Story 1.11: Reset a password, and confirm an email address

As a user who forgot a password,
I want a reset link that works once,
So that I sign in again with no message to an administrator.

**Acceptance Criteria:**

**Given** the identity provider owns the reset flow
**When** a user requests a reset
**Then** the provider runs the flow, and AlpenFlight builds no reset mechanism
**And** AlpenFlight holds no reset token. *(AD-22)*

**Given** a request for a password reset
**When** the email address exists, and when it does not
**Then** the response is identical in both cases
**And** it reveals nothing about which addresses the system holds. *(FR-7)*

**Given** a reset link
**When** the user opens it inside one hour
**Then** they set a new password
**And** the link does not work a second time.

**Given** a reset link
**When** one hour has passed
**Then** the link fails, and the message states that it expired.

**Given** a new user
**When** they confirm their email address
**Then** the provider records the confirmation, and AlpenFlight reads it through the port
**And** the confirmation link lives 24 hours.

**Given** the realm configuration
**When** it is applied
**Then** it sets the password policy, the brute-force detection, and the account lockout
**And** the configuration lives in the repository as a file, so a community install has the same
settings.

### Story 1.12: Manage the users of my club

As a club administrator,
I want to create, link, and deactivate the users of my own club,
So that a new member signs in, and a departed member does not.

**Acceptance Criteria:**

**Given** a club administrator
**When** they create a user
**Then** the system creates the principal through the `IdentityProvider` port, links it to a person
of their own club, and assigns a role from the FR-5 role set
**And** the role is written to an AlpenFlight table. *(FR-88, AD-22)*

**Given** a club administrator of club A
**When** they request a user of club B
**Then** the system answers not found.

**Given** a club administrator
**When** they deactivate a user
**Then** the system deactivates the principal through the port
**And** the user cannot sign in
**And** every audit record that names them stays.

**Given** a club administrator
**When** they remove a role from a user
**Then** the user no longer reaches the surfaces of that role, on their next request
**And** the change never touches the identity provider.
