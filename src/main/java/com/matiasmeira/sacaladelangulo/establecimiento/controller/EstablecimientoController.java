package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador REST para establecimientos.
 */
@RestController
@RequestMapping("/api/v1/establecimientos")
@RequiredArgsConstructor
public class EstablecimientoController {

    private final EstablecimientoService establecimientoService;

    @PostMapping
    public ResponseEntity<Establecimiento> crearEstablecimiento(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid EstablecimientoRequest request) {
        Establecimiento establecimiento = establecimientoService.crearEstablecimiento(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(establecimiento);
    }
}
