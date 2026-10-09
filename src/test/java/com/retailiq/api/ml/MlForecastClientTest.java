package com.retailiq.api.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class MlForecastClientTest {
    private HttpServer server;
    private int status;
    private String responseBody;
    private long delayMillis;
    private final AtomicReference<String> capturedRequestId = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuthorization = new AtomicReference<>();

    @BeforeEach void startServer() throws IOException {
        status = 200; responseBody = validResponse(); delayMillis = 0;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/forecast", this::respond); server.start();
    }
    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void successfulRequestPostsContractAndPropagatesRequestId() {
        MlForecastResponse response = client(Duration.ofSeconds(2)).forecast(request(), "corr-123");
        assertThat(response.forecastDemand()).isEqualByComparingTo("12.5");
        assertThat(capturedRequestId.get()).isEqualTo("corr-123");
        assertThat(capturedAuthorization.get()).isNull();
        assertThat(capturedBody.get()).contains("\"forecast_date\"", "\"prediction_cutoff\"",
                "\"store_nbr\"", "\"sales_rolling_std_7\"");
    }
    @Test void generatesRequestIdWhenCallerDidNotSupplyOne() {
        client(Duration.ofSeconds(2)).forecast(request(), null);
        assertThat(capturedRequestId.get()).matches("[A-Za-z0-9._-]{1,64}");
    }
    @Test void mapsFourHundredResponseToBadGateway() {
        status = 422;
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).forecast(request(), "id"))
                .isInstanceOf(MlIntegrationException.class).hasMessageContaining("HTTP 422");
    }
    @Test void mapsFiveHundredResponseToBadGateway() {
        status = 500;
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).forecast(request(), "id"))
                .isInstanceOf(MlIntegrationException.class).hasMessageContaining("HTTP 500");
    }
    @Test void mapsReadTimeoutToUnavailable() {
        delayMillis = 250;
        assertThatThrownBy(() -> client(Duration.ofMillis(50)).forecast(request(), "id"))
                .isInstanceOf(MlIntegrationException.class).hasMessageContaining("unavailable");
    }
    @Test void mapsConnectionFailureToUnavailable() throws IOException {
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        MlForecastClient disconnected = new MlForecastClient(RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port).build());
        assertThatThrownBy(() -> disconnected.forecast(request(), "id"))
                .isInstanceOf(MlIntegrationException.class).hasMessageContaining("unavailable");
    }
    @Test void rejectsContractMismatchedResponse() {
        responseBody = validResponse().replace("\"store_nbr\":3", "\"store_nbr\":99");
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).forecast(request(), "id"))
                .isInstanceOf(MlIntegrationException.class).hasMessageContaining("contract validation");
    }

    private MlForecastClient client(Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return new MlForecastClient(RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(factory).build());
    }
    private void respond(HttpExchange exchange) throws IOException {
        capturedRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
        capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        if (delayMillis > 0) try { Thread.sleep(delayMillis); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        byte[] bytes = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private static String validResponse() {
        return "{\"store_nbr\":3,\"family\":\"FAMILY A\",\"forecast_date\":\"2026-10-08\","
                + "\"forecast_demand\":12.5,\"forecast_horizon_days\":1,"
                + "\"forecast_type\":\"point_forecast\",\"model_version\":\"demand_forecast_v1.0.2\"}";
    }
    private static MlForecastRequest request() {
        return new MlForecastRequest(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 7), 3,
                "FAMILY A", 1.0, "City", "State", "A", 5, 75.0, 0, 2026, 10, 8,
                3, 41, 4, 0, 1, 1.0, 2.0, 3.0, 4.0, 2.0, 2.5, 3.0, 0.5);
    }
}
