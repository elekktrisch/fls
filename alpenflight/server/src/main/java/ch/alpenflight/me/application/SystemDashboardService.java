package ch.alpenflight.me.application;

import ch.alpenflight.clubs.application.ClubsService;
import ch.alpenflight.flights.application.FlightsService;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.users.application.UsersService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Composes the sysadmin dashboard's cross-tenant tile totals (J-3 T-10):
 * {@code totalClubs}, {@code totalUsers}, {@code totalFlights} — across ALL
 * clubs/tenants, the deliberate opposite of the tenant-scoped club-admin
 * dashboard (T-08).
 *
 * <p>Reads through the owning modules' published application APIs
 * ({@link ClubsService}, {@link UsersService}, {@link FlightsService}) — never
 * their internals. Mirrors {@code ClubDashboardController}'s "compose published
 * counts" pattern, homed in {@code me} because the dashboard is a
 * {@code me}-scoped surface.
 *
 * <h2>Cross-tenant counting</h2>
 *
 * <ul>
 *   <li><b>Clubs</b> are the tenant root, never {@code @TenantId}-scoped — a
 *       plain unscoped count ({@link ClubsService#countActiveClubs()}).</li>
 *   <li><b>Users</b> carry a {@code club_id} but have no {@code @TenantId} — a
 *       plain unscoped count ({@link UsersService#countAllActiveUsers()}).</li>
 *   <li><b>Flights</b> ARE {@code @TenantId}-scoped, so a single count would
 *       see only one club. To span all tenants the totals are summed one club
 *       at a time under {@link Tenants#runAs(UUID, java.util.function.Supplier)}
 *       — the sanctioned cross-tenant read path (ADR 0008 follow-up). No native
 *       SQL is used, so nothing is registered in {@code native-sql-register.md}:
 *       each per-club count still rides the {@code @TenantId} discriminator,
 *       only the effective tenant is rotated.</li>
 * </ul>
 *
 * <p>Works for a tenant-less SYSTEM_ADMINISTRATOR principal (no {@code clubId}
 * claim, per the J-2 audit work): nothing here reads the caller's tenant — the
 * club enumeration drives the flight tally, independent of who is asking. The
 * surface authz gate ({@code @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")}
 * on the controller) pairs with each {@code runAs} per its contract.
 */
@Service
public class SystemDashboardService {

    private final ClubsService clubs;
    private final UsersService users;
    private final FlightsService flights;

    SystemDashboardService(ClubsService clubs, UsersService users, FlightsService flights) {
        this.clubs = clubs;
        this.users = users;
        this.flights = flights;
    }

    /** Cross-tenant totals for the sysadmin dashboard tiles. */
    public SystemDashboardTotals totals() {
        long totalClubs = clubs.countActiveClubs();
        long totalUsers = users.countAllActiveUsers();

        long totalFlights = 0L;
        for (UUID clubId : clubs.activeClubIds()) {
            totalFlights += Tenants.runAs(clubId, flights::countAllFlights);
        }

        return new SystemDashboardTotals(totalClubs, totalUsers, totalFlights);
    }
}
