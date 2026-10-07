package com.retailiq.api.dto.forecast;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ForecastResponse(Long id, Long storeId, Long productId, LocalDate forecastDate,
                               BigDecimal predictedDemand, BigDecimal lowerBound, BigDecimal upperBound,
                               String modelVersion, Instant createdAt) { }
