package com.retailiq.api.controller;

import com.retailiq.api.dto.store.StoreRequest;
import com.retailiq.api.dto.store.StoreResponse;
import com.retailiq.api.service.StoreService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/stores")
@Tag(name = "Stores")
@SecurityRequirement(name = "bearerAuth")
public class StoreController {
    private final StoreService service;
    public StoreController(StoreService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<StoreResponse> create(@Valid @RequestBody StoreRequest request) {
        StoreResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/stores/" + response.id())).body(response);
    }
    @GetMapping public List<StoreResponse> list() { return service.list(); }
    @GetMapping("/{id}") public StoreResponse get(@PathVariable Long id) { return service.get(id); }
    @PutMapping("/{id}") public StoreResponse update(@PathVariable Long id, @Valid @RequestBody StoreRequest request) {
        return service.update(id, request);
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id); return ResponseEntity.noContent().build();
    }
}
