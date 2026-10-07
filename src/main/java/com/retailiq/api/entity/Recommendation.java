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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "recommendations", indexes = {
        @Index(name = "idx_recommendations_store_date", columnList = "store_id,recommendation_date"),
        @Index(name = "idx_recommendations_product_date", columnList = "product_id,recommendation_date")
})
public class Recommendation {

    public enum Type {
        REORDER,
        LOW_STOCK,
        OVERSTOCK,
        NORMAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type recommendationType;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal recommendedQuantity;

    @Column(length = 1000)
    private String reason;

    @Column(nullable = false)
    private LocalDate recommendationDate;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public Store getStore() { return store; }
    public void setStore(Store store) { this.store = store; }

    public Type getRecommendationType() { return recommendationType; }
    public void setRecommendationType(Type recommendationType) { this.recommendationType = recommendationType; }

    public BigDecimal getRecommendedQuantity() { return recommendedQuantity; }
    public void setRecommendedQuantity(BigDecimal recommendedQuantity) { this.recommendedQuantity = recommendedQuantity; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDate getRecommendationDate() { return recommendationDate; }
    public void setRecommendationDate(LocalDate recommendationDate) { this.recommendationDate = recommendationDate; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
