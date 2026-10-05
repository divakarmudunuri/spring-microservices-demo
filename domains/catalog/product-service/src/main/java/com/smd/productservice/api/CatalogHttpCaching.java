package com.smd.productservice.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * HTTP caching for <b>anonymous</b> catalog GETs: {@code Cache-Control: public, max-age=60} and an ETag, so browsers
 * (and nginx) can reuse the answer and ask "has it changed?" ({@code If-None-Match} → 304). A request with an
 * {@code Authorization} header is left alone: never mark a response public that might depend on who asked.
 * (Spring Security only adds its own no-cache headers when none are set, so these win.)
 */
@Configuration
public class CatalogHttpCaching {

    static boolean isAnonymousCatalogGet(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "GET".equals(request.getMethod())
                && request.getHeader(HttpHeaders.AUTHORIZATION) == null
                && (path.startsWith("/api/products") || path.startsWith("/api/categories"));
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> catalogCacheControl() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !isAnonymousCatalogGet(request);
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=60");
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/products", "/api/products/*", "/api/categories");
        return registration;
    }

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> catalogEtags() {
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !isAnonymousCatalogGet(request);
            }
        };
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/products", "/api/products/*", "/api/categories");
        return registration;
    }
}
