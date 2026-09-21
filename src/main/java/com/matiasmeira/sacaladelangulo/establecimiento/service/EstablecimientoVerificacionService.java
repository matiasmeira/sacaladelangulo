package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.util.CuitUtils;
import com.matiasmeira.sacaladelangulo.core.util.RedSocialUrlUtils;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.SolicitarVerificacionRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.SolicitarVerificacionResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Solicitud (y resolicitud) de la verificación manual de un establecimiento, del lado del
 * dueño. Separado de EstablecimientoService (alta/edición del perfil) y de
 * AdminEstablecimientoVerificacionService (resolución de la verificación por un ADMIN)
 * porque es un flujo de un actor y un momento del ciclo de vida distintos -- mismo criterio
 * que PoliticaCancelacionService/FotoEstablecimientoService están separados de
 * EstablecimientoService.
 *
 * <p>La solicitud es POR ESTABLECIMIENTO, no por dueño: un dueño con dos complejos manda
 * dos solicitudes independientes, cada una con sus propios datos de contacto.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EstablecimientoVerificacionService {

    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final RegistroAuditoriaService registroAuditoriaService;

    /**
     * Transiciones permitidas: PENDIENTE -> EN_REVISION (primera solicitud) y RECHAZADO ->
     * EN_REVISION (resolicitud, que además limpia motivoRechazo). Prohibidas: desde
     * EN_REVISION (ya está en cola, no hay nada que reenviar) y desde VERIFICADO (ya pasó la
     * revisión).
     */
    public SolicitarVerificacionResponse solicitarVerificacion(Long establecimientoId, SolicitarVerificacionRequest request, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario dueno = autorizacionEmpleadoService.validarPropietario(establecimiento, email);

        boolean esResolicitud = establecimiento.getEstadoVerificacion() == EstadoVerificacion.RECHAZADO;
        validarTransicion(establecimiento.getEstadoVerificacion());

        if (!CuitUtils.esValido(request.cuit())) {
            throw new IllegalArgumentException("El CUIT ingresado no es válido");
        }
        if (!RedSocialUrlUtils.esUrlValida(request.urlRedSocial())) {
            throw new IllegalArgumentException("La URL debe ser un perfil de Instagram o Facebook");
        }

        establecimiento.setCuit(CuitUtils.normalizar(request.cuit()));
        establecimiento.setRazonSocial(request.razonSocial());
        establecimiento.setTelefonoContacto(request.telefonoContacto());
        establecimiento.setUrlRedSocial(request.urlRedSocial());
        establecimiento.setEstadoVerificacion(EstadoVerificacion.EN_REVISION);
        establecimiento.setFechaSolicitudVerificacion(LocalDateTime.now());
        if (esResolicitud) {
            establecimiento.setMotivoRechazo(null);
        }

        Establecimiento guardado = establecimientoRepository.save(establecimiento);

        registroAuditoriaService.registrarSobreEstablecimiento(dueno, guardado,
                AccionAuditoria.SOLICITAR_VERIFICACION_ESTABLECIMIENTO, guardado.getId(),
                esResolicitud
                        ? "Resolicitud de verificación tras rechazo: " + guardado.getNombre()
                        : "Solicitud de verificación: " + guardado.getNombre());

        return new SolicitarVerificacionResponse(
                guardado.getId(), guardado.getEstadoVerificacion(), guardado.getFechaSolicitudVerificacion());
    }

    private void validarTransicion(EstadoVerificacion actual) {
        if (actual != EstadoVerificacion.PENDIENTE && actual != EstadoVerificacion.RECHAZADO) {
            throw new IllegalArgumentException(
                    "Solo se puede solicitar la verificación de un establecimiento PENDIENTE o RECHAZADO (estado actual: "
                            + actual + ")");
        }
    }

    private Establecimiento buscarEstablecimientoPorId(Long id) {
        return establecimientoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }
}
