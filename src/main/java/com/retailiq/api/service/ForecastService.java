package com.retailiq.api.service;

import com.retailiq.api.dto.forecast.ForecastRequest;
import com.retailiq.api.dto.forecast.ForecastResponse;
import com.retailiq.api.entity.Forecast;
import com.retailiq.api.entity.Product;
import com.retailiq.api.entity.Store;
import com.retailiq.api.exception.ApiException;
import com.retailiq.api.exception.ResourceNotFoundException;
import com.retailiq.api.repository.ForecastRepository;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ForecastService {
    private final ForecastRepository forecasts;
    private final StoreService storeService;
    private final ProductService productService;
    private final CurrentUserService currentUser;

    public ForecastService(ForecastRepository forecasts, StoreService storeService,
                           ProductService productService, CurrentUserService currentUser) {
        this.forecasts = forecasts;
        this.storeService = storeService;
        this.productService = productService;
        this.currentUser = currentUser;
    }
    @Transactional(readOnly = true)
    public List<ForecastResponse> list() {
        List<Forecast> result = currentUser.isAdmin() ? forecasts.findAll() : forecasts.findAllByStoreOwnerEmail(currentUser.email());
        return result.stream().map(this::toResponse).toList();
    }
    @Transactional(readOnly = true)
    public ForecastResponse get(Long id) { return toResponse(accessible(id)); }
    @Transactional(readOnly = true)
    public List<ForecastResponse> byStore(Long id) {
        storeService.accessibleStore(id);
        return (currentUser.isAdmin() ? forecasts.findByStoreId(id) : forecasts.findAllByStoreIdAndStoreOwnerEmail(id, currentUser.email()))
                .stream().map(this::toResponse).toList();
    }
    @Transactional(readOnly = true)
    public List<ForecastResponse> byProduct(Long id) {
        productService.accessibleProduct(id);
        return (currentUser.isAdmin() ? forecasts.findByProductId(id) : forecasts.findAllByProductIdAndStoreOwnerEmail(id, currentUser.email()))
                .stream().map(this::toResponse).toList();
    }
    @PreAuthorize("hasAnyRole('ADMIN', 'SHOPKEEPER')")
    public ForecastResponse ingest(ForecastRequest request) {
        Store store = storeService.accessibleStore(request.storeId());
        Product product = productService.accessibleProduct(request.productId());
        if (!product.getStore().getId().equals(store.getId())) throw new ApiException("Product does not belong to the requested store");
        Forecast forecast = new Forecast();
        forecast.setStore(store);
        forecast.setProduct(product);
        forecast.setForecastDate(request.forecastDate());
        forecast.setPredictedDemand(request.predictedDemand());
        forecast.setLowerBound(request.lowerBound());
        forecast.setUpperBound(request.upperBound());
        forecast.setModelVersion(request.modelVersion().trim());
        return toResponse(forecasts.save(forecast));
    }
    private Forecast accessible(Long id) {
        return (currentUser.isAdmin() ? forecasts.findById(id) : forecasts.findByIdAndStoreOwnerEmail(id, currentUser.email()))
                .orElseThrow(() -> new ResourceNotFoundException("Forecast not found"));
    }
    private ForecastResponse toResponse(Forecast item) {
        return new ForecastResponse(item.getId(), item.getStore().getId(), item.getProduct().getId(), item.getForecastDate(),
                item.getPredictedDemand(), item.getLowerBound(), item.getUpperBound(), item.getModelVersion(), item.getCreatedAt());
    }
}
