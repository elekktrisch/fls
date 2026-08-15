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

@TestConfiguration
public class MockKeycloakDirectoryConfig {

    @Bean
    @Primary
    public KeycloakDeploymentDirectory testKeycloakDeploymentDirectory() {
        KeycloakDeploymentDirectory mock = Mockito.mock(KeycloakDeploymentDirectory.class);
        lenient()
                .when(mock.provisionClubAdminIdentityFailClosed(
                        any(UUID.class), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> UUID.randomUUID());
        return mock;
    }
}
