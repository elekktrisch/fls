package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class UnmappedTablesTest {

    /** Legacy table names exactly as the FLSTest schema-dump spells them. */
    private static final List<String> AC_NAMED_LEGACY_TABLES = List.of(
            "LanguageTranslations",
            "PersonFlightTimeCredits",
            "PersonFlightTimeCreditTransactions",
            "Settings",
            "SystemData",
            "SystemLogs",
            "SystemVersion",
            "UserAccountStates",
            "PersonPersonCategories");

    @Test
    void coversEveryLegacyTableNamedInS183Acceptance() {
        assertThat(UnmappedTables.REGISTRY.keySet())
                .containsAll(AC_NAMED_LEGACY_TABLES);
    }

    @Test
    void everyEntryCarriesNonBlankReason() {
        UnmappedTables.REGISTRY.forEach((table, reason) ->
                assertThat(reason)
                        .as("unmapped reason for %s", table)
                        .isNotBlank());
    }
}
