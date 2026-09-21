package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.CambiarEstadoEstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CambiarEstadoEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.PrevisualizacionEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.SolicitarVerificacionRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.SolicitarVerificacionResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoEstadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoPrevisualizacionService;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoService;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoVerificacionService;
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
 * Controlador REST para establecimientos.
 */
@RestController
@RequestMapping("/api/v1/establecimientos")
@RequiredArgsConstructor
public class EstablecimientoController {

    private final EstablecimientoService establecimientoService;
    private final EstablecimientoVerificacionService establecimientoVerificacionService;
    private final EstablecimientoPrevisualizacionService establecimientoPrevisualizacionService;
    private final EstablecimientoEstadoService establecimientoEstadoService;

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<EstablecimientoResponse> crearEstablecimiento(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid EstablecimientoRequest request) {
        EstablecimientoResponse establecimiento = establecimientoService.crearEstablecimiento(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(establecimiento);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<EstablecimientoResponse>> obtenerMisEstablecimientos(
            @AuthenticationPrincipal UserDetails userDetails) {
        List<EstablecimientoResponse> establecimientos = establecimientoService.obtenerMisEstablecimientos(userDetails.getUsername());
        return ResponseEntity.ok(establecimientos);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<EstablecimientoResponse> actualizarEstablecimiento(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid EstablecimientoRequest request) {
        EstablecimientoResponse establecimiento = establecimientoService.actualizarEstablecimiento(id, request, userDetails.getUsername());
        return ResponseEntity.ok(establecimiento);
    }

    /**
     * Solicita (o resolicita, si el estado actual es RECHAZADO) la verificación manual de
     * este establecimiento. hasRole('OWNER') puro, no hasAnyRole: dejar pasar a un ADMIN acá
     * sería autoverificarse por la ventana de atrás, mismo criterio que
     * AdminEstablecimientoController usa a la inversa.
     */
    @PostMapping("/{id}/solicitar-verificacion")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<SolicitarVerificacionResponse> solicitarVerificacion(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid SolicitarVerificacionRequest request) {
        SolicitarVerificacionResponse response =
                establecimientoVerificacionService.solicitarVerificacion(id, request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    /**
     * Previsualización de la ficha pública de este establecimiento para su dueño o para un
     * ADMIN, sin importar isActive ni estadoVerificacion. No habilita reservar.
     */
    @GetMapping("/{id}/previsualizacion")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<PrevisualizacionEstablecimientoResponse> previsualizar(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(establecimientoPrevisualizacionService.previsualizar(id, userDetails.getUsername()));
    }

    /**
     * Habilita o deshabilita este establecimiento (isActive) sin eliminar nada. Solo el
     * dueño -- mismo criterio de hasRole('OWNER') puro que solicitarVerificacion.
     */
    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<CambiarEstadoEstablecimientoResponse> cambiarEstado(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid CambiarEstadoEstablecimientoRequest request) {
        CambiarEstadoEstablecimientoResponse response =
                establecimientoEstadoService.cambiarEstado(id, request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }
}
