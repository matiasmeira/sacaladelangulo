package com.matiasmeira.sacaladelangulo.auth.controller;

import com.matiasmeira.sacaladelangulo.auth.dto.AuthRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.AuthResponse;
import com.matiasmeira.sacaladelangulo.auth.dto.CompletarRegistroRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.EmpleadoLoginRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.IniciarRegistroRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.RegisterRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.ResetPasswordRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.SolicitarRecuperacionPasswordRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.VerificarCodigoRegistroRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.VerificarCodigoRegistroResponse;
import com.matiasmeira.sacaladelangulo.auth.dto.VerificarTokenResponse;
import com.matiasmeira.sacaladelangulo.auth.service.AuthService;
import com.matiasmeira.sacaladelangulo.auth.service.RecuperacionPasswordService;
import com.matiasmeira.sacaladelangulo.auth.service.RegistroVerificacionService;
import com.matiasmeira.sacaladelangulo.caja.model.DispositivoCaja;
import com.matiasmeira.sacaladelangulo.caja.service.DispositivoCajaGate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
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
    private final RecuperacionPasswordService recuperacionPasswordService;
    private final DispositivoCajaGate dispositivoCajaGate;

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
     * Invalida el JWT actual del usuario autenticado (ver AuthService.logout y B3 en la
     * auditoría). A diferencia del resto de /api/v1/auth/**, requiere autenticación:
     * ver la excepción explícita en SecurityConfig.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal UserDetails userDetails) {
        authService.logout(userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    /**
     * Login de mostrador: nombre del empleado (dentro del establecimiento) + PIN de
     * 4 dígitos. Emite un token de vida corta e independiente de la sesión del dueño.
     * Requiere una cookie de dispositivo de caja válida (ver DispositivoCajaGate): el
     * establecimiento efectivo se toma del dispositivo, no del body.
     */
    @PostMapping("/empleados/login")
    public ResponseEntity<AuthResponse> loginEmpleado(@RequestBody @Valid EmpleadoLoginRequest request, HttpServletRequest httpRequest) {
        DispositivoCaja dispositivo = dispositivoCajaGate.exigirDispositivo(httpRequest);
        AuthResponse response = authService.authenticateEmpleado(request, dispositivo.getEstablecimiento().getId());
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
     * Registro de jugadores en 2 pasos — alternativa al paso 2 por link: valida el token
     * a partir del código de 6 dígitos que el usuario tipeó a mano, devolviendo el mismo
     * token que el link para que el frontend siga con /registro/completar sin cambios.
     */
    @PostMapping("/registro/verificar-codigo")
    public ResponseEntity<VerificarCodigoRegistroResponse> verificarCodigo(@RequestBody @Valid VerificarCodigoRegistroRequest request) {
        return ResponseEntity.ok(registroVerificacionService.verificarCodigo(request.email(), request.codigo()));
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

    /**
     * Recuperación de contraseña — paso 1: siempre devuelve 200, exista o no el email, para
     * no convertir este endpoint en un oráculo de qué cuentas existen (ver
     * RecuperacionPasswordService.solicitarRecuperacion).
     */
    @PostMapping("/password/recuperar")
    public ResponseEntity<Void> solicitarRecuperacionPassword(@RequestBody @Valid SolicitarRecuperacionPasswordRequest request) {
        recuperacionPasswordService.solicitarRecuperacion(request);
        return ResponseEntity.ok().build();
    }

    /**
     * Recuperación de contraseña — paso 2: fija la nueva contraseña a partir del token del
     * link o del email+código tipeado a mano (ver RecuperacionPasswordService.resetPassword).
     */
    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@RequestBody @Valid ResetPasswordRequest request) {
        recuperacionPasswordService.resetPassword(request);
        return ResponseEntity.ok().build();
    }
}
