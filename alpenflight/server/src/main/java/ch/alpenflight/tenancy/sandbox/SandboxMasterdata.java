package ch.alpenflight.tenancy.sandbox;

import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import java.util.List;
import java.util.UUID;

public record SandboxMasterdata(UUID clubId,
                                LocationId homeAirfield,
                                List<LocationId> destinationAirfields,
                                SandboxFleet fleet,
                                SandboxRoster roster) {

    public record SandboxFleet(AircraftId clubGlider,
                               AircraftId singleSeatGlider,
                               AircraftId touringMotorGlider,
                               AircraftId towPlane) {

        public List<AircraftId> all() {
            return List.of(clubGlider, singleSeatGlider, touringMotorGlider, towPlane);
        }
    }

    public record SandboxRoster(PersonId gliderInstructor,
                                PersonId towPilot,
                                PersonId gliderPilot,
                                PersonId gliderTrainee,
                                PersonId winchOperator,
                                PersonId motorInstructor) {

        public List<PersonId> all() {
            return List.of(gliderInstructor, towPilot, gliderPilot,
                    gliderTrainee, winchOperator, motorInstructor);
        }
    }
}
