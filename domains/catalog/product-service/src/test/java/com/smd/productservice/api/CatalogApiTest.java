package com.smd.productservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smd.productservice.PostgresIntegrationTest;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** {@code @Transactional}: MockMvc runs in the test thread, so test data is rolled back after each test. */
@Transactional
class CatalogApiTest extends PostgresIntegrationTest {

    static final String EARBUDS = "20000000-0000-4000-8000-000000000001";
    static final String KEYBOARD = "20000000-0000-4000-8000-000000000003";
    static final String MONITOR = "20000000-0000-4000-8000-000000000004";
    static final String INACTIVE = "20000000-0000-4000-8000-0000000000ff";
    static final String UNKNOWN = "20000000-0000-4000-8000-00000000dead";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void addInactiveProduct() {
        jdbc.update("""
                INSERT INTO products (id, sku, slug, name, description, category_id, image_url, price, active)
                VALUES (?::uuid, 'TEST-INACTIVE', 'retired-gadget', 'Retired Gadget', 'No longer sold.',
                        '10000000-0000-4000-8000-000000000001', '/products/retired-gadget.svg', 9.99, false)""", INACTIVE);
    }

    @Test
    void categoriesAreSortedBySortOrder() throws Exception {
        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug").value(org.hamcrest.Matchers.contains("electronics", "home-kitchen", "outdoor")));
    }

    @Test
    void listIsPagedAndSkipsInactiveProducts() throws Exception {
        mvc.perform(get("/api/products").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(5)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void productBySlugCarriesLevelAndCategory() throws Exception {
        mvc.perform(get("/api/products/{slug}", "mechanical-keyboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(KEYBOARD))
                .andExpect(jsonPath("$.price").value(129.00))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.category.slug").value("electronics"))
                .andExpect(jsonPath("$.availability").value("LOW_STOCK"));
    }

    @Test
    void productById() throws Exception {
        mvc.perform(get("/api/products/{id}", EARBUDS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("wireless-earbuds"))
                .andExpect(jsonPath("$.featured").value(true));
    }

    @Test
    void unknownAndInactiveProductsAreNotFound() throws Exception {
        mvc.perform(get("/api/products/{slug}", "no-such-thing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/product-not-found"));
        mvc.perform(get("/api/products/{id}", INACTIVE)).andExpect(status().isNotFound());
    }

    @Test
    void batchReturnsOnlyKnownActiveProducts() throws Exception {
        mvc.perform(get("/api/products").param("ids", EARBUDS, MONITOR, UNKNOWN, INACTIVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id").value(containsInAnyOrder(EARBUDS, MONITOR)))
                .andExpect(jsonPath("$[?(@.id == '" + MONITOR + "')].availability").value(org.hamcrest.Matchers.contains("OUT_OF_STOCK")));
    }

    @Test
    void batchAcceptsCommaSeparatedIds() throws Exception {
        mvc.perform(get("/api/products").param("ids", EARBUDS + "," + KEYBOARD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void batchIsLimited() throws Exception {
        String[] tooMany = Collections.nCopies(ProductController.MAX_BATCH_SIZE + 1, EARBUDS).toArray(String[]::new);
        mvc.perform(get("/api/products").param("ids", tooMany)).andExpect(status().isBadRequest());
    }

    @Test
    void invalidPagingIsRejected() throws Exception {
        mvc.perform(get("/api/products").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products").param("size", "101")).andExpect(status().isBadRequest());
    }

    @Test
    void theCatalogIsPublicButAnInvalidTokenIsRejected() throws Exception {
        mvc.perform(get("/api/products")).andExpect(status().isOk());              // no token: fine
        mvc.perform(get("/api/categories")).andExpect(status().isOk());
        mvc.perform(get("/api/products").header("Authorization", "Bearer not-a-valid-jwt"))
                .andExpect(status().isUnauthorized());                            // a token that is present must be valid
    }

    @Test
    void responsesNeverContainExactStock() throws Exception {
        String body = mvc.perform(get("/api/products").param("size", "100"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("quantity").doesNotContainIgnoringCase("onHand");
    }
}
