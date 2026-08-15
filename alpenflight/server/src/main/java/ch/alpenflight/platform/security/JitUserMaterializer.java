package ch.alpenflight.platform.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

public interface JitUserMaterializer {

    Optional<UUID> materialize(Jwt jwt);
}
