package com.matiasmeira.sacaladelangulo.publico.controller;

import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.publico.dto.ComplejoCardResponse;
import com.matiasmeira.sacaladelangulo.publico.dto.ComplejoDetalleResponse;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoPublicoService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Zona pública del marketplace: descubrimiento y comparación de complejos para un
 * visitante anónimo. Ningún endpoint de este controller requiere autenticación (ver
 * SecurityConfig) ni expone duenoId ni datos de jugadores.
 */
@RestController
@RequestMapping("/api/v1/publico/complejos")
@RequiredArgsConstructor
public class ComplejoPublicoController {

    private final ComplejoPublicoService complejoPublicoService;

    @GetMapping
    public ResponseEntity<Page<ComplejoCardResponse>> buscarComplejos(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double distanciaKm,
            @RequestParam(required = false) Deporte deporte,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime hora,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(complejoPublicoService.buscarComplejos(lat, lng, distanciaKm, deporte, fecha, hora, pageable));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ComplejoDetalleResponse> obtenerDetalle(@PathVariable String slug) {
        return ResponseEntity.ok(complejoPublicoService.obtenerDetalle(slug));
    }

    @GetMapping("/{slug}/disponibilidad")
    public ResponseEntity<DisponibilidadEstablecimientoResponse> obtenerDisponibilidad(
            @PathVariable String slug,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaFin) {
        return ResponseEntity.ok(complejoPublicoService.obtenerDisponibilidad(slug, fecha, fechaFin));
    }
}
