package com.matiasmeira.sacaladelangulo.auth.controller;

import com.matiasmeira.sacaladelangulo.auth.dto.SolicitarCodigoRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.VerificarCodigoRequest;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador REST para gestión de datos de usuario.
 */
@RestController
@RequestMapping("/api/v1/usuarios")
@RequiredArgsConstructor
public class UsuarioController {

    private final UsuarioService usuarioService;

    @PostMapping("/telefono/solicitar-codigo")
    public ResponseEntity<Void> solicitarCodigo(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid SolicitarCodigoRequest request) {
        String email = userDetails.getUsername();
        usuarioService.solicitarCodigo(email, request.telefono());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/telefono/verificar-codigo")
    public ResponseEntity<Void> verificarCodigo(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid VerificarCodigoRequest request) {
        String email = userDetails.getUsername();
        usuarioService.verificarCodigo(email, request.codigo());
        return ResponseEntity.ok().build();
    }
}
