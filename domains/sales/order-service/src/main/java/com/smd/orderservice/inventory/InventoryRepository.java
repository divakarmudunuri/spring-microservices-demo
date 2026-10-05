package com.smd.orderservice.inventory;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Authoritative stock. Plain SQL, because the decrement has to be one atomic conditional UPDATE. */
@Repository
public class InventoryRepository {

    private final JdbcClient jdbc;

    public InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes {@code quantity} units if, and only if, that many are on hand. The row lock taken by the
     * UPDATE makes concurrent buyers of the same product wait; under READ COMMITTED each one then
     * re-checks {@code quantity_on_hand >= :qty} against the committed value, so stock never goes
     * negative and exactly the right number of buyers succeed.
     *
     * @return the new quantity on hand, or empty if there wasn't enough stock (0 rows updated)
     */
    public Optional<Integer> decrement(UUID productId, int quantity) {
        return jdbc.sql("""
                        UPDATE inventory
                           SET quantity_on_hand = quantity_on_hand - :qty, version = version + 1
                         WHERE product_id = :productId AND quantity_on_hand >= :qty
                        RETURNING quantity_on_hand""")
                .param("qty", quantity)
                .param("productId", productId)
                .query(Integer.class)
                .optional();
    }

    /** Puts {@code quantity} units back. @return the new quantity on hand */
    public int increment(UUID productId, int quantity) {
        return jdbc.sql("""
                        UPDATE inventory
                           SET quantity_on_hand = quantity_on_hand + :qty, version = version + 1
                         WHERE product_id = :productId
                        RETURNING quantity_on_hand""")
                .param("qty", quantity)
                .param("productId", productId)
                .query(Integer.class)
                .single();
    }
}
