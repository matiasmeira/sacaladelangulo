package com.matiasmeira.sacaladelangulo.reserva.controller;

import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import com.matiasmeira.sacaladelangulo.reserva.service.ReservaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador REST para reservas.
 */
@RestController
@RequestMapping("/api/v1/reservas")
@RequiredArgsConstructor
public class ReservaController {

    private final ReservaService reservaService;

    /**
     * Crea una nueva reserva.
     * Protegido: solo jugadores, propietarios de establecimientos y administradores.
     *
     * @param userDetails Detalles del usuario autenticado
     * @param request DTO con datos de la reserva
     * @return ReservaResponse con la reserva creada
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('PLAYER', 'OWNER', 'ADMIN')")
    public ResponseEntity<ReservaResponse> crearReserva(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid ReservaRequest request) {
        ReservaResponse reserva = reservaService.crearReserva(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(reserva);
    }

    /**
     * Confirma una reserva existente.
     * Protegido: solo propietarios de establecimientos y administradores.
     *
     * @param id ID de la reserva a confirmar
     * @param userDetails Detalles del usuario autenticado
     * @return ReservaResponse con la reserva confirmada
     */
    @PutMapping("/{id}/confirmar")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<ReservaResponse> confirmarReserva(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        ReservaResponse reserva = reservaService.confirmarReserva(id, userDetails.getUsername());
        return ResponseEntity.ok(reserva);
    }

    @GetMapping("/cancha/{canchaId}")
    @PreAuthorize("hasAnyRole('PLAYER', 'OWNER', 'ADMIN')")
    public ResponseEntity<org.springframework.data.domain.Page<ReservaResponse>> obtenerReservas(
            @PathVariable Long canchaId,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fecha,
            @org.springframework.data.web.PageableDefault(size = 10) org.springframework.data.domain.Pageable pageable) {
        return org.springframework.http.ResponseEntity.ok(reservaService.obtenerReservasPorCanchaYFecha(canchaId, fecha, pageable));
    }

    @GetMapping("/establecimiento/{estId}")
    @PreAuthorize("hasAnyRole('PLAYER', 'OWNER', 'ADMIN')")
    public ResponseEntity<org.springframework.data.domain.Page<ReservaResponse>> obtenerReservasPorEstablecimiento(
            @PathVariable Long estId,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fecha,
            @org.springframework.data.web.PageableDefault(size = 10) org.springframework.data.domain.Pageable pageable) {
        return org.springframework.http.ResponseEntity.ok(reservaService.obtenerReservasPorEstablecimientoYFecha(estId, fecha, pageable));
    }
}
