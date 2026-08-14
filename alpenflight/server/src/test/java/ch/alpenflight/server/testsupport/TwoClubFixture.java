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

public final class TwoClubFixture {

    private final JdbcTemplate jdbc;
    private final String namePrefix;
    private final String keyPrefix;

    private final ClubRepository clubs;
    private final CountryRepository countries;
    private final ClubStateRepository clubStates;

    private @Nullable UUID clubA;
    private @Nullable UUID clubB;

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

    public UUID clubA() {
        return Objects.requireNonNull(clubA, "clubA() called before seed()");
    }

    public UUID clubB() {
        return Objects.requireNonNull(clubB, "clubB() called before seed()");
    }

    public void seed() {
        seedViaProductionPath();
    }


    private void seedViaProductionPath() {
        ClubRepository repo = Objects.requireNonNull(clubs);
        UUID countryId = firstCountryId();
        UUID clubStateId = firstClubStateId();
        String slugA = slugFor("alpha");
        String slugB = slugFor("bravo");

        String keyA = clubKeyFor('a');
        String keyB = clubKeyFor('b');

        List<UUID> prior = priorClubIds(slugA, slugB, keyA, keyB);
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

    private List<UUID> priorClubIds(String slugA, String slugB, String keyA, String keyB) {
        return jdbc.queryForList(
                "SELECT id FROM t_club WHERE slug IN (?, ?) OR club_key IN (?, ?)",
                UUID.class, slugA, slugB, keyA, keyB);
    }

    private void deleteClubsBySlugOrKey(String slugA, String slugB, String keyA, String keyB) {
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
        String collapsed = sb.toString().replaceAll("-+", "-").replaceAll("^-+|-+$", "");
        if (collapsed.length() < 3) {
            collapsed = (collapsed + "-club").replaceAll("^-+", "");
        }
        return collapsed.length() > 64 ? collapsed.substring(0, 64) : collapsed;
    }

    private String clubKeyFor(char discriminator) {
        String base = keyPrefix == null || keyPrefix.isBlank() ? "K" : keyPrefix;
        String key = base + discriminator;
        return key.length() > 10 ? key.substring(0, 10) : key;
    }


    public void deleteTenantScopedRowsFor(UUID clubId) {
        deleteTenantScopedRowsFor(List.of(clubId));
    }

    public void deleteTenantScopedRowsFor(List<UUID> clubIds) {
        if (clubIds.isEmpty()) {
            return;
        }
        Object[] ids = clubIds.stream().map(UUID::toString).toArray();
        String in = inPlaceholders(clubIds.size());
        jdbc.update("DELETE FROM t_inoutbound_point WHERE location_id IN ("
                        + "  SELECT id FROM t_location WHERE club_id IN (" + in + "))", ids);
        jdbc.update("DELETE FROM t_delivery WHERE operating_club_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM t_flight WHERE operating_club_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM t_aircraft_reservation WHERE operating_club_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM t_planning_day WHERE operating_club_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM t_aircraft WHERE managing_club_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM t_user WHERE club_id IN (" + in + ")", ids);
        for (Class<?> entityClass : TenantScopedEntityCatalog.discoverTenantScopedEntities()) {
            String table = TenantScopedEntityCatalog.resolveTableName(entityClass);
            String tenantCol = TenantScopedEntityCatalog.resolveTenantColumnName(entityClass);
            jdbc.update("DELETE FROM " + table + " WHERE " + tenantCol + " IN (" + in + ")", ids);
        }
    }

    private static String inPlaceholders(int size) {
        return String.join(", ", Collections.nCopies(size, "?::uuid"));
    }
}
