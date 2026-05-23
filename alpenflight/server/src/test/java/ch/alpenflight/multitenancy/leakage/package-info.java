/**
 * S-024 cross-tenant leakage CI gate. Contains:
 *
 * <ul>
 *   <li>{@link ch.alpenflight.multitenancy.leakage.LeakageSweepIT} —
 *       reflective per-repository sweep asserting create-as-A is invisible
 *       to B (and the fail-closed sentinel contract).</li>
 *   <li>{@link ch.alpenflight.multitenancy.leakage.CrossTenantPositiveSweepIT}
 *       — sacred-cow guard for {@code kind: cross-tenant} entries with an
 *       exposed JPA repository.</li>
 *   <li>{@link ch.alpenflight.multitenancy.leakage.CrossTenantNotFoundContract}
 *       — abstract MockMvc base; future tenant-scoped controllers extend it
 *       to inherit the 404-on-cross-tenant-id witness.</li>
 *   <li>{@link ch.alpenflight.multitenancy.leakage.NativeSqlRegisterTest} —
 *       plain JUnit allow-list parser + source-tree grep; rejects native SQL
 *       against tenant-scoped tables that lack a register entry.</li>
 *   <li>{@link ch.alpenflight.multitenancy.leakage.TenantSweepFloorAndPinTest}
 *       — harness-sanity guards: floor entity count + Hibernate version
 *       pin from {@code tenant-rules.yaml}.</li>
 * </ul>
 *
 * <p>The sweep package is deliberately separate from {@code arch/}
 * (bytecode-only ArchUnit) and from {@code server/migration/} (schema-shape
 * checks) — those layers don't run live data; this one does.
 */
package ch.alpenflight.multitenancy.leakage;
