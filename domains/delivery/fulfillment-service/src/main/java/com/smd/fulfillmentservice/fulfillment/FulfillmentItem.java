package com.smd.fulfillmentservice.fulfillment;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "fulfillment_items")
public class FulfillmentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fulfillment_id")
    private Fulfillment fulfillment;

    private UUID productId;

    private int quantity;

    protected FulfillmentItem() {
        // for JPA
    }

    FulfillmentItem(Fulfillment fulfillment, UUID productId, int quantity) {
        this.fulfillment = fulfillment;
        this.productId = productId;
        this.quantity = quantity;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }
}
