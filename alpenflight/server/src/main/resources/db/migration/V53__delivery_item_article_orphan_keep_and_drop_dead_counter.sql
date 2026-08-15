ALTER TABLE t_delivery_item
    ALTER COLUMN article_id DROP NOT NULL;

COMMENT ON COLUMN t_delivery_item.article_id IS
    'Resolved t_article.id for the line item, per (operating_club_id,'
    ' article_number). NULL when the legacy free-text ArticleNumber matches no'
    ' live article (free-typed or deleted) — kept as a null reference, never'
    ' a bundle failure. The article_number snapshot is preserved regardless.';

DROP TABLE IF EXISTS t_club_delivery_number_counter;
