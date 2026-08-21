package ch.alpenflight.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditActorKind;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.audit.domain.MutationAuditEvent;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class EveryClientIpStaysReachableByTheRetentionSweepIT extends PostgresIntegrationTest {

    private static final String PROBE_ENTITY = "PublicFlightRegistration";

    private static final Duration ONE_DAY_PAST_THE_WINDOW = Duration.ofDays(91);

    private static final String IP_OF_THE_UNRESOLVED_TENANT_SUBMISSION = "203.0.113.201";
    private static final String IP_WRITTEN_OUTSIDE_THE_APPLICATION = "203.0.113.202";

    @Autowired AuditTrail auditTrail;
    @Autowired ClientIpRetentionJob job;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;

    private final UUID probeTarget = UUID.randomUUID();
    private final UUID rowWrittenOutsideTheApplication = UUID.randomUUID();

    @AfterEach
    void dropProbeRows() {
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE target_entity_id IN (?::uuid, ?::uuid)",
                probeTarget.toString(), rowWrittenOutsideTheApplication.toString());
    }

    @Test
    void an_anonymous_submission_with_no_resolvable_tenant_lands_without_the_client_ip() {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                auditTrail.recordAnonymousPublicSubmission(
                        AuditAction.CREATE,
                        new AuditedTarget(PROBE_ENTITY, probeTarget, null, null),
                        IP_OF_THE_UNRESOLVED_TENANT_SUBMISSION));

        Map<String, Object> landed = onlyRowTargeting(probeTarget);
        assertThat(landed)
                .as("the listener writes the audit row even when it resolves no tenant")
                .isNotEmpty();
        assertThat(landed.get("tenant_club_id"))
                .as("this is the unscoped write path: the row carries no tenant")
                .isNull();
        assertThat(landed.get("client_ip"))
                .as("the retention sweep reaches a row by its tenant, so an unscoped row that "
                        + "kept its client IP would keep it for ever")
                .isNull();
    }

    @Test
    void no_client_ip_anywhere_in_the_table_survives_the_sweep_once_its_window_elapsed() {
        seedRowWrittenOutsideTheApplication();

        assertThat(clientIpsPastTheWindowAnywhereInTheTable())
                .as("the seeded row is a genuine violator before the sweep runs")
                .isNotEmpty();

        job.runOnce();

        assertThat(clientIpsPastTheWindowAnywhereInTheTable())
                .as("every client IP the table holds must be reachable by the retention path, "
                        + "whatever wrote the row and whatever tenant it carries")
                .isEmpty();
    }

    private void seedRowWrittenOutsideTheApplication() {
        jdbc.update("INSERT INTO t_mutation_audit_event "
                        + "(id, occurred_at, tenant_club_id, action, target_entity_type, "
                        + "target_entity_id, actor_kind, client_ip) "
                        + "VALUES (?::uuid, ?, NULL, ?, ?, ?::uuid, ?, ?)",
                rowWrittenOutsideTheApplication.toString(),
                Timestamp.from(clock.instant().minus(ONE_DAY_PAST_THE_WINDOW)),
                AuditAction.CREATE.name(),
                PROBE_ENTITY,
                rowWrittenOutsideTheApplication.toString(),
                AuditActorKind.ANONYMOUS_PUBLIC.name(),
                IP_WRITTEN_OUTSIDE_THE_APPLICATION);
    }

    private List<Map<String, Object>> clientIpsPastTheWindowAnywhereInTheTable() {
        return jdbc.queryForList(
                "SELECT id, tenant_club_id, occurred_at, client_ip FROM t_mutation_audit_event "
                        + "WHERE client_ip IS NOT NULL AND occurred_at <= ?",
                Timestamp.from(MutationAuditEvent.clientIpRetentionCutoff(clock.instant())));
    }

    private Map<String, @Nullable Object> onlyRowTargeting(UUID targetEntityId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tenant_club_id, client_ip, actor_kind FROM t_mutation_audit_event "
                        + "WHERE target_entity_id = ?::uuid",
                targetEntityId.toString());
        assertThat(rows).hasSizeLessThanOrEqualTo(1);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }
}
