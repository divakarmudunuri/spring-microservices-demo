package com.smd.orderservice.client.tracking;

import com.smd.orderservice.client.DownstreamErrors;
import feign.Response;
import feign.codec.ErrorDecoder;

/** 404 → no tracking yet; other statuses by {@link DownstreamErrors#byStatus}. */
class TrackingErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        if (response.status() == 404) {
            return new TrackingNotFoundException("No tracking at " + response.request().url());
        }
        return DownstreamErrors.byStatus("order-tracking-service", response);
    }
}
