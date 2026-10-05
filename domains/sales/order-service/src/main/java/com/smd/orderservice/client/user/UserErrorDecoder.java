package com.smd.orderservice.client.user;

import com.smd.orderservice.client.DownstreamErrors;
import feign.Response;
import feign.codec.ErrorDecoder;

/** 404 → the user doesn't exist; other statuses by {@link DownstreamErrors#byStatus}. */
class UserErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        if (response.status() == 404) {
            return new CustomerNotFoundException("No user at " + response.request().url());
        }
        return DownstreamErrors.byStatus("user-service", response);
    }
}
