package ch.alpenflight.articles.domain;

import ch.alpenflight.platform.id.ArticleId;
import ch.alpenflight.platform.persistence.PersistedAuditActor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * Article aggregate root. Per-club catalogue entry — a billable line-item
 * shape (article number + name + optional info / long-form description) that
 * the E-09 DeliveryItem flow will reference via FK. Tenant-scoped via
 * Hibernate's {@code @TenantId} on {@code operatingClubId}; every read +
 * write is filtered to the caller's tenant by Hibernate before the service
 * ever sees the row.
 *
 * <p>Column names are derived from field names by Spring Boot's default
 * {@code SpringPhysicalNamingStrategy} (camelCase → snake_case); no field
 * needs an explicit {@code @Column(name = ...)}.
 *
 * <p>Per ADR 0022 directive 2, business rules live on the aggregate, not the
 * schema:
 *
 * <ul>
 *   <li>{@code articleNumber} is non-blank, trimmed, length ≤ 50 (matches V3
 *       column width). No regex — legacy carries business-meaningful codes
 *       (Proffix-style); length + non-blank only.</li>
 *   <li>{@code articleName} is non-blank, trimmed, length ≤ 250.</li>
 *   <li>{@code articleInfo} (≤ 250) and {@code description} (long-form TEXT)
 *       are both nullable free-text fields with no further structure.</li>
 * </ul>
 *
 * <p>Identity-bearing partial UNIQUE on
 * {@code (operating_club_id, article_number) WHERE deleted_on IS NULL}
 * (V3) lets a tenant soft-delete and recreate the same number; the structural
 * UNIQUE catches concurrent INSERT races the service-layer pre-check can't.
 *
 * <p>Renumber is safe post-create — {@code delivery_item.article_number} is
 * a frozen snapshot at booking (Swiss OR Art. 957a), never re-resolved from
 * {@code article_id}. The aggregate exposes {@link #renumber(String)} but
 * does NOT block on existing FK references.
 */
@Entity
@Table(name = "article")
public class Article {

    private static final int MAX_NUMBER_LENGTH = 50;
    private static final int MAX_NAME_LENGTH = 250;
    private static final int MAX_INFO_LENGTH = 250;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private @Nullable UUID operatingClubId;

    @Column(nullable = false, length = MAX_NUMBER_LENGTH)
    private String articleNumber = "";

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String articleName = "";

    @Column(length = MAX_INFO_LENGTH)
    private @Nullable String articleInfo;

    private @Nullable String description;

    @Column(nullable = false)
    private boolean isActive = true;

    private @Nullable Instant deletedOn;

    @PersistedAuditActor
    private @Nullable UUID deletedByUserId;

    protected Article() {
        // JPA.
    }

    /**
     * Factory for a new Article. The tenant ({@link #operatingClubId}) is set
     * by Hibernate's {@code @TenantId} resolver on persist — do NOT pass it
     * here.
     */
    public static Article register(String articleNumber,
                                   String articleName,
                                   @Nullable String articleInfo,
                                   @Nullable String description,
                                   boolean isActive) {
        Article a = new Article();
        a.assignNumber(articleNumber);
        a.assignName(articleName);
        a.assignInfo(articleInfo);
        a.assignDescription(description);
        a.isActive = isActive;
        return a;
    }

    public void rename(String newName) {
        ensureNotDeleted();
        assignName(newName);
    }

    public void renumber(String newNumber) {
        ensureNotDeleted();
        assignNumber(newNumber);
    }

    public void updateInfo(@Nullable String newInfo) {
        ensureNotDeleted();
        assignInfo(newInfo);
    }

    public void updateDescription(@Nullable String newDescription) {
        ensureNotDeleted();
        assignDescription(newDescription);
    }

    public void activate() {
        ensureNotDeleted();
        this.isActive = true;
    }

    public void deactivate() {
        ensureNotDeleted();
        this.isActive = false;
    }

    public void softDelete(@Nullable UUID userId, Clock clock) {
        if (this.deletedOn == null) {
            this.deletedOn = Instant.now(clock);
            this.deletedByUserId = userId;
        }
    }

    private void assignNumber(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("articleNumber must not be blank");
        }
        String trimmed = value.strip();
        if (trimmed.length() > MAX_NUMBER_LENGTH) {
            throw new IllegalArgumentException(
                    "articleNumber exceeds " + MAX_NUMBER_LENGTH + " characters");
        }
        this.articleNumber = trimmed;
    }

    private void assignName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("articleName must not be blank");
        }
        String trimmed = value.strip();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "articleName exceeds " + MAX_NAME_LENGTH + " characters");
        }
        this.articleName = trimmed;
    }

    private void assignInfo(@Nullable String value) {
        if (value == null) {
            this.articleInfo = null;
            return;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            this.articleInfo = null;
            return;
        }
        if (trimmed.length() > MAX_INFO_LENGTH) {
            throw new IllegalArgumentException(
                    "articleInfo exceeds " + MAX_INFO_LENGTH + " characters");
        }
        this.articleInfo = trimmed;
    }

    private void assignDescription(@Nullable String value) {
        if (value == null) {
            this.description = null;
            return;
        }
        String trimmed = value.strip();
        this.description = trimmed.isEmpty() ? null : trimmed;
    }

    private void ensureNotDeleted() {
        if (this.deletedOn != null) {
            throw new IllegalStateException("Cannot mutate a soft-deleted Article");
        }
    }

    public @Nullable ArticleId getId() {
        return ArticleId.ofNullable(id);
    }

    public @Nullable UUID getOperatingClubId() {
        return operatingClubId;
    }

    public String getArticleNumber() {
        return articleNumber;
    }

    public String getArticleName() {
        return articleName;
    }

    public @Nullable String getArticleInfo() {
        return articleInfo;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public boolean isActive() {
        return isActive;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }
}
