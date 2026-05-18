package ch.alpenflight.clubs;

import ch.alpenflight.platform.id.ClubId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * DTOs for the Clubs REST surface. Records (immutable, explicit field set);
 * mass-assignment is structurally impossible because the controller binds to
 * the record, not to {@link Club}.
 *
 * <p>Bean Validation here is fast-fail at the HTTP boundary; the aggregate
 * re-validates per ADR 0022 directive 2.
 */
public final class ClubDtos {

    private ClubDtos() {}

    @Schema(description = "Club projection returned to API consumers.")
    public record ClubResponse(
            ClubId id,
            String name,
            @Nullable String slug,
            String clubKey,
            boolean publicRegistrationEnabled) {}

    @Schema(description = "Payload to create a new club.")
    public record ClubCreateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "^[a-z0-9-]+$") String slug,
            @NotBlank @Size(max = 10) String clubKey,
            boolean publicRegistrationEnabled) {}

    @Schema(description = "Payload to update a club. `clubKey` is immutable post-create.")
    public record ClubUpdateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "^[a-z0-9-]+$") String slug,
            boolean publicRegistrationEnabled) {}
}
