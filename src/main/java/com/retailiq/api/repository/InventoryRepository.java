package com.retailiq.api.repository;

import com.retailiq.api.entity.Inventory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    List<Inventory> findByStoreId(Long storeId);

    List<Inventory> findByProductId(Long productId);

    Optional<Inventory> findByStoreIdAndProductId(Long storeId, Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Inventory> findWithLockByStoreIdAndProductId(Long storeId, Long productId);

    List<Inventory> findByQuantityLessThanEqual(Integer threshold);

    boolean existsByStoreIdAndProductId(Long storeId, Long productId);

    List<Inventory> findAllByStoreOwnerEmail(String email);

    Optional<Inventory> findByIdAndStoreOwnerEmail(Long id, String email);

    @Query("select new com.retailiq.api.repository.InventoryStats(count(i), "
            + "coalesce(sum(case when i.quantity <= i.reorderLevel then 1L else 0L end), 0)) "
            + "from Inventory i where (:allOwners = true or i.store.owner.email = :email)")
    InventoryStats dashboardStats(@Param("allOwners") boolean allOwners, @Param("email") String email);
}
