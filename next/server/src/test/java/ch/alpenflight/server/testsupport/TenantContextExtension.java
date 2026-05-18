package ch.alpenflight.server.testsupport;

import java.lang.reflect.AnnotatedElement;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit Jupiter extension that bridges {@link WithTenant} into
 * {@link TenantTestContext}. {@link PostgresIntegrationTest} registers it
 * via {@code @ExtendWith}; meta-annotated on {@link WithTenant} so any class
 * carrying the annotation directly also gets the extension.
 *
 * <p>Resolution order: method-level {@code @WithTenant} (if present) wins;
 * otherwise the class-level annotation; otherwise no value is set and
 * {@link TenantTestContext#current()} returns empty for that test.
 *
 * <p>S-022 retrofits this extension's {@link #beforeEach(ExtensionContext)}
 * body to also push the value into Spring Security's context, so the
 * production resolver sees the same tenant via the production code path.
 * The annotation surface stays unchanged.
 */
public class TenantContextExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        resolveTenant(context).ifPresent(TenantTestContext::set);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TenantTestContext.clear();
    }

    private static Optional<UUID> resolveTenant(ExtensionContext context) {
        Optional<UUID> fromMethod = context.getTestMethod()
                .flatMap(m -> readAnnotation(m));
        if (fromMethod.isPresent()) {
            return fromMethod;
        }
        return context.getTestClass().flatMap(TenantContextExtension::readAnnotation);
    }

    private static Optional<UUID> readAnnotation(AnnotatedElement element) {
        WithTenant annotation = element.getAnnotation(WithTenant.class);
        if (annotation == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(annotation.value()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "@WithTenant value is not a valid UUID literal: " + annotation.value(), e);
        }
    }
}
