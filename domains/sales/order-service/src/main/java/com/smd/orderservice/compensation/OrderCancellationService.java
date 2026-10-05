package com.smd.orderservice.compensation;

import com.smd.orderservice.events.EventTypes;
import com.smd.orderservice.events.OrderEventPayloads;
import com.smd.orderservice.inventory.InventoryRepository;
import com.smd.orderservice.inventory.StockMovement;
import com.smd.orderservice.inventory.StockMovementRepository;
import com.smd.orderservice.order.Order;
import com.smd.orderservice.order.OrderItem;
import com.smd.orderservice.order.OrderRepository;
import com.smd.orderservice.outbox.OutboxWriter;
import com.smd.orderservice.payment.Payment;
import com.smd.orderservice.payment.PaymentRepository;
import com.smd.orderservice.wallet.WalletRepository;
import com.smd.orderservice.wallet.WalletTransaction;
import com.smd.orderservice.wallet.WalletTransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The saga's one compensation (CLAUDE.md 6.6). Checkout itself needs none: stock and payment are one local
 * transaction. Only one failure can happen after payment, in another service: fulfillment fails. Then this
 * undoes the checkout in <b>one</b> local transaction: stock back, money back, payment REFUNDED, order CANCELLED,
 * and the events that say so.
 *
 * <p>This is a <b>choreography</b> saga: there is no central coordinator. fulfillment-service publishes
 * FULFILLMENT_FAILED without knowing who listens; order-service reacts on its own. (In an orchestrated saga, one
 * coordinator would call each participant and tell it to compensate.)
 *
 * <p>Idempotent: the caller records the event id in {@code processed_event} in this same transaction, and an
 * order that is already CANCELLED is left alone; {@code wallet_transactions} also allows only one REFUND per order.
 */
@Service
public class OrderCancellationService {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationService.class);

    private final OrderRepository orders;
    private final InventoryRepository inventory;
    private final StockMovementRepository stockMovements;
    private final WalletRepository wallets;
    private final WalletTransactionRepository walletTransactions;
    private final PaymentRepository payments;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final Counter cancellations;

    public OrderCancellationService(OrderRepository orders, InventoryRepository inventory,
                                    StockMovementRepository stockMovements, WalletRepository wallets,
                                    WalletTransactionRepository walletTransactions, PaymentRepository payments,
                                    OutboxWriter outbox, Clock clock, MeterRegistry meters) {
        this.orders = orders;
        this.inventory = inventory;
        this.stockMovements = stockMovements;
        this.wallets = wallets;
        this.walletTransactions = walletTransactions;
        this.payments = payments;
        this.outbox = outbox;
        this.clock = clock;
        this.cancellations = Counter.builder("order.cancellations")
                .description("Orders cancelled after a fulfillment failure (refund + restock)").register(meters);
    }

    /**
     * Joins the caller's transaction (the listener's, with the processed-event marker) or starts its own.
     * @return false if there was nothing to do (already cancelled)
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(UUID orderId, String reason) {
        Order order = orders.findWithItemsById(orderId).orElseThrow();
        if (!order.cancel()) {
            log.info("Order {} is already cancelled; nothing to do", orderId);
            return false;
        }
        UUID userId = order.getUserId();

        // stock back, in product-id order (same lock order as checkout: no deadlocks between the two)
        List<OrderItem> items = order.getItems().stream().sorted(Comparator.comparing(OrderItem::getProductId)).toList();
        for (OrderItem item : items) {
            int onHand = inventory.increment(item.getProductId(), item.getQuantity());
            stockMovements.save(StockMovement.cancellation(item.getProductId(), item.getQuantity(), orderId));
            outbox.inventoryEvent(EventTypes.INVENTORY_CHANGED, item.getProductId(),
                    new OrderEventPayloads.InventoryChanged(item.getProductId(), onHand));
        }

        // money back
        Payment payment = payments.findByOrderId(orderId).orElseThrow();
        payment.refund(clock.instant());
        wallets.credit(userId, payment.getAmount());
        walletTransactions.save(WalletTransaction.refund(userId, payment.getAmount(), orderId));

        outbox.orderEvent(EventTypes.INVENTORY_RESTORED, orderId, userId, new OrderEventPayloads.InventoryRestored(
                items.stream().map(i -> new OrderEventPayloads.Line(i.getProductId(), i.getQuantity())).toList()));
        outbox.orderEvent(EventTypes.PAYMENT_REFUNDED, orderId, userId,
                new OrderEventPayloads.PaymentRefunded(payment.getId(), payment.getAmount(), payment.getCurrency()));
        outbox.orderEvent(EventTypes.ORDER_CANCELLED, orderId, userId, new OrderEventPayloads.OrderCancelled(reason));
        cancellations.increment();
        log.info("Order {} cancelled after fulfillment failure: refunded {} {}, stock restored",
                orderId, payment.getAmount(), payment.getCurrency());
        return true;
    }
}
