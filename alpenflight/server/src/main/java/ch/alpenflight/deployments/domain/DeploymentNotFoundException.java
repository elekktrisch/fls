package ch.alpenflight.deployments.domain;

import java.util.UUID;

/**
 * Raised when a {@link Deployment} lookup by id finds nothing. The
 * admin-endpoint exception handler translates this to HTTP 404 — per the
 * security plan, unknown-id should NOT distinguish from forbidden so
 * a token-leak probe cannot enumerate Deployment ids.
 */
public class DeploymentNotFoundException extends RuntimeException {

    private final UUID id;

    public DeploymentNotFoundException(UUID id) {
        super("Deployment not found: " + id);
        this.id = id;
    }

    public UUID getId() {
        return id;
    }
}
