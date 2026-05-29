package ch.alpenflight.migration.bundle;

import java.util.Map;

/**
 * Legacy tables intentionally not ported. Each entry carries a "WHY not
 * mapped" reason so the parity oracle's coverage gate (S-187) can fail
 * loudly when a new legacy DBUpdate adds a table that is neither mapped
 * nor explicitly registered here. Keyed by the legacy table name as it
 * appears in the FLSTest CREATE TABLE statement.
 */
public final class UnmappedTables {

    private UnmappedTables() { }

    public static final Map<String, String> REGISTRY = Map.of(
            "LanguageTranslation",
            "Legacy i18n bundle is replaced by frontend-side i18next per ADR 0004; "
                    + "no destination table.",
            "PersonFlightTimeCredit",
            "Legacy flight-time credit calculation is recomputed from t_flight + "
                    + "t_flight_crew per ADR 0022 directive 2; no destination table.",
            "PersonFlightTimeCreditHistory",
            "Audit-of-recomputed-value; redundant once PersonFlightTimeCredit is "
                    + "recomputed on demand.",
            "Setting",
            "Legacy per-club settings are replaced by typed config on Club aggregate "
                    + "per ADR 0018; no destination table.",
            "SystemData",
            "Legacy global key-value store for cron state; replaced by Spring scheduler "
                    + "+ Postgres advisory locks per ADR 0009.",
            "SystemLog",
            "Legacy application log table; replaced by structured logging (ADR 0011).",
            "SystemVersion",
            "Legacy schema-version table; superseded by Flyway history.",
            "UserAccountState",
            "Legacy account-state lookup; replaced by Keycloak account state per ADR 0007.",
            "PersonPersonCategory",
            "Legacy denormalized join cache for Person-PersonCategory M:N; replaced by "
                    + "t_person_category direct association.");
}
