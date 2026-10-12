package com.retailiq.api.service;

import com.retailiq.api.dto.dashboard.DashboardSummaryResponse;
import com.retailiq.api.dto.dashboard.DashboardTrendPoint;
import com.retailiq.api.entity.Recommendation;
import com.retailiq.api.repository.FamilySalesObservationRepository;
import com.retailiq.api.repository.ForecastRepository;
import com.retailiq.api.repository.InventoryRepository;
import com.retailiq.api.repository.DailySalesTotal;
import com.retailiq.api.repository.ProductRepository;
import com.retailiq.api.repository.RecommendationRepository;
import com.retailiq.api.repository.SaleRepository;
import com.retailiq.api.repository.SalesStats;
import com.retailiq.api.repository.StoreFamilyForecastRepository;
import com.retailiq.api.repository.StoreRepository;
import com.retailiq.api.repository.StoreTransactionMetricRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardSummaryService {
    private final CurrentUserService currentUser;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final InventoryRepository inventory;
    private final SaleRepository sales;
    private final FamilySalesObservationRepository familySales;
    private final StoreTransactionMetricRepository transactions;
    private final ForecastRepository productForecasts;
    private final StoreFamilyForecastRepository familyForecasts;
    private final RecommendationRepository recommendations;

    public DashboardSummaryService(CurrentUserService currentUser, StoreRepository stores,
            ProductRepository products, InventoryRepository inventory, SaleRepository sales,
            FamilySalesObservationRepository familySales, StoreTransactionMetricRepository transactions,
            ForecastRepository productForecasts, StoreFamilyForecastRepository familyForecasts,
            RecommendationRepository recommendations) {
        this.currentUser = currentUser;
        this.stores = stores;
        this.products = products;
        this.inventory = inventory;
        this.sales = sales;
        this.familySales = familySales;
        this.transactions = transactions;
        this.productForecasts = productForecasts;
        this.familyForecasts = familyForecasts;
        this.recommendations = recommendations;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse get() {
        boolean allOwners = currentUser.isAdmin();
        String email = currentUser.email();
        SalesStats manual = sales.dashboardTotals(allOwners, email);
        var inventoryStats = inventory.dashboardStats(allOwners, email);
        long familyForecastCount = allOwners
                ? familyForecasts.count()
                : familyForecasts.countByStoreOwnerEmail(email);
        long productForecastCount = allOwners
                ? productForecasts.count()
                : productForecasts.countByStoreOwnerEmail(email);
        long recommendationCount = allOwners
                ? recommendations.count()
                : recommendations.countByStoreOwnerEmail(email);
        long reorderCount = allOwners
                ? recommendations.countByRecommendationType(Recommendation.Type.REORDER)
                : recommendations.countByStoreOwnerEmailAndRecommendationType(email, Recommendation.Type.REORDER);
        return new DashboardSummaryResponse(
                allOwners ? stores.count() : stores.countByOwnerEmail(email),
                allOwners ? products.count() : products.countByStoreOwnerEmail(email),
                inventoryStats.trackedItems(),
                inventoryStats.lowStockItems(),
                manual.records(),
                manual.monetaryTotal(),
                familySales.countMonetarySales(allOwners, email),
                familySales.sumMonetarySales(allOwners, email),
                transactions.countRows(allOwners, email),
                transactions.sumTransactions(allOwners, email),
                familyForecastCount + productForecastCount,
                recommendationCount,
                reorderCount,
                trend(sales.dashboardTrend(allOwners, email)),
                trend(familySales.monetarySalesTrend(allOwners, email)));
    }

    private static java.util.List<DashboardTrendPoint> trend(java.util.List<DailySalesTotal> totals) {
        return totals.stream().map(item -> new DashboardTrendPoint(item.saleDate().toString(), item.amount())).toList();
    }
}
