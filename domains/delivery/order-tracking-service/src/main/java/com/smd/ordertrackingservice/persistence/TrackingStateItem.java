package com.smd.ordertrackingservice.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/** {@code PK = ORDER#<orderId>, SK = STATE}: the order's current status (data-model/05-dynamodb.md). */
@DynamoDbBean
public class TrackingStateItem {

    private String pk;
    private String sk;
    private String currentStatus;
    private Integer statusRank;
    private String userId;
    private String lastEventAt;
    private String updatedAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPk() {
        return pk;
    }

    public void setPk(String pk) {
        this.pk = pk;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute("SK")
    public String getSk() {
        return sk;
    }

    public void setSk(String sk) {
        this.sk = sk;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public void setCurrentStatus(String currentStatus) {
        this.currentStatus = currentStatus;
    }

    public Integer getStatusRank() {
        return statusRank;
    }

    public void setStatusRank(Integer statusRank) {
        this.statusRank = statusRank;
    }

    /** The customer who owns the order (from the event envelope); used for ownership checks. */
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getLastEventAt() {
        return lastEventAt;
    }

    public void setLastEventAt(String lastEventAt) {
        this.lastEventAt = lastEventAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
