package com.retailiq.api.repository;

import com.retailiq.api.entity.StoreTransactionMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreTransactionMetricRepository extends JpaRepository<StoreTransactionMetric, Long> {
    long countByStore_Owner_IdAndSourceFile(Long ownerId, String sourceFile);

    @Query("select coalesce(sum(m.transactions), 0) from StoreTransactionMetric m "
            + "where (:allOwners = true or m.store.owner.email = :email)")
    Long sumTransactions(@Param("allOwners") boolean allOwners, @Param("email") String email);

    @Query("select count(m) from StoreTransactionMetric m where (:allOwners = true or m.store.owner.email = :email)")
    long countRows(@Param("allOwners") boolean allOwners, @Param("email") String email);
}
