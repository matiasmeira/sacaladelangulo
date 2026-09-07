package com.matiasmeira.sacaladelangulo.reserva.controller;

import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaSemanalRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.TurnoFijoListadoResponse;
import com.matiasmeira.sacaladelangulo.reserva.dto.TurnoFijoResponse;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.service.TurnoFijoService;
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
import org.springframework.web.bind.annotation.*;

/**
 * Turnos fijos semanales. La creación vivía en ReservaController como POST
 * /reservas/semanal; se movió acá para que la serie sea un recurso propio sobre el que
 * cuelgan listar, cancelar, renovar y editar cliente.
 */
@RestController
@RequestMapping("/api/v1/turnos-fijos")
@RequiredArgsConstructor
public class TurnoFijoController {

    private final TurnoFijoService turnoFijoService;

    /**
     * Crea un turno fijo: la regla más una reserva CONFIRMADA por cada fecha del período
     * que cae en el día pedido. Todo-o-nada. Sólo el dueño real del establecimiento o un
     * admin: un empleado no puede comprometer la agenda a un año.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<TurnoFijoResponse> crear(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid ReservaSemanalRequest request) {
        TurnoFijoResponse turnoFijo = turnoFijoService.crear(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(turnoFijo);
    }

    /**
     * Listado de turnos fijos del establecimiento. Lectura, no escritura: además del dueño
     * real y un admin, la puede ver un empleado que ya ve la agenda (ver
     * AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA).
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<Page<TurnoFijoListadoResponse>> listar(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam Long establecimientoId,
            @RequestParam(required = false) EstadoTurnoFijo estado,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(turnoFijoService.listar(
                establecimientoId, estado, pageable, userDetails.getUsername()));
    }

    /**
     * Detalle de una serie con todas sus ocurrencias. Misma autorización que el listado.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<TurnoFijoResponse> detalle(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        return ResponseEntity.ok(turnoFijoService.detalle(id, userDetails.getUsername()));
    }
}
