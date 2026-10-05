package com.smd.storefrontbff.client.product;

import feign.Response;
import feign.codec.ErrorDecoder;

/** 404 → no such product; 5xx / 429 → retryable; any other 4xx → not retryable. */
class ProductErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        if (response.status() == 404) {
            return new ProductNotFoundException("No product at " + response.request().url());
        }
        String message = "product-service answered " + response.status() + " for " + response.request().url();
        return response.status() >= 500 || response.status() == 429
                ? new DownstreamServerException(message)
                : new IllegalStateException(message);
    }
}
