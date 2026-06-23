/**
 * Domain layer for the email-templates module. Holds the {@link
 * ch.alpenflight.emailtemplates.domain.EmailTemplate} aggregate root and its
 * repository port.
 *
 * <p>Per ADR 0023 the {@code domain} package depends only on Jakarta
 * Persistence + JSpecify + Hibernate annotations. No Spring web, no Spring
 * Data, no application-layer types — the layering rules are enforced by the
 * ArchUnit suite.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.emailtemplates.domain;
