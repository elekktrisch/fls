package ch.alpenflight.migration.bundle;

import java.util.Map;

/**
 * Legacy tables intentionally not ported. Each entry carries a "WHY not
 * mapped" reason so the parity oracle's coverage gate (S-187) can fail
 * loudly when a new legacy DBUpdate adds a table that is neither mapped
 * nor explicitly registered here. Keys are the legacy table names exactly
 * as the FLSTest CREATE TABLE statement spells them (plural, as the legacy
 * convention requires) — strict equality against the legacy schema dump.
 *
 * <p>Distinct from {@link Manifest#unmappedReason()} which is keyed by
 * {@link EntityType} (new-stack concept). Registry entries here have no
 * {@link EntityType} at all — there is no destination table.
 */
public final class UnmappedTables {

    private UnmappedTables() { }

    public static final Map<String, String> REGISTRY = Map.ofEntries(
            Map.entry("LanguageTranslations",
                    "Legacy i18n bundle is replaced by frontend-side i18next per ADR 0004; "
                            + "no destination table."),
            Map.entry("PersonFlightTimeCredits",
                    "Legacy per-person flight-time credit cache; recomputed from t_flight "
                            + "+ t_flight_crew per ADR 0022 directive 2; no destination table."),
            Map.entry("PersonFlightTimeCreditTransactions",
                    "Audit of recomputed credit deltas; redundant once "
                            + "PersonFlightTimeCredits is recomputed on demand."),
            Map.entry("Settings",
                    "Legacy per-club settings are replaced by typed config on Club aggregate "
                            + "per ADR 0018; no destination table."),
            Map.entry("SystemData",
                    "Legacy global key-value store for cron state; replaced by Spring "
                            + "scheduler + Postgres advisory locks per ADR 0009."),
            Map.entry("SystemLogs",
                    "Legacy application log table; replaced by structured logging "
                            + "(ADR 0011)."),
            Map.entry("SystemVersion",
                    "Legacy schema-version table; superseded by Flyway history."),
            Map.entry("UserAccountStates",
                    "Legacy account-state lookup; replaced by Keycloak account state per "
                            + "ADR 0007."),
            Map.entry("PersonPersonCategories",
                    "Legacy denormalized join cache for Person-PersonCategory M:N; replaced "
                            + "by direct t_person_category association."));
}
