package com.smd.productservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smd.productservice.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** Anonymous catalog GETs are cacheable by browsers and nginx; anything with a token is not. */
class CatalogHttpCachingTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousResponsesArePublicForAMinuteWithAnEtag() throws Exception {
        String etag = mvc.perform(get("/api/products/mechanical-keyboard"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=60"))
                .andExpect(header().exists("ETag"))
                .andReturn().getResponse().getHeader("ETag");

        // "has it changed?" → no: 304 without a body
        mvc.perform(get("/api/products/mechanical-keyboard").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        mvc.perform(get("/api/categories")).andExpect(header().string("Cache-Control", "public, max-age=60"));
    }

    @Test
    void responsesToAuthenticatedRequestsAreNotMarkedPublic() throws Exception {
        String cacheControl = mvc.perform(get("/api/products")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt())
                        .header("Authorization", "Bearer something"))
                .andReturn().getResponse().getHeader("Cache-Control");

        assertThat(cacheControl).doesNotContain("public");
    }
}
