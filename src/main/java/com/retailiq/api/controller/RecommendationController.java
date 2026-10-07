package com.retailiq.api.controller;

import com.retailiq.api.dto.recommendation.RecommendationRequest;
import com.retailiq.api.dto.recommendation.RecommendationResponse;
import com.retailiq.api.service.RecommendationService;
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
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/recommendations")
@Tag(name = "Recommendations")
@SecurityRequirement(name = "bearerAuth")
public class RecommendationController {
    private final RecommendationService service;
    public RecommendationController(RecommendationService service) { this.service = service; }
    @PostMapping("/ingest") public ResponseEntity<RecommendationResponse> ingest(@Valid @RequestBody RecommendationRequest request) {
        RecommendationResponse response = service.ingest(request);
        return ResponseEntity.created(URI.create("/api/v1/recommendations/" + response.id())).body(response);
    }
    @GetMapping public List<RecommendationResponse> list() { return service.list(); }
    @GetMapping("/{id}") public RecommendationResponse get(@PathVariable Long id) { return service.get(id); }
    @GetMapping("/store/{storeId}") public List<RecommendationResponse> byStore(@PathVariable Long storeId) { return service.byStore(storeId); }
    @GetMapping("/product/{productId}") public List<RecommendationResponse> byProduct(@PathVariable Long productId) { return service.byProduct(productId); }
}
