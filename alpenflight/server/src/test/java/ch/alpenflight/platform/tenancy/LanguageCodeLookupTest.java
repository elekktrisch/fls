package ch.alpenflight.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins the {@code unknown / missing → en} fallback and the per-JVM cache
 * contract on the locale-to-language lookup. Full-stack assertion (the
 * resolved {@code language_id} lands on the JIT-materialised row) lives
 * in {@code UsersJitFirstLoginIT}.
 */
class LanguageCodeLookupTest {

    private static final UUID DE_ID = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LanguageCodeLookup lookup = new LanguageCodeLookup(jdbc);

    @Test
    void resolve_knownLocale_returnsLanguageRowId() {
        when(jdbc.queryForObject(
                "SELECT id FROM t_language WHERE lower(code) = ?", UUID.class, "de"))
                .thenReturn(DE_ID);

        assertThat(lookup.resolve("DE")).isEqualTo(DE_ID);
    }

    @Test
    void resolve_unknownLocale_returnsEnFallback_andDoesNotPoisonCache() {
        when(jdbc.queryForObject(
                eq("SELECT id FROM t_language WHERE lower(code) = ?"), eq(UUID.class), eq("xx")))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(lookup.resolve("xx")).isEqualTo(LanguageCodeLookup.FALLBACK_EN_ID);
        // Second call still queries — unknown locales are not cached so a
        // late-arriving seed (operator dev path) can take effect without a
        // JVM restart.
        when(jdbc.queryForObject(
                eq("SELECT id FROM t_language WHERE lower(code) = ?"), eq(UUID.class), eq("xx")))
                .thenThrow(new EmptyResultDataAccessException(1));
        assertThat(lookup.resolve("xx")).isEqualTo(LanguageCodeLookup.FALLBACK_EN_ID);
    }

    @Test
    void resolve_nullOrBlankLocale_returnsEnFallback_withoutQuery() {
        assertThat(lookup.resolve(null)).isEqualTo(LanguageCodeLookup.FALLBACK_EN_ID);
        assertThat(lookup.resolve("")).isEqualTo(LanguageCodeLookup.FALLBACK_EN_ID);
        assertThat(lookup.resolve("   ")).isEqualTo(LanguageCodeLookup.FALLBACK_EN_ID);
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void resolve_knownLocale_isCachedAfterFirstHit() {
        when(jdbc.queryForObject(
                eq("SELECT id FROM t_language WHERE lower(code) = ?"), eq(UUID.class), eq("de-ch")))
                .thenReturn(DE_ID);

        lookup.resolve("de-CH");
        lookup.resolve("de-CH");
        lookup.resolve("DE-CH");

        verify(jdbc, times(1)).queryForObject(
                "SELECT id FROM t_language WHERE lower(code) = ?", UUID.class, "de-ch");
    }
}
