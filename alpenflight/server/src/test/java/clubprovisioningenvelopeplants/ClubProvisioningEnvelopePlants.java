package clubprovisioningenvelopeplants;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public final class ClubProvisioningEnvelopePlants {

    public record PlantedClubSpecWithADeploymentIdRecordComponent(
            String name,
            String slug,
            UUID deploymentId) {
    }

    public static final class PlantedClubSpecClassWithADeploymentIdField {

        private final String name;
        private final UUID deploymentId;

        public PlantedClubSpecClassWithADeploymentIdField(String name, UUID deploymentId) {
            this.name = name;
            this.deploymentId = deploymentId;
        }

        public String getName() {
            return name;
        }

        public UUID getDeploymentId() {
            return deploymentId;
        }
    }

    public record PlantedTenantHome(UUID deploymentId) {
    }

    public record PlantedClubSpecWithANestedTenantHome(
            String name,
            PlantedTenantHome home) {
    }

    public record PlantedClubSpecBindingTheDeploymentColumnUnderAnInnocentName(
            String name,
            @JsonProperty("deployment_id") UUID owner) {
    }

    public record PlantedClubDeclarationWhoseOwnNameHidesTheTenantSelector(
            String name,
            UUID deploymentId) {
    }

    public record PlantedManifestWhoseClubListCarriesTheDeployment(
            String deploymentName,
            List<PlantedClubDeclarationWhoseOwnNameHidesTheTenantSelector> clubs) {
    }

    private ClubProvisioningEnvelopePlants() {
    }
}
