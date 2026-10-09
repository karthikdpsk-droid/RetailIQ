package com.retailiq.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.entity.Role;
import com.retailiq.api.entity.Store;
import com.retailiq.api.entity.StoreFamilyForecast;
import com.retailiq.api.entity.User;
import com.retailiq.api.repository.MlFamilyRepository;
import com.retailiq.api.repository.ForecastRepository;
import com.retailiq.api.repository.InventoryRepository;
import com.retailiq.api.repository.ProductRepository;
import com.retailiq.api.repository.PromotionPlanRepository;
import com.retailiq.api.repository.RecommendationRepository;
import com.retailiq.api.repository.SaleRepository;
import com.retailiq.api.repository.StoreFamilyForecastRepository;
import com.retailiq.api.repository.StoreRepository;
import com.retailiq.api.repository.UserRepository;
import com.retailiq.api.security.JwtService;
import com.retailiq.api.service.PromotionPlanService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class StoreFamilyForecastIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<String> mlMethod = new AtomicReference<>();
    private static final AtomicReference<String> mlPath = new AtomicReference<>();
    private static final AtomicReference<String> mlRequestId = new AtomicReference<>();
    private static final AtomicReference<JsonNode> mlRequestBody = new AtomicReference<>();
    private static HttpServer mlServer;

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StoreRepository stores;
    @Autowired MlFamilyRepository families;
    @Autowired StoreFamilyForecastRepository forecasts;
    @Autowired SaleRepository sales;
    @Autowired ForecastRepository legacyForecasts;
    @Autowired RecommendationRepository recommendations;
    @Autowired InventoryRepository inventories;
    @Autowired ProductRepository products;
    @Autowired PromotionPlanService promotionPlans;
    @Autowired PromotionPlanRepository promotionPlanRows;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    MockMvc mvc;

    @DynamicPropertySource
    static void mlServerProperties(DynamicPropertyRegistry registry) {
        startMlServer();
        registry.add("retailiq.ml.base-url", () -> "http://127.0.0.1:" + mlServer.getAddress().getPort());
    }

    private static synchronized void startMlServer() {
        if (mlServer != null) return;
        try {
            mlServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mlServer.createContext("/forecast", exchange -> {
                mlMethod.set(exchange.getRequestMethod());
                mlPath.set(exchange.getRequestURI().getPath());
                mlRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
                JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                mlRequestBody.set(request);
                String response = JSON.writeValueAsString(java.util.Map.of(
                        "store_nbr", request.path("store_nbr").asInt(),
                        "family", request.path("family").asText(),
                        "forecast_date", request.path("forecast_date").asText(),
                        "forecast_demand", 125.75,
                        "forecast_horizon_days", 1,
                        "forecast_type", "point_forecast",
                        "model_version", "integration-test-model"));
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            mlServer.start();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start ML test server", ex);
        }
    }

    @AfterAll
    static void stopMlServer() {
        if (mlServer != null) mlServer.stop(0);
    }

    @BeforeEach
    void resetState() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        forecasts.deleteAll();
        sales.deleteAll(); legacyForecasts.deleteAll(); recommendations.deleteAll(); inventories.deleteAll();
        products.deleteAll();
        promotionPlanRows.deleteAll();
        stores.deleteAll();
        families.deleteAll();
        users.deleteAll();
        mlMethod.set(null); mlPath.set(null); mlRequestId.set(null); mlRequestBody.set(null);
    }

    @Test
    void authenticatedRequestBuildsFeaturesCallsMlAndPersistsStoreFamilyForecast() throws Exception {
        User owner = users.save(new User("Forecast Owner", "forecast-owner@example.com",
                passwordEncoder.encode("SecurePass123"), Role.SHOPKEEPER));
        Store store = new Store();
        store.setOwner(owner); store.setStoreCode("ML-FORECAST-1"); store.setName("ML Forecast Store");
        store.setCity("Quito"); store.setState("Pichincha"); store.setAddress("Test address");
        store.setType("A"); store.setMlStoreNbr(3); store.setMlCluster(5);
        store = stores.saveAndFlush(store);
        MlFamily family = families.saveAndFlush(new MlFamily("FAMILY A"));
        String token = jwtService.generateToken(owner);
        long storeId = store.getId();
        LocalDate date = LocalDate.of(2017, 12, 25);
        promotionPlans.createOrUpdate(3, "FAMILY A", date, 2,
                date.minusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());

        mvc.perform(post("/api/v1/forecasts/store-family")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-ID", "forecast-flow-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + storeId + ",\"mlFamily\":\"FAMILY A\",\"forecastDate\":\"" + date + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeId))
                .andExpect(jsonPath("$.mlFamily").value("FAMILY A"))
                .andExpect(jsonPath("$.forecastDate").value(date.toString()))
                .andExpect(jsonPath("$.predictionCutoff").value("2017-12-24"))
                .andExpect(jsonPath("$.forecastDemand").value(125.75))
                .andExpect(jsonPath("$.demandUnit").value("MONETARY_SALES"))
                .andExpect(jsonPath("$.modelVersion").value("integration-test-model"));

        assertThat(mlMethod.get()).isEqualTo("POST");
        assertThat(mlPath.get()).isEqualTo("/forecast");
        assertThat(mlRequestId.get()).isEqualTo("forecast-flow-123");
        JsonNode sent = mlRequestBody.get();
        assertThat(sent.path("store_nbr").asInt()).isEqualTo(3);
        assertThat(sent.path("family").asText()).isEqualTo(family.getFamilyCode());
        assertThat(sent.path("forecast_date").asText()).isEqualTo(date.toString());
        assertThat(sent.path("prediction_cutoff").asText()).isEqualTo("2017-12-24");
        assertThat(sent.path("onpromotion").asDouble()).isEqualTo(2.0);
        assertThat(sent.path("city").asText()).isEqualTo("Quito");
        assertThat(sent.path("state").asText()).isEqualTo("Pichincha");
        assertThat(sent.path("cluster").asInt()).isEqualTo(5);
        assertThat(sent.path("is_holiday_event").asInt()).isEqualTo(1);
        assertThat(sent.path("dcoilwtico").isNumber()).isTrue();

        StoreFamilyForecast persisted = forecasts.findAll().stream().findFirst().orElseThrow();
        assertThat(persisted.getStore().getId()).isEqualTo(storeId);
        assertThat(persisted.getMlFamily().getId()).isEqualTo(family.getId());
        assertThat(persisted.getForecastDate()).isEqualTo(date);
        assertThat(persisted.getPredictionCutoff()).isEqualTo(date.minusDays(1));
        assertThat(persisted.getForecastDemand()).isEqualByComparingTo("125.75");
        assertThat(persisted.getDemandUnit().name()).isEqualTo("MONETARY_SALES");
        assertThat(persisted.getModelVersion()).isEqualTo("integration-test-model");
    }

}
