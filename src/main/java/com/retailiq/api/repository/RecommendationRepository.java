package com.retailiq.api.repository;

import com.retailiq.api.entity.Recommendation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    List<Recommendation> findByStoreId(Long storeId);

    List<Recommendation> findByProductId(Long productId);

    List<Recommendation> findAllByStoreOwnerEmail(String email);

    java.util.Optional<Recommendation> findByIdAndStoreOwnerEmail(Long id, String email);

    List<Recommendation> findAllByStoreIdAndStoreOwnerEmail(Long storeId, String email);

    List<Recommendation> findAllByProductIdAndStoreOwnerEmail(Long productId, String email);
}
