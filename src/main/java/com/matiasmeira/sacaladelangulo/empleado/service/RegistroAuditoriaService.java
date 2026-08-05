package com.matiasmeira.sacaladelangulo.empleado.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.dto.RegistroAuditoriaResponse;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.model.RegistroAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.repository.RegistroAuditoriaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Registra y consulta la actividad de los empleados sobre un establecimiento.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegistroAuditoriaService {

    private final RegistroAuditoriaRepository registroAuditoriaRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;

    /**
     * Se ejecuta en una transacción propia (REQUIRES_NEW) para que el registro quede
     * guardado aunque la operación auditada haya fallado y hecho rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(Usuario empleado, AccionAuditoria accion, Long entidadAfectadaId, boolean exitoso, String detalle) {
        RegistroAuditoria registro = RegistroAuditoria.builder()
                .empleado(empleado)
                .establecimiento(empleado.getEstablecimiento())
                .accion(accion)
                .entidadAfectadaId(entidadAfectadaId)
                .exitoso(exitoso)
                .detalle(detalle)
                .fechaHora(LocalDateTime.now())
                .build();

        registroAuditoriaRepository.save(registro);
        log.info("Auditoría registrada. Empleado: {}, Acción: {}, Éxito: {}", empleado.getId(), accion, exitoso);
    }

    /**
     * Audita una acción administrativa del dueño/admin sobre un empleado (alta, cambio de
     * permisos/PIN, baja) — a diferencia de {@link #registrar}, acá el actor no es el
     * propio `empleado` sino quien lo administra (ver M31 en la auditoría).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarAdministrativa(Usuario actor, Usuario empleadoAfectado, AccionAuditoria accion, String detalle) {
        RegistroAuditoria registro = RegistroAuditoria.builder()
                .empleado(empleadoAfectado)
                .actorId(actor.getId())
                .establecimiento(empleadoAfectado.getEstablecimiento())
                .accion(accion)
                .exitoso(true)
                .detalle(detalle)
                .fechaHora(LocalDateTime.now())
                .build();

        registroAuditoriaRepository.save(registro);
        log.info("Auditoría administrativa registrada. Actor: {}, Empleado: {}, Acción: {}",
                actor.getId(), empleadoAfectado.getId(), accion);
    }

    /**
     * Audita un evento del ciclo de vida de un dispositivo de caja (activación,
     * emparejamiento, revocación). A diferencia de {@link #registrar} y
     * {@link #registrarAdministrativa}, acá no hay ningún empleado afectado: el sujeto
     * de la acción es el dispositivo, identificado por {@code entidadAfectadaId}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarDispositivo(Usuario actor, Establecimiento establecimiento, AccionAuditoria accion, Long entidadAfectadaId, String detalle) {
        RegistroAuditoria registro = RegistroAuditoria.builder()
                .actorId(actor.getId())
                .establecimiento(establecimiento)
                .accion(accion)
                .entidadAfectadaId(entidadAfectadaId)
                .exitoso(true)
                .detalle(detalle)
                .fechaHora(LocalDateTime.now())
                .build();

        registroAuditoriaRepository.save(registro);
        log.info("Auditoría de dispositivo registrada. Actor: {}, Establecimiento: {}, Acción: {}",
                actor.getId(), establecimiento.getId(), accion);
    }

    /**
     * Audita una acción administrativa de dinero que el propio OWNER/ADMIN ejecuta
     * directamente sobre un recurso del establecimiento (gastos, precios/tarifas de
     * cancha) — no hay ningún empleado ni dispositivo involucrado, a diferencia de
     * {@link #registrarAdministrativa} y {@link #registrarDispositivo}. Antes estas
     * acciones no dejaban rastro (ver §3 "Consistencia entre features" en la auditoría).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarSobreEstablecimiento(Usuario actor, Establecimiento establecimiento, AccionAuditoria accion, Long entidadAfectadaId, String detalle) {
        RegistroAuditoria registro = RegistroAuditoria.builder()
                .actorId(actor.getId())
                .establecimiento(establecimiento)
                .accion(accion)
                .entidadAfectadaId(entidadAfectadaId)
                .exitoso(true)
                .detalle(detalle)
                .fechaHora(LocalDateTime.now())
                .build();

        registroAuditoriaRepository.save(registro);
        log.info("Auditoría de establecimiento registrada. Actor: {}, Establecimiento: {}, Acción: {}",
                actor.getId(), establecimiento.getId(), accion);
    }

    @Transactional(readOnly = true)
    public Page<RegistroAuditoriaResponse> listarPorEstablecimiento(Long establecimientoId, Pageable pageable, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        return registroAuditoriaRepository.findByEstablecimientoIdOrderByFechaHoraDesc(establecimientoId, pageable)
                .map(this::mapToResponse);
    }

    private RegistroAuditoriaResponse mapToResponse(RegistroAuditoria registro) {
        Usuario empleado = registro.getEmpleado();
        return new RegistroAuditoriaResponse(
                registro.getId(),
                empleado == null ? null : empleado.getId(),
                empleado == null ? null : empleado.getNombre(),
                registro.getActorId(),
                registro.getAccion().name(),
                registro.getEntidadAfectadaId(),
                registro.getExitoso(),
                registro.getDetalle(),
                registro.getFechaHora()
        );
    }

}
