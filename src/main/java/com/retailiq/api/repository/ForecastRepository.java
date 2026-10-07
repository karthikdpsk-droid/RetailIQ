package com.retailiq.api.repository;

import com.retailiq.api.entity.Forecast;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ForecastRepository extends JpaRepository<Forecast, Long> {

    List<Forecast> findByStoreId(Long storeId);

    List<Forecast> findByProductId(Long productId);

    List<Forecast> findAllByStoreOwnerEmail(String email);

    java.util.Optional<Forecast> findByIdAndStoreOwnerEmail(Long id, String email);

    List<Forecast> findAllByStoreIdAndStoreOwnerEmail(Long storeId, String email);

    List<Forecast> findAllByProductIdAndStoreOwnerEmail(Long productId, String email);
}
