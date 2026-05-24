/**
 * Domain layer for the articles module. Holds the {@link
 * ch.alpenflight.articles.domain.Article} aggregate root, its repository
 * port, and the domain exception vocabulary translated to HTTP problem
 * responses by {@code articles.web}.
 *
 * <p>Per ADR 0023 the {@code domain} package depends only on Jakarta
 * Persistence + JSpecify + Hibernate annotations. No Spring web, no Spring
 * Data, no application-layer types — the layering rules are enforced by
 * the ArchUnit suite.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.articles.domain;
