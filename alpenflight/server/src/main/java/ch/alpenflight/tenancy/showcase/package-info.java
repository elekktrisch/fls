/**
 * The on-demand <strong>showcase seed</strong> (J-3) — a cumulative,
 * deterministic, reusable demo dataset loaded in one command for local dev /
 * demo / the e2e display run. Homed in the {@code tenancy} module because its
 * going-in layer is tenancy + principals (clubs + {@code t_user} rows) and it
 * reuses the co-module {@code provisioning} reference-data seeder.
 *
 * <p>NOT a Flyway {@code V__} migration and NOT on the IT bootstrap path
 * (ADR 0021 keeps ITs lean). Gated by {@code @Profile("showcase")}. The
 * per-journey-extension convention is recorded in this package's
 * {@code README.md} (under {@code src/main/resources/showcase/}).
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.tenancy.showcase;
