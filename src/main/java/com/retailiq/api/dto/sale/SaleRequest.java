package com.retailiq.api.dto.sale;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

public record SaleRequest(
        @NotNull(message = "Store ID is required")
        Long storeId,

        @NotNull(message = "Product ID is required")
        Long productId,

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be greater than 0")
        Integer quantity,

        @NotNull(message = "Sale date is required")
        LocalDate saleDate
) {}
