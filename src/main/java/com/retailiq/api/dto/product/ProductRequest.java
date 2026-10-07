package com.retailiq.api.dto.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ProductRequest(
        @NotNull(message = "Store ID is required")
        Long storeId,

        @NotBlank(message = "Product name is required")
        @Size(max = 150, message = "Product name must not exceed 150 characters")
        String name,

        @NotBlank(message = "SKU is required")
        @Size(max = 100, message = "SKU must not exceed 100 characters")
        String sku,

        @NotBlank(message = "Category is required")
        String category,

        @Size(max = 1000, message = "Description must not exceed 1000 characters")
        String description,

        @NotNull(message = "Unit price is required")
        @DecimalMin(value = "0.00", inclusive = true, message = "Unit price must be greater than or equal to 0")
        BigDecimal unitPrice,

        @NotBlank(message = "Unit is required")
        String unit,

        boolean active
) {}
