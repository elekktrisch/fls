package ch.alpenflight.migration.bundle;

import java.util.Map;

public final class UnmappedTables {

    private UnmappedTables() { }

    public static final Map<String, String> REGISTRY = Map.ofEntries(
            Map.entry("LanguageTranslations",
                    "Legacy i18n bundle is replaced by frontend-side i18next per ADR 0004; "
                            + "no destination table."),
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
            Map.entry("Roles",
                    "Realm-role catalog owned by Keycloak per ADR 0007; importer maps legacy "
                            + "role names to KC realm roles at S-028 provisioning without "
                            + "persisting a local catalog."),
            Map.entry("UserRoles",
                    "Per-user role assignment owned by Keycloak per ADR 0007; legacy "
                            + "assignments are translated to KC realm-role memberships at "
                            + "S-028 provisioning."));
}
