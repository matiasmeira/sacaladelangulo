package com.matiasmeira.sacaladelangulo.buffet.controller;

import com.matiasmeira.sacaladelangulo.buffet.dto.AjustarStockRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoBuffetRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoBuffetResponse;
import com.matiasmeira.sacaladelangulo.buffet.service.ProductoBuffetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controlador REST para el inventario de buffet de un establecimiento.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/productos-buffet")
@RequiredArgsConstructor
public class ProductoBuffetController {

    private final ProductoBuffetService productoBuffetService;

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<ProductoBuffetResponse> crearProducto(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid ProductoBuffetRequest request) {
        ProductoBuffetResponse producto = productoBuffetService.crearProducto(establecimientoId, request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(producto);
    }

    @PutMapping("/{productoId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<ProductoBuffetResponse> actualizarProducto(
            @PathVariable Long establecimientoId,
            @PathVariable Long productoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid ProductoBuffetRequest request) {
        ProductoBuffetResponse producto = productoBuffetService.actualizarProducto(establecimientoId, productoId, request, userDetails.getUsername());
        return ResponseEntity.ok(producto);
    }

    @PatchMapping("/{productoId}/stock")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<ProductoBuffetResponse> ajustarStock(
            @PathVariable Long establecimientoId,
            @PathVariable Long productoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid AjustarStockRequest request) {
        ProductoBuffetResponse producto = productoBuffetService.ajustarStock(establecimientoId, productoId, request, userDetails.getUsername());
        return ResponseEntity.ok(producto);
    }

    /**
     * Catálogo del buffet. Lo puede leer también un EMPLOYEE con
     * REGISTRAR_VENTA_BUFFET: sin el listado de productos no hay forma de armar la
     * venta que ese mismo permiso lo autoriza a registrar. Alta, edición, ajuste de
     * stock y baja siguen siendo solo del dueño.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<List<ProductoBuffetResponse>> listarPorEstablecimiento(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(productoBuffetService.listarPorEstablecimiento(establecimientoId, userDetails.getUsername()));
    }

    @DeleteMapping("/{productoId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Void> eliminarProducto(
            @PathVariable Long establecimientoId,
            @PathVariable Long productoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        productoBuffetService.eliminarProducto(establecimientoId, productoId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }
}
