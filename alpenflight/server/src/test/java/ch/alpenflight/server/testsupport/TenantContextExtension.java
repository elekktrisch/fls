package ch.alpenflight.server.testsupport;

import java.lang.reflect.AnnotatedElement;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

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
