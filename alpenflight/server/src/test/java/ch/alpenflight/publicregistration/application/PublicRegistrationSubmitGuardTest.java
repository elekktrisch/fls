package ch.alpenflight.publicregistration.application;

import static ch.alpenflight.publicregistration.application.PublicRegistrationSubmitGuard.MAX_ATTEMPTS_PER_CLUB;
import static ch.alpenflight.publicregistration.application.PublicRegistrationSubmitGuard.MAX_ATTEMPTS_PER_SOURCE;
import static ch.alpenflight.publicregistration.application.PublicRegistrationSubmitGuard.WINDOW_MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The bucket semantics of the anonymous abuse guard, driven off a settable clock
 * so the window is exercised without sleeping.
 *
 * <p>The separation cases are the non-vacuity half: a single global counter
 * would satisfy "the eleventh attempt is refused" just as well, so every
 * exhaustion is paired with an assertion that a DIFFERENT source, or the same
 * source at a DIFFERENT club, is still served — and that the exhausted bucket is
 * still refusing at that same instant.
 */
class PublicRegistrationSubmitGuardTest {

    private static final String CLUB_A = "alpine-gliding";
    private static final String CLUB_B = "lakeside-soaring";
    private static final String IP_A = "203.0.113.10";
    private static final String IP_B = "203.0.113.11";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-02T09:00:00Z"));
    private final PublicRegistrationSubmitGuard guard = new PublicRegistrationSubmitGuard(clock);

    @Test
    void the_attempt_after_the_club_limit_is_throttled_with_a_retryAfter_inside_the_window() {
        exhaustClubLimit(IP_A, CLUB_A);

        assertThatThrownBy(() -> guard.recordAndCheck(IP_A, CLUB_A))
                .isInstanceOf(PublicRegistrationThrottledException.class)
                .satisfies(thrown -> {
                    long retryAfter =
                            ((PublicRegistrationThrottledException) thrown).retryAfterSeconds();
                    assertThat(retryAfter)
                            .isPositive()
                            .isLessThanOrEqualTo(Duration.ofMinutes(WINDOW_MINUTES).toSeconds());
                });
    }

    @Test
    void a_second_client_ip_does_not_inherit_the_first_ones_full_bucket() {
        exhaustClubLimit(IP_A, CLUB_A);

        assertThatCode(() -> guard.recordAndCheck(IP_B, CLUB_A)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.recordAndCheck(IP_A, CLUB_A))
                .isInstanceOf(PublicRegistrationThrottledException.class);
    }

    @Test
    void a_second_club_does_not_inherit_the_first_ones_full_bucket() {
        exhaustClubLimit(IP_A, CLUB_A);

        assertThatCode(() -> guard.recordAndCheck(IP_A, CLUB_B)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.recordAndCheck(IP_A, CLUB_A))
                .isInstanceOf(PublicRegistrationThrottledException.class);
    }

    @Test
    void the_window_expires_rather_than_banning_the_source_permanently() {
        exhaustClubLimit(IP_A, CLUB_A);
        assertThatThrownBy(() -> guard.recordAndCheck(IP_A, CLUB_A))
                .isInstanceOf(PublicRegistrationThrottledException.class);

        clock.advance(Duration.ofMinutes(WINDOW_MINUTES).plusSeconds(1));

        assertThatCode(() -> guard.recordAndCheck(IP_A, CLUB_A)).doesNotThrowAnyException();
    }

    @Test
    void the_retryAfter_shrinks_as_the_window_slides() {
        exhaustClubLimit(IP_A, CLUB_A);
        long immediately = refusalRetryAfter(IP_A, CLUB_A);

        clock.advance(Duration.ofMinutes(WINDOW_MINUTES / 2));

        assertThat(refusalRetryAfter(IP_A, CLUB_A)).isLessThan(immediately);
    }

    /**
     * Slug enumeration never trips the per-club limit — every fresh slug opens a
     * fresh bucket — so the per-source ceiling is what stops it and what bounds
     * the tracking map an anonymous caller can grow.
     */
    @Test
    void probing_a_fresh_slug_every_time_still_hits_the_perSource_ceiling() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_SOURCE; attempt++) {
            guard.recordAndCheck(IP_A, "probe-" + attempt);
        }

        assertThatThrownBy(() -> guard.recordAndCheck(IP_A, "probe-last"))
                .isInstanceOf(PublicRegistrationThrottledException.class);
        assertThatCode(() -> guard.recordAndCheck(IP_B, "probe-last")).doesNotThrowAnyException();
    }

    @Test
    void slug_case_variants_share_one_bucket() {
        String shouted = CLUB_A.toUpperCase(Locale.ROOT);
        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_CLUB; attempt++) {
            guard.recordAndCheck(IP_A, attempt % 2 == 0 ? CLUB_A : shouted);
        }

        assertThatThrownBy(() -> guard.recordAndCheck(IP_A, CLUB_A))
                .isInstanceOf(PublicRegistrationThrottledException.class);
    }

    private void exhaustClubLimit(String clientIp, String clubSlug) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_CLUB; attempt++) {
            guard.recordAndCheck(clientIp, clubSlug);
        }
    }

    private long refusalRetryAfter(String clientIp, String clubSlug) {
        try {
            guard.recordAndCheck(clientIp, clubSlug);
        } catch (PublicRegistrationThrottledException e) {
            return e.retryAfterSeconds();
        }
        throw new AssertionError("Expected the guard to refuse " + clientIp + " at " + clubSlug);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            this.now = this.now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
