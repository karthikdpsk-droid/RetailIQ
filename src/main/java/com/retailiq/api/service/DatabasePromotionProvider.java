package com.retailiq.api.service;

import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.entity.Store;
import com.retailiq.api.repository.PromotionPlanRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.OptionalDouble;
import org.springframework.stereotype.Component;

@Component
public class DatabasePromotionProvider implements PromotionProvider {
    private final PromotionPlanRepository plans;

    public DatabasePromotionProvider(PromotionPlanRepository plans) { this.plans = plans; }

    @Override
    public OptionalDouble onPromotion(Store store, MlFamily family, LocalDate forecastDate, Instant predictionCutoff) {
        if (store == null || store.getMlStoreNbr() == null || store.getMlStoreNbr() < 1
                || family == null || family.getFamilyCode() == null || family.getFamilyCode().isBlank()
                || forecastDate == null || predictionCutoff == null) return OptionalDouble.empty();
        return plans.findAvailable(store.getMlStoreNbr(), family.getFamilyCode().trim(), forecastDate, predictionCutoff)
                .filter(p -> p.getOnPromotion() != null && p.getOnPromotion() >= 0)
                .map(p -> OptionalDouble.of(p.getOnPromotion().doubleValue()))
                .orElseGet(OptionalDouble::empty);
    }
}
