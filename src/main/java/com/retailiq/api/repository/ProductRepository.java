package com.retailiq.api.repository;

import com.retailiq.api.entity.Product;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByNameContainingIgnoreCase(String name);

    List<Product> findByCategoryContainingIgnoreCase(String category);

    boolean existsBySkuIgnoreCase(String sku);

    List<Product> findAllByStoreOwnerEmail(String email);

    Optional<Product> findByIdAndStoreOwnerEmail(Long id, String email);
}
