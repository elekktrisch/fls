package ch.alpenflight.server.testsupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import ch.alpenflight.tenancy.provisioning.domain.KeycloakDeploymentDirectory;
import java.util.UUID;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Shared test boundary: replaces the production
 * {@link KeycloakDeploymentDirectory} adapter with a {@code @Primary}
 * Mockito mock so migration-ingest ITs exercise the real ingest path
 * WITHOUT hitting a Keycloak realm.
 *
 * <p>Why this exists (J-0c T-07): T-02 wired a real Keycloak HTTP call
 * ({@code provisionMigratedClubAdmins} →
 * {@code KeycloakDeploymentDirectoryAdapter.provisionClubAdminIdentity})
 * into the SHARED ingest path, after {@code provisionDeployment}. The
 * server-IT environment has no Keycloak, so every IT that POSTs a bundle
 * through {@code /api/v1/migrations/{id}/bundle} and reaches provisioning
 * otherwise 500s with a {@code ResourceAccessException} against
 * {@code http://keycloak:8080}. These ITs test ingest, not Keycloak — the
 * boundary is the right thing to mock, mirroring
 * {@code DeploymentProvisioningServiceIT} and the original
 * {@code MigrationBundleIngestIT} mock this generalizes.
 *
 * <p>The default stub returns a fresh synthetic {@code sub} per call so
 * the ingest pipeline completes; it is {@code lenient} so ITs that never
 * reach provisioning (negative / timeout paths) don't trip Mockito's
 * strict-stubbing check. ITs that VERIFY the provision call (e.g.
 * {@code MigrationBundleIngestIT}) {@code Mockito.reset(directory)} and
 * re-stub per test, then {@code verify(...)} as before.
 *
 * <p>Apply via {@code @Import(MockKeycloakDirectoryConfig.class)} on any
 * migration-ingest IT that drives the real ingest.
 */
@TestConfiguration
public class MockKeycloakDirectoryConfig {

    @Bean
    @Primary
    public KeycloakDeploymentDirectory testKeycloakDeploymentDirectory() {
        KeycloakDeploymentDirectory mock = Mockito.mock(KeycloakDeploymentDirectory.class);
        lenient()
                .when(mock.provisionClubAdminIdentity(any(UUID.class), anyString(), anyString()))
                .thenAnswer(inv -> UUID.randomUUID());
        return mock;
    }
}
