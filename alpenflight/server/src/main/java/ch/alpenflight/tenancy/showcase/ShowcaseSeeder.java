package ch.alpenflight.tenancy.showcase;

import ch.alpenflight.tenancy.provisioning.application.ReferenceDataSeeder;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The on-demand <strong>showcase seed</strong> — a cumulative, deterministic,
 * reusable demo dataset (J-3). Distinct from the lean per-IT Flyway dev seeds
 * (ADR 0021 keeps ITs fast; this loader is NEVER on the IT bootstrap path) and
 * distinct from the migrated fanout export (realistic but non-curated). The
 * showcase seed is curated + dial-able: fixed UUIDs so the e2e display spec can
 * assert against known rows and dashboard counts are predictable.
 *
 * <p><strong>Mechanism.</strong> A {@code @Profile("showcase")}
 * {@link ShowcaseSeedRunner} invokes this seeder once at boot. Everything is an
 * idempotent {@code ON CONFLICT DO NOTHING} upsert keyed on the deterministic
 * id, so re-running (or running alongside the always-on V5/V8/V26/V28/V29 dev
 * seeds, which it coexists with — extend, don't fight) is a clean no-op.
 *
 * <p><strong>This task (T-02) seeds the tenancy + principal layer only:</strong>
 * a 2nd showcase club + its reference data (member_state / flight_type, via the
 * same {@link ReferenceDataSeeder} a real provisioned club gets) + the paired
 * {@code t_user} rows for all three roles across both clubs (including one pilot
 * with NO flights so the empty-state stays reachable). Data entities
 * (aircraft / locations / flights) ride the per-journey-extension convention
 * documented in this module's {@code README.md} — see T-03 for the first
 * extension.
 *
 * <p>Runs JDBC-directly (mirroring {@link ReferenceDataSeeder}) rather than
 * through JPA so it doesn't depend on the {@code @TenantId} resolver: every
 * insert carries its {@code club_id} explicitly. The whole seed runs in one
 * transaction so {@link ReferenceDataSeeder#seedDefaults}'s
 * {@code Propagation.MANDATORY} contract is satisfied.
 */
@Component
public class ShowcaseSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(ShowcaseSeeder.class);

    // -------------------------------------------------------------------------
    // Deterministic identities. Suffix scheme mirrors the V5/V8 dev seeds:
    //   club:   019e30c3-2c00-7001-8000-00000000000N
    //   t_user: 019e30c3-2c00-7100-8000-0000000000NN
    // and the showcase principals' Keycloak subs (realm-export.json user ids):
    //           019e30c3-2c00-7200-8000-0000000000NN
    // -------------------------------------------------------------------------

    /** Club 1 — the canonical V5 dev club {@code seed-club-1}; reused, not re-created. */
    static final UUID CLUB_1 = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    /** Club 2 — the net-new showcase club so cross-tenant aggregates span &ge;2 clubs. */
    static final UUID CLUB_2 = UUID.fromString("019e30c3-2c00-7001-8000-000000000002");

    // Reference UUIDs from reference-seeds-canonical-uuids.json (V2 baseline).
    private static final UUID COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    /**
     * Showcase principal: deterministic {@code t_user} id + its paired Keycloak
     * sub (the realm-export user {@code id}) + the tenant it belongs to. The
     * three role realm-users themselves live in {@code alpenflight/auth/realm-export.json};
     * this seeder only materialises the matching {@code t_user} rows so
     * {@code UserPrincipalLookup.resolveTenantFor(jwt)} resolves a tenant the
     * moment the showcase principal authenticates (no JIT race).
     */
    private record ShowcasePrincipal(
            UUID userId, UUID keycloakSub, UUID clubId, String username, String friendlyName) {}

    private static final List<ShowcasePrincipal> PRINCIPALS = List.of(
            // --- Already in realm-export + seeded by V8/V26/V28/V29; the showcase
            //     reuses them rather than inventing duplicates (clubadmin1 = club-1
            //     admin, pilot1 = club-1 pilot WITH flights, sysadmin = global). ---
            // --- Net-new showcase principals (added to realm-export.json in T-02): ---
            new ShowcasePrincipal(
                    UUID.fromString("019e30c3-2c00-7100-8000-000000000020"),
                    UUID.fromString("019e30c3-2c00-7200-8000-000000000020"),
                    CLUB_1, "pilot-empty1", "Pilot Empty One"),     // club-1 pilot with NO flights
            new ShowcasePrincipal(
                    UUID.fromString("019e30c3-2c00-7100-8000-000000000021"),
                    UUID.fromString("019e30c3-2c00-7200-8000-000000000021"),
                    CLUB_2, "clubadmin-c2", "Club Admin Two-Club"), // club-2 admin
            new ShowcasePrincipal(
                    UUID.fromString("019e30c3-2c00-7100-8000-000000000022"),
                    UUID.fromString("019e30c3-2c00-7200-8000-000000000022"),
                    CLUB_2, "pilot-c2", "Pilot Two-Club"));         // club-2 pilot WITH flights (T-03)

    private final JdbcTemplate jdbc;
    private final ReferenceDataSeeder referenceDataSeeder;

    public ShowcaseSeeder(JdbcTemplate jdbc, ReferenceDataSeeder referenceDataSeeder) {
        this.jdbc = jdbc;
        this.referenceDataSeeder = referenceDataSeeder;
    }

    /**
     * Idempotently loads the showcase tenancy + principal layer. Safe to re-run
     * and safe to run after the always-on dev seeds (every write is
     * {@code ON CONFLICT DO NOTHING}). Logs exactly what it loaded.
     */
    @Transactional
    public void seed() {
        LOG.info("showcase-seed: loading tenancy + principals (idempotent upserts) ...");

        seedShowcaseClub();
        // Reference data for BOTH clubs. Club 1 already has it (provisioned long
        // ago) — the ON CONFLICT keeps that a no-op; Club 2 gets it fresh, the
        // same member_state + flight_type defaults a real provisioned club gets.
        referenceDataSeeder.seedDefaults(CLUB_1);
        referenceDataSeeder.seedDefaults(CLUB_2);
        seedPrincipals();

        LOG.info("showcase-seed: done — clubs=[seed-club-1, showcase-club-2], "
                + "reference-data seeded per club, {} net-new principal(s): {}",
                PRINCIPALS.size(),
                PRINCIPALS.stream().map(ShowcasePrincipal::username).toList());
    }

    private void seedShowcaseClub() {
        // deployment_id omitted → defaults to the operator Deployment
        // (00000000-0000-0000-0000-000000000002, V14), exactly like seed-club-1:
        // a long-lived operator-hosted club, not a trial.
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id,
                        slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                ON CONFLICT (id) DO NOTHING
                """,
                CLUB_2.toString(), "Showcase Club Two", "SHOW2",
                COUNTRY_CH.toString(), CLUB_STATE_ACTIVE.toString(), "showcase-club-2");
    }

    private void seedPrincipals() {
        for (ShowcasePrincipal p : PRINCIPALS) {
            jdbc.update("""
                    INSERT INTO t_user (id, club_id, username, friendly_name,
                            notification_email, language_id, keycloak_sub)
                    VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                    ON CONFLICT (id) DO NOTHING
                    """,
                    p.userId().toString(), p.clubId().toString(), p.username(),
                    p.friendlyName(), p.username() + "@example.com",
                    LANGUAGE_DE.toString(), p.keycloakSub().toString());
        }
    }
}
