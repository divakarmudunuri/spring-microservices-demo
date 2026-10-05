package com.smd.shippingservice.api;

import com.smd.shippingservice.shipment.ShipmentStatus;
import com.smd.shippingservice.shipment.ShippingService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admins see every shipment / delivery. */
@RestController
@RequestMapping("/api/admin/shipments")
@PreAuthorize("hasRole('ADMIN')")
public class AdminShipmentController {

    private final ShippingService shipping;

    public AdminShipmentController(ShippingService shipping) {
        this.shipping = shipping;
    }

    /** Newest first; {@code status} optional. */
    @GetMapping
    public ShipmentPage list(@RequestParam(required = false) ShipmentStatus status,
                             @RequestParam(defaultValue = "0") @Min(0) int page,
                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var found = shipping.list(status, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        return new ShipmentPage(found.getContent().stream().map(ShipmentResponse::from).toList(),
                found.getNumber(), found.getSize(), found.getTotalElements(), found.getTotalPages());
    }

    @GetMapping("/{trackingNumber}")
    public ShipmentResponse byTrackingNumber(@PathVariable String trackingNumber) {
        return shipping.findByTrackingNumber(trackingNumber).map(ShipmentResponse::from).orElseThrow(() -> {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No shipment " + trackingNumber);
            problem.setType(URI.create("/problems/shipment-not-found"));
            problem.setTitle("Shipment not found");
            return new ErrorResponseException(HttpStatus.NOT_FOUND, problem, null);
        });
    }

    public record ShipmentPage(List<ShipmentResponse> content, int page, int size, long totalElements, int totalPages) {
    }
}
