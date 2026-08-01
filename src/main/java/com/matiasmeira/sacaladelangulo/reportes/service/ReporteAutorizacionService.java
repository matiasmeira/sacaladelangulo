package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Helper compartido por los 4 services de reportes: mismo patrón de autorización que
 * CanchaService.validarPropietario / VentaMetricasService.validarPropietarioOAdmin, extraído
 * acá porque los 4 services nuevos lo necesitan igual (no se toca el resto del proyecto,
 * que sigue duplicándolo localmente por convención propia).
 */
@Component
@RequiredArgsConstructor
public class ReporteAutorizacionService {

    private final EstablecimientoRepository establecimientoRepository;
    private final UsuarioRepository usuarioRepository;

    public Establecimiento validarDuenoDelEstablecimiento(Long establecimientoId, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
        if (usuario.getRol() != Role.ADMIN && !establecimiento.getDueno().getId().equals(usuario.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }
        return establecimiento;
    }
}
