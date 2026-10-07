package com.retailiq.api.dto.inventory;

import java.time.Instant;

public record InventoryResponse(
        Long id,
        Long storeId,
        Long productId,
        Integer quantity,
        Integer reorderLevel,
        Integer safetyStock,
        Instant updatedAt
) {}
