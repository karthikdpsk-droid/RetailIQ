package com.retailiq.api.dto.recommendation;

import com.retailiq.api.entity.Recommendation;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record RecommendationRequest(
        @NotNull(message = "Store ID is required") Long storeId,
        @NotNull(message = "Product ID is required") Long productId,
        @NotNull(message = "Recommendation type is required") Recommendation.Type recommendationType,
        @NotNull(message = "Recommended quantity is required")
        @DecimalMin(value = "0.00", message = "Recommended quantity must be non-negative") BigDecimal recommendedQuantity,
        @Size(max = 1000, message = "Reason must not exceed 1000 characters") String reason,
        @NotNull(message = "Recommendation date is required") LocalDate recommendationDate
) { }
