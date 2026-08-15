package ch.alpenflight.me.application;

import ch.alpenflight.clubs.application.ClubsService;
import ch.alpenflight.flights.application.FlightsService;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.users.application.UsersService;
import java.util.UUID;
import org.springframework.stereotype.Service;

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
