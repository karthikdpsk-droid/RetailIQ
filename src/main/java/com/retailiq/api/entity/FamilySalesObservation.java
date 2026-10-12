package com.retailiq.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Dataset observations remain family-level monetary values, not product-unit sales. */
@Entity
@Table(name = "family_sales_observations", uniqueConstraints = @UniqueConstraint(
        name = "uk_family_sales_observation", columnNames = {"store_id", "ml_family_id", "observation_date"}))
public class FamilySalesObservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ml_family_id", nullable = false)
    private MlFamily mlFamily;

    @Column(name = "observation_date", nullable = false)
    private LocalDate observationDate;

    @Column(precision = 19, scale = 4)
    private BigDecimal sales;

    @Column(name = "on_promotion", precision = 19, scale = 4)
    private BigDecimal onPromotion;

    @Column(name = "source_file", nullable = false, length = 20)
    private String sourceFile;

    protected FamilySalesObservation() {}

    public FamilySalesObservation(Store store, MlFamily mlFamily, LocalDate observationDate,
            BigDecimal sales, BigDecimal onPromotion, String sourceFile) {
        this.store = store;
        this.mlFamily = mlFamily;
        this.observationDate = observationDate;
        this.sales = sales;
        this.onPromotion = onPromotion;
        this.sourceFile = sourceFile;
    }

    public Long getId() { return id; }
    public Store getStore() { return store; }
    public MlFamily getMlFamily() { return mlFamily; }
    public LocalDate getObservationDate() { return observationDate; }
    public BigDecimal getSales() { return sales; }
    public BigDecimal getOnPromotion() { return onPromotion; }
    public String getSourceFile() { return sourceFile; }
}
