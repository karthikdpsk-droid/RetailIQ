package com.retailiq.api.service;

import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.entity.PromotionPlan;
import com.retailiq.api.exception.ResourceNotFoundException;
import com.retailiq.api.repository.MlFamilyRepository;
import com.retailiq.api.repository.PromotionPlanRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PromotionPlanService {
    private final PromotionPlanRepository plans;
    private final MlFamilyRepository families;

    public PromotionPlanService(PromotionPlanRepository plans, MlFamilyRepository families) {
        this.plans = plans; this.families = families;
    }

    public PromotionPlan createOrUpdate(Integer storeNbr, String familyCode, LocalDate forecastDate,
            Integer onPromotion, Instant availableFrom) {
        if (storeNbr == null || storeNbr < 1) throw new IllegalArgumentException("ML store number must be positive");
        if (familyCode == null || familyCode.isBlank()) throw new IllegalArgumentException("ML family is required");
        if (forecastDate == null) throw new IllegalArgumentException("Forecast date is required");
        if (onPromotion == null || onPromotion < 0) throw new IllegalArgumentException("onPromotion must be nonnegative");
        if (availableFrom == null) throw new IllegalArgumentException("availableFrom is required");

        MlFamily family = families.findByFamilyCode(familyCode.trim())
                .orElseThrow(() -> new ResourceNotFoundException("ML family not found"));
        PromotionPlan plan = plans.findByMlStoreNbrAndMlFamilyIdAndForecastDate(storeNbr, family.getId(), forecastDate)
                .orElseGet(PromotionPlan::new);
        plan.setMlStoreNbr(storeNbr);
        plan.setMlFamily(family);
        plan.setForecastDate(forecastDate);
        plan.setOnPromotion(onPromotion);
        plan.setAvailableFrom(availableFrom);
        return plans.saveAndFlush(plan);
    }
}
