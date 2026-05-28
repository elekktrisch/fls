package ch.alpenflight.migration.bundle;

import java.util.Locale;

/**
 * Topological insert order for migration ingest.
 *
 * <p>Enum declaration order IS the ingest order — {@link #values()} returns the
 * sequence S-141 walks per Club, S-139 writes the bundle in, and the parity
 * oracle walks for FK-integrity assertions. Re-ordering the constants here is a
 * schema-level decision; the FK-target-precedes-source invariant is enforced
 * by an ArchUnit rule under S-183.
 *
 * <p>The {@link #group} field carries the V2/V3/V4 sub-package classification:
 * each mapper lives under {@code ch.alpenflight.migration.bundle.identity},
 * {@code .flight}, or {@code .accounting} matching the originating Flyway
 * migration that defined the target table. The package-to-Group binding is
 * convention until S-183 lands the ArchUnit assertion.
 */
public enum EntityType {

    COUNTRY(Group.IDENTITY),
    LANGUAGE(Group.IDENTITY),
    CLUB_STATE(Group.IDENTITY),
    MEMBER_STATE(Group.IDENTITY),
    PERSON_CATEGORY(Group.IDENTITY),
    ROLE(Group.IDENTITY),

    CLUB(Group.IDENTITY),
    PERSON(Group.IDENTITY),
    PERSON_CLUB(Group.IDENTITY),
    USER(Group.IDENTITY),
    USER_ROLE(Group.IDENTITY),

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

    /** V2/V3/V4 Flyway-cluster grouping used to route mappers into sub-packages. */
    public enum Group { IDENTITY, FLIGHT, ACCOUNTING }

    private final Group group;

    EntityType(Group group) {
        this.group = group;
    }

    public Group group() {
        return group;
    }

    /** Stable lowercased identifier for use in {@code legacy_id_map_<name>} table names. */
    public String tempTableSuffix() {
        return name().toLowerCase(Locale.ROOT);
    }
}
