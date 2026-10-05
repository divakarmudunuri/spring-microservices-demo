package com.smd.shippingservice.api;

import com.smd.shippingservice.shipment.ShippingService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin listing endpoints arrive in phase 11, with security. */
@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final ShippingService shipping;

    public ShipmentController(ShippingService shipping) {
        this.shipping = shipping;
    }

    /** Used by order-service's details aggregator. */
    @GetMapping("/by-order/{orderId}")
    public ShipmentResponse byOrder(@PathVariable UUID orderId) {
        return shipping.findByOrder(orderId).map(ShipmentResponse::from).orElseThrow(() -> notFound(orderId));
    }

    private static ErrorResponseException notFound(UUID orderId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No shipment for order " + orderId);
        problem.setType(URI.create("/problems/shipment-not-found"));
        problem.setTitle("Shipment not found");
        return new ErrorResponseException(HttpStatus.NOT_FOUND, problem, null);
    }
}
