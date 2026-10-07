package com.retailiq.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.retailiq.api.dto.auth.LoginRequest;
import com.retailiq.api.dto.auth.RegisterRequest;
import com.retailiq.api.entity.Role;
import com.retailiq.api.entity.User;
import com.retailiq.api.exception.DuplicateResourceException;
import com.retailiq.api.repository.UserRepository;
import com.retailiq.api.security.JwtService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {

    private UserRepository users;
    private BCryptPasswordEncoder encoder;
    private AuthenticationManager authenticationManager;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        users = org.mockito.Mockito.mock(UserRepository.class);
        encoder = new BCryptPasswordEncoder();
        authenticationManager = org.mockito.Mockito.mock(AuthenticationManager.class);
        jwtService = org.mockito.Mockito.mock(JwtService.class);
        authService = new AuthService(users, encoder, authenticationManager, jwtService);
        when(jwtService.generateToken(any(User.class))).thenReturn("test-token");
    }

    @Test
    void registrationNormalizesEmailHashesPasswordAndAlwaysAssignsShopkeeper() {
        when(users.existsByEmail("shop@example.com")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = authService.register(new RegisterRequest("Shop Owner", " Shop@Example.com ", "StrongPass123"));

        var saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        User user = saved.getValue();
        assertEquals("shop@example.com", user.getEmail());
        assertNotEquals("StrongPass123", user.getPassword());
        org.junit.jupiter.api.Assertions.assertTrue(encoder.matches("StrongPass123", user.getPassword()));
        assertEquals(Role.SHOPKEEPER, user.getRole());
        assertEquals("SHOPKEEPER", response.user().role().name());
    }

    @Test
    void duplicateEmailIsRejected() {
        when(users.existsByEmail("shop@example.com")).thenReturn(true);
        assertThrows(DuplicateResourceException.class,
                () -> authService.register(new RegisterRequest("Shop Owner", "SHOP@example.com", "StrongPass123")));
    }

    @Test
    void successfulLoginAuthenticatesNormalizedEmail() {
        User user = new User("Shop Owner", "shop@example.com", "bcrypt-hash", Role.SHOPKEEPER);
        when(authenticationManager.authenticate(any())).thenReturn(
                new UsernamePasswordAuthenticationToken("shop@example.com", null));
        when(users.findByEmail("shop@example.com")).thenReturn(Optional.of(user));

        var response = authService.login(new LoginRequest(" SHOP@Example.com ", "StrongPass123"));

        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
        assertEquals("test-token", response.token());
        assertEquals("shop@example.com", response.user().email());
    }

    @Test
    void incorrectPasswordIsRejectedAsInvalidCredentials() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));
        assertThrows(BadCredentialsException.class,
                () -> authService.login(new LoginRequest("shop@example.com", "wrong-password")));
    }

    @Test
    void unknownEmailIsRejectedAsInvalidCredentials() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));
        assertThrows(BadCredentialsException.class,
                () -> authService.login(new LoginRequest("unknown@example.com", "StrongPass123")));
    }
}
