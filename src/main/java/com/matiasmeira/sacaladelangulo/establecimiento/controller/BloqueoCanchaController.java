package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoCanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoCanchaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.service.BloqueoCanchaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/canchas/{canchaId}/bloqueos")
@RequiredArgsConstructor
public class BloqueoCanchaController {

    private final BloqueoCanchaService bloqueoCanchaService;

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<BloqueoCanchaResponse> crearBloqueo(
            @PathVariable Long establecimientoId,
            @PathVariable Long canchaId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid BloqueoCanchaRequest request) {
        BloqueoCanchaResponse response = bloqueoCanchaService.crearBloqueo(canchaId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
