package ch.alpenflight.articles.web;

import ch.alpenflight.articles.application.ArticleDtos.ArticleCreateRequest;
import ch.alpenflight.articles.application.ArticleDtos.ArticleDetail;
import ch.alpenflight.articles.application.ArticleDtos.ArticleListItem;
import ch.alpenflight.articles.application.ArticleDtos.ArticleUpdateRequest;
import ch.alpenflight.articles.application.ArticlesService;
import ch.alpenflight.platform.id.ArticleId;
import ch.alpenflight.platform.tenancy.UserPrincipalLookup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/articles", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Articles", description = "Article CRUD (per-club tenant-scoped masterdata).")
public class ArticlesController {

    private final ArticlesService service;
    private final UserPrincipalLookup userLookup;

    public ArticlesController(ArticlesService service, UserPrincipalLookup userLookup) {
        this.service = service;
        this.userLookup = userLookup;
    }

    @Operation(summary = "List Articles for the caller's tenant, sorted by articleNumber.")
    @ApiResponse(responseCode = "200", description = "Array of Article listitem projections.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<ArticleListItem> listArticles(
            @RequestParam(name = "includeInactive", required = false, defaultValue = "false")
                    boolean includeInactive) {
        return service.listArticles(includeInactive);
    }

    @Operation(summary = "Read a single Article by id.")
    @ApiResponse(responseCode = "200", description = "Article detail projection.")
    @ApiResponse(responseCode = "404",
            description = "No active Article with that id (includes cross-tenant lookup).")
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ArticleDetail getArticle(@PathVariable ArticleId id) {
        return service.getArticle(id);
    }

    @Operation(summary = "Register a new Article in the caller's tenant.")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @ApiResponse(responseCode = "409", description = "articleNumber already in use for this tenant.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<ArticleDetail> registerArticle(
            @Valid @RequestBody ArticleCreateRequest req) {
        ArticleDetail created = service.registerArticle(req);
        return ResponseEntity.created(URI.create("/api/v1/articles/" + created.id()))
                .body(created);
    }

    @Operation(summary = "Update an Article.")
    @ApiResponse(responseCode = "200", description = "Updated.")
    @ApiResponse(responseCode = "404", description = "No active Article with that id.")
    @ApiResponse(responseCode = "409", description = "articleNumber already in use for this tenant.")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ArticleDetail updateArticle(@PathVariable ArticleId id,
                                       @Valid @RequestBody ArticleUpdateRequest req) {
        return service.updateArticle(id, req);
    }

    @Operation(summary = "Soft-delete an Article.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "No active Article with that id.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<Void> deleteArticle(@PathVariable ArticleId id,
                                              @AuthenticationPrincipal @Nullable Jwt jwt) {
        service.softDeleteArticle(id, principalUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    private @Nullable UUID principalUserId(@Nullable Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        return userLookup.resolveUserIdFor(jwt).orElse(null);
    }
}
