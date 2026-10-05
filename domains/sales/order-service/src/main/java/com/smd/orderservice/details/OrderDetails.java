package com.smd.orderservice.details;

import com.smd.orderservice.client.product.ProductInfo;
import com.smd.orderservice.client.shipping.Shipment;
import com.smd.orderservice.client.tracking.Tracking;
import com.smd.orderservice.client.user.Customer;
import com.smd.orderservice.order.Order;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** The order (always, from this service's database) plus the four sections fetched in parallel. */
public record OrderDetails(Order order, Section<Customer> customer, Section<Map<UUID, ProductInfo>> products,
                           Section<Shipment> shipping, Section<Tracking> tracking) {

    public boolean degraded() {
        return !unavailableSections().isEmpty();
    }

    public List<String> unavailableSections() {
        return Stream.of(customer, products, shipping, tracking).filter(s -> !s.available()).map(Section::name).toList();
    }
}
