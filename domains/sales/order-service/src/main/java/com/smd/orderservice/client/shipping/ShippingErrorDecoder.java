package com.smd.orderservice.client.shipping;

import com.smd.orderservice.client.DownstreamErrors;
import feign.Response;
import feign.codec.ErrorDecoder;

/** 404 → no shipment yet; other statuses by {@link DownstreamErrors#byStatus}. */
class ShippingErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        if (response.status() == 404) {
            return new ShipmentNotFoundException("No shipment at " + response.request().url());
        }
        return DownstreamErrors.byStatus("shipping-service", response);
    }
}
