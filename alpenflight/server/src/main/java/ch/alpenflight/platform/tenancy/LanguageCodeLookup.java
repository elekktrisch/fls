package ch.alpenflight.platform.tenancy;

import ch.alpenflight.referencedata.domain.LanguageRepository;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class LanguageCodeLookup {

    public static final UUID FALLBACK_EN_ID =
            UUID.fromString("019e2e15-2c00-77d3-8000-0000000007d3");

    private final LanguageRepository languages;
    private final ConcurrentMap<String, UUID> neverEvictedCacheOfStaticLanguageSeed =
            new ConcurrentHashMap<>();

    public LanguageCodeLookup(LanguageRepository languages) {
        this.languages = languages;
    }

    public UUID resolve(@Nullable String locale) {
        if (locale == null || locale.isBlank()) {
            return FALLBACK_EN_ID;
        }
        String key = locale.toLowerCase(Locale.ROOT);
        UUID cached = neverEvictedCacheOfStaticLanguageSeed.get(key);
        if (cached != null) {
            return cached;
        }
        return queryFor(key).map(uuid -> {
            neverEvictedCacheOfStaticLanguageSeed.put(key, uuid);
            return uuid;
        }).orElse(FALLBACK_EN_ID);
    }

    private Optional<UUID> queryFor(String lowerCode) {
        return languages.findIdByCodeIgnoreCase(lowerCode);
    }
}
