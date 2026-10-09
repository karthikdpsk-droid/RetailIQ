package com.retailiq.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "store_family_forecasts",
        uniqueConstraints = @UniqueConstraint(name = "uk_store_family_forecast_version",
                columnNames = {"store_id", "ml_family_id", "forecast_date", "model_version"}),
        indexes = @Index(name = "idx_store_family_forecast_date", columnList = "store_id,forecast_date"))
public class StoreFamilyForecast {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ml_family_id", nullable = false)
    private MlFamily mlFamily;

    @Column(name = "forecast_date", nullable = false)
    private LocalDate forecastDate;

    @Column(name = "prediction_cutoff", nullable = false)
    private LocalDate predictionCutoff;

    @Column(name = "forecast_demand", nullable = false, precision = 19, scale = 4)
    private BigDecimal forecastDemand;

    @Enumerated(EnumType.STRING)
    @Column(name = "demand_unit", nullable = false, length = 32)
    private DemandUnit demandUnit = DemandUnit.MONETARY_SALES;

    @Column(name = "model_version", nullable = false, length = 100)
    private String modelVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (demandUnit == null) demandUnit = DemandUnit.MONETARY_SALES;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Store getStore() { return store; }
    public void setStore(Store store) { this.store = store; }
    public MlFamily getMlFamily() { return mlFamily; }
    public void setMlFamily(MlFamily mlFamily) { this.mlFamily = mlFamily; }
    public LocalDate getForecastDate() { return forecastDate; }
    public void setForecastDate(LocalDate forecastDate) { this.forecastDate = forecastDate; }
    public LocalDate getPredictionCutoff() { return predictionCutoff; }
    public void setPredictionCutoff(LocalDate predictionCutoff) { this.predictionCutoff = predictionCutoff; }
    public BigDecimal getForecastDemand() { return forecastDemand; }
    public void setForecastDemand(BigDecimal forecastDemand) { this.forecastDemand = forecastDemand; }
    public DemandUnit getDemandUnit() { return demandUnit; }
    public void setDemandUnit(DemandUnit demandUnit) { this.demandUnit = demandUnit; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
