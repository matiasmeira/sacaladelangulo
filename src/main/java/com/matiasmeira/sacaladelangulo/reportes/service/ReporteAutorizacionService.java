package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Adaptador para los 6 services de reportes: reciben el {@code establecimientoId} del path,
 * no la entidad, así que este componente sólo carga el {@link Establecimiento} y delega el
 * chequeo "dueño o admin" en {@link AutorizacionEmpleadoService#validarPropietarioOAdmin}, el
 * componente central de autorización del proyecto. Antes reimplementaba la regla localmente;
 * quedó desincronizado un tiempo (ver historia del archivo) hasta que se unificó acá.
 */
@Component
@RequiredArgsConstructor
public class ReporteAutorizacionService {

    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;

    public Establecimiento validarDuenoDelEstablecimiento(Long establecimientoId, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);
        return establecimiento;
    }
}
