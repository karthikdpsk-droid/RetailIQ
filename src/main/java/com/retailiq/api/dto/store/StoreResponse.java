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
        Instant createdAt,
        Instant updatedAt
) {}
