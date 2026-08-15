package ch.alpenflight.platform.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
class OpenApiConfig {

    private static final String BEARER_AUTH_SCHEME = "bearerAuth";

    @Bean
    OpenAPI alpenflightOpenAPI(
            @Value("${alpenflight.openapi.server-url:http://localhost:8080}") String serverUrl) {
        SecurityRequirement bearerAuthInheritedByEveryOperation =
                new SecurityRequirement().addList(BEARER_AUTH_SCHEME);
        return new OpenAPI()
                .info(new Info()
                        .title("AlpenFlight API")
                        .version("0.0.1-SNAPSHOT")
                        .description("Glider club operations platform. "
                                + "Source of truth for the SPA-generated TS client."))
                .addServersItem(new Server().url(serverUrl))
                .components(new Components().addSecuritySchemes(BEARER_AUTH_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("OIDC bearer token (Keycloak in dev, hosted IdP TBD in prod).")))
                .addSecurityItem(bearerAuthInheritedByEveryOperation);
    }
}
