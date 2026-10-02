package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoAutorizado;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Adaptador para los 6 services de reportes: reciben el {@code establecimientoId} del path,
 * no la entidad, así que este componente sólo carga el {@link Establecimiento} y delega el
 * chequeo "dueño o admin" en {@link AutorizacionEmpleadoService#validarPropietarioOAdmin}, el
 * componente central de autorización del proyecto. Antes reimplementaba la regla localmente;
 * quedó desincronizado un tiempo (ver historia del archivo) hasta que se unificó acá.
 *
 * <p>Los services de reportes lo llaman ANTES de validar el rango de fechas, y un establecimiento
 * inexistente responde igual que uno ajeno (ver EstablecimientoAutorizado).
 */
@Component
@RequiredArgsConstructor
public class ReporteAutorizacionService {

    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;

    public Establecimiento validarDuenoDelEstablecimiento(Long establecimientoId, String email) {
        return EstablecimientoAutorizado.autorizar(establecimientoRepository.findById(establecimientoId),
                establecimiento -> autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email));
    }
}
