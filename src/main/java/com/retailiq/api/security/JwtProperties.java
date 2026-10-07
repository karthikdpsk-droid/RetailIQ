package com.retailiq.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import io.jsonwebtoken.io.Decoders;

@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String secret;
    private long expirationMs;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public void setExpirationMs(long expirationMs) {
        this.expirationMs = expirationMs;
    }

    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be set to a Base64-encoded secret of at least 32 bytes");
        }
        try {
            if (Decoders.BASE64.decode(secret).length < 32) {
                throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT_SECRET must be valid Base64", ex);
        }
        if (expirationMs <= 0) throw new IllegalStateException("JWT_EXPIRATION must be positive");
    }
}
