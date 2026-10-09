package com.retailiq.api.dto.forecast;

import com.retailiq.api.entity.DemandUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record StoreFamilyForecastResponse(Long id, Long storeId, String mlFamily,
        LocalDate forecastDate, LocalDate predictionCutoff, BigDecimal forecastDemand,
        DemandUnit demandUnit, String modelVersion, Instant createdAt) {
}
