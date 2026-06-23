package ch.alpenflight.joinrequests.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.joinrequests.domain.JoinRequest;
import ch.alpenflight.joinrequests.domain.JoinRequestRepository;
import ch.alpenflight.joinrequests.domain.JoinRequestStatus;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Round-trip + schema proof for {@link JoinRequest} (S-178). Verifies the
 * structural invariant the migration owns — the {@code ux_join_request_alive}
 * one-open-per-(sub, club) partial UNIQUE — and the tenancy boundary: a request
 * under club A is invisible to club B. The lifecycle FSM itself is unit-tested
 * (JoinRequestStateMachineTest); here we prove only what crosses the DB.
 */
class JoinRequestRepositoryIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_JRR_";
    private static final String KEY_PREFIX = "IT_JR_";

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-23T12:00:00Z"), ZoneOffset.UTC);

    @Autowired private JoinRequestRepository requests;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;
    @Autowired private JdbcTemplate jdbc;

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @Test
    void persists_a_pending_request_and_reads_it_back_by_id() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        UUID id = saveSubmit(clubA, sub, "the note");

        var loaded = TenantTestContext.runAs(clubA, () -> requests.findById(id));
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getKeycloakSub()).isEqualTo(sub);
        assertThat(loaded.get().getClubId()).isEqualTo(clubA);
        assertThat(loaded.get().getStatus()).isEqualTo(JoinRequestStatus.PENDING);
        assertThat(loaded.get().getNote()).isEqualTo("the note");
        assertThat(loaded.get().getEmail()).isEqualTo("pilot@example.com");
    }

    @Test
    void the_partial_unique_rejects_a_second_pending_for_the_same_sub_and_club() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        saveSubmit(clubA, sub, null);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .as("ux_join_request_alive — one open request per (sub, club)")
                .isThrownBy(() -> saveSubmit(clubA, sub, null));
    }

    @Test
    void the_same_sub_may_open_a_pending_request_in_a_different_club() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        saveSubmit(clubA, sub, null);

        // No collision: the partial UNIQUE keys on (sub, club) — a different club
        // is a different pair.
        UUID idB = saveSubmit(clubB, sub, null);
        assertThat(idB).isNotNull();
    }

    @Test
    void withdrawing_a_request_frees_the_pair_to_re_submit() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        UUID firstId = saveSubmit(clubA, sub, null);

        TenantTestContext.runAs(clubA, () -> {
            JoinRequest first = requests.findById(firstId).orElseThrow();
            first.withdraw(clock);
            requests.save(first);
        });

        // The withdrawn row is no longer PENDING, so ux_join_request_alive lets a
        // fresh pending request through for the same (sub, club).
        UUID secondId = saveSubmit(clubA, sub, null);
        assertThat(secondId).isNotEqualTo(firstId);

        // Exactly one PENDING for the pair now exists — the new one. (The pending
        // list filters on status, so the withdrawn first row is excluded; this is
        // deterministic where ordering two same-timestamp rows would not be.)
        TenantTestContext.runAs(clubA, () ->
                assertThat(requests.findPendingForCurrentTenant())
                        .extracting(JoinRequest::getId)
                        .containsExactly(secondId));

        var latest = TenantTestContext.runAs(clubA, () -> requests.findLatestBySub(sub));
        assertThat(latest).isPresent();
    }

    @Test
    void pending_list_is_tenant_scoped_to_the_caller_club() {
        UUID subA = UuidCreator.getTimeOrderedEpoch();
        UUID subB = UuidCreator.getTimeOrderedEpoch();
        saveSubmit(clubA, subA, null);
        saveSubmit(clubB, subB, null);

        TenantTestContext.runAs(clubA, () ->
                assertThat(requests.findPendingForCurrentTenant())
                        .as("club A sees only its own pending request")
                        .extracting(JoinRequest::getKeycloakSub)
                        .containsExactly(subA));
        TenantTestContext.runAs(clubB, () ->
                assertThat(requests.findPendingForCurrentTenant())
                        .extracting(JoinRequest::getKeycloakSub)
                        .containsExactly(subB));
    }

    private UUID saveSubmit(UUID clubId, UUID sub, String note) {
        return TenantTestContext.runAs(clubId, () -> {
            JoinRequest r = JoinRequest.submit(
                    UuidCreator.getTimeOrderedEpoch(), sub,
                    "pilot@example.com", "Test Pilot", clubId, note, clock);
            return requests.save(r).getId();
        });
    }
}
