package com.retailiq.api.service;

import com.retailiq.api.dto.auth.AuthResponse;
import com.retailiq.api.dto.auth.LoginRequest;
import com.retailiq.api.dto.auth.RegisterRequest;
import com.retailiq.api.dto.user.UserResponse;
import com.retailiq.api.entity.Role;
import com.retailiq.api.entity.User;
import com.retailiq.api.exception.DuplicateResourceException;
import com.retailiq.api.repository.UserRepository;
import com.retailiq.api.security.JwtService;
import java.util.Locale;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("Email already exists");
        }

        User user = new User(request.name().trim(), email,
                passwordEncoder.encode(request.password()), Role.SHOPKEEPER);
        User savedUser = userRepository.save(user);
        log.info("Shopkeeper account registered");
        return response(savedUser);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.password()));
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        log.info("User authentication succeeded");
        return response(user);
    }

    private AuthResponse response(User user) {
        UserResponse safeUser = new UserResponse(user.getId(), user.getName(), user.getEmail(),
                user.getRole(), user.getCreatedAt(), user.getUpdatedAt());
        return new AuthResponse(jwtService.generateToken(user), "Bearer", safeUser);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
