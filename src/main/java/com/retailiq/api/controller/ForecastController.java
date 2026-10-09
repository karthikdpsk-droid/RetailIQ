package com.retailiq.api.controller;

import com.retailiq.api.dto.forecast.ForecastRequest;
import com.retailiq.api.dto.forecast.ForecastResponse;
import com.retailiq.api.service.ForecastService;
import com.retailiq.api.service.ForecastOrchestrationService;
import com.retailiq.api.dto.forecast.StoreFamilyForecastCreateRequest;
import com.retailiq.api.dto.forecast.StoreFamilyForecastResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/forecasts")
@Tag(name = "Forecasts")
@SecurityRequirement(name = "bearerAuth")
public class ForecastController {
    private final ForecastService service;
    private final ForecastOrchestrationService orchestration;
    public ForecastController(ForecastService service, ForecastOrchestrationService orchestration) {
        this.service = service; this.orchestration = orchestration;
    }
    @PostMapping("/store-family")
    public ResponseEntity<StoreFamilyForecastResponse> requestStoreFamilyForecast(
            @Valid @RequestBody StoreFamilyForecastCreateRequest request,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return ResponseEntity.ok(orchestration.forecast(request, requestId));
    }
    @PostMapping("/ingest") public ResponseEntity<ForecastResponse> ingest(@Valid @RequestBody ForecastRequest request) {
        ForecastResponse response = service.ingest(request);
        return ResponseEntity.created(URI.create("/api/v1/forecasts/" + response.id())).body(response);
    }
    @GetMapping public List<ForecastResponse> list() { return service.list(); }
    @GetMapping("/{id}") public ForecastResponse get(@PathVariable Long id) { return service.get(id); }
    @GetMapping("/store/{storeId}") public List<ForecastResponse> byStore(@PathVariable Long storeId) { return service.byStore(storeId); }
    @GetMapping("/store-family/store/{storeId}")
    public List<StoreFamilyForecastResponse> byStoreFamily(@PathVariable Long storeId) { return service.byStoreFamily(storeId); }
    @GetMapping("/product/{productId}") public List<ForecastResponse> byProduct(@PathVariable Long productId) { return service.byProduct(productId); }
}
