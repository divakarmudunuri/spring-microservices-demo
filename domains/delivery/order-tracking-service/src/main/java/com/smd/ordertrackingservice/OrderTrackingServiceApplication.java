package com.smd.ordertrackingservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OrderTrackingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderTrackingServiceApplication.class, args);
    }
}
