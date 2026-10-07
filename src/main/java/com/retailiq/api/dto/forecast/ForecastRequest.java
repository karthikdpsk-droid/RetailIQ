package com.retailiq.api.dto.forecast;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ForecastRequest(
        @NotNull(message = "Product ID is required")
        Long productId,

        @NotNull(message = "Store ID is required")
        Long storeId,

        @NotNull(message = "Forecast date is required")
        LocalDate forecastDate,

        @NotNull(message = "Predicted demand is required")
        @DecimalMin(value = "0.00", inclusive = true, message = "Predicted demand must be greater than or equal to 0")
        BigDecimal predictedDemand,

        @DecimalMin(value = "0.00", inclusive = true, message = "Lower bound must be greater than or equal to 0")
        BigDecimal lowerBound,

        @DecimalMin(value = "0.00", inclusive = true, message = "Upper bound must be greater than or equal to 0")
        BigDecimal upperBound,

        @NotBlank(message = "Model version is required")
        String modelVersion
) {}
