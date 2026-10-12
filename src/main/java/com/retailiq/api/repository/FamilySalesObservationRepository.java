package com.retailiq.api.repository;

import com.retailiq.api.entity.FamilySalesObservation;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamilySalesObservationRepository extends JpaRepository<FamilySalesObservation, Long> {
    long countByStore_Owner_IdAndSourceFile(Long ownerId, String sourceFile);

    @Query("select coalesce(sum(o.sales), 0) from FamilySalesObservation o "
            + "where o.sales is not null and (:allOwners = true or o.store.owner.email = :email)")
    java.math.BigDecimal sumMonetarySales(@Param("allOwners") boolean allOwners, @Param("email") String email);

    @Query("select count(o) from FamilySalesObservation o where o.sales is not null "
            + "and (:allOwners = true or o.store.owner.email = :email)")
    long countMonetarySales(@Param("allOwners") boolean allOwners, @Param("email") String email);

    @Query("select new com.retailiq.api.repository.DailySalesTotal(o.observationDate, sum(o.sales)) "
            + "from FamilySalesObservation o where o.sales is not null "
            + "and (:allOwners = true or o.store.owner.email = :email) "
            + "group by o.observationDate order by o.observationDate")
    List<DailySalesTotal> monetarySalesTrend(@Param("allOwners") boolean allOwners, @Param("email") String email);

    List<FamilySalesObservation> findByStore_IdAndMlFamily_IdAndObservationDateBetween(
            Long storeId, Long familyId, LocalDate start, LocalDate end);

    @Query("select o from FamilySalesObservation o where o.store.mlStoreNbr = :storeNbr "
            + "and o.mlFamily.familyCode = :familyCode and o.observationDate = :date "
            + "and o.onPromotion is not null")
    Optional<FamilySalesObservation> findPromotion(
            @Param("storeNbr") Integer storeNbr,
            @Param("familyCode") String familyCode,
            @Param("date") LocalDate date);
}
