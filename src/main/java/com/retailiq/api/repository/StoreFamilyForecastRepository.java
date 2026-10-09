package com.retailiq.api.repository;

import com.retailiq.api.entity.StoreFamilyForecast;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreFamilyForecastRepository extends JpaRepository<StoreFamilyForecast, Long> {
    List<StoreFamilyForecast> findByStoreIdOrderByForecastDateDesc(Long storeId);
    List<StoreFamilyForecast> findByStoreIdAndMlFamilyFamilyCodeOrderByForecastDateDesc(Long storeId, String familyCode);
    List<StoreFamilyForecast> findByStoreIdAndForecastDate(Long storeId, LocalDate forecastDate);
    boolean existsByStoreIdAndMlFamilyIdAndForecastDateAndModelVersion(
            Long storeId, Long mlFamilyId, LocalDate forecastDate, String modelVersion);
}
