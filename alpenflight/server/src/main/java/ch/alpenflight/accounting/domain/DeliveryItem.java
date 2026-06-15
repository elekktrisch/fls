package ch.alpenflight.accounting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate-internal billing line owned by {@link Delivery}, mapping the EXISTING
 * V4 table {@code t_delivery_item}. One invoice line — engine output, never
 * hand-editable. It dies with its parent ({@code ON DELETE CASCADE}) and is
 * loaded only through the {@link Delivery#getItems()} ordered set.
 *
 * <p>NOT a tenant-discriminated aggregate root: the denormalized
 * {@code operating_club_id} is a plain column (the DeliveryCreationTestItem /
 * PlanningDayAssignment internal-child pattern), so it carries no {@code @TenantId}
 * and is not an S-024 leakage-sweep participant — the parent's {@code @TenantId}
 * scopes the whole graph.
 *
 * <p>{@code article_number} + {@code unit_type_code} are frozen snapshots per
 * Swiss OR Art. 957a — never re-resolved from {@code article_id}. Read-only this
 * iteration: column mapping + getters, no write factory (J-10b).
 */
@Entity
@Table(name = "t_delivery_item")
public class DeliveryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private @Nullable UUID operatingClubId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "article_id", nullable = false)
    private @Nullable UUID articleId;

    @Column(name = "article_number", nullable = false, length = 50)
    private String articleNumber = "";

    @Column(name = "item_text", length = 250)
    private @Nullable String itemText;

    @Column(name = "additional_information", length = 250)
    private @Nullable String additionalInformation;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 4)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "discount_in_percent", nullable = false)
    private int discountInPercent;

    @Column(name = "unit_type_code", nullable = false, length = 50)
    private String unitTypeCode = "";

    protected DeliveryItem() {
        // JPA.
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getOperatingClubId() {
        return operatingClubId;
    }

    public int getPosition() {
        return position;
    }

    public @Nullable UUID getArticleId() {
        return articleId;
    }

    public String getArticleNumber() {
        return articleNumber;
    }

    public @Nullable String getItemText() {
        return itemText;
    }

    public @Nullable String getAdditionalInformation() {
        return additionalInformation;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getDiscountInPercent() {
        return discountInPercent;
    }

    public String getUnitTypeCode() {
        return unitTypeCode;
    }
}
