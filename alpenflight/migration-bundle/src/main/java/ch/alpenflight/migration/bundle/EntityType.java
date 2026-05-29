package ch.alpenflight.migration.bundle;

import java.util.Locale;

/**
 * Topological insert order — declaration order IS ingest order. FK targets
 * must precede their sources; S-183's ArchUnit rule makes this structural.
 * {@link Group} routes mappers to the {@code .identity}, {@code .flight}, and
 * {@code .accounting} sub-packages matching V2/V3/V4 Flyway boundaries.
 *
 * <p>Role / UserRole intentionally absent: per ADR 0007, Keycloak owns the
 * realm-role catalog. Legacy {@code Roles} / {@code UserRoles} tables are
 * registered in {@link UnmappedTables#REGISTRY}.
 */
public enum EntityType {

    COUNTRY(Group.IDENTITY),
    LANGUAGE(Group.IDENTITY),
    CLUB_STATE(Group.IDENTITY),

    CLUB(Group.IDENTITY),
    PERSON(Group.IDENTITY),

    MEMBER_STATE(Group.IDENTITY),
    PERSON_CATEGORY(Group.IDENTITY),

    USER(Group.IDENTITY),
    PERSON_CLUB(Group.IDENTITY),
    PERSON_CATEGORY_ASSIGNMENT(Group.IDENTITY),

    LOCATION(Group.FLIGHT),
    START_TYPE(Group.FLIGHT),
    FLIGHT_TYPE(Group.FLIGHT),
    AIRCRAFT(Group.FLIGHT),
    AIRCRAFT_AIRCRAFT_STATE(Group.FLIGHT),
    AIRCRAFT_OPERATING_COUNTER(Group.FLIGHT),

    AIRCRAFT_RESERVATION(Group.ACCOUNTING),
    PLANNING_DAY(Group.ACCOUNTING),
    PLANNING_DAY_ASSIGNMENT(Group.ACCOUNTING),
    ARTICLE(Group.ACCOUNTING),
    ACCOUNTING_RULE_FILTER(Group.ACCOUNTING),

    FLIGHT(Group.FLIGHT),
    FLIGHT_CREW(Group.FLIGHT),

    DELIVERY(Group.ACCOUNTING),
    DELIVERY_ITEM(Group.ACCOUNTING),

    AUDIT_LOG(Group.IDENTITY);

    public enum Group { IDENTITY, FLIGHT, ACCOUNTING }

    private final Group group;

    EntityType(Group group) {
        this.group = group;
    }

    public Group group() {
        return group;
    }

    public String temporaryTableSuffix() {
        return name().toLowerCase(Locale.ROOT);
    }
}
