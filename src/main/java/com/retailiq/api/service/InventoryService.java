package com.retailiq.api.service;

import com.retailiq.api.dto.inventory.InventoryRequest;
import com.retailiq.api.dto.inventory.InventoryResponse;
import com.retailiq.api.entity.Inventory;
import com.retailiq.api.entity.Product;
import com.retailiq.api.entity.Store;
import com.retailiq.api.exception.ApiException;
import com.retailiq.api.exception.DuplicateResourceException;
import com.retailiq.api.exception.ResourceNotFoundException;
import com.retailiq.api.repository.InventoryRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class InventoryService {
    private final InventoryRepository inventories;
    private final StoreService storeService;
    private final ProductService productService;
    private final CurrentUserService currentUser;

    public InventoryService(InventoryRepository inventories, StoreService storeService,
                            ProductService productService, CurrentUserService currentUser) {
        this.inventories = inventories;
        this.storeService = storeService;
        this.productService = productService;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<InventoryResponse> list() {
        List<Inventory> result = currentUser.isAdmin() ? inventories.findAll() : inventories.findAllByStoreOwnerEmail(currentUser.email());
        return result.stream().map(this::toResponse).toList();
    }
    @Transactional(readOnly = true)
    public InventoryResponse get(Long id) { return toResponse(accessible(id)); }
    @Transactional(readOnly = true)
    public List<InventoryResponse> byStore(Long storeId) {
        storeService.accessibleStore(storeId);
        return inventories.findByStoreId(storeId).stream().map(this::toResponse).toList();
    }
    @Transactional(readOnly = true)
    public List<InventoryResponse> byProduct(Long productId) {
        productService.accessibleProduct(productId);
        return inventories.findByProductId(productId).stream().map(this::toResponse).toList();
    }

    public InventoryResponse create(InventoryRequest request) {
        Store store = storeService.accessibleStore(request.storeId());
        Product product = productService.accessibleProduct(request.productId());
        requireSameStore(store, product);
        if (inventories.existsByStoreIdAndProductId(store.getId(), product.getId())) {
            throw new DuplicateResourceException("Inventory already exists for this store and product");
        }
        Inventory inventory = new Inventory();
        inventory.setStore(store);
        inventory.setProduct(product);
        apply(inventory, request);
        return toResponse(inventories.save(inventory));
    }

    public InventoryResponse update(Long id, InventoryRequest request) {
        Inventory inventory = accessible(id);
        Store store = storeService.accessibleStore(request.storeId());
        Product product = productService.accessibleProduct(request.productId());
        requireSameStore(store, product);
        inventories.findByStoreIdAndProductId(store.getId(), product.getId()).ifPresent(existing -> {
            if (!existing.getId().equals(id)) throw new DuplicateResourceException("Inventory already exists for this store and product");
        });
        inventory.setStore(store);
        inventory.setProduct(product);
        apply(inventory, request);
        return toResponse(inventories.save(inventory));
    }

    public InventoryResponse adjust(Long id, int adjustment) {
        Inventory inventory = accessible(id);
        long updated = (long) inventory.getQuantity() + adjustment;
        if (updated < 0 || updated > Integer.MAX_VALUE) throw new ApiException("Stock adjustment would produce an invalid quantity");
        inventory.setQuantity((int) updated);
        return toResponse(inventories.save(inventory));
    }

    public void decreaseForSale(Long storeId, Long productId, int quantity) {
        Inventory inventory = inventories.findWithLockByStoreIdAndProductId(storeId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory record not found for this store and product"));
        if (inventory.getQuantity() < quantity) throw new ApiException("Insufficient stock for this sale");
        inventory.setQuantity(inventory.getQuantity() - quantity);
        inventories.save(inventory);
    }

    public void delete(Long id) { inventories.delete(accessible(id)); }

    @Transactional(readOnly = true)
    public Inventory accessible(Long id) {
        return (currentUser.isAdmin() ? inventories.findById(id) : inventories.findByIdAndStoreOwnerEmail(id, currentUser.email()))
                .orElseThrow(() -> new ResourceNotFoundException("Inventory record not found"));
    }

    private void requireSameStore(Store store, Product product) {
        if (!product.getStore().getId().equals(store.getId())) {
            throw new ApiException("Product does not belong to the requested store");
        }
    }
    private void apply(Inventory inventory, InventoryRequest request) {
        inventory.setQuantity(request.quantity());
        inventory.setReorderLevel(request.reorderLevel());
        inventory.setSafetyStock(request.safetyStock());
    }
    private InventoryResponse toResponse(Inventory inventory) {
        return new InventoryResponse(inventory.getId(), inventory.getStore().getId(), inventory.getProduct().getId(),
                inventory.getQuantity(), inventory.getReorderLevel(), inventory.getSafetyStock(), inventory.getUpdatedAt());
    }
}
