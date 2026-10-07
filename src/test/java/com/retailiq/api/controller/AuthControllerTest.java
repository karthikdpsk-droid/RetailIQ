package com.retailiq.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailiq.api.dto.auth.AuthResponse;
import com.retailiq.api.dto.auth.LoginRequest;
import com.retailiq.api.dto.auth.RegisterRequest;
import com.retailiq.api.dto.user.UserResponse;
import com.retailiq.api.entity.Role;
import com.retailiq.api.exception.DuplicateResourceException;
import com.retailiq.api.exception.GlobalExceptionHandler;
import com.retailiq.api.service.AuthService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class AuthControllerTest {

    private AuthService authService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void invalidRegistrationDataReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateEmailReturnsConflict() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new DuplicateResourceException("Email already exists"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("Shop Owner", "shop@example.com", "StrongPass123"))))
                .andExpect(status().isConflict());
    }

    @Test
    void registrationResponseDoesNotExposePassword() throws Exception {
        UserResponse safeUser = new UserResponse(1L, "Shop Owner", "shop@example.com", Role.SHOPKEEPER,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(new AuthResponse("test-token", "Bearer", safeUser));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("Shop Owner", "shop@example.com", "StrongPass123"))))
                .andExpect(status().isCreated())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("password"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("StrongPass123"))));
    }

    @Test
    void invalidLoginDataReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidCredentialsReturnUnauthorized() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("shop@example.com", "wrong-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticationDtoLogTextRedactsPasswordsAndTokens() {
        org.junit.jupiter.api.Assertions.assertFalse(
                new RegisterRequest("Owner", "owner@example.com", "StrongPass123").toString().contains("StrongPass123"));
        org.junit.jupiter.api.Assertions.assertFalse(
                new LoginRequest("owner@example.com", "StrongPass123").toString().contains("StrongPass123"));
        org.junit.jupiter.api.Assertions.assertFalse(
                new AuthResponse("jwt-secret-value", "Bearer", null).toString().contains("jwt-secret-value"));
    }
}
