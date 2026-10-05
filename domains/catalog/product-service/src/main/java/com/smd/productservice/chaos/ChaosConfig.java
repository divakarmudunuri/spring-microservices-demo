package com.smd.productservice.chaos;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Profile("local")
class ChaosConfig implements WebMvcConfigurer {

    private final ChaosSettings settings;

    ChaosConfig(ChaosSettings settings) {
        this.settings = settings;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ChaosInterceptor(settings)).addPathPatterns("/api/**");
    }
}
