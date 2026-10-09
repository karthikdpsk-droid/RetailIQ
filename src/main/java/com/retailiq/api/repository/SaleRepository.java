package com.retailiq.api.repository;

import com.retailiq.api.entity.Sale;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
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

    @Query("SELECT new com.retailiq.api.repository.DailySalesTotal(s.saleDate, SUM(s.totalAmount)) " +
            "FROM Sale s JOIN s.product p " +
            "WHERE s.store.id = :storeId AND s.store.mlStoreNbr = :mlStoreNbr " +
            "AND p.store.id = s.store.id AND p.mlFamily.id = :mlFamilyId " +
            "AND s.saleDate BETWEEN :fromDate AND :cutoff " +
            "GROUP BY s.saleDate")
    List<DailySalesTotal> aggregateMonetarySalesByStoreFamilyAndDate(
            @Param("storeId") Long storeId,
            @Param("mlStoreNbr") Integer mlStoreNbr,
            @Param("mlFamilyId") Long mlFamilyId,
            @Param("fromDate") LocalDate fromDate,
            @Param("cutoff") LocalDate cutoff);
}
