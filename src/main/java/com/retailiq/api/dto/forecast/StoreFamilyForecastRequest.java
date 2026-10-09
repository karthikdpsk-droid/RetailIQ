package com.retailiq.api.dto.forecast;

import com.retailiq.api.entity.DemandUnit;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.AssertTrue;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Persistence contract for a store-family result; no product dimension exists. */
public record StoreFamilyForecastRequest(
        @NotNull @Positive Long storeId,
        @NotBlank String mlFamily,
        @NotNull LocalDate forecastDate,
        @NotNull LocalDate predictionCutoff,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal forecastDemand,
        @NotNull DemandUnit demandUnit,
        @NotBlank String modelVersion) {

    @AssertTrue(message = "predictionCutoff must be one day before forecastDate")
    public boolean isPredictionCutoffValid() {
        return forecastDate == null || predictionCutoff == null
                || forecastDate.equals(predictionCutoff.plusDays(1));
    }
}
