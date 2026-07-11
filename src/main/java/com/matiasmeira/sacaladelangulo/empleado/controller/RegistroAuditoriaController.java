package com.matiasmeira.sacaladelangulo.empleado.controller;

import com.matiasmeira.sacaladelangulo.empleado.dto.RegistroAuditoriaResponse;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador REST para que el dueño revise la actividad de sus empleados.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/registro-auditoria")
@RequiredArgsConstructor
public class RegistroAuditoriaController {

    private final RegistroAuditoriaService registroAuditoriaService;

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Page<RegistroAuditoriaResponse>> listarPorEstablecimiento(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(registroAuditoriaService.listarPorEstablecimiento(establecimientoId, pageable, userDetails.getUsername()));
    }
}
