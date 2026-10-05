package com.smd.productservice.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smd.productservice.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** CLAUDE.md 6.12: category, search, featured, sorting and paging on the public product list. */
class CatalogFilterTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void byCategory() throws Exception {
        mvc.perform(get("/api/products").param("category", "outdoor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[*].category.slug", everyItem(is("outdoor"))));
        mvc.perform(get("/api/products").param("category", "no-such-category")).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void searchMatchesNameOrDescriptionIgnoringCase() throws Exception {
        mvc.perform(get("/api/products").param("q", "KEYBOARD"))
                .andExpect(jsonPath("$.content[*].slug", contains("mechanical-keyboard")));
        mvc.perform(get("/api/products").param("q", "bluetooth"))   // only in the earbuds' description
                .andExpect(jsonPath("$.content[*].slug", contains("wireless-earbuds")));
        mvc.perform(get("/api/products").param("q", "%"))           // a wildcard character is just a character
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void featuredOnly() throws Exception {
        mvc.perform(get("/api/products").param("featured", "true"))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[*].featured", everyItem(is(true))));
    }

    @Test
    void sortOrders() throws Exception {
        mvc.perform(get("/api/products").param("sort", "price-asc").param("size", "2"))
                .andExpect(jsonPath("$.content[*].slug", contains("led-headlamp", "bamboo-cutting-board")));
        mvc.perform(get("/api/products").param("sort", "price-desc").param("size", "1"))
                .andExpect(jsonPath("$.content[*].slug", contains("monitor-27-4k")));
        mvc.perform(get("/api/products").param("sort", "newest").param("size", "1"))
                .andExpect(jsonPath("$.content", hasSize(1)));
        mvc.perform(get("/api/products").param("sort", "cheapest")).andExpect(status().isBadRequest());
    }

    @Test
    void filtersCombine() throws Exception {
        mvc.perform(get("/api/products").param("category", "electronics").param("featured", "true").param("sort", "price-desc"))
                .andExpect(jsonPath("$.content[*].slug", contains("mechanical-keyboard", "wireless-earbuds")));
    }
}
