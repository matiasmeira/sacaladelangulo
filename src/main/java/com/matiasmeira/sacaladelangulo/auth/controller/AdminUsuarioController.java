package com.matiasmeira.sacaladelangulo.auth.controller;

import com.matiasmeira.sacaladelangulo.auth.service.UsuarioEliminacionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de administración para eliminar/anonimizar cualquier cuenta (PLAYER/OWNER/ADMIN).
 * Requiere autenticación (cae bajo el anyRequest().authenticated() de SecurityConfig) y la
 * autorización de rol ADMIN se valida a mano dentro del service, mismo patrón que
 * AdminMailsController/OfertaMarketingService (no se usa @PreAuthorize en este repo).
 */
@RestController
@RequestMapping("/api/v1/admin/usuarios")
@RequiredArgsConstructor
public class AdminUsuarioController {

    private final UsuarioEliminacionService usuarioEliminacionService;

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(
            @PathVariable Long id,
            @RequestParam(name = "forzar", defaultValue = "false") boolean forzar,
            @AuthenticationPrincipal UserDetails userDetails) {
        usuarioEliminacionService.eliminarComoAdmin(userDetails.getUsername(), id, forzar);
        return ResponseEntity.noContent().build();
    }
}
