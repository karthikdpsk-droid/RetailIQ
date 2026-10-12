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
import java.time.LocalDate;

/** Store/day transaction counts are kept separate from monetary sales. */
@Entity
@Table(name = "store_transaction_metrics", uniqueConstraints = @UniqueConstraint(
        name = "uk_store_transaction_metric", columnNames = {"store_id", "metric_date", "source_file"}))
public class StoreTransactionMetric {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "metric_date", nullable = false)
    private LocalDate metricDate;

    @Column(nullable = false)
    private Integer transactions;

    @Column(name = "source_file", nullable = false, length = 30)
    private String sourceFile;

    protected StoreTransactionMetric() {}

    public StoreTransactionMetric(Store store, LocalDate metricDate, Integer transactions, String sourceFile) {
        this.store = store;
        this.metricDate = metricDate;
        this.transactions = transactions;
        this.sourceFile = sourceFile;
    }

    public Long getId() { return id; }
    public Store getStore() { return store; }
    public LocalDate getMetricDate() { return metricDate; }
    public Integer getTransactions() { return transactions; }
    public String getSourceFile() { return sourceFile; }
}
