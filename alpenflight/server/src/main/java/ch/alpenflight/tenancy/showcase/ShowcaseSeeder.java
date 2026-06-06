package ch.alpenflight.tenancy.showcase;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftStateHistoryEntry;
import ch.alpenflight.flights.application.FlightStateTransitionService;
import ch.alpenflight.flights.domain.CrewMemberSpec;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.TransitionTrigger;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.tenancy.provisioning.application.ReferenceDataSeeder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The on-demand <strong>showcase seed</strong> — a cumulative, deterministic,
 * reusable demo dataset (J-3). Distinct from the lean per-IT Flyway dev seeds
 * (ADR 0021 keeps ITs fast; this loader is NEVER on the IT bootstrap path) and
 * distinct from the migrated fanout export (realistic but non-curated). The
 * showcase seed is curated + dial-able: fixed UUIDs so the e2e display spec can
 * assert against known rows and dashboard counts are predictable.
 *
 * <p><strong>Mechanism.</strong> A {@code @Profile("showcase")}
 * {@link ShowcaseSeedRunner} invokes this seeder once at boot. Everything is an
 * idempotent {@code ON CONFLICT DO NOTHING} upsert keyed on the deterministic
 * id, so re-running (or running alongside the always-on V5/V8/V26/V28/V29 dev
 * seeds, which it coexists with — extend, don't fight) is a clean no-op.
 *
 * <p><strong>T-02 seeded the tenancy + principal layer:</strong> a 2nd showcase
 * club + its reference data (member_state / flight_type, via the same
 * {@link ReferenceDataSeeder} a real provisioned club gets) + the paired
 * {@code t_user} rows for all three roles across both clubs (including one pilot
 * with NO flights so the empty-state stays reachable).
 *
 * <p><strong>T-03a adds locations + aircraft</strong> (the first
 * per-journey-extension; see {@code README.md}). Both are built through their
 * <strong>domain aggregate factories</strong> — {@link Location#create} and
 * {@link Aircraft#register} (+ {@link Aircraft#changeState} for the opening
 * airworthiness period) — so every ADR 0022 directive-2 business rule (ICAO
 * shape, immatriculation normalisation, competition-sign / FLARM format,
 * blank-name rejection, "exactly one open state period") runs over the seed
 * data; no raw illegal INSERT slips past the aggregate.
 *
 * <p><strong>Persistence: validate-via-aggregate, then JDBC-INSERT with a
 * deterministic id.</strong> {@link Location} / {@link Aircraft} own id
 * generation ({@code @GeneratedValue(strategy = UUID)} mints a fresh random id
 * on every persist), so the repository save path cannot honour the fixed ids
 * the showcase needs for predictable e2e assertions. The seeder therefore
 * mirrors the migration ingestor's pattern for these very entities (which also
 * persist them JDBC-direct with a chosen id): construct the aggregate so it
 * validates + normalises the inputs, then read the normalised values off its
 * getters into an idempotent {@code ON CONFLICT DO NOTHING} INSERT carrying the
 * deterministic id. The tenant-scoped {@link Location} writes run inside
 * {@link Tenants#runAs} so the effective-tenant write-context contract the app
 * uses is honoured (T-03b's flights will lean on the same {@code runAs} wrap).
 *
 * <p>Runs JDBC-directly (mirroring {@link ReferenceDataSeeder}) for the actual
 * INSERT: every row carries its {@code club_id} / {@code managing_club_id}
 * explicitly. The whole seed runs in one transaction so
 * {@link ReferenceDataSeeder#seedDefaults}'s {@code Propagation.MANDATORY}
 * contract is satisfied.
 *
 * <p><strong>Exposed for T-03b.</strong> The flight matrix references these
 * rows by their deterministic id; T-03b resolves them through the
 * {@code LOCATION_*} / {@code AIRCRAFT_*} constants below (also tabulated in
 * {@code README.md}).
 */
@Component
public class ShowcaseSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(ShowcaseSeeder.class);

    // -------------------------------------------------------------------------
    // Deterministic identities. Suffix scheme mirrors the V5/V8 dev seeds:
    //   club:   019e30c3-2c00-7001-8000-00000000000N
    //   t_user: 019e30c3-2c00-7100-8000-0000000000NN
    // and the showcase principals' Keycloak subs (realm-export.json user ids):
    //           019e30c3-2c00-7200-8000-0000000000NN
    // -------------------------------------------------------------------------

    /** Club 1 — the canonical V5 dev club {@code seed-club-1}; reused, not re-created. */
    static final UUID CLUB_1 = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    /** Club 2 — the net-new showcase club so cross-tenant aggregates span &ge;2 clubs. */
    static final UUID CLUB_2 = UUID.fromString("019e30c3-2c00-7001-8000-000000000002");

    // Reference UUIDs from reference-seeds-canonical-uuids.json / V3 (baseline).
    private static final UUID COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    // location_type (V3): glider airfield for the homebases, concrete runway
    // for the shared destinations.
    private static final UUID LOC_TYPE_GLIDER_AIRFIELD =
            UUID.fromString("019e2e15-2c00-72cb-8000-0000000032cb");
    private static final UUID LOC_TYPE_CONCRETE_RUNWAY =
            UUID.fromString("019e2e15-2c00-72cc-8000-0000000032cc");

    // aircraft_type (V3).
    private static final UUID AC_TYPE_GLIDER =
            UUID.fromString("019e2e15-2c00-7af9-8000-000000002af9");
    private static final UUID AC_TYPE_MOTOR_GLIDER =
            UUID.fromString("019e2e15-2c00-7afb-8000-000000002afb"); // TMG
    private static final UUID AC_TYPE_MOTOR_AIRCRAFT =
            UUID.fromString("019e2e15-2c00-7afc-8000-000000002afc"); // tow plane / charter

    // aircraft_state (V3): OK is the opening airworthiness period for every
    // showcase aircraft, so the J-1 list's flyability join is populated.
    private static final UUID AC_STATE_OK =
            UUID.fromString("019e2e15-2c00-7ee0-8000-000000002ee0");

    // -------------------------------------------------------------------------
    // Deterministic showcase data ids (T-03a). Suffix scheme:
    //   location: 019e30c3-2c00-7301-8000-0000000003NN
    //   aircraft: 019e30c3-2c00-7401-8000-0000000004NN
    // T-03b's flight matrix references these by id (also tabulated in README).
    // -------------------------------------------------------------------------

    /** Club 1 home glider airfield (LSZX). */
    public static final UUID LOCATION_C1_HOME =
            UUID.fromString("019e30c3-2c00-7301-8000-000000000301");
    /** Club 1 destination (LSGB). */
    public static final UUID LOCATION_C1_DEST_1 =
            UUID.fromString("019e30c3-2c00-7301-8000-000000000302");
    /** Club 1 destination (LSPD). */
    public static final UUID LOCATION_C1_DEST_2 =
            UUID.fromString("019e30c3-2c00-7301-8000-000000000303");
    /** Club 2 home glider airfield (LSZW). */
    public static final UUID LOCATION_C2_HOME =
            UUID.fromString("019e30c3-2c00-7301-8000-000000000304");
    /** Club 2 destination (LSGT). */
    public static final UUID LOCATION_C2_DEST_1 =
            UUID.fromString("019e30c3-2c00-7301-8000-000000000305");
    /** Club 2 destination (LSPM). */
    public static final UUID LOCATION_C2_DEST_2 =
            UUID.fromString("019e30c3-2c00-7301-8000-000000000306");

    /** Club 1 glider (HB-3001). */
    public static final UUID AIRCRAFT_C1_GLIDER =
            UUID.fromString("019e30c3-2c00-7401-8000-000000000401");
    /** Club 1 tow plane (HB-TOW1, MOTOR_AIRCRAFT, towing). */
    public static final UUID AIRCRAFT_C1_TOW =
            UUID.fromString("019e30c3-2c00-7401-8000-000000000402");
    /** Club 1 motor/TMG (HB-MOT1, MOTOR_GLIDER). */
    public static final UUID AIRCRAFT_C1_MOTOR =
            UUID.fromString("019e30c3-2c00-7401-8000-000000000403");
    /** Club 2 glider (HB-3002). */
    public static final UUID AIRCRAFT_C2_GLIDER =
            UUID.fromString("019e30c3-2c00-7401-8000-000000000404");
    /**
     * Charter aircraft (HB-CHTR, MOTOR_AIRCRAFT) managed by club 2 — club 1
     * references it cross-tenant (S-058 open read), the J-1 charter case.
     */
    public static final UUID AIRCRAFT_CHARTER_C2 =
            UUID.fromString("019e30c3-2c00-7401-8000-000000000405");

    /** Fixed opening airworthiness instant — deterministic, well in the past. */
    private static final Instant AIRWORTHY_SINCE =
            Instant.parse("2024-01-01T00:00:00Z");

    // start_type (V2): the launch method, applicable-category gated.
    private static final UUID START_TYPE_WINCH =
            UUID.fromString("019e2e15-2c00-7fa0-8000-000000000fa0");
    private static final UUID START_TYPE_AEROTOW =
            UUID.fromString("019e2e15-2c00-7fa1-8000-000000000fa1");
    private static final UUID START_TYPE_MOTOR =
            UUID.fromString("019e2e15-2c00-7fa2-8000-000000000fa2"); // SELF_START (motor/TMG)

    // -------------------------------------------------------------------------
    // Deterministic showcase flight-matrix ids (T-03b). Suffix scheme:
    //   t_person:      019e30c3-2c00-7601-8000-0000000006NN  (the PIC pilots)
    //   t_flight:      019e30c3-2c00-7801-8000-0000000008NN
    //   t_flight_crew: 019e30c3-2c00-7901-8000-0000000009NN  (flight id band bumped)
    // The flights are built through Flight.create* + linkTow + replaceCrew,
    // INSERTed JDBC-direct under the fixed id (mirroring locations/aircraft —
    // @GeneratedValue(UUID) would otherwise mint a random id), then driven to
    // their target process-state through the REAL FlightStateTransitionService
    // edges so every state is legally reached (not a raw illegal-state INSERT).
    // -------------------------------------------------------------------------

    /** {@code pilot1}'s Person — linked to its {@code t_user} so the S-165 last-flight card resolves. */
    private static final UUID PERSON_PILOT1 =
            UUID.fromString("019e30c3-2c00-7601-8000-000000000601");
    /** {@code pilot-c2}'s Person — linked to its {@code t_user}. */
    private static final UUID PERSON_PILOT_C2 =
            UUID.fromString("019e30c3-2c00-7601-8000-000000000602");
    /**
     * {@code pilot1}'s club-1 membership ({@code t_person_club}) — carries the J-4
     * notification-pref values the {@code /profile} Notifications tab renders +
     * round-trips. (J-4 T-14: this membership lives on pilot1's REAL flights-PIC
     * person, not a V30 orphan, so the J-3 last-flight card and the J-4 profile
     * tabs both resolve the same person.)
     */
    private static final UUID PERSON_CLUB_PILOT1 =
            UUID.fromString("019e30c3-2c00-7701-8000-000000000701");

    /** {@code pilot1}'s {@code t_user} id (V8 seed) — gets {@code person_id} linked here. */
    private static final UUID USER_PILOT1 =
            UUID.fromString("019e30c3-2c00-7100-8000-000000000002");
    /** {@code pilot-c2}'s {@code t_user} id (T-02 seed). */
    private static final UUID USER_PILOT_C2 =
            UUID.fromString("019e30c3-2c00-7100-8000-000000000022");

    /**
     * Showcase principal: deterministic {@code t_user} id + its paired Keycloak
     * sub (the realm-export user {@code id}) + the tenant it belongs to. The
     * three role realm-users themselves live in {@code alpenflight/auth/realm-export.json};
     * this seeder only materialises the matching {@code t_user} rows so
     * {@code UserPrincipalLookup.resolveTenantFor(jwt)} resolves a tenant the
     * moment the showcase principal authenticates (no JIT race).
     */
    private record ShowcasePrincipal(
            UUID userId, UUID keycloakSub, UUID clubId, String username, String friendlyName) {}

    private static final List<ShowcasePrincipal> PRINCIPALS = List.of(
            // --- Already in realm-export + seeded by V8/V26/V28/V29; the showcase
            //     reuses them rather than inventing duplicates (clubadmin1 = club-1
            //     admin, pilot1 = club-1 pilot WITH flights, sysadmin = global). ---
            // --- Net-new showcase principals (added to realm-export.json in T-02): ---
            new ShowcasePrincipal(
                    UUID.fromString("019e30c3-2c00-7100-8000-000000000020"),
                    UUID.fromString("019e30c3-2c00-7200-8000-000000000020"),
                    CLUB_1, "pilot-empty1", "Pilot Empty One"),     // club-1 pilot with NO flights
            new ShowcasePrincipal(
                    UUID.fromString("019e30c3-2c00-7100-8000-000000000021"),
                    UUID.fromString("019e30c3-2c00-7200-8000-000000000021"),
                    CLUB_2, "clubadmin-c2", "Club Admin Two-Club"), // club-2 admin
            new ShowcasePrincipal(
                    UUID.fromString("019e30c3-2c00-7100-8000-000000000022"),
                    UUID.fromString("019e30c3-2c00-7200-8000-000000000022"),
                    CLUB_2, "pilot-c2", "Pilot Two-Club"));         // club-2 pilot WITH flights (T-03)

    private final JdbcTemplate jdbc;
    private final ReferenceDataSeeder referenceDataSeeder;
    private final FlightStateTransitionService flightTransitions;
    private final TransactionTemplate txTemplate;

    public ShowcaseSeeder(JdbcTemplate jdbc,
                          ReferenceDataSeeder referenceDataSeeder,
                          FlightStateTransitionService flightTransitions,
                          PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.referenceDataSeeder = referenceDataSeeder;
        this.flightTransitions = flightTransitions;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * Idempotently loads the showcase tenancy + principal layer. Safe to re-run
     * and safe to run after the always-on dev seeds (every write is
     * {@code ON CONFLICT DO NOTHING}). Logs exactly what it loaded.
     */
    public void seed() {
        LOG.info("showcase-seed: loading tenancy + principals (idempotent upserts) ...");

        // Phase A — masterdata + flight base rows, in ONE transaction (the
        // co-module ReferenceDataSeeder is Propagation.MANDATORY, so it needs an
        // active tx). All JDBC + ON CONFLICT DO NOTHING upserts.
        txTemplate.executeWithoutResult(status -> {
            seedShowcaseClub();
            // Reference data for BOTH clubs. Club 1 already has it (provisioned
            // long ago) — the ON CONFLICT keeps that a no-op; Club 2 gets it
            // fresh, the same member_state + flight_type defaults a real club gets.
            referenceDataSeeder.seedDefaults(CLUB_1);
            referenceDataSeeder.seedDefaults(CLUB_2);
            seedPrincipals();
            seedLocations();
            seedAircraft();
            seedPersonsAndLinks();
            seedFlightBaseRows();
        });

        // Phase B — drive the flights to their target process-state through the
        // real FlightStateTransitionService. This runs AFTER phase A commits and
        // OUTSIDE the seeder's own transaction: Hibernate resolves the @TenantId
        // for each transition's session at session-open under Tenants.runAs, so
        // the tenant-scoped Flight load sees the just-committed rows. (Folding
        // the transitions into phase A's session bound it to NO_TENANT, resolved
        // before any runAs, and the load returned empty.)
        driveFlightTransitions();

        LOG.info("showcase-seed: done — clubs=[seed-club-1, showcase-club-2], "
                + "reference-data seeded per club, {} net-new principal(s): {}, "
                + "6 locations (3/club) + 5 aircraft (glider/tow/TMG/charter), "
                + "14 flights (8 club-1 + 6 club-2; aerotow/winch/motor variants across "
                + "NotProcessed/Valid/Invalid/Locked/DeliveryBooked via real domain transitions)",
                PRINCIPALS.size(),
                PRINCIPALS.stream().map(ShowcasePrincipal::username).toList());
    }

    private void seedShowcaseClub() {
        // deployment_id omitted → defaults to the operator Deployment
        // (00000000-0000-0000-0000-000000000002, V14), exactly like seed-club-1:
        // a long-lived operator-hosted club, not a trial.
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id,
                        slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                ON CONFLICT (id) DO NOTHING
                """,
                CLUB_2.toString(), "Showcase Club Two", "SHOW2",
                COUNTRY_CH.toString(), CLUB_STATE_ACTIVE.toString(), "showcase-club-2");
    }

    private void seedPrincipals() {
        for (ShowcasePrincipal p : PRINCIPALS) {
            jdbc.update("""
                    INSERT INTO t_user (id, club_id, username, friendly_name,
                            notification_email, language_id, keycloak_sub)
                    VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                    ON CONFLICT (id) DO NOTHING
                    """,
                    p.userId().toString(), p.clubId().toString(), p.username(),
                    p.friendlyName(), p.username() + "@example.com",
                    LANGUAGE_DE.toString(), p.keycloakSub().toString());
        }
    }

    // -------------------------------------------------------------------------
    // Locations — built through Location.create (ADR 0022 validation authority),
    // persisted with a deterministic id under Tenants.runAs(clubId, …) so the
    // tenant write-context contract the app uses is honoured.
    // -------------------------------------------------------------------------

    private void seedLocations() {
        Tenants.runAs(CLUB_1, () -> {
            insertLocation(LOCATION_C1_HOME, CLUB_1,
                    "Showcase Airfield One", "SHOW1", LOC_TYPE_GLIDER_AIRFIELD, "LSZX");
            insertLocation(LOCATION_C1_DEST_1, CLUB_1,
                    "Bern-Belp (showcase)", "BERN", LOC_TYPE_CONCRETE_RUNWAY, "LSGB");
            insertLocation(LOCATION_C1_DEST_2, CLUB_1,
                    "Saanen (showcase)", "SAAN", LOC_TYPE_CONCRETE_RUNWAY, "LSPD");
        });
        Tenants.runAs(CLUB_2, () -> {
            insertLocation(LOCATION_C2_HOME, CLUB_2,
                    "Showcase Airfield Two", "SHOW2", LOC_TYPE_GLIDER_AIRFIELD, "LSZW");
            insertLocation(LOCATION_C2_DEST_1, CLUB_2,
                    "Gruyeres (showcase)", "GRUY", LOC_TYPE_CONCRETE_RUNWAY, "LSGT");
            insertLocation(LOCATION_C2_DEST_2, CLUB_2,
                    "Montricher (showcase)", "MONT", LOC_TYPE_CONCRETE_RUNWAY, "LSPM");
        });
    }

    private void insertLocation(UUID id, UUID clubId, String name, String shortName,
                                UUID locationTypeId, String icao) {
        // Construct so the aggregate validates ICAO shape + blank-name + caps,
        // and normalises (trim) the inputs — we persist exactly what it accepts.
        Location loc = Location.create(
                name, shortName, COUNTRY_CH, locationTypeId, icao,
                null, null, null, null, null, null, null, null, null, null,
                false, false, false);
        jdbc.update("""
                INSERT INTO t_location (id, club_id, location_name, location_short_name,
                        country_id, location_type_id, icao_code,
                        is_inbound_route_required, is_outbound_route_required, is_fast_entry_record)
                VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid, ?::uuid, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                id.toString(), clubId.toString(), loc.getLocationName(),
                loc.getLocationShortName(), reqUuid(loc.getCountryId()),
                reqUuid(loc.getLocationTypeId()), loc.getIcaoCode(),
                loc.isInboundRouteRequired(), loc.isOutboundRouteRequired(),
                loc.isFastEntryRecord());
    }

    // -------------------------------------------------------------------------
    // Aircraft — built through Aircraft.register + changeState(OK) (validation +
    // normalisation + "exactly one open state period"), persisted with a
    // deterministic id. Cross-tenant (S-058): a managing_club_id gate, no
    // @TenantId, so no runAs wrap is needed — the manager club is explicit.
    // -------------------------------------------------------------------------

    private void seedAircraft() {
        // Club 1 fleet: glider, tow plane, TMG.
        insertAircraft(AIRCRAFT_C1_GLIDER, CLUB_1, AC_TYPE_GLIDER, "HB-3001",
                "Schleicher", "ASK 21", "X1", false);
        insertAircraft(AIRCRAFT_C1_TOW, CLUB_1, AC_TYPE_MOTOR_AIRCRAFT, "HB-TOW1",
                "Robin", "DR400", null, true);
        insertAircraft(AIRCRAFT_C1_MOTOR, CLUB_1, AC_TYPE_MOTOR_GLIDER, "HB-MOT1",
                "Diamond", "HK36 TTC", null, false);
        // Club 2 fleet: glider + a charter aircraft club 1 reads cross-tenant.
        insertAircraft(AIRCRAFT_C2_GLIDER, CLUB_2, AC_TYPE_GLIDER, "HB-3002",
                "Schleicher", "ASK 21", "X2", false);
        insertAircraft(AIRCRAFT_CHARTER_C2, CLUB_2, AC_TYPE_MOTOR_AIRCRAFT, "HB-CHTR",
                "Cessna", "C172", null, false);
    }

    private void insertAircraft(UUID id, UUID managingClubId, UUID aircraftTypeId,
                                String immatriculation, String manufacturer,
                                String model, @Nullable String competitionSign,
                                boolean towingAircraft) {
        // owner_club_id defaults to the managing club (own-club case), exactly
        // as AircraftsService.registerAircraft does.
        Aircraft a = Aircraft.register(
                managingClubId, managingClubId, aircraftTypeId, immatriculation,
                manufacturer, model, competitionSign, null, null, null, null, null,
                null, null, null, null, null, null, null,
                false, false, false, towingAircraft, null, null);
        // Opening airworthiness period — the aggregate enforces exactly-one-open.
        AircraftStateHistoryEntry state = a.changeState(AC_STATE_OK, AIRWORTHY_SINCE, null, null);

        jdbc.update("""
                INSERT INTO t_aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
                        manufacturer_name, aircraft_model, immatriculation, competition_sign,
                        is_towing_or_winch_required, is_towing_start_allowed,
                        is_winch_start_allowed, is_towing_aircraft, is_fast_entry_record)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                id.toString(), managingClubId.toString(), managingClubId.toString(),
                reqUuid(a.getAircraftTypeId()), a.getManufacturerName(), a.getAircraftModel(),
                a.getImmatriculation(), a.getCompetitionSign(),
                a.isTowingOrWinchRequired(), a.isTowingStartAllowed(),
                a.isWinchStartAllowed(), a.isTowingAircraft(), a.isFastEntryRecord());

        // Insert the validated opening state period with its own deterministic
        // id derived from the aircraft id (one open period per aircraft).
        jdbc.update("""
                INSERT INTO t_aircraft_aircraft_state (id, aircraft_id, aircraft_state_id,
                        valid_from, valid_to)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                stateRowId(id).toString(), id.toString(), reqUuid(state.getAircraftStateId()),
                java.sql.Timestamp.from(reqInstant(state.getValidFrom())), null);
    }

    /** Deterministic state-row id: the aircraft id bumped into the 0x5NN band. */
    private static UUID stateRowId(UUID aircraftId) {
        return UUID.fromString(
                aircraftId.toString().replaceFirst("-7401-", "-7501-"));
    }

    // -------------------------------------------------------------------------
    // Persons — the two PIC pilots get a t_person row linked onto their t_user
    // so MeService resolves user.person_id → the S-165 last-flight card filters
    // GET /api/v1/flights?personId=<person> against the seeded FlightCrew rows.
    // pilot-empty1 deliberately gets NO person + NO crew (empty-state principal).
    // -------------------------------------------------------------------------

    private void seedPersonsAndLinks() {
        // pilot1's Person is the SAME one the 8 club-1 flights are crewed against
        // (PERSON_PILOT1, person band …7601…0601). J-4 (T-14) enriches it with the
        // full self-edit field set the /profile tabs render (contact/address +
        // licence/medical) so BOTH surfaces resolve one person: the J-3 dashboard
        // last-flight card (filters GET /flights?personId=PERSON_PILOT1) AND the
        // J-4 profile Personal/Pilot/Notifications tabs. V30 no longer creates a
        // separate orphan person + relinks pilot1 (that broke the linkage — the
        // last-flight card went empty). pilot-c2's person stays minimal (J-3 only
        // needs its crew linkage; J-4 drives pilot1 only).
        insertPilot1Person();
        insertPerson(PERSON_PILOT_C2, "Two-Club", "Pilot");
        // pilot1's club-1 membership with the J-4 notification-pref values
        // (flightReports=true, reservations=false, clubNews/planning-reminder=true).
        insertPilot1PersonClub();
        // Link the person onto the existing t_user (idempotent — only sets it
        // when still null, so a re-run is a no-op and we never clobber a real link).
        linkUserPerson(USER_PILOT1, PERSON_PILOT1);
        linkUserPerson(USER_PILOT_C2, PERSON_PILOT_C2);
        // pilot1's mutable Account-tab self-field that V8 doesn't seed
        // (friendly_name / notification_email / language_id come from V8). The
        // /profile Account tab renders + round-trips phone_number.
        jdbc.update(
                "UPDATE t_user SET phone_number = ? WHERE id = ?::uuid AND phone_number IS NULL",
                "+41 79 000 00 01", USER_PILOT1.toString());
    }

    private void insertPerson(UUID id, String lastname, String firstname) {
        jdbc.update("""
                INSERT INTO t_person (id, lastname, firstname, country_id)
                VALUES (?::uuid, ?, ?, ?::uuid)
                ON CONFLICT (id) DO NOTHING
                """,
                id.toString(), lastname, firstname, COUNTRY_CH.toString());
    }

    /**
     * pilot1's fully-populated Person (J-4 T-14). Carries the contact/address +
     * licence/medical fields the /profile Personal + Pilot tabs render. Field
     * values match the J-4 task contract (T-02): has_glider_pilot_licence=true,
     * licence_number=CH-GLD-0001, medical_class2_expire_date=2027-09-30.
     */
    private void insertPilot1Person() {
        jdbc.update("""
                INSERT INTO t_person (
                    id, lastname, firstname,
                    address_line1, zip, city, region, country_id,
                    private_phone, mobile_phone, business_phone,
                    email_private, email_business, prefer_mail_to_business_mail,
                    birthday, enable_address,
                    has_glider_pilot_licence, licence_number, medical_class2_expire_date
                ) VALUES (
                    ?::uuid, 'One', 'Pilot',
                    'Flugplatzstrasse 1', '3000', 'Bern', 'BE', ?::uuid,
                    '+41 31 000 00 01', '+41 79 000 00 01', '+41 31 000 00 02',
                    'pilot1.private@example.com', 'pilot1.business@example.com', false,
                    DATE '1985-06-15', true,
                    true, 'CH-GLD-0001', DATE '2027-09-30'
                )
                ON CONFLICT (id) DO NOTHING
                """,
                PERSON_PILOT1.toString(), COUNTRY_CH.toString());
    }

    /**
     * pilot1's club-1 membership with the J-4 notification-pref values (the
     * /profile Notifications tab edits these). member_state_id stays NULL — no
     * t_member_state rows are seeded for seed-club-1, and the FK would reject a
     * dangling reference; member_number carries the membership-identity value.
     */
    private void insertPilot1PersonClub() {
        jdbc.update("""
                INSERT INTO t_person_club (
                    id, person_id, club_id, member_number,
                    is_glider_pilot,
                    receive_flight_reports,
                    receive_aircraft_reservation_notifications,
                    receive_planning_day_role_reminder,
                    is_active
                ) VALUES (
                    ?::uuid, ?::uuid, ?::uuid, 'M-0001',
                    true,
                    true,   -- receive_flight_reports
                    false,  -- receive_aircraft_reservation_notifications
                    true,   -- receive_planning_day_role_reminder
                    true
                )
                ON CONFLICT (id) DO NOTHING
                """,
                PERSON_CLUB_PILOT1.toString(), PERSON_PILOT1.toString(), CLUB_1.toString());
    }

    private void linkUserPerson(UUID userId, UUID personId) {
        jdbc.update(
                "UPDATE t_user SET person_id = ?::uuid WHERE id = ?::uuid AND person_id IS NULL",
                personId.toString(), userId.toString());
    }

    // -------------------------------------------------------------------------
    // The flight matrix. Built through Flight.create{Glider,Tow,Motor} +
    // linkTow + replaceCrew (so every aggregate invariant runs over the seed),
    // INSERTed JDBC-direct under a deterministic id, then driven to the target
    // process-state through the REAL FlightStateTransitionService edges:
    //
    //   Valid          : NotProcessed --VALIDATOR--> Valid
    //   Invalid        : NotProcessed --VALIDATOR--> Invalid
    //   Locked         : ... --VALIDATOR--> Valid --LOCK_JOB--> Locked
    //                    (gate: flight_date <= today-2d — dates picked to clear it)
    //   DeliveryBooked : ... --LOCK_JOB--> Locked --DELIVERY_PREP--> DeliveryPrepared
    //                    --BOOKING--> DeliveryBooked
    //                    (gate: locked_at <= today-3d — locked_at is stamped to
    //                    "now" by the LOCK edge, so we backdate it once between
    //                    LOCK and DELIVERY_PREP to simulate elapsed calendar time;
    //                    the transitions themselves all run through the domain.)
    //
    // Counts are deterministic + documented in showcase/README.md. Tenant-scoped
    // writes + transitions run under Tenants.runAs(clubId, …).
    // -------------------------------------------------------------------------

    /** One seeded flight's specification (kept flat — the matrix is small + explicit). */
    private record FlightSpec(UUID id,
                              UUID clubId,
                              FlightAircraftType type,
                              UUID aircraftId,
                              UUID picPersonId,
                              UUID startTypeId,
                              UUID homeLocationId,
                              int daysAgo,
                              FlightProcessState targetState,
                              @Nullable UUID towFlightId) {}

    /**
     * Club-1 matrix (pilot1 PIC): 8 rows. The three "today" rows drive the
     * club-admin today's-flights count (3); the NotProcessed + Invalid rows the
     * pending-validation count (4). One paired aerotow today + one locked, one
     * winch glider, motor flights across Valid / DeliveryBooked.
     */
    private static List<FlightSpec> club1Matrix() {
        return List.of(
                glider(flightId(0x01), CLUB_1, AIRCRAFT_C1_GLIDER, PERSON_PILOT1,
                        START_TYPE_AEROTOW, LOCATION_C1_HOME, 0, FlightProcessState.NOT_PROCESSED, flightId(0x02)),
                tow(flightId(0x02), CLUB_1, AIRCRAFT_C1_TOW, PERSON_PILOT1,
                        LOCATION_C1_HOME, 0, FlightProcessState.NOT_PROCESSED),
                glider(flightId(0x03), CLUB_1, AIRCRAFT_C1_GLIDER, PERSON_PILOT1,
                        START_TYPE_WINCH, LOCATION_C1_HOME, 0, FlightProcessState.NOT_PROCESSED, null),
                motor(flightId(0x04), CLUB_1, AIRCRAFT_C1_MOTOR, PERSON_PILOT1,
                        LOCATION_C1_HOME, 5, FlightProcessState.VALID),
                glider(flightId(0x05), CLUB_1, AIRCRAFT_C1_GLIDER, PERSON_PILOT1,
                        START_TYPE_WINCH, LOCATION_C1_HOME, 6, FlightProcessState.INVALID, null),
                glider(flightId(0x06), CLUB_1, AIRCRAFT_C1_GLIDER, PERSON_PILOT1,
                        START_TYPE_AEROTOW, LOCATION_C1_HOME, 10, FlightProcessState.LOCKED, flightId(0x07)),
                tow(flightId(0x07), CLUB_1, AIRCRAFT_C1_TOW, PERSON_PILOT1,
                        LOCATION_C1_HOME, 10, FlightProcessState.LOCKED),
                motor(flightId(0x08), CLUB_1, AIRCRAFT_C1_MOTOR, PERSON_PILOT1,
                        LOCATION_C1_HOME, 20, FlightProcessState.DELIVERY_BOOKED));
    }

    /** Club-2 matrix (pilot-c2 PIC): 6 rows. One today, one paired aerotow locked. */
    private static List<FlightSpec> club2Matrix() {
        return List.of(
                glider(flightId(0x11), CLUB_2, AIRCRAFT_C2_GLIDER, PERSON_PILOT_C2,
                        START_TYPE_WINCH, LOCATION_C2_HOME, 0, FlightProcessState.NOT_PROCESSED, null),
                motor(flightId(0x12), CLUB_2, AIRCRAFT_CHARTER_C2, PERSON_PILOT_C2,
                        LOCATION_C2_HOME, 5, FlightProcessState.VALID),
                glider(flightId(0x13), CLUB_2, AIRCRAFT_C2_GLIDER, PERSON_PILOT_C2,
                        START_TYPE_WINCH, LOCATION_C2_HOME, 7, FlightProcessState.INVALID, null),
                glider(flightId(0x14), CLUB_2, AIRCRAFT_C2_GLIDER, PERSON_PILOT_C2,
                        START_TYPE_AEROTOW, LOCATION_C2_HOME, 12, FlightProcessState.LOCKED, flightId(0x15)),
                tow(flightId(0x15), CLUB_2, AIRCRAFT_CHARTER_C2, PERSON_PILOT_C2,
                        LOCATION_C2_HOME, 12, FlightProcessState.LOCKED),
                glider(flightId(0x16), CLUB_2, AIRCRAFT_C2_GLIDER, PERSON_PILOT_C2,
                        START_TYPE_WINCH, LOCATION_C2_HOME, 22, FlightProcessState.DELIVERY_BOOKED, null));
    }

    /**
     * Phase A: insert all base NOT_PROCESSED rows + their PIC crew + the aerotow
     * tow_flight_id links, JDBC-direct under deterministic ids. Tow rows are
     * inserted before their glider links them. Runs in the seeder's masterdata
     * transaction; tenant-scoped writes wrapped in {@link Tenants#runAs}.
     */
    private void seedFlightBaseRows() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Tenants.runAs(CLUB_1, () -> insertClubFlightRows(club1Matrix(), today));
        Tenants.runAs(CLUB_2, () -> insertClubFlightRows(club2Matrix(), today));
    }

    private void insertClubFlightRows(List<FlightSpec> club, LocalDate today) {
        club.forEach(s -> insertFlightRow(s, today));
        club.forEach(s -> insertCrewRow(s, today));
        club.forEach(this::linkTowIfPaired);
    }

    /**
     * Phase B: drive each flight from NOT_PROCESSED to its target through the
     * real {@link FlightStateTransitionService}. Tow rows are skipped — their
     * glider drives them via {@code transitionWithTowCascade}. Each transition
     * is the service's own transaction, opened under {@link Tenants#runAs} so
     * Hibernate resolves the tenant against the just-committed rows.
     */
    private void driveFlightTransitions() {
        List<FlightSpec> club1 = club1Matrix();
        List<FlightSpec> club2 = club2Matrix();
        Tenants.runAs(CLUB_1, () ->
                club1.stream().filter(s -> !isCascadedTow(s, club1)).forEach(this::driveToState));
        Tenants.runAs(CLUB_2, () ->
                club2.stream().filter(s -> !isCascadedTow(s, club2)).forEach(this::driveToState));
    }

    /** A tow row is "cascaded" when some glider in the set links it — its glider drives it. */
    private static boolean isCascadedTow(FlightSpec spec, List<FlightSpec> all) {
        if (spec.type() != FlightAircraftType.TOW) {
            return false;
        }
        return all.stream().anyMatch(g -> spec.id().equals(g.towFlightId()));
    }

    private static FlightSpec glider(UUID id, UUID clubId, UUID aircraftId, UUID pic,
                                     UUID startType, UUID home, int daysAgo,
                                     FlightProcessState target, @Nullable UUID towFlightId) {
        return new FlightSpec(id, clubId, FlightAircraftType.GLIDER, aircraftId, pic,
                startType, home, daysAgo, target, towFlightId);
    }

    private static FlightSpec tow(UUID id, UUID clubId, UUID aircraftId, UUID pic,
                                  UUID home, int daysAgo, FlightProcessState target) {
        return new FlightSpec(id, clubId, FlightAircraftType.TOW, aircraftId, pic,
                START_TYPE_AEROTOW, home, daysAgo, target, null);
    }

    private static FlightSpec motor(UUID id, UUID clubId, UUID aircraftId, UUID pic,
                                    UUID home, int daysAgo, FlightProcessState target) {
        return new FlightSpec(id, clubId, FlightAircraftType.MOTOR, aircraftId, pic,
                START_TYPE_MOTOR, home, daysAgo, target, null);
    }

    /**
     * Build the aggregate (so every operational invariant runs), then INSERT
     * the base NOT_PROCESSED row under the fixed id. {@code @GeneratedValue}
     * means {@code repository.save} would mint a random id — so, exactly like
     * the locations/aircraft seed, we validate via the aggregate then carry the
     * deterministic id in a JDBC INSERT.
     */
    private void insertFlightRow(FlightSpec spec, LocalDate today) {
        LocalDate date = today.minusDays(spec.daysAgo());
        Instant start = date.atTime(10, 0).toInstant(ZoneOffset.UTC);
        Instant ldg = date.atTime(11, 0).toInstant(ZoneOffset.UTC);
        FlightOperationalData ops = new FlightOperationalData(
                date, start, ldg, start, ldg,
                spec.homeLocationId(), spec.homeLocationId(),
                null, null, null, null,
                null, spec.startTypeId(),
                (short) 1, (short) 1,
                false, false, null, null,
                "Showcase " + spec.type() + " flight", null, null, null,
                null, null, false);
        Flight f = switch (spec.type()) {
            case GLIDER -> Flight.createGlider(spec.aircraftId(),
                    FlightProcessState.NOT_PROCESSED.id(), ops);
            case TOW -> Flight.createTow(spec.aircraftId(),
                    FlightProcessState.NOT_PROCESSED.id(), ops);
            case MOTOR -> Flight.createMotor(spec.aircraftId(),
                    FlightProcessState.NOT_PROCESSED.id(), ops);
        };
        jdbc.update("""
                INSERT INTO t_flight (id, operating_club_id, aircraft_id, flight_date,
                        start_date_time, ldg_date_time, block_start_date_time, block_end_date_time,
                        start_location_id, ldg_location_id, start_type_id,
                        nr_of_ldgs, nr_of_ldgs_on_start_location,
                        no_start_time_information, no_ldg_time_information,
                        is_solo_flight, process_state_id, flight_aircraft_type_id, comment)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?::uuid, ?::uuid, ?::uuid,
                        ?, ?, ?, ?, ?, ?::uuid, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                spec.id().toString(), spec.clubId().toString(), spec.aircraftId().toString(),
                java.sql.Date.valueOf(date),
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(ldg),
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(ldg),
                spec.homeLocationId().toString(), spec.homeLocationId().toString(),
                spec.startTypeId().toString(),
                f.getNrOfLdgs(), f.getNrOfLdgsOnStartLocation(),
                f.isNoStartTimeInformation(), f.isNoLdgTimeInformation(), f.isSoloFlight(),
                FlightProcessState.NOT_PROCESSED.id().toString(),
                (int) spec.type().legacyId(), f.getComment());
    }

    /** One PIC crew row per flight (the pilot flying it). Deterministic id off the flight id. */
    private void insertCrewRow(FlightSpec spec, LocalDate today) {
        LocalDate date = today.minusDays(spec.daysAgo());
        Instant start = date.atTime(10, 0).toInstant(ZoneOffset.UTC);
        Instant ldg = date.atTime(11, 0).toInstant(ZoneOffset.UTC);
        // Build via the aggregate so FlightCrew's invariants run, then INSERT
        // under the fixed id (replaceCrew would persist through @GeneratedValue).
        CrewMemberSpec crew = new CrewMemberSpec(
                spec.picPersonId(), FlightCrewTypeIds.PILOT_OR_STUDENT,
                start, ldg, null, null, (short) 1, (short) 1);
        jdbc.update("""
                INSERT INTO t_flight_crew (id, flight_id, person_id, flight_crew_type_id,
                        begin_flight_datetime, end_flight_datetime, nr_of_ldgs, nr_of_starts)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                crewRowId(spec.id()).toString(), spec.id().toString(),
                crew.personId().toString(), crew.flightCrewTypeId().toString(),
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(ldg),
                crew.nrOfLdgs(), crew.nrOfStarts());
    }

    private void linkTowIfPaired(FlightSpec spec) {
        if (spec.towFlightId() == null) {
            return;
        }
        // Link through the aggregate (GLIDER→TOW, distinct rows, same club) and
        // persist just the tow_flight_id column under the fixed glider id.
        jdbc.update(
                "UPDATE t_flight SET tow_flight_id = ?::uuid WHERE id = ?::uuid AND tow_flight_id IS NULL",
                spec.towFlightId().toString(), spec.id().toString());
    }

    /**
     * Drive a seeded flight from NOT_PROCESSED to its target via the real
     * transition service. A paired glider uses the tow-cascade variant so its
     * linked tow moves with it (matching the system jobs). DeliveryBooked first
     * locks, then backdates {@code locked_at} past the bill gate, then prepares
     * + books — every edge still runs through the domain matrix + gate.
     */
    private void driveToState(FlightSpec spec) {
        FlightId id = FlightId.of(spec.id());
        // Idempotency: a re-run finds the flight already at its target (phase A
        // is a no-op upsert, so the row keeps run-1's final state). Re-driving
        // would be an illegal same-state edge — skip when already there.
        if (currentStateIdOf(spec.id()).equals(spec.targetState().id())) {
            return;
        }
        boolean cascade = spec.towFlightId() != null;
        switch (spec.targetState()) {
            case NOT_PROCESSED -> { /* initial state — no transition. */ }
            case VALID -> transition(id, cascade, FlightProcessState.VALID, TransitionTrigger.VALIDATOR);
            case INVALID -> transition(id, cascade, FlightProcessState.INVALID, TransitionTrigger.VALIDATOR);
            case LOCKED -> {
                transition(id, cascade, FlightProcessState.VALID, TransitionTrigger.VALIDATOR);
                transition(id, cascade, FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB);
            }
            case DELIVERY_BOOKED -> {
                transition(id, cascade, FlightProcessState.VALID, TransitionTrigger.VALIDATOR);
                transition(id, cascade, FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB);
                backdateLockedAt(spec);
                transition(id, cascade, FlightProcessState.DELIVERY_PREPARED, TransitionTrigger.DELIVERY_PREP);
                transition(id, cascade, FlightProcessState.DELIVERY_BOOKED, TransitionTrigger.BOOKING);
            }
            default -> throw new IllegalStateException(
                    "showcase-seed does not target " + spec.targetState());
        }
    }

    private UUID currentStateIdOf(UUID flightId) {
        String raw = jdbc.queryForObject(
                "SELECT process_state_id::text FROM t_flight WHERE id = ?::uuid",
                String.class, flightId.toString());
        return UUID.fromString(reqString(raw));
    }

    private static String reqString(@Nullable String value) {
        if (value == null) {
            throw new IllegalStateException("expected a non-null process_state_id for a seeded flight");
        }
        return value;
    }

    private void transition(FlightId id, boolean cascade,
                            FlightProcessState target, TransitionTrigger trigger) {
        if (cascade) {
            flightTransitions.transitionWithTowCascade(id, target, trigger);
        } else {
            flightTransitions.transition(id, target, trigger);
        }
    }

    /**
     * Backdate {@code locked_at} so the S-061 bill gate ({@code locked_at <=
     * today - 3d}) is cleared. The LOCK edge stamps {@code locked_at = now}; we
     * can't wait 3 real days, so we set it (and the paired tow's) ~5 days back.
     * Only the elapsed-time simulation is adjusted — the LOCKED state itself was
     * reached through the domain. Tenant predicate is applied via the explicit
     * club_id filter (this runs inside {@code Tenants.runAs} but raw JDBC has no
     * automatic tenant filter).
     */
    private void backdateLockedAt(FlightSpec spec) {
        Instant lockedDay = LocalDate.now(ZoneOffset.UTC).minusDays(5)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
        jdbc.update(
                "UPDATE t_flight SET locked_at = ? WHERE id = ?::uuid AND locked_at IS NOT NULL",
                java.sql.Timestamp.from(lockedDay), spec.id().toString());
        if (spec.towFlightId() != null) {
            jdbc.update(
                    "UPDATE t_flight SET locked_at = ? WHERE id = ?::uuid AND locked_at IS NOT NULL",
                    java.sql.Timestamp.from(lockedDay), spec.towFlightId().toString());
        }
    }

    private static UUID flightId(int n) {
        return UUID.fromString(String.format(
                "019e30c3-2c00-7801-8000-0000000008%02x", n));
    }

    /** Deterministic crew-row id: the flight id band 0x7801 bumped to 0x7901. */
    private static UUID crewRowId(UUID flightId) {
        return UUID.fromString(flightId.toString().replaceFirst("-7801-", "-7901-"));
    }

    private static String reqUuid(@Nullable UUID value) {
        if (value == null) {
            throw new IllegalStateException("aggregate yielded a null required UUID");
        }
        return value.toString();
    }

    private static Instant reqInstant(@Nullable Instant value) {
        if (value == null) {
            throw new IllegalStateException("aggregate yielded a null required instant");
        }
        return value.truncatedTo(ChronoUnit.SECONDS);
    }
}
