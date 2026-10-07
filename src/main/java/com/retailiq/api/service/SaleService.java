package com.retailiq.api.service;

import com.retailiq.api.dto.sale.SaleRequest;
import com.retailiq.api.dto.sale.SaleResponse;
import com.retailiq.api.entity.Product;
import com.retailiq.api.entity.Sale;
import com.retailiq.api.entity.Store;
import com.retailiq.api.repository.SaleRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SaleService {
    private final SaleRepository sales;
    private final StoreService storeService;
    private final ProductService productService;
    private final InventoryService inventoryService;
    private final CurrentUserService currentUser;

    public SaleService(SaleRepository sales, StoreService storeService, ProductService productService,
                       InventoryService inventoryService, CurrentUserService currentUser) {
        this.sales = sales;
        this.storeService = storeService;
        this.productService = productService;
        this.inventoryService = inventoryService;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<SaleResponse> list() {
        List<Sale> result = currentUser.isAdmin() ? sales.findAll() : sales.findAllByStoreOwnerEmail(currentUser.email());
        return result.stream().map(this::toResponse).toList();
    }
    @Transactional(readOnly = true)
    public SaleResponse get(Long id) { return toResponse(accessible(id)); }
    @Transactional(readOnly = true)
    public List<SaleResponse> byStore(Long id) {
        storeService.accessibleStore(id);
        return (currentUser.isAdmin() ? sales.findByStoreId(id) : sales.findAllByStoreIdAndStoreOwnerEmail(id, currentUser.email()))
                .stream().map(this::toResponse).toList();
    }
    @Transactional(readOnly = true)
    public List<SaleResponse> byProduct(Long id) {
        productService.accessibleProduct(id);
        return (currentUser.isAdmin() ? sales.findByProductId(id) : sales.findAllByProductIdAndStoreOwnerEmail(id, currentUser.email()))
                .stream().map(this::toResponse).toList();
    }

    public SaleResponse create(SaleRequest request) {
        Store store = storeService.accessibleStore(request.storeId());
        Product product = productService.accessibleProduct(request.productId());
        if (!product.getStore().getId().equals(store.getId())) {
            throw new com.retailiq.api.exception.ApiException("Product does not belong to the requested store");
        }
        if (!product.isActive()) throw new com.retailiq.api.exception.ApiException("Inactive products cannot be sold");
        inventoryService.decreaseForSale(store.getId(), product.getId(), request.quantity());
        Sale sale = new Sale();
        sale.setStore(store);
        sale.setProduct(product);
        sale.setQuantity(request.quantity());
        sale.setSaleDate(request.saleDate());
        BigDecimal unitPrice = product.getUnitPrice();
        sale.setUnitPrice(unitPrice);
        sale.setTotalAmount(unitPrice.multiply(BigDecimal.valueOf(request.quantity())));
        return toResponse(sales.save(sale));
    }

    private Sale accessible(Long id) {
        return (currentUser.isAdmin() ? sales.findById(id) : sales.findByIdAndStoreOwnerEmail(id, currentUser.email()))
                .orElseThrow(() -> new com.retailiq.api.exception.ResourceNotFoundException("Sale not found"));
    }
    private SaleResponse toResponse(Sale sale) {
        return new SaleResponse(sale.getId(), sale.getStore().getId(), sale.getProduct().getId(), sale.getQuantity(),
                sale.getSaleDate(), sale.getUnitPrice(), sale.getTotalAmount(), sale.getCreatedAt());
    }
}
