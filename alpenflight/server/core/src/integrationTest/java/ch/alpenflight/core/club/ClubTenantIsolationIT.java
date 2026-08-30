package ch.alpenflight.core.club;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.PlatformApplication;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = PlatformApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ClubTenantIsolationIT {

    private static final String APP_PASSWORD = "app-user-test-password";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6")
            .withDatabaseName("alpenflight")
            .withUsername("alpenflight")
            .withPassword("alpenflight");

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.placeholders.appPassword", () -> APP_PASSWORD);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_user");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    @Test
    @Transactional
    void hibernateMintsAndReadsAUuidV7IdThroughTheNonOwnerRole() {
        Club club = new Club("Proof Club");

        entityManager.persist(club);
        entityManager.flush();

        assertThat(club.getId()).isNotNull();
        assertThat(club.getId().version()).isEqualTo(7);

        entityManager
                .createNativeQuery("select set_config('app.current_club_id', ?1, true)")
                .setParameter(1, club.getId().toString())
                .getSingleResult();
        entityManager.clear();

        Club reloaded = entityManager.find(Club.class, club.getId());

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getName()).isEqualTo("Proof Club");
    }

    @Test
    void matchingClubSessionVariableReturnsTheOneSeededRow() throws Exception {
        UUID seededClubId = seededClubId();

        assertThat(seededClubId.version()).isEqualTo(7);
        assertThat(queryClubRowCountAs(seededClubId.toString())).isEqualTo(1);
    }

    @Test
    void unsetClubSessionVariableReturnsZeroRowsWithNoError() throws Exception {
        assertThat(queryClubRowCountAs(null)).isEqualTo(0);
    }

    @Test
    void wrongClubSessionVariableReturnsZeroRows() throws Exception {
        assertThat(queryClubRowCountAs(UUID.randomUUID().toString())).isEqualTo(0);
    }

    @Test
    void clubTableHasForceRowLevelSecurityEnabled() throws Exception {
        try (Connection ownerConnection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = ownerConnection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery("SELECT relforcerowsecurity FROM pg_class WHERE relname = 'club'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getBoolean("relforcerowsecurity")).isTrue();
        }
    }

    private UUID seededClubId() throws Exception {
        try (Connection ownerConnection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = ownerConnection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT id FROM club")) {
            assertThat(resultSet.next()).isTrue();
            return (UUID) resultSet.getObject("id");
        }
    }

    private int queryClubRowCountAs(String clubId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (clubId != null) {
                    try (PreparedStatement setClubId =
                            connection.prepareStatement("select set_config('app.current_club_id', ?, true)")) {
                        setClubId.setString(1, clubId);
                        setClubId.execute();
                    }
                }
                try (Statement statement = connection.createStatement();
                        ResultSet resultSet = statement.executeQuery("SELECT * FROM club")) {
                    int count = 0;
                    while (resultSet.next()) {
                        count++;
                    }
                    return count;
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
