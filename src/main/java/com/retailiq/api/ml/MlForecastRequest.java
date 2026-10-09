package com.retailiq.api.ml;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

/** Internal wire DTO matching the FastAPI /forecast request contract. */
public record MlForecastRequest(
        @JsonProperty("forecast_date") LocalDate forecastDate,
        @JsonProperty("prediction_cutoff") LocalDate predictionCutoff,
        @JsonProperty("store_nbr") Integer storeNbr,
        String family,
        Double onpromotion,
        String city,
        String state,
        String type,
        Integer cluster,
        Double dcoilwtico,
        @JsonProperty("is_holiday_event") Integer isHolidayEvent,
        Integer year,
        Integer month,
        Integer day,
        @JsonProperty("day_of_week") Integer dayOfWeek,
        @JsonProperty("week_of_year") Integer weekOfYear,
        Integer quarter,
        @JsonProperty("is_weekend") Integer isWeekend,
        @JsonProperty("has_promotion") Integer hasPromotion,
        @JsonProperty("sales_lag_1") Double salesLag1,
        @JsonProperty("sales_lag_7") Double salesLag7,
        @JsonProperty("sales_lag_14") Double salesLag14,
        @JsonProperty("sales_lag_28") Double salesLag28,
        @JsonProperty("sales_rolling_mean_7") Double salesRollingMean7,
        @JsonProperty("sales_rolling_mean_14") Double salesRollingMean14,
        @JsonProperty("sales_rolling_mean_28") Double salesRollingMean28,
        @JsonProperty("sales_rolling_std_7") Double salesRollingStd7) {}
