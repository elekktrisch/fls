package ch.alpenflight.migration.bundle;

import java.util.Locale;

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

    PERSON_FLIGHT_TIME_CREDIT(Group.ACCOUNTING),
    PERSON_FLIGHT_TIME_CREDIT_TRANSACTION(Group.ACCOUNTING),

    AUDIT_LOG(Group.IDENTITY);

    public enum Group { IDENTITY, FLIGHT, ACCOUNTING }

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

    public boolean fansOut() {
        return fansOut;
    }

    public boolean idMapSeededFromProvisioning() {
        return this == CLUB;
    }

    public boolean emitsIdentityMap() {
        return this != AIRCRAFT_AIRCRAFT_STATE && this != PERSON_CLUB;
    }

    public String temporaryTableSuffix() {
        return name().toLowerCase(Locale.ROOT);
    }
}
