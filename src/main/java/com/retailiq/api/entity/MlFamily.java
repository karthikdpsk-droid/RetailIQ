package com.retailiq.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Explicit ML target-family value; deliberately independent of Product.category. */
@Entity
@Table(name = "ml_families", uniqueConstraints =
        @UniqueConstraint(name = "uk_ml_families_family_code", columnNames = "family_code"))
public class MlFamily {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_code", nullable = false, length = 100)
    private String familyCode;

    public MlFamily() {}

    public MlFamily(String familyCode) { this.familyCode = familyCode; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFamilyCode() { return familyCode; }
    public void setFamilyCode(String familyCode) { this.familyCode = familyCode; }
}
