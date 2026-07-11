package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.DiaNoLaborableRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.DiaNoLaborableResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.service.DiaNoLaborableService;
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
 * Controlador REST para excepciones puntuales al horario de atención semanal
 * (feriados, cierres especiales) de un establecimiento.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/dias-no-laborables")
@RequiredArgsConstructor
public class DiaNoLaborableController {

    private final DiaNoLaborableService diaNoLaborableService;

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<DiaNoLaborableResponse> crear(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid DiaNoLaborableRequest request) {
        DiaNoLaborableResponse response = diaNoLaborableService.crear(establecimientoId, request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Void> eliminar(
            @PathVariable Long establecimientoId,
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        diaNoLaborableService.eliminar(establecimientoId, id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<DiaNoLaborableResponse>> listar(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(diaNoLaborableService.listar(establecimientoId, userDetails.getUsername()));
    }
}
