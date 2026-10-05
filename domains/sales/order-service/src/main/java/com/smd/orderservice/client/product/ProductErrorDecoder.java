package com.smd.orderservice.client.product;

import com.smd.orderservice.client.DownstreamErrors;
import com.smd.orderservice.client.DownstreamClientException;
import feign.Response;
import feign.codec.ErrorDecoder;

/**
 * The batch endpoint answers 200 and simply leaves unknown ids out, so a 404 here means the endpoint
 * itself is missing (wrong path or version): not retryable, like any other 4xx.
 */
class ProductErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        if (response.status() == 404) {
            return new DownstreamClientException("product-service has no " + response.request().url());
        }
        return DownstreamErrors.byStatus("product-service", response);
    }
}
