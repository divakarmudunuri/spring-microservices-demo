package com.smd.cartservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxLines        different products per cart (50)
 * @param maxQuantity     units of one product (10)
 * @param guestTtl        an untouched guest cart expires after this (7 days)
 * @param customerTtl     an untouched customer cart expires after this (30 days)
 */
@ConfigurationProperties(prefix = "cart")
public record CartProperties(@DefaultValue("50") int maxLines, @DefaultValue("10") int maxQuantity,
                             @DefaultValue("7d") Duration guestTtl, @DefaultValue("30d") Duration customerTtl) {
}
