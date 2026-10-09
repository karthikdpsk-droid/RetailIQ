package com.retailiq.api.service;

import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.entity.Store;
import java.time.Instant;
import java.time.LocalDate;
import java.util.OptionalDouble;

public interface PromotionProvider {
    OptionalDouble onPromotion(Store store, MlFamily family, LocalDate forecastDate, Instant predictionCutoff);
}
