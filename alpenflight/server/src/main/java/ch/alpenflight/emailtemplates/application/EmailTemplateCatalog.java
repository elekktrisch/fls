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

/**
 * Enumerates the S-082 Thymeleaf FILE defaults — the system templates that ship
 * with the app rather than living in {@code t_email_template}. The union read
 * starts from this set and lets a club override suppress the matching file
 * default.
 *
 * <p>The derivable key set is the bare file stem of every product template
 * under {@code classpath*:templates/email/*.html}. The {@code smoke} template is
 * a render-seam test artifact, not a product template, so it is excluded.
 *
 * <p>Keys are canonicalized lower-case to match the aggregate, so a file named
 * {@code Foo.html} and an override keyed {@code foo} resolve to the same union
 * entry regardless of case.
 */
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

    /** The file defaults that seed the union read, one per product template stem. */
    public List<FileDefault> fileDefaults() {
        return fileDefaults;
    }

    /**
     * The locale every shipped file default is keyed under. The single-locale
     * send path resolves a DB override against this same locale, so a file
     * default and its override share one identity.
     */
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

    /** A system/file default: its key, default locale, and the raw Thymeleaf source. */
    public record FileDefault(String templateKey, String languageLocale, String body) {}
}
