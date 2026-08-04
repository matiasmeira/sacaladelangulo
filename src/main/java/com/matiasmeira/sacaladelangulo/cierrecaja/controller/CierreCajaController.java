package com.matiasmeira.sacaladelangulo.cierrecaja.controller;

import com.matiasmeira.sacaladelangulo.cierrecaja.dto.AbrirCajaRequest;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.CajaAbiertaResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.CerrarCajaRequest;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.CierreCajaResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.MovimientoCajaResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.MovimientoManualRequest;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.TurnoCajaDetalleResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.TurnoCajaResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.TurnoCajaResumenResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.service.TurnoCajaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador REST para la gestión de turnos de caja (apertura, movimientos manuales,
 * cierre/arqueo) de un establecimiento. Toda la lógica de negocio y el chequeo fino de
 * permisos vive en {@link TurnoCajaService}.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/caja")
@RequiredArgsConstructor
public class CierreCajaController {

    private final TurnoCajaService turnoCajaService;

    @PostMapping("/abrir")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<TurnoCajaResponse> abrirCaja(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid AbrirCajaRequest request) {
        TurnoCajaResponse turno = turnoCajaService.abrirCaja(establecimientoId, request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(turno);
    }

    @GetMapping("/abierta")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<CajaAbiertaResponse> getCajaAbierta(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(turnoCajaService.getCajaAbierta(establecimientoId, userDetails.getUsername()));
    }

    @PostMapping("/movimientos")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<MovimientoCajaResponse> registrarMovimientoManual(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid MovimientoManualRequest request) {
        MovimientoCajaResponse movimiento = turnoCajaService.registrarMovimientoManual(establecimientoId, request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(movimiento);
    }

    @PostMapping("/{turnoId}/cerrar")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<CierreCajaResponse> cerrarCaja(
            @PathVariable Long establecimientoId,
            @PathVariable Long turnoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid CerrarCajaRequest request) {
        return ResponseEntity.ok(turnoCajaService.cerrarCaja(establecimientoId, turnoId, request, userDetails.getUsername()));
    }

    @GetMapping("/turnos")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Page<TurnoCajaResumenResponse>> listarTurnos(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(turnoCajaService.listarTurnos(establecimientoId, pageable, userDetails.getUsername()));
    }

    @GetMapping("/turnos/{turnoId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<TurnoCajaDetalleResponse> getDetalleTurno(
            @PathVariable Long establecimientoId,
            @PathVariable Long turnoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(turnoCajaService.getDetalleTurno(establecimientoId, turnoId, userDetails.getUsername()));
    }
}
