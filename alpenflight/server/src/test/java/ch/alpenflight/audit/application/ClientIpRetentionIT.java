package ch.alpenflight.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditActorKind;
import ch.alpenflight.audit.domain.MutationAuditEvent;
import ch.alpenflight.audit.domain.MutationAuditEventRepository;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class ClientIpRetentionIT extends PostgresIntegrationTest {

    private static final Duration ONE_DAY_PAST_THE_WINDOW = Duration.ofDays(91);
    private static final Duration EXACTLY_THE_WINDOW = Duration.ofDays(90);
    private static final Duration ONE_DAY_INSIDE_THE_WINDOW = Duration.ofDays(89);
    private static final Duration YESTERDAY = Duration.ofDays(1);

    private static final String IP_PAST_THE_WINDOW = "203.0.113.91";
    private static final String IP_AT_THE_WINDOW = "203.0.113.90";
    private static final String IP_INSIDE_THE_WINDOW = "203.0.113.89";
    private static final String IP_OF_THE_ERASURE_SUBJECT = "203.0.113.11";
    private static final String IP_OF_THE_BYSTANDER = "203.0.113.12";
    private static final String IP_OF_THE_OTHER_TENANT = "203.0.113.77";
    private static final String IP_OF_THE_RETIRED_CLUB = "203.0.113.78";

    private static final String ANONYMOUS_ENTITY = "PublicFlightRegistration";
    private static final String MID_SWEEP_FAILURE = "mid-sweep write refused";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @Autowired ClientIpRetentionJob job;
    @Autowired Clock clock;

    @MockitoSpyBean MutationAuditEventRepository events;

    private UUID clubA;
    private UUID clubB;
    private String adminOfClubA;
    private String adminOfClubB;

    private UUID rowPastTheWindow;
    private UUID rowAtTheWindow;
    private UUID rowInsideTheWindow;
    private UUID rowOfTheErasureSubject;
    private UUID rowOfTheBystander;
    private UUID rowOfTheOtherTenant;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_CIR_", "IT_CIR_");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        dropSeededAuditRows();

        Instant seededAt = Instant.now();
        rowPastTheWindow = seedAnonymousRow(clubA, seededAt.minus(ONE_DAY_PAST_THE_WINDOW),
                IP_PAST_THE_WINDOW);
        rowAtTheWindow = seedAnonymousRow(clubA, seededAt.minus(EXACTLY_THE_WINDOW),
                IP_AT_THE_WINDOW);
        rowInsideTheWindow = seedAnonymousRow(clubA, seededAt.minus(ONE_DAY_INSIDE_THE_WINDOW),
                IP_INSIDE_THE_WINDOW);
        rowOfTheErasureSubject = seedAnonymousRow(clubA, seededAt.minus(YESTERDAY),
                IP_OF_THE_ERASURE_SUBJECT);
        rowOfTheBystander = seedAnonymousRow(clubA, seededAt.minus(YESTERDAY),
                IP_OF_THE_BYSTANDER);
        rowOfTheOtherTenant = seedAnonymousRow(clubB, seededAt.minus(YESTERDAY),
                IP_OF_THE_OTHER_TENANT);

        adminOfClubA = mintClubAdministrator(clubA);
        adminOfClubB = mintClubAdministrator(clubB);
    }

    @AfterEach
    void dropSeededAuditRowsAfterTheRun() {
        dropSeededAuditRows();
    }

    @Test
    void the_sweep_redacts_a_row_past_the_window_and_the_row_survives_with_every_other_cell_intact() {
        Map<String, Object> before = rawRow(rowPastTheWindow);
        assertThat(before.get("client_ip")).isEqualTo(IP_PAST_THE_WINDOW);

        ClientIpRetentionJob.RunSummary summary = job.runOnce();

        Map<String, Object> after = rawRow(rowPastTheWindow);
        assertThat(after)
                .as("the redaction keeps the audit row; a job that deleted rows would find none")
                .isNotEmpty();
        assertThat(after.get("client_ip")).isNull();
        before.remove("client_ip");
        after.remove("client_ip");
        assertThat(after)
                .as("every other cell of the audit row is byte-for-byte what it was")
                .containsExactlyInAnyOrderEntriesOf(before);
        assertThat(summary.redactedClientIpCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void the_sweep_leaves_a_row_inside_the_window_and_another_tenants_recent_row_untouched() {
        job.runOnce();

        assertThat(clientIpOf(rowInsideTheWindow))
                .as("a row one day inside the 90-day window keeps its client IP")
                .isEqualTo(IP_INSIDE_THE_WINDOW);
        assertThat(clientIpOf(rowOfTheErasureSubject)).isEqualTo(IP_OF_THE_ERASURE_SUBJECT);
        assertThat(clientIpOf(rowOfTheOtherTenant))
                .as("the per-tenant sweep redacts only what the window says, in every tenant alike")
                .isEqualTo(IP_OF_THE_OTHER_TENANT);
    }

    @Test
    void the_sweep_redacts_a_row_that_is_exactly_ninety_days_old() {
        job.runOnce();

        assertThat(clientIpOf(rowAtTheWindow))
                .as("90 days exactly falls on the redact side: the window keeps an IP for "
                        + "strictly less than 90 days")
                .isNull();
    }

    @Test
    void the_sweep_reaches_the_rows_of_a_soft_deleted_club() {
        Club retired = clubs.findActiveById(clubB).orElseThrow();
        retired.softDelete(clock);
        clubs.save(retired);
        UUID rowOfTheRetiredClub = seedAnonymousRow(clubB,
                Instant.now().minus(ONE_DAY_PAST_THE_WINDOW), IP_OF_THE_RETIRED_CLUB);

        job.runOnce();

        assertThat(clientIpOf(rowOfTheRetiredClub))
                .as("a club that closed its account still holds the registrants' IPs, so the "
                        + "retention sweep must reach its rows too")
                .isNull();
        assertThat(clientIpOf(rowOfTheOtherTenant))
                .as("and the retired club's rows inside the window are still untouched")
                .isEqualTo(IP_OF_THE_OTHER_TENANT);
    }

    @Test
    void the_on_request_path_redacts_the_targeted_subject_and_nothing_else() {
        ResponseEntity<String> accepted = redactAsAdmin(rowOfTheErasureSubject, adminOfClubA);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(clientIpOf(rowOfTheErasureSubject)).isNull();
        assertThat(rawRow(rowOfTheErasureSubject))
                .as("the erasure keeps the audit row")
                .isNotEmpty();
        assertThat(clientIpOf(rowOfTheBystander))
                .as("the second anonymous row of the same tenant is untouched")
                .isEqualTo(IP_OF_THE_BYSTANDER);
        assertThat(clientIpOf(rowInsideTheWindow)).isEqualTo(IP_INSIDE_THE_WINDOW);

        ResponseEntity<String> refused = redactAsAdmin(rowOfTheOtherTenant, adminOfClubA);
        assertThat(refused.getStatusCode())
                .as("an administrator of another club cannot reach this row at all")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(clientIpOf(rowOfTheOtherTenant)).isEqualTo(IP_OF_THE_OTHER_TENANT);

        assertThat(redactAsAdmin(rowOfTheOtherTenant, adminOfClubB).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(clientIpOf(rowOfTheOtherTenant)).isNull();

        assertThat(erasureAuditRowsOf(clubA))
                .as("the erasure request itself leaves a trace, and that trace carries no IP")
                .hasSize(1)
                .allSatisfy(row -> {
                    assertThat(row.get("target_entity_id"))
                            .hasToString(rowOfTheErasureSubject.toString());
                    assertThat(row.get("client_ip")).isNull();
                });
    }

    @Test
    void a_failure_mid_sweep_rolls_back_the_row_the_run_already_redacted() {
        doThrow(new IllegalStateException(MID_SWEEP_FAILURE))
                .when(events).saveRedactedRow(argThat(row -> rowAtTheWindow.equals(row.getId())));

        job.runOnce();

        assertThat(clientIpOf(rowPastTheWindow))
                .as("the sweep opens a real transaction, so the row it redacted before the "
                        + "failure keeps its client IP")
                .isEqualTo(IP_PAST_THE_WINDOW);
        assertThat(clientIpOf(rowAtTheWindow)).isEqualTo(IP_AT_THE_WINDOW);
    }

    private ResponseEntity<String> redactAsAdmin(UUID auditEventId, String token) {
        return rest.exchange(
                RequestEntity.delete(URI.create(
                                "/api/v1/admin/audit-events/" + auditEventId + "/client-ip"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private String mintClubAdministrator(UUID clubId) {
        UUID sub = UUID.randomUUID();
        return jwts.mintJitReady(sub, clubId, c -> c
                .claim("preferred_username", "client-ip-retention-admin-" + sub)
                .claim("locale", "en")
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private UUID seedAnonymousRow(UUID clubId, Instant occurredAt, String clientIp) {
        return TenantTestContext.runAs(clubId, () -> events.append(MutationAuditEvent.builder()
                .action(AuditAction.CREATE)
                .targetEntityType(ANONYMOUS_ENTITY)
                .targetEntityId(clubId)
                .occurredAt(occurredAt)
                .actorKind(AuditActorKind.ANONYMOUS_PUBLIC)
                .clientIp(clientIp)
                .build())).getId();
    }

    private @Nullable String clientIpOf(UUID auditEventId) {
        Object value = rawRow(auditEventId).get("client_ip");
        return value == null ? null : value.toString();
    }

    private Map<String, Object> rawRow(UUID auditEventId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM t_mutation_audit_event WHERE id = ?::uuid", auditEventId.toString());
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private List<Map<String, Object>> erasureAuditRowsOf(UUID clubId) {
        return jdbc.queryForList(
                "SELECT target_entity_id, client_ip FROM t_mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND action = ?",
                clubId.toString(), AuditAction.CLIENT_IP_REDACTED.name());
    }

    private void dropSeededAuditRows() {
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE tenant_club_id IN (?::uuid, ?::uuid)",
                clubA == null ? UUID.randomUUID().toString() : clubA.toString(),
                clubB == null ? UUID.randomUUID().toString() : clubB.toString());
    }
}
