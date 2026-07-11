package com.matiasmeira.sacaladelangulo.auth.controller;

import com.matiasmeira.sacaladelangulo.auth.dto.AuthRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.AuthResponse;
import com.matiasmeira.sacaladelangulo.auth.dto.EmpleadoLoginRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.RegisterRequest;
import com.matiasmeira.sacaladelangulo.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador REST para registrar y autenticar usuarios.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register/player")
    public ResponseEntity<AuthResponse> registerPlayer(@RequestBody @Valid RegisterRequest request) {
        AuthResponse response = authService.registerPlayer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/register/owner")
    public ResponseEntity<AuthResponse> registerOwner(@RequestBody @Valid RegisterRequest request) {
        AuthResponse response = authService.registerOwner(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody @Valid AuthRequest request) {
        AuthResponse response = authService.authenticate(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Login de mostrador: nombre del empleado (dentro del establecimiento) + PIN de
     * 4 dígitos. Emite un token de vida corta e independiente de la sesión del dueño.
     */
    @PostMapping("/empleados/login")
    public ResponseEntity<AuthResponse> loginEmpleado(@RequestBody @Valid EmpleadoLoginRequest request) {
        AuthResponse response = authService.authenticateEmpleado(request);
        return ResponseEntity.ok(response);
    }
}
