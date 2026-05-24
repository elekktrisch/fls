/**
 * Cross-cutting JPA / persistence helpers shared by every module's
 * {@code domain} layer. Holds annotations and tiny pure utilities that
 * sit above any single aggregate's concerns — for example
 * {@link ch.alpenflight.platform.persistence.PersistedAuditActor} marks
 * write-only audit-actor columns so static-analysis warnings don't have
 * to be silenced inline at every usage.
 *
 * <p>Per ADR 0023 modules' {@code domain} packages may depend on
 * {@code ch.alpenflight.platform.id} + {@code .persistence} but nothing
 * higher up.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.platform.persistence;
