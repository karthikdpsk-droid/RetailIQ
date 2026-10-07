package com.retailiq.api.dto.user;

import com.retailiq.api.entity.Role;
import java.time.Instant;

public record UserResponse(
        Long id,
        String name,
        String email,
        Role role,
        Instant createdAt,
        Instant updatedAt
) {}
