package ch.alpenflight.me.application;

/**
 * Cross-tenant totals for the sysadmin dashboard tiles (J-3 T-10). All three
 * span EVERY club/tenant — the deliberate opposite of the tenant-scoped
 * club-admin counts. Published read shape: {@code SystemDashboardController}
 * projects this onto its wire DTO.
 *
 * @param totalClubs   active clubs across the deployment (clubs are the tenant
 *                     root — unscoped count)
 * @param totalUsers   active users across all clubs (User has no
 *                     {@code @TenantId} — unscoped count)
 * @param totalFlights non-deleted flights across all clubs, summed per club
 *                     under {@code Tenants.runAs} (the sanctioned cross-tenant
 *                     read path for {@code @TenantId}-scoped Flight)
 */
public record SystemDashboardTotals(long totalClubs, long totalUsers, long totalFlights) {}
