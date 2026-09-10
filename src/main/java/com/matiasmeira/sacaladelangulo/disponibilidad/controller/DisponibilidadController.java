package com.matiasmeira.sacaladelangulo.disponibilidad.controller;

import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.service.DisponibilidadService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
     * Cada cancha trae además ocupadaPorPool: los rangos en los que queda sin cupo por
     * consumo de pool de otra cancha (ver DisponibilidadCanchaResponse). Accesible a
     * cualquier usuario autenticado, incluido PLAYER: no expone identidad de jugadores ni
     * datos de reservas ajenas, aunque ocupadaPorPool sí es información derivada de
     * reservas existentes del establecimiento. La protección al jugador anónimo (sin
     * cuenta) es responsabilidad de ComplejoPublicoService, que pide esta misma grilla
     * con ese campo en null.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('PLAYER', 'OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<DisponibilidadEstablecimientoResponse> obtenerDisponibilidad(
            @PathVariable Long establecimientoId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaFin) {
        return ResponseEntity.ok(disponibilidadService.obtenerDisponibilidad(establecimientoId, fecha, fechaFin, true));
    }
}
