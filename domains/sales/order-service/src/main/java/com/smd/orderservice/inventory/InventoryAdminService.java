package com.smd.orderservice.inventory;

import com.smd.orderservice.client.product.ProductAdapter;
import com.smd.orderservice.client.product.ProductInfo;
import com.smd.orderservice.composition.ParallelCalls;
import com.smd.orderservice.events.EventTypes;
import com.smd.orderservice.events.OrderEventPayloads;
import com.smd.orderservice.outbox.OutboxWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exact stock for admins (the public only ever sees a level), and restocking. */
@Service
public class InventoryAdminService {

    private static final Logger log = LoggerFactory.getLogger(InventoryAdminService.class);
    static final int NAME_BATCH = 100;   // product-service's batch limit

    private final JdbcClient jdbc;
    private final InventoryRepository inventory;
    private final StockMovementRepository stockMovements;
    private final OutboxWriter outbox;
    private final ProductAdapter products;
    private final ParallelCalls parallelCalls;

    public InventoryAdminService(JdbcClient jdbc, InventoryRepository inventory, StockMovementRepository stockMovements,
                                 OutboxWriter outbox, ProductAdapter products, ParallelCalls parallelCalls) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.stockMovements = stockMovements;
        this.outbox = outbox;
        this.products = products;
        this.parallelCalls = parallelCalls;
    }

    /**
     * All stock, with product names from product-service: one batch call per 100 products, all in parallel.
     * If product-service is unavailable the numbers are still returned, without names ({@code namesAvailable=false}).
     */
    public InventoryView list() {
        List<StockRow> rows = jdbc.sql("SELECT product_id, quantity_on_hand, updated_at FROM inventory ORDER BY product_id")
                .query((rs, n) -> new StockRow(rs.getObject("product_id", UUID.class), rs.getInt("quantity_on_hand"),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
        List<CompletableFuture<Map<UUID, ProductInfo>>> calls = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += NAME_BATCH) {
            List<UUID> ids = rows.subList(i, Math.min(i + NAME_BATCH, rows.size())).stream().map(StockRow::productId).toList();
            calls.add(parallelCalls.submit("product-names", () -> products.getProducts(ids)));
        }
        Map<UUID, ProductInfo> names = new HashMap<>();
        boolean namesAvailable = true;
        for (CompletableFuture<Map<UUID, ProductInfo>> call : calls) {
            try {
                names.putAll(ParallelCalls.await(call));
            } catch (RuntimeException e) {
                namesAvailable = false;
                log.warn("admin inventory: product names unavailable: {}", e.toString());
            }
        }
        List<InventoryView.Item> items = rows.stream().map(r -> new InventoryView.Item(r.productId(),
                names.containsKey(r.productId()) ? names.get(r.productId()).name() : null, r.quantityOnHand(), r.updatedAt()))
                .toList();
        return new InventoryView(items, namesAvailable);
    }

    /** One transaction: the stock, the audit row (who and why) and INVENTORY_CHANGED for product-service. */
    @Transactional
    public int restock(UUID productId, int quantity, UUID adminId, String note) {
        int onHand = inventory.restock(productId, quantity).orElseThrow(() -> new UnknownProductException(productId));
        stockMovements.save(StockMovement.restock(productId, quantity, adminId, note));
        outbox.inventoryEvent(EventTypes.INVENTORY_CHANGED, productId, new OrderEventPayloads.InventoryChanged(productId, onHand));
        return onHand;
    }

    private record StockRow(UUID productId, int quantityOnHand, Instant updatedAt) {
    }
}
