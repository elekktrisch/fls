package ch.alpenflight.audit.domain;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker on a {@code @RestController} method that uses a mutating HTTP verb
 * (almost always {@code POST}) as a <em>read</em>: a paged-list / search /
 * lookup that carries its query in the request body but performs no state
 * change. Such an endpoint legitimately emits no {@link AuditTrail} event, so
 * the {@code ControllerAuditCoverage} ArchUnit guard exempts it.
 *
 * <p>This is the narrow escape for the SPA-compat {@code POST .../page/{start}/
 * {size}} pattern (legacy {@code PageableSearchFilter} bodies don't fit a
 * {@code GET} query string). It is NOT a way to suppress audit on a real
 * mutation — a method that changes state must reach {@link AuditTrail#record}
 * (or use {@link AuditedBy}), never this marker.
 *
 * <p>Informational at runtime — no Spring stereotype; its value lives at build
 * time as the guard's read-shaped-POST exemption.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ReadOnlyQuery {
}
