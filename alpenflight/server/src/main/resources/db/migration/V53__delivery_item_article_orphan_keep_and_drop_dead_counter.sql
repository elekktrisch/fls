-- DeliveryItem.ArticleNumber is a free-text legacy string with no FK (legacy
-- dbo.DeliveryItems has ArticleNumber but no ArticleId). On migration it resolves
-- to the per-club t_article.id; a free-typed or deleted ArticleNumber has no
-- matching article and must be KEPT with a null article_id rather than failing
-- the bundle with a 23503. So article_id becomes nullable; the RESTRICT FK stays
-- (a non-null value must still reference a live article).
ALTER TABLE t_delivery_item
    ALTER COLUMN article_id DROP NOT NULL;

COMMENT ON COLUMN t_delivery_item.article_id IS
    'Resolved t_article.id for the line item, per (operating_club_id,'
    ' article_number). NULL when the legacy free-text ArticleNumber matches no'
    ' live article (free-typed or deleted) — kept as a null reference, never'
    ' a bundle failure. The article_number snapshot is preserved regardless.';

-- t_club_delivery_number_counter backed a monotonic delivery-number allocator
-- that was never wired: legacy DeliveryNumber is externally supplied free text
-- (Proffix), there is no counter/sequence, and V52 collapsed delivery_number to
-- a nullable text column. Drop the dead table.
DROP TABLE IF EXISTS t_club_delivery_number_counter;
