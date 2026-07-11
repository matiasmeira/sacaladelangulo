package com.matiasmeira.sacaladelangulo.empleado.controller;

import com.matiasmeira.sacaladelangulo.empleado.dto.ActualizarPermisosRequest;
import com.matiasmeira.sacaladelangulo.empleado.dto.CambiarPinRequest;
import com.matiasmeira.sacaladelangulo.empleado.dto.EmpleadoNombreResponse;
import com.matiasmeira.sacaladelangulo.empleado.dto.EmpleadoRequest;
import com.matiasmeira.sacaladelangulo.empleado.dto.EmpleadoResponse;
import com.matiasmeira.sacaladelangulo.empleado.service.EmpleadoService;
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
 * Controlador REST para la gestión de empleados de un establecimiento.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/empleados")
@RequiredArgsConstructor
public class EmpleadoController {

    private final EmpleadoService empleadoService;

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<EmpleadoResponse> crearEmpleado(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid EmpleadoRequest request) {
        EmpleadoResponse empleado = empleadoService.crearEmpleado(establecimientoId, request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(empleado);
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<List<EmpleadoResponse>> listarPorEstablecimiento(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(empleadoService.listarPorEstablecimiento(establecimientoId, userDetails.getUsername()));
    }

    /**
     * Listado público (sin autenticación) para la pantalla de mostrador: el dueño
     * activa el "modo caja" y esta lista permite que el empleado toque su nombre
     * antes de ingresar el PIN, sin necesitar el token del dueño.
     */
    @GetMapping("/activos")
    public ResponseEntity<List<EmpleadoNombreResponse>> listarNombresActivos(@PathVariable Long establecimientoId) {
        return ResponseEntity.ok(empleadoService.listarNombresActivos(establecimientoId));
    }

    @PutMapping("/{empleadoId}/permisos")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<EmpleadoResponse> actualizarPermisos(
            @PathVariable Long establecimientoId,
            @PathVariable Long empleadoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid ActualizarPermisosRequest request) {
        EmpleadoResponse empleado = empleadoService.actualizarPermisos(establecimientoId, empleadoId, request, userDetails.getUsername());
        return ResponseEntity.ok(empleado);
    }

    @PutMapping("/{empleadoId}/pin")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<EmpleadoResponse> cambiarPin(
            @PathVariable Long establecimientoId,
            @PathVariable Long empleadoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid CambiarPinRequest request) {
        EmpleadoResponse empleado = empleadoService.cambiarPin(establecimientoId, empleadoId, request, userDetails.getUsername());
        return ResponseEntity.ok(empleado);
    }

    @DeleteMapping("/{empleadoId}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> desactivarEmpleado(
            @PathVariable Long establecimientoId,
            @PathVariable Long empleadoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        empleadoService.desactivarEmpleado(establecimientoId, empleadoId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }
}
