package com.smd.cartservice.client.product;

import com.smd.cartservice.client.DownstreamClientException;
import com.smd.cartservice.client.DownstreamServerException;
import feign.Response;
import feign.codec.ErrorDecoder;

/** 5xx / 429 → retryable; anything else (the batch endpoint never answers 404 for unknown ids) → not retryable. */
class ProductErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        String message = "product-service answered " + response.status() + " for " + response.request().url();
        return response.status() >= 500 || response.status() == 429
                ? new DownstreamServerException(message)
                : new DownstreamClientException(message);
    }
}
