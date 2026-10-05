package com.smd.orderservice.checkout;

import com.smd.orderservice.events.EventTypes;
import com.smd.orderservice.events.OrderEventPayloads;
import com.smd.orderservice.inventory.InventoryRepository;
import com.smd.orderservice.inventory.OutOfStockException;
import com.smd.orderservice.inventory.StockMovement;
import com.smd.orderservice.inventory.StockMovementRepository;
import com.smd.orderservice.order.Order;
import com.smd.orderservice.order.OrderRepository;
import com.smd.orderservice.order.ShippingAddress;
import com.smd.orderservice.outbox.OutboxWriter;
import com.smd.orderservice.payment.Payment;
import com.smd.orderservice.payment.PaymentRepository;
import com.smd.orderservice.wallet.InsufficientFundsException;
import com.smd.orderservice.wallet.WalletRepository;
import com.smd.orderservice.wallet.WalletTransaction;
import com.smd.orderservice.wallet.WalletTransactionRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 4 of checkout: stock, payment and order confirmation in ONE local database transaction.
 * Either everything below commits, or nothing does: no stock taken, no money taken, no events written.
 *
 * <p>Transaction rules this class follows:
 * <ul>
 *   <li><b>Separate bean.</b> Transaction boundaries live in their own Spring beans
 *       ({@link OrderInitiationService}, this class, {@link OrderRejectionService}). Calling a
 *       {@code @Transactional} method from another method of the same class bypasses the Spring proxy,
 *       and the annotation silently does nothing.</li>
 *   <li><b>Unchecked exceptions + {@code rollbackFor = Exception.class}.</b> {@link OutOfStockException} and
 *       {@link InsufficientFundsException} are unchecked, and rollbackFor covers checked ones too: by default
 *       Spring does not roll back on a checked exception.</li>
 *   <li><b>No Feign or Kafka in here.</b> Remote calls happened before (step 3). Events go to the outbox
 *       table in this same transaction, so they are published only if it commits.</li>
 *   <li><b>Short.</b> Prices and the total were computed before; this method only takes locks and writes.</li>
 *   <li><b>Lock order.</b> Items are decremented sorted by product id, so two orders for the same products
 *       always lock rows in the same order and cannot deadlock each other.</li>
 * </ul>
 */
@Service
public class CheckoutService {

    private final OrderRepository orders;
    private final InventoryRepository inventory;
    private final StockMovementRepository stockMovements;
    private final WalletRepository wallets;
    private final WalletTransactionRepository walletTransactions;
    private final PaymentRepository payments;
    private final OutboxWriter outbox;

    public CheckoutService(OrderRepository orders, InventoryRepository inventory,
                           StockMovementRepository stockMovements, WalletRepository wallets,
                           WalletTransactionRepository walletTransactions, PaymentRepository payments,
                           OutboxWriter outbox) {
        this.orders = orders;
        this.inventory = inventory;
        this.stockMovements = stockMovements;
        this.wallets = wallets;
        this.walletTransactions = walletTransactions;
        this.payments = payments;
        this.outbox = outbox;
    }

    /**
     * @param lines      the order's lines with prices fetched before the transaction
     * @param total      sum of quantity × unit price, computed before the transaction
     * @param address    the customer's default address, snapshotted onto the order
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Order checkout(UUID orderId, List<PricedLine> lines, BigDecimal total, ShippingAddress address) {
        Order order = orders.findWithItemsById(orderId).orElseThrow();
        UUID userId = order.getUserId();

        // 1. stock: one atomic conditional UPDATE per item, in product-id order
        List<PricedLine> lockOrder = lines.stream().sorted(Comparator.comparing(PricedLine::productId)).toList();
        Map<UUID, Integer> newQuantities = new LinkedHashMap<>();
        for (PricedLine line : lockOrder) {
            int left = inventory.decrement(line.productId(), line.quantity())
                    .orElseThrow(() -> new OutOfStockException(line.productId()));
            newQuantities.put(line.productId(), left);
            stockMovements.save(StockMovement.sale(line.productId(), line.quantity(), orderId));
        }

        // 2.-3. payment: debit the wallet the same way, and record it in the ledger
        if (!wallets.debit(userId, total)) {
            throw new InsufficientFundsException(userId);
        }
        walletTransactions.save(WalletTransaction.payment(userId, total, orderId));

        // 4. the payment itself
        Payment payment = payments.save(Payment.captured(orderId, userId, total, order.getCurrency()));

        // 5. confirm the order
        Map<UUID, BigDecimal> unitPrices = lines.stream()
                .collect(Collectors.toMap(PricedLine::productId, PricedLine::unitPrice));
        order.confirm(unitPrices, total, address);

        // 6. events, committed with everything above
        var reserved = lockOrder.stream().map(l -> new OrderEventPayloads.Line(l.productId(), l.quantity())).toList();
        var priced = lockOrder.stream()
                .map(l -> new OrderEventPayloads.PricedLine(l.productId(), l.quantity(), l.unitPrice())).toList();
        outbox.orderEvent(EventTypes.INVENTORY_RESERVED, orderId, userId,
                new OrderEventPayloads.InventoryReserved(reserved));
        outbox.orderEvent(EventTypes.PAYMENT_CAPTURED, orderId, userId,
                new OrderEventPayloads.PaymentCaptured(payment.getId(), payment.getAmount(), payment.getCurrency()));
        outbox.orderEvent(EventTypes.ORDER_CONFIRMED, orderId, userId, new OrderEventPayloads.OrderConfirmed(
                priced, total, order.getCurrency(), address, order.getCartId()));
        newQuantities.forEach((productId, left) -> outbox.inventoryEvent(EventTypes.INVENTORY_CHANGED, productId,
                new OrderEventPayloads.InventoryChanged(productId, left)));
        return order;
    }
}
