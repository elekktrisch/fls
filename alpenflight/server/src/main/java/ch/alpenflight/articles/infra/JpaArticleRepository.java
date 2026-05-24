package ch.alpenflight.articles.infra;

import ch.alpenflight.articles.domain.Article;
import ch.alpenflight.articles.domain.ArticleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA implementation of {@link ArticleRepository}. Extends both
 * the abstract port and {@code JpaRepository<Article, UUID>} so the
 * application layer depends on the port (ADR 0023) while Spring Data
 * generates the runtime bean.
 *
 * <p>Article is tenant-scoped via {@code @TenantId} on
 * {@code Article.operatingClubId}; Hibernate appends the tenant predicate to
 * every query automatically. Soft-delete is filtered at the query layer.
 */
public interface JpaArticleRepository
        extends JpaRepository<Article, UUID>, ArticleRepository {

    @Override
    @Query("select a from Article a where a.deletedOn is null and a.isActive = true "
            + "order by a.articleNumber asc")
    List<Article> findAllActive();

    @Override
    @Query("select a from Article a where a.deletedOn is null "
            + "order by a.articleNumber asc")
    List<Article> findAllActiveIncludingInactive();

    @Override
    @Query("select a from Article a where a.id = :id and a.deletedOn is null")
    Optional<Article> findActiveById(@Param("id") UUID id);

    @Override
    @Query("select a from Article a where a.articleNumber = :number and a.deletedOn is null")
    Optional<Article> findActiveByNumber(@Param("number") String number);
}
