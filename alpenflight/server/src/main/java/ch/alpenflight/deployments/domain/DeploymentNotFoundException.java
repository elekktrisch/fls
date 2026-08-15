package ch.alpenflight.deployments.domain;

import java.util.UUID;

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
