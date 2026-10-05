package com.smd.cartservice.persistence;

import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

/**
 * {@code PK = CART#<cartId>} (data-model/05-dynamodb.md). {@code ownerUserId} is null for a guest cart, so guest
 * carts don't appear in the sparse {@code byOwner} index.
 */
@DynamoDbBean
public class CartItem {

    public static final String BY_OWNER = "byOwner";

    private String pk;
    private String cartId;
    private String ownerUserId;
    private List<CartLineItem> items = new ArrayList<>();
    private Long version;
    private String createdAt;
    private String updatedAt;
    private Long expiresAt;

    public static String pk(String cartId) {
        return "CART#" + cartId;
    }

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPk() {
        return pk;
    }

    public void setPk(String pk) {
        this.pk = pk;
    }

    public String getCartId() {
        return cartId;
    }

    public void setCartId(String cartId) {
        this.cartId = cartId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = BY_OWNER)
    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public List<CartLineItem> getItems() {
        return items;
    }

    public void setItems(List<CartLineItem> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }

    /** Optimistic locking: every write is conditional on the version read. */
    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** DynamoDB TTL (epoch seconds): abandoned carts disappear on their own. */
    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
