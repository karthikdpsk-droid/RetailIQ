package com.retailiq.api.dto.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record InventoryRequest(
        @NotNull(message = "Store ID is required")
        Long storeId,

        @NotNull(message = "Product ID is required")
        Long productId,

        @NotNull(message = "Quantity is required")
        @PositiveOrZero(message = "Quantity must be greater than or equal to 0")
        Integer quantity,

        @NotNull(message = "Reorder level is required")
        @PositiveOrZero(message = "Reorder level must be greater than or equal to 0")
        Integer reorderLevel,

        @NotNull(message = "Safety stock is required")
        @PositiveOrZero(message = "Safety stock must be greater than or equal to 0")
        Integer safetyStock
) {}
