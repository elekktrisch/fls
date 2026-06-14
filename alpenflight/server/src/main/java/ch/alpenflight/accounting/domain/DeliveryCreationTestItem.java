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
 * Aggregate-internal expected-line entity owned by {@link DeliveryCreationTest}.
 * One row of the harness's expected {@code DeliveryItem} set, mapping the EXISTING
 * V4 table {@code t_delivery_creation_test_item}. It dies with its parent
 * ({@code ON DELETE CASCADE}; no soft-delete column) and is never loaded or
 * mutated independently — the parent rebuilds the whole set atomically on
 * {@link DeliveryCreationTest#captureExpected}.
 *
 * <p>NOT a tenant-discriminated aggregate root: the denormalized
 * {@code operating_club_id} is a plain column (the FlightCrew / PlanningDayAssignment
 * internal-child pattern), so it carries no {@code @TenantId} and is not an S-024
 * leakage-sweep participant — the parent's {@code @TenantId} scopes the whole
 * graph. The tenant is stamped from the parent on build, not by Hibernate's
 * resolver.
 */
@Entity
@Table(name = "t_delivery_creation_test_item")
public class DeliveryCreationTestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private @Nullable UUID operatingClubId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "article_number", nullable = false, length = 50)
    private String articleNumber = "";

    @Column(name = "item_text", length = 250)
    private @Nullable String itemText;

    @Column(name = "additional_information", length = 250)
    private @Nullable String additionalInformation;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 4)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "unit_price", precision = 12, scale = 4)
    private @Nullable BigDecimal unitPrice;

    @Column(name = "unit_type_code", nullable = false, length = 50)
    private String unitTypeCode = "";

    @Column(name = "discount_in_percent", nullable = false)
    private int discountInPercent;

    protected DeliveryCreationTestItem() {
        // JPA.
    }

    static DeliveryCreationTestItem of(DeliveryItemDetails details, @Nullable BigDecimal unitPrice) {
        DeliveryCreationTestItem item = new DeliveryCreationTestItem();
        item.position = details.position();
        item.articleNumber = details.articleNumber();
        item.itemText = details.itemText();
        item.additionalInformation = details.additionalInformation();
        item.quantity = details.quantity();
        item.unitPrice = unitPrice;
        item.unitTypeCode = details.unitType();
        item.discountInPercent = details.discountInPercent();
        return item;
    }

    void assignOperatingClub(@Nullable UUID clubId) {
        this.operatingClubId = clubId;
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

    public @Nullable BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public String getUnitTypeCode() {
        return unitTypeCode;
    }

    public int getDiscountInPercent() {
        return discountInPercent;
    }
}
