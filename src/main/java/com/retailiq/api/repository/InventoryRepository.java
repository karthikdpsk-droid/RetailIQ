package com.retailiq.api.repository;

import com.retailiq.api.entity.Inventory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

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
}
