package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.ActualizarPoliticaCancelacionRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.PoliticaCancelacionResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.service.PoliticaCancelacionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * Política de cancelación de un establecimiento. Sub-recurso propio, mismo criterio que
 * FotoEstablecimientoController: no son más métodos de EstablecimientoController porque no
 * tienen nada que ver con el alta/edición del perfil del establecimiento.
 *
 * @PreAuthorize filtra por rol; la validación de que ESTE establecimiento sea del usuario
 * la hace el servicio con validarPropietarioOAdmin.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/politicas-cancelacion")
@RequiredArgsConstructor
@Tag(name = "Política de cancelación", description = "Configuración del plazo mínimo y del período de gracia para que un jugador cancele su reserva")
public class PoliticaCancelacionController {

    private final PoliticaCancelacionService politicaCancelacionService;

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @Operation(
            summary = "Consultar la política de cancelación",
            description = "Devuelve las horas mínimas de anticipación y los minutos de gracia configurados. reservasFuturasAfectadas siempre es null acá."
    )
    public ResponseEntity<PoliticaCancelacionResponse> obtener(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(
                politicaCancelacionService.obtenerPoliticaCancelacion(establecimientoId, userDetails.getUsername()));
    }

    @PatchMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @Operation(
            summary = "Actualizar la política de cancelación",
            description = "Actualiza horas de anticipación y/o minutos de gracia (semántica PATCH: un campo en null no se modifica). Devuelve cuántas reservas futuras quedan bajo la nueva política."
    )
    public ResponseEntity<PoliticaCancelacionResponse> actualizar(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid ActualizarPoliticaCancelacionRequest request) {
        return ResponseEntity.ok(
                politicaCancelacionService.actualizarPoliticaCancelacion(establecimientoId, request, userDetails.getUsername()));
    }
}
