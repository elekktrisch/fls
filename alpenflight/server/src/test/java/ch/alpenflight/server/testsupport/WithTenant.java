package ch.alpenflight.server.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test class or method as running under a specific tenant context.
 * {@link TenantContextExtension} parses {@link #value()} as a UUID and pushes
 * it into {@link TenantTestContext} before each test, clearing afterwards.
 * Method-level annotations override class-level ones.
 *
 * <p>The value is a {@code String} (Java annotations can't carry {@code UUID})
 * holding a UUID literal — e.g. {@code @WithTenant("019e30c3-2c00-7001-8000-000000000001")}.
 * S-022 consumes this contract: the production tenant ID type is
 * {@code UUID}, and the test-side annotation parses to the same.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(TenantContextExtension.class)
public @interface WithTenant {

    String value();
}
