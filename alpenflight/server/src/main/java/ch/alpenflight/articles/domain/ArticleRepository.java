package ch.alpenflight.articles.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for {@link Article} persistence. Implemented by
 * {@code ch.alpenflight.articles.infra.JpaArticleRepository}.
 *
 * <p>Article is tenant-scoped via Hibernate's {@code @TenantId} discriminator
 * on {@code Article.operatingClubId}. The discriminator rides on every read +
 * write query automatically; the service layer trusts it and adds only the
 * role-within-tenant checks at the controller.
 *
 * <p>Soft-delete (V3 {@code deleted_on}) is filtered at the query layer.
 */
public interface ArticleRepository {

    List<Article> findAllActive();

    List<Article> findAllActiveIncludingInactive();

    Optional<Article> findActiveById(UUID id);

    Optional<Article> findActiveByNumber(String number);

    Article save(Article article);

    /** Flushes the persistence context — used to surface DB-side UNIQUE races synchronously. */
    void flush();
}
