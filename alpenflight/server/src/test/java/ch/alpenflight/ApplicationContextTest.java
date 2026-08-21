package ch.alpenflight;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class ApplicationContextTest {

    static final String ENTITY_NAME_NO_CLASS_UNDER_CH_ALPENFLIGHT_DECLARES =
            "SnapshotTypeThatNoClassDeclares";

    static final String PLANTED_REDACTION_RULE_NAMING_A_TYPE_THAT_DOES_NOT_EXIST =
            "--audit.redaction.entities." + ENTITY_NAME_NO_CLASS_UNDER_CH_ALPENFLIGHT_DECLARES
                    + ".allow[0]=aFieldNoSnapshotDeclares";

    @DynamicPropertySource
    static void datasourceAndFlywayPropsSinceBaseFlywayTargetsTheMigratorRole(
            DynamicPropertyRegistry r) {
        var pg = SharedPostgresContainer.INSTANCE;
        r.add("spring.datasource.url", pg::jdbcUrl);
        r.add("spring.datasource.username", pg::username);
        r.add("spring.datasource.password", pg::password);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.flyway.url", pg::jdbcUrl);
        r.add("spring.flyway.user", pg::username);
        r.add("spring.flyway.password", pg::password);
    }

    @Test
    void contextLoads() {
    }

    @Test
    void the_real_application_refuses_to_start_when_a_redaction_rule_names_a_thing_that_does_not_exist() {
        var pg = SharedPostgresContainer.INSTANCE;

        assertThatThrownBy(() -> new SpringApplicationBuilder(AlpenFlightApplication.class)
                .profiles("test")
                .run("--spring.datasource.url=" + pg.jdbcUrl(),
                        "--spring.datasource.username=" + pg.username(),
                        "--spring.datasource.password=" + pg.password(),
                        "--spring.flyway.url=" + pg.jdbcUrl(),
                        "--spring.flyway.user=" + pg.username(),
                        "--spring.flyway.password=" + pg.password(),
                        PLANTED_REDACTION_RULE_NAMING_A_TYPE_THAT_DOES_NOT_EXIST)
                .close())
                .as("the audit redaction startup guard must run in the REAL SpringApplication that "
                        + "production runs, not only in a sliced ApplicationContextRunner: deleting "
                        + "its @Component annotation must red HERE")
                .rootCause()
                .hasMessageContaining("refuses to start")
                .hasMessageContaining(ENTITY_NAME_NO_CLASS_UNDER_CH_ALPENFLIGHT_DECLARES);
    }
}
