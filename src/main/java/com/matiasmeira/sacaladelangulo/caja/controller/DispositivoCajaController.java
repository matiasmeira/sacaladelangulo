package com.matiasmeira.sacaladelangulo.caja.controller;

import com.matiasmeira.sacaladelangulo.caja.dto.ActivarLocalRequest;
import com.matiasmeira.sacaladelangulo.caja.dto.ActivarLocalResponse;
import com.matiasmeira.sacaladelangulo.caja.dto.DispositivoCajaResponse;
import com.matiasmeira.sacaladelangulo.caja.dto.EmparejarRequest;
import com.matiasmeira.sacaladelangulo.caja.dto.EmparejarResponse;
import com.matiasmeira.sacaladelangulo.caja.service.DispositivoCajaService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Gestión (OWNER/ADMIN) de los dispositivos de caja emparejados de un establecimiento.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/caja/dispositivos")
@RequiredArgsConstructor
public class DispositivoCajaController {

    private final DispositivoCajaService dispositivoCajaService;

    /**
     * Activa la PC actual como caja: pensado para cuando el dueño/admin está físicamente
     * en el local. Setea la cookie de dispositivo directamente en la respuesta.
     */
    @PostMapping("/activar-local")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<ActivarLocalResponse> activarLocal(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody(required = false) ActivarLocalRequest request,
            HttpServletResponse response) {
        return ResponseEntity.ok(dispositivoCajaService.activarLocal(establecimientoId, userDetails.getUsername(), request, response));
    }

    /**
     * Genera un código de emparejamiento de un solo uso para habilitar una PC en la que
     * el dueño/admin no está presente. El código crudo solo se devuelve en esta respuesta.
     */
    @PostMapping("/emparejar")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<EmparejarResponse> emparejar(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody(required = false) EmparejarRequest request) {
        return ResponseEntity.ok(dispositivoCajaService.emparejar(establecimientoId, userDetails.getUsername(), request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<DispositivoCajaResponse>> listar(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(dispositivoCajaService.listar(establecimientoId, userDetails.getUsername()));
    }

    @DeleteMapping("/{dispositivoId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Void> revocar(
            @PathVariable Long establecimientoId,
            @PathVariable Long dispositivoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        dispositivoCajaService.revocar(establecimientoId, dispositivoId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }
}
