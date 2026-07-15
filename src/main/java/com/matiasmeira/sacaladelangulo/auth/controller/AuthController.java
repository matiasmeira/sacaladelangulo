package com.matiasmeira.sacaladelangulo.auth.controller;

import com.matiasmeira.sacaladelangulo.auth.dto.AuthRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.AuthResponse;
import com.matiasmeira.sacaladelangulo.auth.dto.CompletarRegistroRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.EmpleadoLoginRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.IniciarRegistroRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.RegisterRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.VerificarTokenResponse;
import com.matiasmeira.sacaladelangulo.auth.service.AuthService;
import com.matiasmeira.sacaladelangulo.auth.service.RegistroVerificacionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controlador REST para registrar y autenticar usuarios.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RegistroVerificacionService registroVerificacionService;

    /**
     * Endpoint de registro de jugadores en 1 solo paso, deprecado: permitía crear la
     * cuenta sin verificar el email, en paralelo al flujo de 2 pasos (ver
     * /registro/iniciar-verificar-completar más abajo), lo que anulaba la verificación.
     * Se mantiene la ruta mapeada (en vez de borrarla) para devolver un 410 explícito con
     * la migración esperada a quien todavía le pegue.
     */
    @PostMapping("/register/player")
    public ResponseEntity<Map<String, String>> registerPlayerDeprecado() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "error", "Este endpoint fue dado de baja. Registrate con el flujo de 2 pasos: " +
                        "POST /api/v1/auth/registro/iniciar, luego POST /api/v1/auth/registro/completar."
        ));
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

    /**
     * Registro de jugadores en 2 pasos — paso 1: recibe solo el email y dispara un link
     * de verificación (ver RegistroVerificacionService).
     */
    @PostMapping("/registro/iniciar")
    public ResponseEntity<Void> iniciarRegistro(@RequestBody @Valid IniciarRegistroRequest request) {
        registroVerificacionService.iniciarRegistro(request);
        return ResponseEntity.ok().build();
    }

    /**
     * Registro de jugadores en 2 pasos — paso 2: valida el token del link (sin
     * consumirlo) para que el frontend avance a la pantalla de completar los datos.
     */
    @GetMapping("/registro/verificar")
    public ResponseEntity<VerificarTokenResponse> verificarToken(@RequestParam String token) {
        return ResponseEntity.ok(registroVerificacionService.verificarToken(token));
    }

    /**
     * Registro de jugadores en 2 pasos — paso 3: revalida el token, crea el usuario con
     * el resto de sus datos y devuelve el JWT de sesión.
     */
    @PostMapping("/registro/completar")
    public ResponseEntity<AuthResponse> completarRegistro(@RequestBody @Valid CompletarRegistroRequest request) {
        AuthResponse response = registroVerificacionService.completarRegistro(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
