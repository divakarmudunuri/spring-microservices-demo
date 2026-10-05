package com.smd.cartservice.persistence;

import com.smd.cartservice.config.DynamoDbProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactDeleteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * All DynamoDB access of cart-service. Every write of a cart is conditional on its {@code version} (optimistic
 * locking): two concurrent changes can't silently overwrite each other.
 */
@Repository
@DependsOn("dynamoDbTableInitializer")
public class CartRepository {

    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private final DynamoDbEnhancedClient enhanced;
    private final DynamoDbTable<CartItem> carts;
    private final DynamoDbTable<ProcessedEventItem> events;
    private final DynamoDbIndex<CartItem> byOwner;

    public CartRepository(DynamoDbEnhancedClient enhanced, DynamoDbProperties properties) {
        this.enhanced = enhanced;
        this.carts = enhanced.table(properties.tableName(), TableSchema.fromBean(CartItem.class));
        this.events = enhanced.table(properties.tableName(), TableSchema.fromBean(ProcessedEventItem.class));
        this.byOwner = carts.index(CartItem.BY_OWNER);
    }

    /** Strongly consistent: right after a write, the caller reads its own change. */
    public Optional<CartItem> find(String cartId) {
        return Optional.ofNullable(carts.getItem(GetItemEnhancedRequest.builder()
                .key(key(cartId)).consistentRead(true).build()));
    }

    /** A customer's single cart, through the sparse {@code byOwner} index (eventually consistent, like every GSI). */
    public Optional<CartItem> findByOwner(String userId) {
        return byOwner.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()))
                .stream().flatMap(page -> page.items().stream()).findFirst();
    }

    /** @return false if a cart with this id already exists (nothing written) */
    public boolean create(CartItem cart) {
        cart.setVersion(1L);
        try {
            carts.putItem(PutItemEnhancedRequest.builder(CartItem.class).item(cart)
                    .conditionExpression(Expression.builder().expression("attribute_not_exists(PK)").build()).build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /** Writes the cart if nobody changed it since it was read; bumps the version. */
    public void save(CartItem cart) {
        long expected = cart.getVersion();
        cart.setVersion(expected + 1);
        try {
            carts.putItem(PutItemEnhancedRequest.builder(CartItem.class).item(cart)
                    .conditionExpression(versionIs(expected)).build());
        } catch (ConditionalCheckFailedException e) {
            cart.setVersion(expected);
            throw new CartConflictException("Cart changed concurrently");
        }
    }

    public void delete(String cartId) {
        carts.deleteItem(key(cartId));
    }

    /**
     * Merge on login, atomically: the customer's cart (with the guest lines added) is written and the guest cart is
     * deleted in one {@code TransactWriteItems}. So a merge can never be applied twice, even after a crash.
     *
     * @return false if the guest cart was already gone (merged before, or expired): nothing written
     * @throws CartConflictException the customer's cart changed concurrently (retry)
     */
    public boolean mergeAndDeleteGuest(CartItem customerCart, String guestCartId) {
        long expected = customerCart.getVersion();
        customerCart.setVersion(expected + 1);
        try {
            enhanced.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
                    .addPutItem(carts, TransactPutItemEnhancedRequest.builder(CartItem.class).item(customerCart)
                            .conditionExpression(versionIs(expected)).build())
                    .addDeleteItem(carts, TransactDeleteItemEnhancedRequest.builder().key(key(guestCartId))
                            .conditionExpression(Expression.builder().expression("attribute_exists(PK)").build()).build())
                    .build());
            return true;
        } catch (TransactionCanceledException e) {
            customerCart.setVersion(expected);
            List<CancellationReason> reasons = e.cancellationReasons();
            if (failed(reasons, 1)) {
                return false;
            }
            if (failed(reasons, 0)) {
                throw new CartConflictException("Cart changed concurrently");
            }
            throw e;
        }
    }

    /**
     * Clearing after checkout, atomically: the processed-event marker (only if new) together with the cart's new
     * contents, or its deletion when nothing is left.
     *
     * @param cart the cart with the ordered quantities removed, or null if the cart no longer exists
     * @return false if this event was already applied (duplicate delivery): nothing written
     * @throws CartConflictException the cart changed concurrently (the Kafka error handler retries)
     */
    public boolean applyCheckout(ProcessedEventItem marker, CartItem cart, boolean deleteCart) {
        TransactWriteItemsEnhancedRequest.Builder tx = TransactWriteItemsEnhancedRequest.builder()
                .addPutItem(events, TransactPutItemEnhancedRequest.builder(ProcessedEventItem.class).item(marker)
                        .conditionExpression(Expression.builder().expression("attribute_not_exists(PK)").build()).build());
        Long expected = cart == null ? null : cart.getVersion();
        if (cart != null && deleteCart) {
            tx.addDeleteItem(carts, TransactDeleteItemEnhancedRequest.builder().key(key(cart.getCartId()))
                    .conditionExpression(versionIs(expected)).build());
        } else if (cart != null) {
            cart.setVersion(expected + 1);
            tx.addPutItem(carts, TransactPutItemEnhancedRequest.builder(CartItem.class).item(cart)
                    .conditionExpression(versionIs(expected)).build());
        }
        try {
            enhanced.transactWriteItems(tx.build());
            return true;
        } catch (TransactionCanceledException e) {
            if (cart != null) {
                cart.setVersion(expected);
            }
            List<CancellationReason> reasons = e.cancellationReasons();
            if (failed(reasons, 0)) {
                return false;
            }
            if (failed(reasons, 1)) {
                throw new CartConflictException("Cart changed while clearing it");
            }
            throw e;
        }
    }

    private static Key key(String cartId) {
        return Key.builder().partitionValue(CartItem.pk(cartId)).build();
    }

    private static Expression versionIs(long expected) {
        return Expression.builder().expression("version = :expected")
                .expressionValues(Map.of(":expected", AttributeValue.builder().n(Long.toString(expected)).build()))
                .build();
    }

    private static boolean failed(List<CancellationReason> reasons, int action) {
        return reasons.size() > action && CONDITIONAL_CHECK_FAILED.equals(reasons.get(action).code());
    }
}
