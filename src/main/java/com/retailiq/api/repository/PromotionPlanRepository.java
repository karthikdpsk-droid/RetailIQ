package com.retailiq.api.repository;

import com.retailiq.api.entity.PromotionPlan;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromotionPlanRepository extends JpaRepository<PromotionPlan, Long> {
    @Query("select p from PromotionPlan p where p.mlStoreNbr = :storeNbr "
            + "and p.mlFamily.familyCode = :familyCode and p.forecastDate = :forecastDate "
            + "and p.availableFrom <= :cutoff")
    Optional<PromotionPlan> findAvailable(@Param("storeNbr") Integer storeNbr,
            @Param("familyCode") String familyCode, @Param("forecastDate") LocalDate forecastDate,
            @Param("cutoff") Instant cutoff);

    Optional<PromotionPlan> findByMlStoreNbrAndMlFamilyIdAndForecastDate(
            Integer mlStoreNbr, Long familyId, LocalDate forecastDate);
}
