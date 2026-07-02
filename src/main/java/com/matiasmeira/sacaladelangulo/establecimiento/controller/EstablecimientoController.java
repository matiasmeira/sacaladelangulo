package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Controlador REST para establecimientos.
 */
@RestController
@RequestMapping("/api/v1/establecimientos")
@RequiredArgsConstructor
public class EstablecimientoController {

    private final EstablecimientoService establecimientoService;

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

    @GetMapping("/buscar")
    public ResponseEntity<List<EstablecimientoResponse>> buscarEstablecimientos(
            @RequestParam Double latitud,
            @RequestParam Double longitud,
            @RequestParam(required = false, defaultValue = "10.0") Double distanciaKm,
            @RequestParam(required = false) String deporte,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime hora) {
        List<EstablecimientoResponse> resultados = establecimientoService.buscarEstablecimientos(latitud, longitud, distanciaKm, deporte, fecha, hora);
        return ResponseEntity.ok(resultados);
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
}
