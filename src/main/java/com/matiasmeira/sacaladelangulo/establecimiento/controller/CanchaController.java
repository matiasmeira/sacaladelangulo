package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.service.CanchaService;
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
 * Controlador REST para canchas.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/canchas")
@RequiredArgsConstructor
public class CanchaController {

    private final CanchaService canchaService;

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<CanchaResponse> crearCancha(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid CanchaRequest request) {
        CanchaResponse cancha = canchaService.crearCancha(establecimientoId, request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(cancha);
    }

    /**
     * Canchas activas del establecimiento. La puede leer también un EMPLOYEE con
     * CREAR_RESERVA_MANUAL: es el catálogo sobre el que se elige dónde cargar el turno
     * que ese permiso lo autoriza a crear. Alta, edición y baja siguen siendo del dueño.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<List<CanchaResponse>> obtenerCanchasPorEstablecimiento(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<CanchaResponse> canchas = canchaService.obtenerCanchasPorEstablecimiento(establecimientoId, userDetails.getUsername());
        return ResponseEntity.ok(canchas);
    }

    @PutMapping("/{canchaId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<CanchaResponse> actualizarCancha(
            @PathVariable Long establecimientoId,
            @PathVariable Long canchaId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid CanchaRequest request) {
        CanchaResponse cancha = canchaService.actualizarCancha(establecimientoId, canchaId, request, userDetails.getUsername());
        return ResponseEntity.ok(cancha);
    }

    @DeleteMapping("/{canchaId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Void> desactivarCancha(
            @PathVariable Long establecimientoId,
            @PathVariable Long canchaId,
            @AuthenticationPrincipal UserDetails userDetails) {
        canchaService.desactivarCancha(establecimientoId, canchaId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }
}
