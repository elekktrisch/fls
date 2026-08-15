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

    @Column(name = "article_id")
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
    }

    static DeliveryItem fromEngineLine(UUID operatingClubId, UUID articleId, DeliveryItemDetails line) {
        DeliveryItem item = new DeliveryItem();
        item.operatingClubId = operatingClubId;
        item.position = line.position();
        item.articleId = articleId;
        item.articleNumber = line.articleNumber();
        item.itemText = line.itemText();
        item.additionalInformation = line.additionalInformation();
        item.quantity = line.quantity();
        item.discountInPercent = line.discountInPercent();
        item.unitTypeCode = line.unitType();
        return item;
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
