/**
 * Persons infra: Spring Data JPA repository implementation for the
 * {@code Person} aggregate. Per ADR 0023, {@code infra/} owns the
 * Spring-Data dependency — the {@code application/} layer depends on the
 * {@code domain/} port only.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.persons.infra;
