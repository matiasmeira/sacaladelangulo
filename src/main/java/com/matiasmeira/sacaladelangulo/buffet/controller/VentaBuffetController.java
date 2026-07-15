package com.matiasmeira.sacaladelangulo.buffet.controller;

import com.matiasmeira.sacaladelangulo.buffet.dto.MetricasVentasResponse;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaResponse;
import com.matiasmeira.sacaladelangulo.buffet.service.VentaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Controlador REST para el registro de ventas del buffet.
 */
@RestController
@RequestMapping("/api/v1/buffet/ventas")
@RequiredArgsConstructor
public class VentaBuffetController {

    private final VentaService ventaService;

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<VentaResponse> registrarVenta(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid VentaRequest request) {
        VentaResponse venta = ventaService.registrarVenta(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(venta);
    }

    @PutMapping("/{id}/cancelar")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<VentaResponse> cancelarVenta(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        VentaResponse venta = ventaService.cancelarVenta(id, userDetails.getUsername());
        return ResponseEntity.ok(venta);
    }

    /**
     * Métricas de ventas del buffet en un rango de fechas: ingreso total, cantidad de
     * ventas, ticket promedio y ranking de productos más vendidos.
     */
    @GetMapping("/metricas")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<MetricasVentasResponse> obtenerMetricas(
            @RequestParam Long establecimientoId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(ventaService.obtenerMetricas(establecimientoId, desde, hasta, userDetails.getUsername()));
    }
}
