package com.matiasmeira.sacaladelangulo.reserva.controller;

import com.matiasmeira.sacaladelangulo.reserva.dto.FinalizarReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.MoverReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaManualRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaSemanalRequest;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.service.ReservaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

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
     * Crea una reserva de mostrador (cliente presencial/telefónico sin cuenta registrada).
     * Protegido: solo el dueño real del establecimiento de la cancha, o un administrador.
     *
     * @param userDetails Detalles del usuario autenticado
     * @param request DTO con datos de la reserva manual y del cliente
     * @return ReservaResponse con la reserva creada, ya en estado CONFIRMADA
     */
    @PostMapping("/manual")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<ReservaResponse> crearReservaManual(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid ReservaManualRequest request) {
        ReservaResponse reserva = reservaService.crearReservaManual(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(reserva);
    }

    /**
     * Crea un turno fijo semanal: genera una reserva individual, ya CONFIRMADA, por
     * cada fecha del período que coincida con el día de la semana solicitado.
     * Protegido: el dueño del establecimiento o un administrador (mismo criterio que
     * ReservaService.validarPropietarioOAdmin, ver M12 en la auditoría).
     *
     * @param userDetails Detalles del usuario autenticado
     * @param request DTO con el rango de fechas, día/horario recurrente y datos del cliente
     * @return Lista de ReservaResponse con cada ocurrencia creada
     */
    @PostMapping("/semanal")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<ReservaResponse>> crearReservaSemanal(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid ReservaSemanalRequest request) {
        List<ReservaResponse> reservas = reservaService.crearReservaSemanal(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(reservas);
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

    @PutMapping("/{id}/cancelar")
    @PreAuthorize("hasAnyRole('PLAYER', 'OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<ReservaResponse> cancelarReserva(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        ReservaResponse reserva = reservaService.cancelarReserva(id, userDetails.getUsername());
        return ResponseEntity.ok(reserva);
    }

    /**
     * Finaliza una reserva (el turno ya se jugó), registrando el método de pago con el
     * que se saldó.
     * Protegido: propietarios, administradores, y empleados con el permiso FINALIZAR_RESERVA.
     *
     * @param id ID de la reserva a finalizar
     * @param userDetails Detalles del usuario autenticado
     * @param request DTO con el método de pago utilizado
     * @return ReservaResponse con la reserva finalizada
     */
    @PatchMapping("/{id}/finalizar")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<ReservaResponse> finalizarReserva(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid FinalizarReservaRequest request) {
        ReservaResponse reserva = reservaService.finalizarReserva(id, request.metodoPago(), userDetails.getUsername());
        return ResponseEntity.ok(reserva);
    }

    /**
     * Marca una reserva confirmada como AUSENTE (no-show): el jugador no se presentó al turno.
     * Protegido: propietarios, administradores, y empleados con el permiso MARCAR_AUSENTE.
     *
     * @param id ID de la reserva a marcar como ausente
     * @param userDetails Detalles del usuario autenticado
     * @return ReservaResponse con la reserva actualizada
     */
    @PatchMapping("/{id}/ausente")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<ReservaResponse> marcarAusente(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        ReservaResponse reserva = reservaService.marcarAusente(id, userDetails.getUsername());
        return ResponseEntity.ok(reserva);
    }

    /**
     * Revierte una reserva marcada como AUSENTE de vuelta a CONFIRMADA.
     * Protegido: solo propietarios de establecimientos y administradores.
     *
     * @param id ID de la reserva a revertir
     * @param userDetails Detalles del usuario autenticado
     * @return ReservaResponse con la reserva actualizada
     */
    @PatchMapping("/{id}/revertir-ausencia")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<ReservaResponse> revertirAusencia(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        ReservaResponse reserva = reservaService.revertirAusencia(id, userDetails.getUsername());
        return ResponseEntity.ok(reserva);
    }

    /**
     * Mueve una reserva a otra cancha del mismo establecimiento (por ejemplo, para
     * reubicarla cuando la cancha original fue bloqueada y hay una equivalente libre).
     * Protegido: solo propietarios de establecimientos y administradores.
     *
     * @param id ID de la reserva a mover
     * @param userDetails Detalles del usuario autenticado
     * @param request DTO con el ID de la cancha destino
     * @return ReservaResponse con la reserva ya reasignada
     */
    @PutMapping("/{id}/mover-cancha")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<ReservaResponse> moverReservaDeCancha(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid MoverReservaRequest request) {
        ReservaResponse reserva = reservaService.moverReservaDeCancha(id, request.nuevaCanchaId(), userDetails.getUsername());
        return ResponseEntity.ok(reserva);
    }

    @GetMapping("/cancha/{canchaId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Page<ReservaResponse>> obtenerReservas(
            @PathVariable Long canchaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(defaultValue = "false") boolean incluirCanceladas,
            @AuthenticationPrincipal UserDetails userDetails,
            @ParameterObject @PageableDefault(size = 10) Pageable pageable) {
        return ResponseEntity.ok(reservaService.obtenerReservasPorCanchaYFecha(canchaId, fecha, incluirCanceladas, pageable, userDetails.getUsername()));
    }

    @GetMapping("/establecimiento/{estId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Page<ReservaResponse>> obtenerReservasPorEstablecimiento(
            @PathVariable Long estId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(defaultValue = "false") boolean incluirCanceladas,
            @AuthenticationPrincipal UserDetails userDetails,
            @ParameterObject @PageableDefault(size = 10) Pageable pageable) {
        return ResponseEntity.ok(reservaService.obtenerReservasPorEstablecimientoYFecha(estId, fecha, incluirCanceladas, pageable, userDetails.getUsername()));
    }

    /**
     * Lista las reservas del jugador autenticado, opcionalmente filtradas por estado.
     * Protegido: solo jugadores (rol PLAYER).
     *
     * @param userDetails Detalles del usuario autenticado
     * @param estado Filtro opcional por estado de la reserva
     * @param pageable Paginación y orden (por defecto, más recientes primero)
     * @return Page de ReservaResponse con las reservas del jugador
     */
    @GetMapping("/mis-reservas")
    @PreAuthorize("hasRole('PLAYER')")
    public ResponseEntity<Page<ReservaResponse>> obtenerMisReservas(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) EstadoReserva estado,
            @ParameterObject @PageableDefault(size = 10, sort = "fechaHoraInicio", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(reservaService.obtenerMisReservas(userDetails.getUsername(), estado, pageable));
    }
}
