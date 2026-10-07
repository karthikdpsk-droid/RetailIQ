package com.retailiq.api.repository;

import com.retailiq.api.entity.Store;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StoreRepository extends JpaRepository<Store, Long> {

    List<Store> findByNameContainingIgnoreCase(String name);

    List<Store> findByCityContainingIgnoreCase(String city);

    List<Store> findAllByOwnerEmail(String email);

    java.util.Optional<Store> findByIdAndOwnerEmail(Long id, String email);

    boolean existsByStoreCodeIgnoreCase(String storeCode);
}
