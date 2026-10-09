package com.retailiq.api.repository;

import com.retailiq.api.entity.MlFamily;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlFamilyRepository extends JpaRepository<MlFamily, Long> {
    Optional<MlFamily> findByFamilyCode(String familyCode);
    boolean existsByFamilyCode(String familyCode);
}
