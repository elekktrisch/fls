package ch.alpenflight.server.testsupport;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.referencedata.domain.ClubState;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.Country;
import ch.alpenflight.referencedata.domain.CountryRepository;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two-club seeding helper used by the S-024 leakage sweep and the per-aggregate
 * isolation ITs. Creates {@code CLUB_A} and {@code CLUB_B} so tests can
 * {@code TenantTestContext.runAs(clubA(), ...)} without also having to construct
 * a tenant row.
 *
 * <p>Per ADR 0021 — pre-clean by stable test-name keys at seed time, no
 * {@code @AfterEach}. Per-class instances pick their own prefix so multiple
 * {@code IT}s coexist in the JVM without colliding.
 *
 * <h2>Two seeding paths (J-26 T-19, ADR 0027 — Option B)</h2>
 *
 * <ul>
 *   <li><strong>Production path (new, preferred):</strong> built with the
 *       {@link ClubRepository} / {@link CountryRepository} /
 *       {@link ClubStateRepository} domain ports, {@link #seed()} creates the
 *       two clubs through the real {@link Club#create} → {@code ClubRepository.save}
 *       path (mirroring {@code ClubsService.createClub} /
 *       {@code DeploymentProvisioningService.provision}). The ids are MINTED by
 *       the JPA {@code @GeneratedValue} and read back off the saved aggregates —
 *       {@link #clubA()} / {@link #clubB()} return those minted ids. A consumer
 *       captures the ids AFTER {@code seed()} rather than pinning them up front.
 *       This is the ADR-0027-clean path: zero seeding JDBC.</li>
 *   <li><strong>Legacy pinned-id path (deprecated, JDBC):</strong> built with a
 *       caller-supplied {@code (clubA, clubB)} UUID pair, {@link #seed()} INSERTs
 *       the two clubs at those exact ids via raw JDBC. Retained UNCHANGED so the
 *       ~28 existing consumers keep compiling + passing while T-19b/c/d migrate
 *       them to the production path; T-19d deletes this path. See
 *       {@link #TwoClubFixture(JdbcTemplate, UUID, UUID, String, String)}.</li>
 * </ul>
 *
 * <p>{@link #seed()} also clears tenant-scoped child rows under the two
 * clubs via {@link #deleteTenantScopedRows()} — driven by
 * {@link TenantScopedEntityCatalog} so a new {@code @TenantId} entity lands
 * its cleanup automatically. Aggregate-internal child tables that lack a
 * direct {@code club_id} (today: {@code inoutbound_point}) are handled by
 * an explicit reverse-dependency delete. Teardown is hard-DELETE JDBC under
 * BOTH paths (legit test infra — the production path deletes by the minted
 * ids it learned at save time).
 */
public final class TwoClubFixture {

    private final JdbcTemplate jdbc;
    private final String namePrefix;
    private final String keyPrefix;

    // Production path collaborators. Null in the legacy pinned-id path.
    private final @Nullable ClubRepository clubs;
    private final @Nullable CountryRepository countries;
    private final @Nullable ClubStateRepository clubStates;

    // Pinned up-front in the legacy path; minted at seed() in the production
    // path (hence non-final + nullable until seed() runs).
    private @Nullable UUID clubA;
    private @Nullable UUID clubB;

    /**
     * Production-create path (J-26 T-19a, ADR-0027-clean). Creates the two
     * clubs through {@link Club#create} + {@link ClubRepository#save}; the ids
     * are MINTED by JPA and read back via {@link #clubA()} / {@link #clubB()}
     * after {@link #seed()}. The {@code namePrefix} / {@code keyPrefix} derive a
     * valid, unique slug per club (see {@link #slugFor}); slugs aren't asserted
     * by consumers (only {@code ux_club_slug} uniqueness matters), so any valid
     * unique value works. The {@code jdbc} handle is used for teardown DELETEs
     * only — no seeding JDBC on this path.
     */
    public TwoClubFixture(JdbcTemplate jdbc,
                          ClubRepository clubs,
                          CountryRepository countries,
                          ClubStateRepository clubStates,
                          String namePrefix, String keyPrefix) {
        this.jdbc = jdbc;
        this.clubs = Objects.requireNonNull(clubs, "clubs");
        this.countries = Objects.requireNonNull(countries, "countries");
        this.clubStates = Objects.requireNonNull(clubStates, "clubStates");
        this.namePrefix = namePrefix;
        this.keyPrefix = keyPrefix;
    }

    /**
     * Legacy pinned-id path. Pre-creates {@code clubA} / {@code clubB} at the
     * supplied UUIDs via raw JDBC INSERT.
     *
     * @deprecated J-26 T-19 — Option B replaces pinned ids with minted ids
     *     discovered after {@link #seed()}. Use
     *     {@link #TwoClubFixture(JdbcTemplate, ClubRepository, CountryRepository,
     *     ClubStateRepository, String, String)} and capture the ids via
     *     {@link #clubA()} / {@link #clubB()}. T-19b/c/d migrate the remaining
     *     consumers; T-19d removes this constructor and its seeding JDBC.
     */
    @Deprecated
    public TwoClubFixture(JdbcTemplate jdbc, UUID clubA, UUID clubB,
                          String namePrefix, String keyPrefix) {
        this.jdbc = jdbc;
        this.clubA = clubA;
        this.clubB = clubB;
        this.namePrefix = namePrefix;
        this.keyPrefix = keyPrefix;
        this.clubs = null;
        this.countries = null;
        this.clubStates = null;
    }

    /** The first club's id (minted on the production path, pinned on the legacy path). */
    public UUID clubA() {
        return Objects.requireNonNull(clubA,
                "clubA() called before seed() on the production path");
    }

    /** The second club's id (minted on the production path, pinned on the legacy path). */
    public UUID clubB() {
        return Objects.requireNonNull(clubB,
                "clubB() called before seed() on the production path");
    }

    /** Wipes prior tenant rows + clubs, then creates the two test clubs. */
    public void seed() {
        if (clubs != null) {
            seedViaProductionPath();
        } else {
            seedViaLegacyJdbc();
        }
    }

    // -- Production path (new) --------------------------------------------------

    /**
     * Mints both clubs through the real {@link Club#create} →
     * {@link ClubRepository#save} path under the operator {@link Deployment},
     * exactly as {@code ClubsService.createClub} does. Clubs are the tenant root
     * (never {@code @TenantId}-scoped), so the save runs OUTSIDE any
     * {@code Tenants.runAs} — same as production. Pre-cleans any rows left from a
     * prior run by the deterministic derived slugs, then reads the minted ids
     * back off the saved aggregates.
     */
    private void seedViaProductionPath() {
        ClubRepository repo = Objects.requireNonNull(clubs);
        UUID countryId = firstCountryId();
        UUID clubStateId = firstClubStateId();
        String slugA = slugFor("alpha");
        String slugB = slugFor("bravo");

        String keyA = clubKeyFor('a');
        String keyB = clubKeyFor('b');

        // Re-runnability across the JVM: a previous run minted clubs at these
        // deterministic slugs/keys. Clear their tenant-scoped children + the club
        // rows before re-minting — both ux_club_slug (partial: slug IS NOT NULL)
        // and ux_club_key (FULL index, includes soft-deleted rows) would
        // otherwise reject the re-mint. Match prior rows by slug OR key,
        // INCLUDING soft-deleted ones (a partially-failed prior run can leave a
        // deleted_on row whose key still collides), so the lookup is NOT filtered
        // on deleted_on.
        List<UUID> prior = priorClubIds(slugA, slugB, keyA, keyB);
        // Cross-club FK order: a prior clubB row can reference a prior clubA
        // charter aircraft (S-058 cross-club aircraft), so all children of BOTH
        // prior clubs must go before EITHER club's aircraft/parents. Delete them
        // together in one global child→parent pass, never per-club.
        if (!prior.isEmpty()) {
            deleteTenantScopedRowsFor(prior);
        }
        deleteClubsBySlugOrKey(slugA, slugB, keyA, keyB);

        Club a = repo.save(Club.create(
                namePrefix + "alpha", slugA, keyA,
                false, countryId, clubStateId, Deployment.OPERATOR_ID));
        Club b = repo.save(Club.create(
                namePrefix + "bravo", slugB, keyB,
                false, countryId, clubStateId, Deployment.OPERATOR_ID));

        this.clubA = mintedId(a, "clubA");
        this.clubB = mintedId(b, "clubB");
    }

    private UUID firstCountryId() {
        List<Country> rows = Objects.requireNonNull(countries).findAllOrdered();
        return requireReferenceId(rows.isEmpty() || rows.getFirst().getId() == null
                ? null : rows.getFirst().getId().value(), "t_country");
    }

    private UUID firstClubStateId() {
        List<ClubState> rows = Objects.requireNonNull(clubStates).findAllOrdered();
        return requireReferenceId(rows.isEmpty() || rows.getFirst().getId() == null
                ? null : rows.getFirst().getId().value(), "t_club_state");
    }

    /**
     * Ids of any prior-run clubs at these slugs/keys, INCLUDING soft-deleted
     * rows — both unique indexes (slug partial, key full) reject a colliding
     * re-mint regardless of {@code deleted_on}, so the lookup is not filtered on
     * it. Used to scope the children cleanup before the club rows are hard-deleted.
     */
    private List<UUID> priorClubIds(String slugA, String slugB, String keyA, String keyB) {
        return jdbc.queryForList(
                "SELECT id FROM t_club WHERE slug IN (?, ?) OR club_key IN (?, ?)",
                UUID.class, slugA, slugB, keyA, keyB);
    }

    private void deleteClubsBySlugOrKey(String slugA, String slugB, String keyA, String keyB) {
        // Hard-delete the prior club rows so the re-mint's INSERT clears both
        // ux_club_slug and ux_club_key (both reject colliding soft-deleted rows).
        jdbc.update("DELETE FROM t_club WHERE slug IN (?, ?) OR club_key IN (?, ?)",
                slugA, slugB, keyA, keyB);
    }

    private static UUID mintedId(Club club, String label) {
        ClubId id = club.getId();
        if (id == null) {
            throw new IllegalStateException(label + " save returned a null id");
        }
        return id.value();
    }

    private static UUID requireReferenceId(@Nullable UUID id, String table) {
        if (id == null) {
            throw new IllegalStateException(
                    "No row in " + table + " — V3 seed must populate at least one reference row");
        }
        return id;
    }

    /**
     * Derives a valid, deterministic, per-club-unique slug from this fixture's
     * {@code keyPrefix} + a per-club discriminator. {@link Club#create} enforces
     * {@code ^[a-z0-9-]{3,64}$}, so the raw consumer prefixes ({@code IT_FRQ_},
     * {@code cdash}, {@code TGIT}, …) are lowercased, invalid chars collapsed to
     * {@code -}, padded to ≥3 chars, and suffixed with {@code -<discriminator>}.
     * Slugs aren't asserted by consumers — only their uniqueness is — so the
     * exact shape doesn't matter, only validity + the per-club difference.
     */
    String slugFor(String discriminator) {
        return sanitizeSlug(keyPrefix + "-" + discriminator);
    }

    private static String sanitizeSlug(String raw) {
        String lowered = raw.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lowered.length());
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ? c : '-');
        }
        // Collapse runs of '-' and trim leading/trailing '-' so the slug stays
        // tidy (purely cosmetic; the pattern itself permits runs).
        String collapsed = sb.toString().replaceAll("-+", "-").replaceAll("^-+|-+$", "");
        if (collapsed.length() < 3) {
            collapsed = (collapsed + "-club").replaceAll("^-+", "");
        }
        return collapsed.length() > 64 ? collapsed.substring(0, 64) : collapsed;
    }

    /** A valid (≤10-char, non-blank) club_key for the given discriminator. */
    private String clubKeyFor(char discriminator) {
        String base = keyPrefix == null || keyPrefix.isBlank() ? "K" : keyPrefix;
        String key = base + discriminator;
        return key.length() > 10 ? key.substring(0, 10) : key;
    }

    // -- Legacy pinned-id path (deprecated; JDBC seeding, removed in T-19d) -----

    private void seedViaLegacyJdbc() {
        deleteTenantScopedRows();
        deleteClubs();
        insertClub(Objects.requireNonNull(clubA), "alpha");
        insertClub(Objects.requireNonNull(clubB), "bravo");
    }

    public void deleteClubs() {
        jdbc.update("DELETE FROM t_club WHERE id IN (?::uuid, ?::uuid)",
                clubA().toString(), clubB().toString());
    }

    /** Deletes every tenant-scoped row under the two seed clubs. */
    public void deleteTenantScopedRows() {
        deleteTenantScopedRowsFor(List.of(clubA(), clubB()));
    }

    /**
     * Deletes every tenant-scoped row under one club id (production-path
     * per-prior-club convenience). Prefer {@link #deleteTenantScopedRowsFor(List)}
     * when more than one club's rows must go in the same teardown — a single
     * club is FK-safe in isolation, but two clubs can cross-reference (a clubB
     * flight on a clubA charter aircraft, S-058), so they must be deleted in one
     * global child→parent pass, not per-club.
     */
    public void deleteTenantScopedRowsFor(UUID clubId) {
        deleteTenantScopedRowsFor(List.of(clubId));
    }

    /**
     * Deletes every tenant-scoped row under ALL the given club ids in one global
     * child→parent pass. Cross-club FK safety is the reason this takes a list
     * rather than running per-club: since S-058 a flight / reservation / tow-link
     * of one club can reference ANOTHER club's charter aircraft (cross-club
     * aircraft), so every club's child rows must be deleted before EITHER club's
     * aircraft/parents — deleting one club's {@code t_aircraft} while another
     * club's referencing flights still exist violates the RESTRICT FK. Each
     * statement therefore spans every club id ({@code IN (...)}) before moving to
     * the next-shallower dependency level. The {@code @TenantId}-catalog loop runs
     * last so it isn't blocked by the RESTRICT FKs
     * (flights/reservations/planning-days/aircraft) cleared above.
     */
    public void deleteTenantScopedRowsFor(List<UUID> clubIds) {
        if (clubIds.isEmpty()) {
            return;
        }
        Object[] ids = clubIds.stream().map(UUID::toString).toArray();
        String in = inPlaceholders(clubIds.size());
        // Aggregate-internal child via parent FK chain — handled before
        // location's own delete. Today's only such case; future stories add
        // siblings here as they land.
        jdbc.update("DELETE FROM t_inoutbound_point WHERE location_id IN ("
                        + "  SELECT id FROM t_location WHERE club_id IN (" + in + "))", ids);
        // Flight rows hold a FK to Aircraft (ON DELETE RESTRICT). Delete flights
        // for ALL clubs up-front so the aircraft cleanup below isn't blocked —
        // including a cross-club flight that references another club's aircraft.
        // CASCADE handles flight_crew + the self tow_flight_id (SET NULL) via the
        // schema-level FKs.
        jdbc.update("DELETE FROM t_flight WHERE operating_club_id IN (" + in + ")", ids);
        // Aircraft reservations hold a RESTRICT FK to Aircraft (and to Location /
        // reservation-type, deleted later in the catalog loop). Like flights,
        // delete them for ALL clubs up-front so the explicit aircraft cleanup
        // below isn't blocked. The tenant-scoped reservation + type rows still get
        // a second (idempotent) delete in the catalog loop.
        jdbc.update("DELETE FROM t_aircraft_reservation WHERE operating_club_id IN (" + in + ")", ids);
        // Planning days hold a RESTRICT FK to Location; their assignment children
        // hold a RESTRICT FK to the assignment-type lookup. Delete days up-front
        // (CASCADE clears the assignment children) so the catalog loop's later
        // t_location / t_planning_day_assignment_type deletes aren't blocked. The
        // tenant-scoped day + type rows still get a second (idempotent) catalog
        // delete; t_planning_day_assignment is an aggregate-internal child (no
        // @TenantId) so it never appears in the catalog loop.
        jdbc.update("DELETE FROM t_planning_day WHERE operating_club_id IN (" + in + ")", ids);
        // Aircraft is cross-tenant since S-058 (reverts S-159), so it's no longer
        // in the catalog loop below. But managing_club_id → club is ON DELETE
        // RESTRICT, so aircraft rows under the seed clubs would block
        // deleteClubs(). Delete only AFTER every club's flights/reservations above
        // are gone (cross-club references). aircraft_aircraft_state and
        // aircraft_operating_counter cascade via their FKs.
        jdbc.update("DELETE FROM t_aircraft WHERE managing_club_id IN (" + in + ")", ids);
        for (Class<?> entityClass : TenantScopedEntityCatalog.discoverTenantScopedEntities()) {
            String table = TenantScopedEntityCatalog.resolveTableName(entityClass);
            String tenantCol = TenantScopedEntityCatalog.resolveTenantColumnName(entityClass);
            jdbc.update("DELETE FROM " + table + " WHERE " + tenantCol + " IN (" + in + ")", ids);
        }
    }

    /** Builds a {@code ?::uuid, ?::uuid, …} placeholder list of the given size. */
    private static String inPlaceholders(int size) {
        return String.join(", ", Collections.nCopies(size, "?::uuid"));
    }

    private void insertClub(UUID id, String slug) {
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM t_club_state LIMIT 1", UUID.class);
        // deployment_id defaults to the operator Deployment via the V14
        // column DEFAULT — IT fixtures don't need to surface it.
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id,
                                  slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                """,
                id.toString(),
                namePrefix + slug,
                keyPrefix + slug.charAt(0),
                Objects.requireNonNull(countryId).toString(),
                Objects.requireNonNull(clubStateId).toString(),
                namePrefix + slug);
    }
}
