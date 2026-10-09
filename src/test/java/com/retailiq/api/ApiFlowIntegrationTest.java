package com.retailiq.api;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailiq.api.entity.Role;
import com.retailiq.api.entity.User;
import com.retailiq.api.repository.ForecastRepository;
import com.retailiq.api.repository.InventoryRepository;
import com.retailiq.api.repository.ProductRepository;
import com.retailiq.api.repository.RecommendationRepository;
import com.retailiq.api.repository.SaleRepository;
import com.retailiq.api.repository.StoreRepository;
import com.retailiq.api.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class ApiFlowIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StoreRepository stores;
    @Autowired ProductRepository products;
    @Autowired InventoryRepository inventories;
    @Autowired SaleRepository sales;
    @Autowired ForecastRepository forecasts;
    @Autowired RecommendationRepository recommendations;
    @Autowired PasswordEncoder passwordEncoder;

    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void resetDatabase() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        sales.deleteAll();
        forecasts.deleteAll();
        recommendations.deleteAll();
        inventories.deleteAll();
        products.deleteAll();
        stores.deleteAll();
        users.deleteAll();
    }

    @Test
    void corsPreflightAllowsViteOriginAndFrontendHeaders() throws Exception {
        mvc.perform(options("/api/v1/auth/register")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,authorization,x-request-id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Methods",
                        org.hamcrest.Matchers.containsString("POST")))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.containsString("authorization")))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.containsString("x-request-id")))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void registrationIsAccessibleFromViteOrigin() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CORS Owner\",\"email\":\"cors@example.com\",\"password\":\"SecurePass123\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void authenticatedShopkeeperOwnsStoreProductInventoryAndSales() throws Exception {
        mvc.perform(get("/api/v1/stores")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/stores").header("Authorization", bearer("not.a.jwt")))
                .andExpect(status().isUnauthorized());

        String ownerToken = registerAndGetToken("owner@example.com", "Owner One");
        MvcResult storeResult = mvc.perform(post("/api/v1/stores").header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"storeCode\":\"ST-1\",\"name\":\"Main Store\",\"city\":\"Pune\",\"state\":\"MH\",\"address\":\"Market Road\",\"type\":\"RETAIL\",\"active\":true,\"mlStoreNbr\":3,\"mlCluster\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").isNumber())
                .andExpect(jsonPath("$.mlStoreNbr").value(3))
                .andExpect(jsonPath("$.mlCluster").value(5))
                .andReturn();
        long storeId = read(storeResult).path("id").asLong();
        mvc.perform(put("/api/v1/stores/{id}", storeId).header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeCode\":\"ST-1\",\"name\":\"Main Store Updated\",\"city\":\"Pune\",\"state\":\"MH\",\"address\":\"Market Road\",\"type\":\"RETAIL\",\"active\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Main Store Updated"))
                .andExpect(jsonPath("$.mlStoreNbr").value(3)).andExpect(jsonPath("$.mlCluster").value(5));
        mvc.perform(get("/api/v1/stores").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(storeId));

        MvcResult productResult = mvc.perform(post("/api/v1/products").header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + storeId + ",\"name\":\"Rice\",\"sku\":\"RICE-1\",\"category\":\"Grocery\",\"unitPrice\":3.50,\"unit\":\"kg\",\"active\":true}"))
                .andExpect(status().isCreated()).andReturn();
        long productId = read(productResult).path("id").asLong();
        mvc.perform(put("/api/v1/products/{id}", productId).header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + storeId + ",\"name\":\"Rice\",\"sku\":\"RICE-1\",\"category\":\"Grocery\",\"unitPrice\":3.50,\"unit\":\"kg\",\"active\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.storeId").value(storeId));
        mvc.perform(get("/api/v1/products").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].sku").value("RICE-1"));

        MvcResult inventoryResult = mvc.perform(post("/api/v1/inventory").header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + storeId + ",\"productId\":" + productId + ",\"quantity\":10,\"reorderLevel\":3,\"safetyStock\":2}"))
                .andExpect(status().isCreated()).andReturn();
        long inventoryId = read(inventoryResult).path("id").asLong();
        mvc.perform(put("/api/v1/inventory/{id}", inventoryId).header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + storeId + ",\"productId\":" + productId + ",\"quantity\":10,\"reorderLevel\":3,\"safetyStock\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(10));
        mvc.perform(post("/api/v1/inventory").header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + storeId + ",\"productId\":" + productId + ",\"quantity\":1,\"reorderLevel\":1,\"safetyStock\":1}"))
                .andExpect(status().isConflict());

        MvcResult saleResult = mvc.perform(post("/api/v1/sales").header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + storeId + ",\"productId\":" + productId + ",\"quantity\":4,\"saleDate\":\"2026-10-06\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(14.0)).andReturn();
        long saleId = read(saleResult).path("id").asLong();
        mvc.perform(get("/api/v1/sales/{id}", saleId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(saleId));
        mvc.perform(get("/api/v1/sales/store/{id}", storeId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(saleId));
        mvc.perform(get("/api/v1/sales").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].quantity").value(4));
        mvc.perform(get("/api/v1/sales/product/{id}", productId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].totalAmount").value(14.0));
        mvc.perform(get("/api/v1/inventory/{id}", inventoryId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(6));
        mvc.perform(patch("/api/v1/inventory/{id}/stock", inventoryId).header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"adjustment\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(7));

        String otherToken = registerAndGetToken("other@example.com", "Owner Two");
        mvc.perform(get("/api/v1/stores/{id}", storeId).header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products/{id}", productId).header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/inventory/{id}", inventoryId).header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/forecasts/invalid").header("Authorization", bearer(otherToken)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/sales/store/{id}", storeId).header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/forecasts/ingest").header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + storeId + ",\"productId\":" + productId + ",\"forecastDate\":\"2026-10-07\",\"predictedDemand\":5,\"modelVersion\":\"test\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.modelVersion").value("test"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/inventory/{id}", inventoryId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/inventory/{id}", inventoryId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCanIngestMlResultsAndShopkeeperCannotChooseAdminRole() throws Exception {
        String ownerToken = registerAndGetToken("owner@example.com", "Owner One");
        mvc.perform(get("/api/v1/auth/register").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Attempt Admin\",\"email\":\"forced@example.com\",\"password\":\"password-123\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.role").value("SHOPKEEPER"));

        var owner = users.findByEmail("owner@example.com").orElseThrow();
        var store = new com.retailiq.api.entity.Store();
        store.setOwner(owner); store.setStoreCode("ST-ADMIN"); store.setName("Store"); store.setCity("Pune");
        store.setState("MH"); store.setAddress("Address"); store.setType("RETAIL");
        store = stores.save(store);
        var product = new com.retailiq.api.entity.Product();
        product.setStore(store); product.setName("Tea"); product.setSku("TEA-1"); product.setCategory("Grocery");
        product.setUnit("box"); product.setUnitPrice(new java.math.BigDecimal("2.00"));
        product = products.save(product);
        long storeId = store.getId(); long productId = product.getId();

        users.save(new User("Administrator", "admin@example.com", passwordEncoder.encode("admin-pass-123"), Role.ADMIN));
        String adminToken = loginAndGetToken("admin@example.com", "admin-pass-123");
        mvc.perform(post("/api/v1/forecasts/ingest").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + storeId + ",\"productId\":" + productId + ",\"forecastDate\":\"2026-10-07\",\"predictedDemand\":12.5,\"modelVersion\":\"ml-v1\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.predictedDemand").value(12.5));
        mvc.perform(post("/api/v1/recommendations/ingest").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + storeId + ",\"productId\":" + productId + ",\"recommendationType\":\"REORDER\",\"recommendedQuantity\":8,\"reason\":\"Predicted demand exceeds stock\",\"recommendationDate\":\"2026-10-06\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.recommendationType").value("REORDER"));
        mvc.perform(get("/api/v1/forecasts").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].modelVersion").value("ml-v1"));
        mvc.perform(get("/api/v1/forecasts").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].modelVersion").value("ml-v1"));
        mvc.perform(get("/api/v1/recommendations/store/{id}", storeId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].recommendedQuantity").value(8));
        mvc.perform(get("/api/v1/recommendations/product/{id}", productId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].recommendationType").value("REORDER"));
    }

    @Test
    void openApiDocumentIsAvailable() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/register']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth").exists());
    }

    @Test
    void storeAndProductCanBeUpdatedAndDeleted() throws Exception {
        String token = registerAndGetToken("crud@example.com", "CRUD Owner");
        MvcResult storeResult = mvc.perform(post("/api/v1/stores").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeCode\":\"CRUD-1\",\"name\":\"CRUD Store\",\"city\":\"Pune\",\"state\":\"MH\",\"address\":\"Address\",\"type\":\"RETAIL\",\"active\":true}"))
                .andExpect(status().isCreated()).andReturn();
        long storeId = read(storeResult).path("id").asLong();
        MvcResult productResult = mvc.perform(post("/api/v1/products").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + storeId + ",\"name\":\"Soap\",\"sku\":\"SOAP-1\",\"category\":\"Home\",\"unitPrice\":2.00,\"unit\":\"piece\",\"active\":true}"))
                .andExpect(status().isCreated()).andReturn();
        long productId = read(productResult).path("id").asLong();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/products/{id}", productId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/products/{id}", productId).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/stores/{id}", storeId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/stores/{id}", storeId).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    private String registerAndGetToken(String email, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of(
                                "name", name, "email", email, "password", "SecurePass123"))))
                .andExpect(status().isCreated()).andReturn();
        return read(result).path("token").asText();
    }

    private String loginAndGetToken(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return read(result).path("token").asText();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String bearer(String token) { return "Bearer " + token; }
}
