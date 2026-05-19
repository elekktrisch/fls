/**
 * Reference-data entities (Country, ClubState) + repository ports.
 *
 * <p>Per ADR 0023: aggregates / value objects depend only on the JDK,
 * JPA annotations (the deliberate Hibernate-on-aggregate concession),
 * JSpecify, and {@code ch.alpenflight.platform.*}. No Spring web, no
 * Spring stereotypes, no Jackson. Reference rows have no aggregate
 * invariants — they are loaded as-is from the V2 seed; the repository
 * ports speak read-only finders.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.referencedata.domain;
