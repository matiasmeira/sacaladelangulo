package com.matiasmeira.sacaladelangulo.gastos.controller;

import com.matiasmeira.sacaladelangulo.gastos.dto.GastoRequest;
import com.matiasmeira.sacaladelangulo.gastos.dto.GastoResponse;
import com.matiasmeira.sacaladelangulo.gastos.model.CategoriaGasto;
import com.matiasmeira.sacaladelangulo.gastos.service.GastoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Controlador REST para el gestor de gastos de un establecimiento. Solo dueño/admin.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/gastos")
@RequiredArgsConstructor
public class GastoController {

    private final GastoService gastoService;

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<GastoResponse> registrarGasto(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid GastoRequest request) {
        GastoResponse gasto = gastoService.registrarGasto(establecimientoId, request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(gasto);
    }

    @PutMapping("/{gastoId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<GastoResponse> editarGasto(
            @PathVariable Long establecimientoId,
            @PathVariable Long gastoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid GastoRequest request) {
        GastoResponse gasto = gastoService.editarGasto(establecimientoId, gastoId, request, userDetails.getUsername());
        return ResponseEntity.ok(gasto);
    }

    @DeleteMapping("/{gastoId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Void> eliminarGasto(
            @PathVariable Long establecimientoId,
            @PathVariable Long gastoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        gastoService.eliminarGasto(establecimientoId, gastoId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Page<GastoResponse>> listarGastos(
            @PathVariable Long establecimientoId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) CategoriaGasto categoria,
            @AuthenticationPrincipal UserDetails userDetails,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(gastoService.listarGastos(establecimientoId, userDetails.getUsername(), desde, hasta, categoria, pageable));
    }
}
