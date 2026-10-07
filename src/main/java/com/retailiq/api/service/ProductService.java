package com.retailiq.api.service;

import com.retailiq.api.dto.product.ProductRequest;
import com.retailiq.api.dto.product.ProductResponse;
import com.retailiq.api.entity.Product;
import com.retailiq.api.entity.Store;
import com.retailiq.api.exception.DuplicateResourceException;
import com.retailiq.api.exception.ResourceNotFoundException;
import com.retailiq.api.repository.ProductRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProductService {
    private final ProductRepository products;
    private final StoreService storeService;
    private final CurrentUserService currentUser;

    public ProductService(ProductRepository products, StoreService storeService, CurrentUserService currentUser) {
        this.products = products;
        this.storeService = storeService;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> list() {
        List<Product> result = currentUser.isAdmin() ? products.findAll() : products.findAllByStoreOwnerEmail(currentUser.email());
        return result.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id) { return toResponse(accessibleProduct(id)); }

    public ProductResponse create(ProductRequest request) {
        if (products.existsBySkuIgnoreCase(request.sku().trim())) throw new DuplicateResourceException("SKU already exists");
        Product product = new Product();
        apply(product, request);
        return toResponse(products.save(product));
    }

    public ProductResponse update(Long id, ProductRequest request) {
        Product product = accessibleProduct(id);
        if (!product.getSku().equalsIgnoreCase(request.sku().trim()) && products.existsBySkuIgnoreCase(request.sku().trim())) {
            throw new DuplicateResourceException("SKU already exists");
        }
        apply(product, request);
        return toResponse(products.save(product));
    }

    public void delete(Long id) { products.delete(accessibleProduct(id)); }

    @Transactional(readOnly = true)
    public Product accessibleProduct(Long id) {
        return (currentUser.isAdmin() ? products.findById(id) : products.findByIdAndStoreOwnerEmail(id, currentUser.email()))
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
    }

    private void apply(Product product, ProductRequest request) {
        Store store = storeService.accessibleStore(request.storeId());
        product.setStore(store);
        product.setName(request.name().trim());
        product.setSku(request.sku().trim().toUpperCase(java.util.Locale.ROOT));
        product.setCategory(request.category().trim());
        product.setDescription(request.description() == null ? null : request.description().trim());
        product.setUnitPrice(request.unitPrice());
        product.setUnit(request.unit().trim());
        product.setActive(request.active());
    }

    private ProductResponse toResponse(Product product) {
        return new ProductResponse(product.getId(), product.getStore().getId(), product.getName(), product.getSku(),
                product.getCategory(), product.getDescription(), product.getUnitPrice(), product.getUnit(),
                product.isActive(), product.getCreatedAt(), product.getUpdatedAt());
    }
}
