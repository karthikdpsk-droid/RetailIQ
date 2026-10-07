package com.retailiq.api.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retailiq.api.entity.Role;
import com.retailiq.api.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {
    private static final String SECRET = "VGhpc0lzQUxvY2FsVGVzdFNlY3JldEtleUZvclJldGFpbElR";
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setExpirationMs(60_000);
        jwtService = new JwtService(properties);
    }

    @Test
    void generatedTokenIsValidAndContainsEmailIdentity() {
        User user = new User("Shopkeeper", "shop@example.com", "hash", Role.SHOPKEEPER);
        String token = jwtService.generateToken(user);
        assertTrue(jwtService.isTokenValid(token));
        org.junit.jupiter.api.Assertions.assertEquals("shop@example.com", jwtService.extractUsername(token));
    }

    @Test
    void invalidAndExpiredTokensAreRejected() {
        assertFalse(jwtService.isTokenValid("not.a.jwt"));
        var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        String expired = Jwts.builder().subject("shop@example.com")
                .issuedAt(Date.from(Instant.now().minusSeconds(3600)))
                .expiration(Date.from(Instant.now().minusSeconds(1800)))
                .signWith(key).compact();
        assertFalse(jwtService.isTokenValid(expired));
    }

    @Test
    void jwtConfigurationRejectsWeakSecrets() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("c2hvcnQ=");
        properties.setExpirationMs(1000);
        assertThrows(IllegalStateException.class, properties::validate);
    }
}
