package com.retailiq.api.controller;

import com.retailiq.api.dto.sale.SaleRequest;
import com.retailiq.api.dto.sale.SaleResponse;
import com.retailiq.api.service.SaleService;
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
@RequestMapping("/api/v1/sales")
@Tag(name = "Sales")
@SecurityRequirement(name = "bearerAuth")
public class SaleController {
    private final SaleService service;
    public SaleController(SaleService service) { this.service = service; }
    @PostMapping public ResponseEntity<SaleResponse> create(@Valid @RequestBody SaleRequest request) {
        SaleResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/sales/" + response.id())).body(response);
    }
    @GetMapping public List<SaleResponse> list() { return service.list(); }
    @GetMapping("/{id}") public SaleResponse get(@PathVariable Long id) { return service.get(id); }
    @GetMapping("/store/{storeId}") public List<SaleResponse> byStore(@PathVariable Long storeId) { return service.byStore(storeId); }
    @GetMapping("/product/{productId}") public List<SaleResponse> byProduct(@PathVariable Long productId) { return service.byProduct(productId); }
}
