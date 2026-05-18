/**
 * Clubs use-case orchestration. Transactional services, request/response
 * DTOs, the domain-to-DTO mapper.
 *
 * <p>Per ADR 0023 this layer depends on {@code clubs.domain} (aggregates +
 * the {@link ch.alpenflight.clubs.domain.ClubRepository} port) and on
 * Spring's transaction + DI infrastructure. It must NOT depend on
 * {@code clubs.web} (controllers) or {@code clubs.infra} (Spring Data
 * implementation) — the bidirectional ban is enforced by
 * {@code ch.alpenflight.arch.LayeringRulesTest}.
 *
 * <p>DTOs ship from this package because they're the service's wire
 * contract. The controller in {@code clubs.web} adapts HTTP to the
 * service signatures.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.clubs.application;
