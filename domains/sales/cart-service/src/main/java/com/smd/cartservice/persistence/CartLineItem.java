package com.smd.cartservice.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/** One line of a cart: only the product and the quantity. Prices are never stored, so they can't go stale. */
@DynamoDbBean
public class CartLineItem {

    private String productId;
    private int quantity;
    private String addedAt;

    public CartLineItem() {
    }

    public CartLineItem(String productId, int quantity, String addedAt) {
        this.productId = productId;
        this.quantity = quantity;
        this.addedAt = addedAt;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public String getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(String addedAt) {
        this.addedAt = addedAt;
    }
}
