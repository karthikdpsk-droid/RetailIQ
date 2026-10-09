package com.retailiq.api.service;

import com.retailiq.api.dto.store.StoreRequest;
import com.retailiq.api.dto.store.StoreResponse;
import com.retailiq.api.entity.Store;
import com.retailiq.api.entity.User;
import com.retailiq.api.exception.DuplicateResourceException;
import com.retailiq.api.exception.ResourceNotFoundException;
import com.retailiq.api.repository.StoreRepository;
import com.retailiq.api.repository.UserRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class StoreService {
    private final StoreRepository stores;
    private final UserRepository users;
    private final CurrentUserService currentUser;

    public StoreService(StoreRepository stores, UserRepository users, CurrentUserService currentUser) {
        this.stores = stores;
        this.users = users;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> list() {
        List<Store> result = currentUser.isAdmin() ? stores.findAll() : stores.findAllByOwnerEmail(currentUser.email());
        return result.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public StoreResponse get(Long id) { return toResponse(accessibleStore(id)); }

    public StoreResponse create(StoreRequest request) {
        if (stores.existsByStoreCodeIgnoreCase(request.storeCode().trim())) throw new DuplicateResourceException("Store code already exists");
        User owner = users.findByEmail(currentUser.email())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        Store store = new Store();
        store.setOwner(owner);
        apply(store, request);
        return toResponse(stores.save(store));
    }

    public StoreResponse update(Long id, StoreRequest request) {
        Store store = accessibleStore(id);
        if (!store.getStoreCode().equalsIgnoreCase(request.storeCode().trim()) && stores.existsByStoreCodeIgnoreCase(request.storeCode().trim())) {
            throw new DuplicateResourceException("Store code already exists");
        }
        apply(store, request);
        return toResponse(stores.save(store));
    }

    public void delete(Long id) { stores.delete(accessibleStore(id)); }

    @Transactional(readOnly = true)
    public Store accessibleStore(Long id) {
        return (currentUser.isAdmin() ? stores.findById(id) : stores.findByIdAndOwnerEmail(id, currentUser.email()))
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));
    }

    private void apply(Store store, StoreRequest request) {
        if ((request.mlStoreNbr() == null) != (request.mlCluster() == null)) {
            throw new IllegalArgumentException("ML store number and ML cluster must be provided together");
        }
        store.setStoreCode(request.storeCode().trim().toUpperCase(java.util.Locale.ROOT));
        store.setName(request.name().trim());
        store.setCity(request.city().trim());
        store.setState(request.state().trim());
        store.setAddress(request.address().trim());
        store.setType(request.type().trim());
        store.setActive(request.active());
        if (request.mlStoreNbr() != null) {
            store.setMlStoreNbr(request.mlStoreNbr());
            store.setMlCluster(request.mlCluster());
        }
    }

    private StoreResponse toResponse(Store store) {
        return new StoreResponse(store.getId(), store.getOwner().getId(), store.getStoreCode(), store.getName(),
                store.getCity(), store.getState(), store.getAddress(), store.getType(), store.isActive(),
                store.getMlStoreNbr(), store.getMlCluster(), store.getCreatedAt(), store.getUpdatedAt());
    }
}
