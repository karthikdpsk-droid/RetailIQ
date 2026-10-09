package com.retailiq.api.dto.store;

import java.time.Instant;

public record StoreResponse(
        Long id,
        Long ownerId,
        String storeCode,
        String name,
        String city,
        String state,
        String address,
        String type,
        boolean active,
        Integer mlStoreNbr,
        Integer mlCluster,
        Instant createdAt,
        Instant updatedAt
) {}
