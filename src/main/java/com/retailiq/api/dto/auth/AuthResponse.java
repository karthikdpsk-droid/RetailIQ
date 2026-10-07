package com.retailiq.api.dto.auth;

import com.retailiq.api.dto.user.UserResponse;

public record AuthResponse(
        String token,
        String tokenType,
        UserResponse user
) {
    @Override
    public String toString() {
        return "AuthResponse[token=[REDACTED], tokenType=" + tokenType + ", user=" + user + "]";
    }
}
