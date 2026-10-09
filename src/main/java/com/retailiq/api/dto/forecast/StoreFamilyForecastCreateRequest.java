package com.retailiq.api.dto.forecast;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

/** Business-level forecast command; model features remain server-owned. */
public record StoreFamilyForecastCreateRequest(
        @NotNull @Positive Long storeId,
        @NotBlank String mlFamily,
        @NotNull LocalDate forecastDate) {}
