package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoCanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoCanchaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.service.BloqueoCanchaService;
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
import java.util.List;

@RestController
@RequiredArgsConstructor
public class BloqueoCanchaController {

    private final BloqueoCanchaService bloqueoCanchaService;

    @PostMapping("/api/v1/establecimientos/{establecimientoId}/canchas/{canchaId}/bloqueos")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<BloqueoCanchaResponse> crearBloqueo(
            @PathVariable Long establecimientoId,
            @PathVariable Long canchaId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid BloqueoCanchaRequest request) {
        BloqueoCanchaResponse response = bloqueoCanchaService.crearBloqueo(
                establecimientoId, canchaId, request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/api/v1/establecimientos/{establecimientoId}/canchas/{canchaId}/bloqueos/{bloqueoId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Void> eliminarBloqueo(
            @PathVariable Long establecimientoId,
            @PathVariable Long canchaId,
            @PathVariable Long bloqueoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        bloqueoCanchaService.eliminarBloqueo(establecimientoId, canchaId, bloqueoId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/establecimientos/{establecimientoId}/canchas/{canchaId}/bloqueos")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<BloqueoCanchaResponse>> listarPorCancha(
            @PathVariable Long establecimientoId,
            @PathVariable Long canchaId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(bloqueoCanchaService.listarPorCancha(establecimientoId, canchaId, userDetails.getUsername()));
    }

    /**
     * Bloqueos de todo el establecimiento en una fecha dada. Usado por la grilla de
     * disponibilidad del jugador para no mostrar como libres horarios bloqueados.
     */
    @GetMapping("/api/v1/establecimientos/{establecimientoId}/bloqueos")
    @PreAuthorize("hasAnyRole('PLAYER', 'OWNER', 'ADMIN')")
    public ResponseEntity<List<BloqueoCanchaResponse>> listarPorEstablecimientoYFecha(
            @PathVariable Long establecimientoId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        return ResponseEntity.ok(bloqueoCanchaService.listarPorEstablecimientoYFecha(establecimientoId, fecha));
    }
}
