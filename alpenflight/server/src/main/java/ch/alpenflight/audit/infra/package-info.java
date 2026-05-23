/**
 * Spring Data JPA adapter for the {@link
 * ch.alpenflight.audit.domain.MutationAuditEventRepository} port. Per
 * ADR 0023 only {@code audit.application} and the listener may inject the
 * port — {@code audit.web} stays one layer removed.
 */
@NullMarked
package ch.alpenflight.audit.infra;

import org.jspecify.annotations.NullMarked;
