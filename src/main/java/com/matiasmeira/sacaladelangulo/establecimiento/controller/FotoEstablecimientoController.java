package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.FotoEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.ReordenarFotosRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.service.FotoEstablecimientoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Gestión de las fotos de un establecimiento. Sub-recurso propio y no métodos más de
 * EstablecimientoController: el ciclo de vida de las fotos (multipart, ImageKit, orden)
 * no tiene nada que ver con el alta y edición del establecimiento.
 *
 * @PreAuthorize filtra por rol; la validación de que ESTE establecimiento sea del usuario
 * la hace el servicio con validarPropietarioOAdmin.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{id}/fotos")
@RequiredArgsConstructor
public class FotoEstablecimientoController {

    private final FotoEstablecimientoService fotoEstablecimientoService;

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<FotoEstablecimientoResponse>> listar(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(fotoEstablecimientoService.listar(id, userDetails.getUsername()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<FotoEstablecimientoResponse> subir(
            @PathVariable Long id,
            @RequestParam("archivo") MultipartFile archivo,
            @AuthenticationPrincipal UserDetails userDetails) throws IOException {
        byte[] contenido = archivo.getBytes();
        String nombreArchivo = archivo.getOriginalFilename() == null
                ? "foto"
                : archivo.getOriginalFilename();

        FotoEstablecimientoResponse foto =
                fotoEstablecimientoService.subir(id, contenido, nombreArchivo, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(foto);
    }

    @DeleteMapping("/{fileId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Void> borrar(
            @PathVariable Long id,
            @PathVariable String fileId,
            @AuthenticationPrincipal UserDetails userDetails) {
        fotoEstablecimientoService.borrar(id, fileId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/orden")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<FotoEstablecimientoResponse>> reordenar(
            @PathVariable Long id,
            @RequestBody @Valid ReordenarFotosRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(
                fotoEstablecimientoService.reordenar(id, request.fileIds(), userDetails.getUsername()));
    }
}
