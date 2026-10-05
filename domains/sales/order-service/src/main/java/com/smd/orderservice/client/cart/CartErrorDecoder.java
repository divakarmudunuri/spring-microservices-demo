package com.smd.orderservice.client.cart;

import com.smd.orderservice.client.DownstreamErrors;
import feign.Response;
import feign.codec.ErrorDecoder;

/** A customer always has a cart (created on first read), so every error goes by {@link DownstreamErrors#byStatus}. */
class CartErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        return DownstreamErrors.byStatus("cart-service", response);
    }
}
