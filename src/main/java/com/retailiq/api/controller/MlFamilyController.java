package com.retailiq.api.controller;

import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.exception.DuplicateResourceException;
import com.retailiq.api.repository.MlFamilyRepository;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ml-families")
@Tag(name = "ML Families")
@SecurityRequirement(name = "bearerAuth")
public class MlFamilyController {

    private final MlFamilyRepository repository;

    public MlFamilyController(MlFamilyRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<MlFamily> list() {
        return repository.findAll();
    }

    public record CreateMlFamilyRequest(String familyCode) {}

    @PostMapping
    public ResponseEntity<MlFamily> create(@RequestBody CreateMlFamilyRequest request) {
        if (request == null || request.familyCode() == null || request.familyCode().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String code = request.familyCode().trim().toUpperCase(java.util.Locale.ROOT);
        if (repository.findByFamilyCode(code).isPresent()) {
            throw new DuplicateResourceException("ML family code already exists");
        }
        MlFamily family = repository.save(new MlFamily(code));
        return ResponseEntity.status(HttpStatus.CREATED).body(family);
    }
}
