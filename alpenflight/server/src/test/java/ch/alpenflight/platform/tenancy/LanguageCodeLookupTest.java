package ch.alpenflight.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.alpenflight.referencedata.domain.LanguageRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code unknown / missing → en} fallback and the per-JVM cache
 * contract on the locale-to-language lookup. Full-stack assertion (the
 * resolved {@code language_id} lands on the JIT-materialised row) lives
 * in {@code UsersJitFirstLoginIT}.
 *
 * <p>Collaborates with the {@link LanguageRepository} JPA port (ADR 0027 —
 * the JdbcTemplate dependency was retired at J-26 T-14). The lookup always
 * presents the repository a lowercased code, so these mocks stub on the
 * lowercase key.
 */
class LanguageCodeLookupTest {

    private static final UUID DE_ID = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    private final LanguageRepository languages = mock(LanguageRepository.class);
    private final LanguageCodeLookup lookup = new LanguageCodeLookup(languages);

    @Test
    void resolve_knownLocale_returnsLanguageRowId() {
        when(languages.findIdByCodeIgnoreCase("de")).thenReturn(Optional.of(DE_ID));

        assertThat(lookup.resolve("DE")).isEqualTo(DE_ID);
    }

    @Test
    void resolve_unknownLocale_returnsEnFallback_andDoesNotPoisonCache() {
        when(languages.findIdByCodeIgnoreCase("xx")).thenReturn(Optional.empty());

        assertThat(lookup.resolve("xx")).isEqualTo(LanguageCodeLookup.FALLBACK_EN_ID);
        // Second call still queries — unknown locales are not cached so a
        // late-arriving seed (operator dev path) can take effect without a
        // JVM restart.
        assertThat(lookup.resolve("xx")).isEqualTo(LanguageCodeLookup.FALLBACK_EN_ID);
        verify(languages, times(2)).findIdByCodeIgnoreCase("xx");
    }

    @Test
    void resolve_nullOrBlankLocale_returnsEnFallback_withoutQuery() {
        assertThat(lookup.resolve(null)).isEqualTo(LanguageCodeLookup.FALLBACK_EN_ID);
        assertThat(lookup.resolve("")).isEqualTo(LanguageCodeLookup.FALLBACK_EN_ID);
        assertThat(lookup.resolve("   ")).isEqualTo(LanguageCodeLookup.FALLBACK_EN_ID);
        verifyNoInteractions(languages);
    }

    @Test
    void resolve_knownLocale_isCachedAfterFirstHit() {
        when(languages.findIdByCodeIgnoreCase("de-ch")).thenReturn(Optional.of(DE_ID));

        lookup.resolve("de-CH");
        lookup.resolve("de-CH");
        lookup.resolve("DE-CH");

        verify(languages, times(1)).findIdByCodeIgnoreCase("de-ch");
    }
}
