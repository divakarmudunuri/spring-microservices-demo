package com.smd.shippingservice.api;

import com.smd.shippingservice.security.CurrentUser;
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

@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final ShippingService shipping;
    private final CurrentUser currentUser;

    public ShipmentController(ShippingService shipping, CurrentUser currentUser) {
        this.shipping = shipping;
        this.currentUser = currentUser;
    }

    /**
     * The owner (a customer, directly or through order-service's aggregator with the relayed token) or an ADMIN.
     * Someone else's shipment is a 404, not a 403, so order ids can't be probed.
     */
    @GetMapping("/by-order/{orderId}")
    public ShipmentResponse byOrder(@PathVariable UUID orderId) {
        return shipping.findByOrder(orderId)
                .filter(s -> currentUser.mayRead(s.getUserId()))
                .map(ShipmentResponse::from)
                .orElseThrow(() -> notFound(orderId));
    }

    private static ErrorResponseException notFound(UUID orderId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No shipment for order " + orderId);
        problem.setType(URI.create("/problems/shipment-not-found"));
        problem.setTitle("Shipment not found");
        return new ErrorResponseException(HttpStatus.NOT_FOUND, problem, null);
    }
}
