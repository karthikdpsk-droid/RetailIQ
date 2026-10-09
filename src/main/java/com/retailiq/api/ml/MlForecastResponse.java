package com.retailiq.api.ml;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;

public record MlForecastResponse(
        @JsonProperty("store_nbr") Integer storeNbr,
        String family,
        @JsonProperty("forecast_date") LocalDate forecastDate,
        @JsonProperty("forecast_demand") BigDecimal forecastDemand,
        @JsonProperty("forecast_horizon_days") Integer forecastHorizonDays,
        @JsonProperty("forecast_type") String forecastType,
        @JsonProperty("model_version") String modelVersion) {}
