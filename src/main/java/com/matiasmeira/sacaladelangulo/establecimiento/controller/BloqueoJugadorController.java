package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoJugadorRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoJugadorResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.service.BloqueoJugadorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controlador REST para el bloqueo de jugadores por establecimiento.
 * Protegido: solo propietarios de establecimientos y administradores.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/jugadores-bloqueados")
@RequiredArgsConstructor
public class BloqueoJugadorController {

    private final BloqueoJugadorService bloqueoJugadorService;

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<BloqueoJugadorResponse> crearBloqueo(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid BloqueoJugadorRequest request) {
        BloqueoJugadorResponse response = bloqueoJugadorService.crearBloqueo(establecimientoId, request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{jugadorId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Void> eliminarBloqueo(
            @PathVariable Long establecimientoId,
            @PathVariable Long jugadorId,
            @AuthenticationPrincipal UserDetails userDetails) {
        bloqueoJugadorService.eliminarBloqueo(establecimientoId, jugadorId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<BloqueoJugadorResponse>> listarBloqueados(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(bloqueoJugadorService.listarBloqueados(establecimientoId, userDetails.getUsername()));
    }
}
