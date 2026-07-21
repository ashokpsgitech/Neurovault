package com.neurovault.backend.controller;

import com.neurovault.backend.dto.AuthResponse;
import com.neurovault.backend.dto.LoginRequest;
import com.neurovault.backend.dto.RegisterRequest;
import com.neurovault.backend.dto.UserDto;
import com.neurovault.backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * Controller exposing REST API endpoints for user registration and authentication login.
 * Supports both /api/auth and /api/v1/auth mappings for complete client compatibility.
 */
@RestController
@RequestMapping({"/api/auth", "/api/v1/auth"})
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Endpoint to register a new user client or host.
     */
    @PostMapping("/register")
    public ResponseEntity<UserDto> register(@Valid @RequestBody RegisterRequest request) {
        UserDto registeredUser = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(registeredUser);
    }

    /**
     * Endpoint to authenticate users and fetch JWT token.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint to fetch current authenticated user profile.
     */
    @GetMapping("/me")
    public ResponseEntity<UserDto> me(Principal principal) {
        UserDto currentUser = authService.getCurrentUser(principal.getName());
        return ResponseEntity.ok(currentUser);
    }
}
