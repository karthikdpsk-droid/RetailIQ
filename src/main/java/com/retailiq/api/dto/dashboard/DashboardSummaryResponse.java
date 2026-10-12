package com.retailiq.api.dto.dashboard;

import java.math.BigDecimal;
import java.util.List;

public record DashboardSummaryResponse(
        long stores,
        long products,
        long trackedInventoryItems,
        long lowStockItems,
        long manualSaleRecords,
        BigDecimal manualSalesAmount,
        long importedFamilySalesRecords,
        BigDecimal importedFamilySalesAmount,
        long importedTransactionRows,
        long importedTransactions,
        long forecastRecords,
        long recommendationRecords,
        long reorderRecommendations,
        List<DashboardTrendPoint> manualSalesTrend,
        List<DashboardTrendPoint> importedFamilySalesTrend) {}
