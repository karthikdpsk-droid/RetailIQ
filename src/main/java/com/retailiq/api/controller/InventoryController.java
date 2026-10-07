package com.retailiq.api.controller;

import com.retailiq.api.dto.inventory.InventoryRequest;
import com.retailiq.api.dto.inventory.InventoryResponse;
import com.retailiq.api.dto.inventory.StockAdjustmentRequest;
import com.retailiq.api.service.InventoryService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory")
@SecurityRequirement(name = "bearerAuth")
public class InventoryController {
    private final InventoryService service;
    public InventoryController(InventoryService service) { this.service = service; }
    @PostMapping public ResponseEntity<InventoryResponse> create(@Valid @RequestBody InventoryRequest request) {
        InventoryResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/" + response.id())).body(response);
    }
    @GetMapping public List<InventoryResponse> list(@RequestParam(required = false) Long storeId,
                                                     @RequestParam(required = false) Long productId) {
        if (storeId != null) return service.byStore(storeId);
        if (productId != null) return service.byProduct(productId);
        return service.list();
    }
    @GetMapping("/{id}") public InventoryResponse get(@PathVariable Long id) { return service.get(id); }
    @PutMapping("/{id}") public InventoryResponse update(@PathVariable Long id, @Valid @RequestBody InventoryRequest request) {
        return service.update(id, request);
    }
    @PatchMapping("/{id}/stock") public InventoryResponse adjust(@PathVariable Long id, @Valid @RequestBody StockAdjustmentRequest request) {
        return service.adjust(id, request.adjustment());
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id); return ResponseEntity.noContent().build();
    }
}
