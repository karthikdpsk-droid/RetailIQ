package com.retailiq.api.dto.product;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        Long id,
        Long storeId,
        String name,
        String sku,
        String category,
        String description,
        BigDecimal unitPrice,
        String unit,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {}
