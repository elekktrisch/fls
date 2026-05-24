package ch.alpenflight.articles.application;

import ch.alpenflight.platform.id.ArticleId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * DTOs for the Article REST surface. Records (immutable, explicit field set);
 * mass-assignment is structurally impossible because the controller binds to
 * the record, not to the {@link ch.alpenflight.articles.domain.Article}
 * aggregate.
 *
 * <p>{@code operatingClubId} (the {@code @TenantId} discriminator) is
 * <strong>absent</strong> from {@link ArticleCreateRequest} and {@link
 * ArticleUpdateRequest}: it is set by Hibernate's {@code @TenantId} resolver
 * from the JWT on persist (A04 mass-assignment defense).
 *
 * <p>{@code articleNumber} carries no regex on the wire — legacy uses
 * business-meaningful codes (Proffix-style); length + non-blank only. The
 * aggregate trims and re-validates on both register + renumber.
 */
public final class ArticleDtos {

    private ArticleDtos() {}

    @Schema(description = "Article detail projection — full payload.")
    public record ArticleDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ArticleId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String articleNumber,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String articleName,
            @Nullable String articleInfo,
            @Nullable String description,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isActive) {}

    @Schema(description = "Article listitem projection — sorted by articleNumber.")
    public record ArticleListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ArticleId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String articleNumber,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String articleName,
            @Nullable String articleInfo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isActive) {}

    @Schema(description = "Payload to register a new Article in the caller's tenant.")
    public record ArticleCreateRequest(
            @NotBlank @Size(max = 50) String articleNumber,
            @NotBlank @Size(max = 250) String articleName,
            @Nullable @Size(max = 250) String articleInfo,
            @Nullable String description,
            // Boxed + @NotNull — legacy [Required] semantics: a payload that
            // omits the flag is a 400, not a silent inactive-by-default insert.
            @NotNull Boolean isActive) {}

    @Schema(description = "Payload to update an Article. operating_club_id is not settable here (A04 defense).")
    public record ArticleUpdateRequest(
            @NotBlank @Size(max = 50) String articleNumber,
            @NotBlank @Size(max = 250) String articleName,
            @Nullable @Size(max = 250) String articleInfo,
            @Nullable String description,
            @NotNull Boolean isActive) {}
}
