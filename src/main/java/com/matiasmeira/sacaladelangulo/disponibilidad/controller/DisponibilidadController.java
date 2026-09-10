package com.matiasmeira.sacaladelangulo.disponibilidad.controller;

import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.service.DisponibilidadService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Controlador REST para la grilla consolidada de disponibilidad de un establecimiento.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/disponibilidad")
@RequiredArgsConstructor
public class DisponibilidadController {

    private final DisponibilidadService disponibilidadService;

    /**
     * Devuelve, para un establecimiento y un rango de fechas (o un único día si se omite
     * fechaFin), la grilla de turnos 100% disponibles por cancha y duración, ya cruzada
     * contra horarios de atención, días no laborables, bloqueos y reservas existentes.
     * Accesible a cualquier usuario autenticado, incluido PLAYER, sin cambios en
     * slotsLibres. Cada cancha trae además ocupadaPorPool — los rangos en los que queda
     * sin cupo por consumo de pool de otra cancha, información agregada derivada de las
     * reservas del establecimiento — pero ese campo SOLO se puebla si quien pide tiene
     * acceso de PANEL a ESE establecimiento (dueño, admin, o empleado con permiso
     * operativo de agenda; ver AutorizacionEmpleadoService.tieneAccesoDePanel). Estar
     * autenticado no alcanza por sí solo: registrarse es gratis, y el {@code @PreAuthorize}
     * de este método acepta PLAYER sin distinguir de qué establecimiento es cada uno. Para
     * quien no califica (un jugador sin ese acceso, o el dueño de otro establecimiento) el
     * campo va en null, igual que en la disponibilidad pública de ComplejoPublicoService.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('PLAYER', 'OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<DisponibilidadEstablecimientoResponse> obtenerDisponibilidad(
            @PathVariable Long establecimientoId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaFin,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(
                disponibilidadService.obtenerDisponibilidadParaPanel(establecimientoId, fecha, fechaFin, userDetails.getUsername()));
    }
}
