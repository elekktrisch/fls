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

    LOCATION(Group.FLIGHT, FanOut.YES),
    INOUTBOUND_POINT(Group.FLIGHT, FanOut.YES),
    START_TYPE(Group.FLIGHT),
    FLIGHT_TYPE(Group.FLIGHT),
    AIRCRAFT(Group.FLIGHT),
    AIRCRAFT_AIRCRAFT_STATE(Group.FLIGHT),
    AIRCRAFT_OPERATING_COUNTER(Group.FLIGHT),

    AIRCRAFT_RESERVATION_TYPE(Group.ACCOUNTING),
    AIRCRAFT_RESERVATION(Group.ACCOUNTING),
    PLANNING_DAY(Group.ACCOUNTING),
    PLANNING_DAY_ASSIGNMENT_TYPE(Group.ACCOUNTING),
    PLANNING_DAY_ASSIGNMENT(Group.ACCOUNTING),
    ARTICLE(Group.ACCOUNTING),
    ACCOUNTING_RULE_FILTER(Group.ACCOUNTING),

    FLIGHT(Group.FLIGHT),
    FLIGHT_CREW(Group.FLIGHT),

    DELIVERY(Group.ACCOUNTING),
    DELIVERY_ITEM(Group.ACCOUNTING),

    AUDIT_LOG(Group.IDENTITY);

    public enum Group { IDENTITY, FLIGHT, ACCOUNTING }

    /** Internal tri-state-free marker so the constant table reads declaratively. */
    private enum FanOut { YES, NO }

    private final Group group;
    private final boolean fansOut;

    EntityType(Group group) {
        this(group, FanOut.NO);
    }

    EntityType(Group group, FanOut fanOut) {
        this.group = group;
        this.fansOut = fanOut == FanOut.YES;
    }

    public Group group() {
        return group;
    }

    /**
     * Fan-out entities are tenant-scoped shared legacy masterdata: one legacy
     * row referenced by many clubs must land as N {@code club_id}-distinct
     * rows in the new stack (ADR 0008 tenant partitioning), so the producer
     * derives a per-(legacy_guid, legacy club) id via
     * {@link Coercions#deriveFanOutId} and emits a 3-column
     * {@code (legacy_guid, club_id, new_uuid)} id-map row
     * ({@link LegacyIdMapWriter#write(UUID, UUID, UUID)}).
     *
     * <p>{@code LOCATION} is the first fan-out entity; {@code INOUTBOUND_POINT}
     * is its child and itself carries {@code club_id} per the J-0b architect
     * decision, so it fans out too. Everything else — CLUB, COUNTRY,
     * CLUB_STATE, and the identity entities — is NOT fan-out and keeps the
     * existing 2-column id-map format untouched.
     */
    public boolean fansOut() {
        return fansOut;
    }

    public String temporaryTableSuffix() {
        return name().toLowerCase(Locale.ROOT);
    }
}
