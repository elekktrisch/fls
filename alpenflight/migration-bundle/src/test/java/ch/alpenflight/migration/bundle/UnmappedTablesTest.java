package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnmappedTablesTest {

    @Test
    void carriesEveryLegacyTableNamedInS183Acceptance() {
        assertThat(UnmappedTables.REGISTRY).containsKeys(
                "LanguageTranslation",
                "PersonFlightTimeCredit",
                "Setting",
                "SystemData",
                "SystemLog",
                "SystemVersion",
                "UserAccountState",
                "PersonPersonCategory");
    }

    @Test
    void everyEntryCarriesNonBlankReason() {
        UnmappedTables.REGISTRY.forEach((table, reason) ->
                assertThat(reason)
                        .as("unmapped reason for %s", table)
                        .isNotBlank());
    }
}
