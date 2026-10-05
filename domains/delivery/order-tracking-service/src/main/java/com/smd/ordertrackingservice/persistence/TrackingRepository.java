package com.smd.ordertrackingservice.persistence;

import com.smd.ordertrackingservice.config.DynamoDbProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * All DynamoDB access of this service. The rest of the code doesn't know it's DynamoDB.
 *
 * <p>There is deliberately no "list all orders" here: that would be a full-table {@code Scan}
 * (reads every item, slow and expensive at scale). Admins list orders from order-service (Postgres,
 * indexed) and come here for one order's timeline.
 */
@Repository
@DependsOn("dynamoDbTableInitializer")   // the table must exist before the Kafka listener writes
public class TrackingRepository {

    static final String STATE_SK = "STATE";
    private static final TableSchema<TrackingStateItem> STATE_SCHEMA = TableSchema.fromBean(TrackingStateItem.class);
    private static final TableSchema<TrackingEventItem> EVENT_SCHEMA = TableSchema.fromBean(TrackingEventItem.class);
    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private final DynamoDbClient dynamo;
    private final DynamoDbTable<TrackingStateItem> stateTable;
    private final String tableName;
    private final Clock clock;
    private final MeterRegistry meters;

    public TrackingRepository(DynamoDbClient dynamo, DynamoDbEnhancedClient enhanced, DynamoDbProperties properties,
                              Clock clock, MeterRegistry meters) {
        this.dynamo = dynamo;
        this.tableName = properties.tableName();
        this.stateTable = enhanced.table(tableName, STATE_SCHEMA);
        this.clock = clock;
        this.meters = meters;
    }

    public static String orderPk(UUID orderId) {
        return "ORDER#" + orderId;
    }

    /**
     * Records one event: a single {@code TransactWriteItems} with two actions.
     * <ol>
     *   <li><b>Put</b> the {@code EVENT#…} item if it doesn't exist yet. Its key is built only from the event's own
     *       fields ({@code occurredAt}, {@code eventId}), so a redelivered event has the same key and fails this
     *       condition. That's the idempotency check; no separate processed-events table is needed.</li>
     *   <li><b>Update</b> the {@code STATE} item only if the new status ranks higher than the current one.
     *       Events from different topics can arrive out of order; this makes the status only move forward.</li>
     * </ol>
     * Both actions succeed or neither does. When the transaction is cancelled, the cancellation reasons
     * (one per action, in order) tell us why:
     * <ul>
     *   <li>(1) failed → we already have this event: a duplicate, acknowledge and skip.</li>
     *   <li>only (2) failed → the event is older than the current status: still record it in the timeline
     *       (a plain conditional Put), leave the status alone, and acknowledge.</li>
     *   <li>anything else (throttling, a transaction conflict, network) → throw, so the Kafka error handler
     *       retries and finally sends the record to the DLT.</li>
     * </ul>
     * There is no transaction spanning Kafka and DynamoDB: correctness comes from at-least-once delivery plus
     * these idempotent conditional writes. The listener acknowledges only after this method returns.
     */
    public WriteOutcome record(TrackingEventItem event, String userId, int statusRank) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        try {
            WriteOutcome result = write(event, userId, statusRank);
            outcome = result.name().toLowerCase();
            return result;
        } finally {
            sample.stop(meters.timer("tracking.dynamodb.write", "outcome", outcome));
        }
    }

    private WriteOutcome write(TrackingEventItem event, String userId, int statusRank) {
        Put putEvent = Put.builder()
                .tableName(tableName)
                .item(EVENT_SCHEMA.itemToMap(event, true))
                .conditionExpression("attribute_not_exists(PK)")
                .build();
        Update moveStatusForward = Update.builder()
                .tableName(tableName)
                .key(Map.of("PK", s(event.getPk()), "SK", s(STATE_SK)))
                .updateExpression("SET currentStatus = :status, statusRank = :rank, lastEventAt = :at, "
                        + "updatedAt = :now, userId = if_not_exists(userId, :user)")
                .conditionExpression("attribute_not_exists(statusRank) OR statusRank < :rank")
                .expressionAttributeValues(Map.of(
                        ":status", s(event.getStatus()),
                        ":rank", AttributeValue.builder().n(Integer.toString(statusRank)).build(),
                        ":at", s(event.getOccurredAt()),
                        ":now", s(Timestamps.format(clock.instant())),
                        ":user", s(userId)))
                .build();
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(TransactWriteItem.builder().put(putEvent).build(),
                                   TransactWriteItem.builder().update(moveStatusForward).build())
                    .build());
            return WriteOutcome.WRITTEN;
        } catch (TransactionCanceledException e) {
            List<CancellationReason> reasons = e.cancellationReasons();
            boolean eventExists = failedCondition(reasons, 0);
            boolean statusNotNewer = failedCondition(reasons, 1);
            if (eventExists) {
                return WriteOutcome.DUPLICATE;
            }
            if (statusNotNewer) {
                return putEventOnly(putEvent);
            }
            throw e;   // throttling, conflict, ...: let the Kafka error handler retry
        }
    }

    /** Older than the current status: keep it in the timeline without touching STATE. */
    private WriteOutcome putEventOnly(Put putEvent) {
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(putEvent.item())
                    .conditionExpression(putEvent.conditionExpression())
                    .build());
            return WriteOutcome.STALE;
        } catch (ConditionalCheckFailedException e) {
            return WriteOutcome.DUPLICATE;   // a concurrent redelivery wrote it in between
        }
    }

    /** One Query on the partition: the STATE item and every EVENT# item, sorted by SK (= time order). */
    public Optional<OrderTimeline> findTimeline(UUID orderId) {
        TrackingStateItem state = null;
        List<TrackingEventItem> events = new ArrayList<>();
        QueryRequest query = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk")
                .expressionAttributeValues(Map.of(":pk", s(orderPk(orderId))))
                .build();
        for (Map<String, AttributeValue> item : dynamo.queryPaginator(query).items()) {
            if (STATE_SK.equals(item.get("SK").s())) {
                state = STATE_SCHEMA.mapToItem(item);
            } else {
                events.add(EVENT_SCHEMA.mapToItem(item));
            }
        }
        return state == null ? Optional.empty() : Optional.of(new OrderTimeline(state, events));
    }

    /**
     * The STATE item alone, with a strongly consistent read: the aggregator calls this right after checkout,
     * and an eventually consistent read could still return the previous status (read-your-writes).
     */
    public Optional<TrackingStateItem> findState(UUID orderId) {
        return Optional.ofNullable(stateTable.getItem(GetItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(orderPk(orderId)).sortValue(STATE_SK).build())
                .consistentRead(true)
                .build()));
    }

    private static boolean failedCondition(List<CancellationReason> reasons, int action) {
        return reasons.size() > action && CONDITIONAL_CHECK_FAILED.equals(reasons.get(action).code());
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
