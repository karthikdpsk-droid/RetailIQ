package com.retailiq.api.service;

import com.retailiq.api.dto.recommendation.RecommendationRequest;
import com.retailiq.api.dto.recommendation.RecommendationResponse;
import com.retailiq.api.entity.Product;
import com.retailiq.api.entity.Recommendation;
import com.retailiq.api.entity.Store;
import com.retailiq.api.exception.ApiException;
import com.retailiq.api.exception.ResourceNotFoundException;
import com.retailiq.api.repository.RecommendationRepository;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RecommendationService {
    private final RecommendationRepository recommendations;
    private final StoreService storeService;
    private final ProductService productService;
    private final CurrentUserService currentUser;

    public RecommendationService(RecommendationRepository recommendations, StoreService storeService,
                                 ProductService productService, CurrentUserService currentUser) {
        this.recommendations = recommendations;
        this.storeService = storeService;
        this.productService = productService;
        this.currentUser = currentUser;
    }
    @Transactional(readOnly = true)
    public List<RecommendationResponse> list() {
        List<Recommendation> result = currentUser.isAdmin() ? recommendations.findAll() : recommendations.findAllByStoreOwnerEmail(currentUser.email());
        return result.stream().map(this::toResponse).toList();
    }
    @Transactional(readOnly = true)
    public RecommendationResponse get(Long id) { return toResponse(accessible(id)); }
    @Transactional(readOnly = true)
    public List<RecommendationResponse> byStore(Long id) {
        storeService.accessibleStore(id);
        return (currentUser.isAdmin() ? recommendations.findByStoreId(id) : recommendations.findAllByStoreIdAndStoreOwnerEmail(id, currentUser.email()))
                .stream().map(this::toResponse).toList();
    }
    @Transactional(readOnly = true)
    public List<RecommendationResponse> byProduct(Long id) {
        productService.accessibleProduct(id);
        return (currentUser.isAdmin() ? recommendations.findByProductId(id) : recommendations.findAllByProductIdAndStoreOwnerEmail(id, currentUser.email()))
                .stream().map(this::toResponse).toList();
    }
    @PreAuthorize("hasAnyRole('ADMIN', 'SHOPKEEPER')")
    public RecommendationResponse ingest(RecommendationRequest request) {
        Store store = storeService.accessibleStore(request.storeId());
        Product product = productService.accessibleProduct(request.productId());
        if (!product.getStore().getId().equals(store.getId())) throw new ApiException("Product does not belong to the requested store");
        Recommendation recommendation = new Recommendation();
        recommendation.setStore(store);
        recommendation.setProduct(product);
        recommendation.setRecommendationType(request.recommendationType());
        recommendation.setRecommendedQuantity(request.recommendedQuantity());
        recommendation.setReason(request.reason() == null ? null : request.reason().trim());
        recommendation.setRecommendationDate(request.recommendationDate());
        return toResponse(recommendations.save(recommendation));
    }
    private Recommendation accessible(Long id) {
        return (currentUser.isAdmin() ? recommendations.findById(id) : recommendations.findByIdAndStoreOwnerEmail(id, currentUser.email()))
                .orElseThrow(() -> new ResourceNotFoundException("Recommendation not found"));
    }
    private RecommendationResponse toResponse(Recommendation item) {
        return new RecommendationResponse(item.getId(), item.getStore().getId(), item.getProduct().getId(),
                item.getRecommendationType(), item.getRecommendedQuantity(), item.getReason(),
                item.getRecommendationDate(), item.getCreatedAt());
    }
}
