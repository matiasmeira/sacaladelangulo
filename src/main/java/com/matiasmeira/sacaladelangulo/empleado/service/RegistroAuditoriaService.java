package com.matiasmeira.sacaladelangulo.empleado.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
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
import org.springframework.security.access.AccessDeniedException;
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
    private final UsuarioRepository usuarioRepository;

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

    @Transactional(readOnly = true)
    public Page<RegistroAuditoriaResponse> listarPorEstablecimiento(Long establecimientoId, Pageable pageable, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        validarPropietarioOAdmin(establecimiento, email);

        return registroAuditoriaRepository.findByEstablecimientoIdOrderByFechaHoraDesc(establecimientoId, pageable)
                .map(this::mapToResponse);
    }

    private RegistroAuditoriaResponse mapToResponse(RegistroAuditoria registro) {
        return new RegistroAuditoriaResponse(
                registro.getId(),
                registro.getEmpleado().getId(),
                registro.getEmpleado().getNombre(),
                registro.getActorId(),
                registro.getAccion().name(),
                registro.getEntidadAfectadaId(),
                registro.getExitoso(),
                registro.getDetalle(),
                registro.getFechaHora()
        );
    }

    private void validarPropietarioOAdmin(Establecimiento establecimiento, String email) {
        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
        if (usuarioAutenticado.getRol() != Role.ADMIN &&
                !establecimiento.getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }
    }
}
