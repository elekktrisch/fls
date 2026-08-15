package ch.alpenflight.articles.application;

import ch.alpenflight.articles.application.ArticleDtos.ArticleCreateRequest;
import ch.alpenflight.articles.application.ArticleDtos.ArticleDetail;
import ch.alpenflight.articles.application.ArticleDtos.ArticleListItem;
import ch.alpenflight.articles.application.ArticleDtos.ArticleUpdateRequest;
import ch.alpenflight.articles.domain.Article;
import ch.alpenflight.articles.domain.ArticleNotFoundException;
import ch.alpenflight.articles.domain.ArticleRepository;
import ch.alpenflight.articles.domain.DuplicateArticleNumberException;
import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.platform.id.ArticleId;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ArticlesService {

    private static final String AUDIT_ENTITY_TYPE = "Article";
    private static final String NUMBER_UNIQUE_CONSTRAINT = "ux_article_club_number";

    private final ArticleRepository articles;
    private final Clock clock;
    private final AuditTrail auditTrail;

    public ArticlesService(ArticleRepository articles,
                           Clock clock,
                           AuditTrail auditTrail) {
        this.articles = articles;
        this.clock = clock;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public List<ArticleListItem> listArticles(boolean includeInactive) {
        List<Article> rows = includeInactive
                ? articles.findAllActiveIncludingInactive()
                : articles.findAllActive();
        return rows.stream().map(ArticleMapper::toListItem).toList();
    }

    @Transactional(readOnly = true)
    public ArticleDetail getArticle(ArticleId id) {
        return ArticleMapper.toDetail(loadOrThrow(id));
    }

    public ArticleDetail registerArticle(ArticleCreateRequest req) {
        String number = req.articleNumber().strip();
        articles.findActiveByNumber(number).ifPresent(existing -> {
            throw new DuplicateArticleNumberException(number);
        });

        Article a = Article.register(
                number,
                req.articleName(),
                req.articleInfo(),
                req.description(),
                Boolean.TRUE.equals(req.isActive()));
        ArticleDetail created = ArticleMapper.toDetail(persist(a, number));
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, created.id().value(), created));
        return created;
    }

    public ArticleDetail updateArticle(ArticleId id, ArticleUpdateRequest req) {
        Article a = loadOrThrow(id);
        ArticleDetail before = ArticleMapper.toDetail(a);

        String number = req.articleNumber().strip();
        articles.findActiveByNumber(number)
                .filter(other -> !sameRow(other, id))
                .ifPresent(other -> {
                    throw new DuplicateArticleNumberException(number);
                });

        a.renumber(number);
        a.rename(req.articleName());
        a.updateInfo(req.articleInfo());
        a.updateDescription(req.description());
        if (Boolean.TRUE.equals(req.isActive())) {
            a.activate();
        } else {
            a.deactivate();
        }

        ArticleDetail after = ArticleMapper.toDetail(persist(a, number));
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id.value(), before, after));
        return after;
    }

    public void softDeleteArticle(ArticleId id, @Nullable UUID userId) {
        Article a = loadOrThrow(id);
        ArticleDetail before = ArticleMapper.toDetail(a);
        a.softDelete(userId, clock);
        articles.save(a);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, id.value(), before));
    }

    private Article loadOrThrow(ArticleId id) {
        return articles.findActiveById(id.value())
                .orElseThrow(() -> new ArticleNotFoundException(id));
    }

    private Article persist(Article a, String number) {
        try {
            Article saved = articles.save(a);
            articles.flush();
            return saved;
        } catch (DataIntegrityViolationException e) {
            String causeMessage = e.getMostSpecificCause() == null
                    ? ""
                    : String.valueOf(e.getMostSpecificCause().getMessage());
            if (causeMessage.contains(NUMBER_UNIQUE_CONSTRAINT)) {
                throw new DuplicateArticleNumberException(number);
            }
            throw e;
        }
    }

    private static boolean sameRow(Article other, ArticleId id) {
        ArticleId otherId = other.getId();
        return otherId != null && otherId.value().equals(id.value());
    }
}
