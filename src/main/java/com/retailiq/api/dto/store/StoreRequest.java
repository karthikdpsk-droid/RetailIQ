package com.retailiq.api.dto.store;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StoreRequest(
        @NotBlank(message = "Store code is required")
        @Size(max = 50, message = "Store code must not exceed 50 characters")
        String storeCode,

        @NotBlank(message = "Store name is required")
        @Size(max = 150, message = "Store name must not exceed 150 characters")
        String name,

        @NotBlank(message = "City is required")
        String city,

        @NotBlank(message = "State is required")
        String state,

        @NotBlank(message = "Address is required")
        String address,

        @NotBlank(message = "Store type is required")
        String type,

        boolean active
) {}
