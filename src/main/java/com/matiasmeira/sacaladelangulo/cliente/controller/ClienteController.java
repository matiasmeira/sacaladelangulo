package com.matiasmeira.sacaladelangulo.cliente.controller;

import com.matiasmeira.sacaladelangulo.cliente.dto.ClienteDetalleResponse;
import com.matiasmeira.sacaladelangulo.cliente.dto.ClienteResponse;
import com.matiasmeira.sacaladelangulo.cliente.service.ClienteService;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Padrón de clientes de un establecimiento: listado con métricas agregadas, ficha
 * individual e historial de reservas.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/clientes")
@RequiredArgsConstructor
public class ClienteController {

    private final ClienteService clienteService;

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Page<ClienteResponse>> listarClientes(
            @PathVariable Long establecimientoId,
            @RequestParam(required = false) String buscar,
            @RequestParam(required = false) Boolean soloBloqueados,
            @AuthenticationPrincipal UserDetails userDetails,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(clienteService.listarClientes(
                establecimientoId, buscar, soloBloqueados, pageable, userDetails.getUsername()));
    }

    @GetMapping("/{jugadorId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<ClienteDetalleResponse> obtenerDetalle(
            @PathVariable Long establecimientoId,
            @PathVariable Long jugadorId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(clienteService.obtenerDetalle(establecimientoId, jugadorId, userDetails.getUsername()));
    }

    @GetMapping("/{jugadorId}/reservas")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Page<ReservaResponse>> listarReservas(
            @PathVariable Long establecimientoId,
            @PathVariable Long jugadorId,
            @AuthenticationPrincipal UserDetails userDetails,
            @ParameterObject @PageableDefault(size = 20, sort = "fechaHoraInicio", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(clienteService.listarReservasDeCliente(
                establecimientoId, jugadorId, pageable, userDetails.getUsername()));
    }
}
