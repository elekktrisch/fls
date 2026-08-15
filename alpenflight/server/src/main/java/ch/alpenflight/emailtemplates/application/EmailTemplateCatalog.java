package ch.alpenflight.emailtemplates.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

@Component
public class EmailTemplateCatalog {

    private static final String LOCATION = "classpath*:templates/email/*.html";
    private static final String DEFAULT_LOCALE = "de";
    private static final Set<String> NON_PRODUCT_STEMS = Set.of("smoke");

    private final List<FileDefault> fileDefaults;

    public EmailTemplateCatalog() {
        this(new PathMatchingResourcePatternResolver());
    }

    EmailTemplateCatalog(ResourcePatternResolver resolver) {
        this.fileDefaults = scan(resolver);
    }

    public List<FileDefault> fileDefaults() {
        return fileDefaults;
    }

    public static String defaultLocale() {
        return DEFAULT_LOCALE;
    }

    private static List<FileDefault> scan(ResourcePatternResolver resolver) {
        try {
            Resource[] resources = resolver.getResources(LOCATION);
            return java.util.Arrays.stream(resources)
                    .map(EmailTemplateCatalog::toStem)
                    .filter(stem -> !NON_PRODUCT_STEMS.contains(stem))
                    .distinct()
                    .sorted()
                    .map(stem -> new FileDefault(stem, DEFAULT_LOCALE, readBody(resolver, stem)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to enumerate email file defaults", e);
        }
    }

    private static String toStem(Resource resource) {
        String name = Objects.requireNonNull(resource.getFilename(), "email template resource has no filename");
        String withoutSuffix = name.substring(0, name.length() - ".html".length());
        return withoutSuffix.toLowerCase(Locale.ROOT);
    }

    private static String readBody(ResourcePatternResolver resolver, String stem) {
        Resource resource = resolver.getResource("classpath:templates/email/" + stem + ".html");
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read email file default: " + stem, e);
        }
    }

    public record FileDefault(String templateKey, String languageLocale, String body) {}
}
