package ch.alpenflight.tenancy.showcase;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftStateHistoryEntry;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.tenancy.provisioning.application.ReferenceDataSeeder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    public ShowcaseSeeder(JdbcTemplate jdbc, ReferenceDataSeeder referenceDataSeeder) {
        this.jdbc = jdbc;
        this.referenceDataSeeder = referenceDataSeeder;
    }

    /**
     * Idempotently loads the showcase tenancy + principal layer. Safe to re-run
     * and safe to run after the always-on dev seeds (every write is
     * {@code ON CONFLICT DO NOTHING}). Logs exactly what it loaded.
     */
    @Transactional
    public void seed() {
        LOG.info("showcase-seed: loading tenancy + principals (idempotent upserts) ...");

        seedShowcaseClub();
        // Reference data for BOTH clubs. Club 1 already has it (provisioned long
        // ago) — the ON CONFLICT keeps that a no-op; Club 2 gets it fresh, the
        // same member_state + flight_type defaults a real provisioned club gets.
        referenceDataSeeder.seedDefaults(CLUB_1);
        referenceDataSeeder.seedDefaults(CLUB_2);
        seedPrincipals();
        seedLocations();
        seedAircraft();

        LOG.info("showcase-seed: done — clubs=[seed-club-1, showcase-club-2], "
                + "reference-data seeded per club, {} net-new principal(s): {}, "
                + "6 locations (3/club) + 5 aircraft (glider/tow/TMG/charter)",
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
