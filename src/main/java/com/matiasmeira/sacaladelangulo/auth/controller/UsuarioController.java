package com.matiasmeira.sacaladelangulo.auth.controller;

import com.matiasmeira.sacaladelangulo.auth.dto.EliminarCuentaRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.PerfilResponse;
import com.matiasmeira.sacaladelangulo.auth.dto.SolicitarCodigoRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.VerificarCodigoRequest;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioEliminacionService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controlador REST para gestión de datos de usuario.
 *
 * <p>Los endpoints de verificación de teléfono ({@code /telefono/*}) están apagados por la
 * propiedad {@code app.telefono.verificacion-habilitada} (default false): apagados responden
 * 410 sin generar ni enviar ningún código. El flujo (UsuarioService) se conserva intacto.
 */
@RestController
@RequestMapping("/api/v1/usuarios")
@RequiredArgsConstructor
public class UsuarioController {

    private final UsuarioService usuarioService;
    private final UsuarioEliminacionService usuarioEliminacionService;

    @Value("${app.telefono.verificacion-habilitada:false}")
    private boolean verificacionTelefonoHabilitada;

    private ResponseEntity<Map<String, String>> verificacionNoDisponible() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "error", "La verificación por teléfono todavía no está disponible."));
    }

    @PostMapping("/telefono/solicitar-codigo")
    public ResponseEntity<?> solicitarCodigo(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid SolicitarCodigoRequest request) {
        if (!verificacionTelefonoHabilitada) {
            return verificacionNoDisponible();
        }
        String email = userDetails.getUsername();
        usuarioService.solicitarCodigo(email, request.telefono());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/telefono/verificar-codigo")
    public ResponseEntity<?> verificarCodigo(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid VerificarCodigoRequest request) {
        if (!verificacionTelefonoHabilitada) {
            return verificacionNoDisponible();
        }
        String email = userDetails.getUsername();
        usuarioService.verificarCodigo(email, request.codigo());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<PerfilResponse> me(@AuthenticationPrincipal UserDetails userDetails) {
        String email = userDetails.getUsername();
        return ResponseEntity.ok(usuarioService.obtenerPerfil(email));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> eliminarMiCuenta(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid EliminarCuentaRequest request) {
        String email = userDetails.getUsername();
        usuarioEliminacionService.autoeliminar(email, request.password());
        return ResponseEntity.noContent().build();
    }
}
