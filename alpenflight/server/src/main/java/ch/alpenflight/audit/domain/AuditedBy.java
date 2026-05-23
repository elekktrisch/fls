package ch.alpenflight.audit.domain;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker on a {@code @RestController} class or method that delegates auditing
 * to a named service bean which calls
 * {@link AuditTrail#record} transitively. The {@code ControllerAuditCoverage}
 * ArchUnit rule (in {@code src/test/java/ch/alpenflight/arch}) accepts either
 * (a) the controller method's call graph reaching {@link AuditTrail} OR
 * (b) this annotation naming the service bean responsible.
 *
 * <p>The annotation is informational at runtime — it carries no Spring
 * stereotype. Its value lives at build time, as the safety-net escape for
 * controllers whose audit hookup is harder for ArchUnit to trace (e.g.
 * lambda-dispatched async paths).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AuditedBy {

    /** Bean name of the service that owns the audit emission for this controller. */
    String value();
}
