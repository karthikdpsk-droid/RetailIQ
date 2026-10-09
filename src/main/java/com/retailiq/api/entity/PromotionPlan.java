package com.retailiq.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "promotion_plans", uniqueConstraints = @UniqueConstraint(
        name = "uk_promotion_plan_store_family_date",
        columnNames = {"ml_store_nbr", "ml_family_id", "forecast_date"}))
public class PromotionPlan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ml_store_nbr", nullable = false)
    private Integer mlStoreNbr;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ml_family_id", nullable = false)
    private MlFamily mlFamily;

    @Column(name = "forecast_date", nullable = false)
    private LocalDate forecastDate;

    @Column(name = "on_promotion", nullable = false)
    private Integer onPromotion;

    @Column(name = "available_from", nullable = false)
    private Instant availableFrom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getMlStoreNbr() { return mlStoreNbr; }
    public void setMlStoreNbr(Integer mlStoreNbr) { this.mlStoreNbr = mlStoreNbr; }
    public MlFamily getMlFamily() { return mlFamily; }
    public void setMlFamily(MlFamily mlFamily) { this.mlFamily = mlFamily; }
    public LocalDate getForecastDate() { return forecastDate; }
    public void setForecastDate(LocalDate forecastDate) { this.forecastDate = forecastDate; }
    public Integer getOnPromotion() { return onPromotion; }
    public void setOnPromotion(Integer onPromotion) { this.onPromotion = onPromotion; }
    public Instant getAvailableFrom() { return availableFrom; }
    public void setAvailableFrom(Instant availableFrom) { this.availableFrom = availableFrom; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
