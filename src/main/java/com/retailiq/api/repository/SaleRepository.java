package com.retailiq.api.repository;

import com.retailiq.api.entity.Sale;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SaleRepository extends JpaRepository<Sale, Long> {

    List<Sale> findByStoreId(Long storeId);

    List<Sale> findByProductId(Long productId);

    List<Sale> findAllByStoreOwnerEmail(String email);

    Optional<Sale> findByIdAndStoreOwnerEmail(Long id, String email);

    List<Sale> findAllByStoreIdAndStoreOwnerEmail(Long storeId, String email);

    List<Sale> findAllByProductIdAndStoreOwnerEmail(Long productId, String email);

    @Query("SELECT s FROM Sale s WHERE (:fromDate IS NULL OR s.saleDate >= :fromDate) AND (:toDate IS NULL OR s.saleDate <= :toDate)")
    List<Sale> findByDateRange(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);
}
