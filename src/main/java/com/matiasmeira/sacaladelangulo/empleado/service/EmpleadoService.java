package com.matiasmeira.sacaladelangulo.empleado.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.dto.ActualizarPermisosRequest;
import com.matiasmeira.sacaladelangulo.empleado.dto.CambiarPinRequest;
import com.matiasmeira.sacaladelangulo.empleado.dto.EmpleadoMapper;
import com.matiasmeira.sacaladelangulo.empleado.dto.EmpleadoNombreResponse;
import com.matiasmeira.sacaladelangulo.empleado.dto.EmpleadoRequest;
import com.matiasmeira.sacaladelangulo.empleado.dto.EmpleadoResponse;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Gestión de empleados (Usuario con rol EMPLOYEE) de un establecimiento: alta,
 * permisos, PIN y baja. Solo el dueño real (o un administrador) puede administrarlos.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class EmpleadoService {

    private static final String DOMINIO_EMAIL_SINTETICO = "empleados.sacaladelangulo.interno";

    private final UsuarioRepository usuarioRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmpleadoMapper empleadoMapper;
    private final RegistroAuditoriaService registroAuditoriaService;

    public EmpleadoResponse crearEmpleado(Long establecimientoId, EmpleadoRequest request, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario actor = validarPropietarioOAdmin(establecimiento, email);

        if (usuarioRepository.existsByEstablecimientoIdAndNombreAndRol(establecimientoId, request.nombre(), Role.EMPLOYEE)) {
            throw new IllegalArgumentException("Ya existe un empleado con ese nombre en este establecimiento");
        }

        Usuario empleado = Usuario.builder()
                .email(generarEmailSintetico())
                .password(passwordEncoder.encode(request.pin()))
                .nombre(request.nombre())
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(request.permisos() == null ? new HashSet<>() : new HashSet<>(request.permisos()))
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build();

        Usuario empleadoGuardado = usuarioRepository.save(empleado);
        log.info("Empleado creado. ID: {}, Establecimiento: {}", empleadoGuardado.getId(), establecimientoId);

        registroAuditoriaService.registrarAdministrativa(actor, empleadoGuardado, AccionAuditoria.CREAR_EMPLEADO,
                "Empleado creado: " + empleadoGuardado.getNombre());
        return empleadoMapper.mapToResponse(empleadoGuardado);
    }

    @Transactional(readOnly = true)
    public List<EmpleadoResponse> listarPorEstablecimiento(Long establecimientoId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        validarPropietarioOAdmin(establecimiento, email);

        return usuarioRepository.findByEstablecimientoIdAndRol(establecimientoId, Role.EMPLOYEE).stream()
                .map(empleadoMapper::mapToResponse)
                .toList();
    }

    /**
     * Listado público (sin autenticación) usado por la pantalla de mostrador para que
     * el empleado toque su nombre antes de ingresar el PIN. Solo expone id + nombre.
     */
    @Transactional(readOnly = true)
    public List<EmpleadoNombreResponse> listarNombresActivos(Long establecimientoId) {
        return usuarioRepository.findByEstablecimientoIdAndRolAndIsActiveTrue(establecimientoId, Role.EMPLOYEE).stream()
                .map(empleadoMapper::mapToNombreResponse)
                .toList();
    }

    public EmpleadoResponse actualizarPermisos(Long establecimientoId, Long empleadoId, ActualizarPermisosRequest request, String email) {
        Usuario empleado = buscarEmpleadoDelEstablecimiento(establecimientoId, empleadoId);
        Usuario actor = validarPropietarioOAdmin(empleado.getEstablecimiento(), email);

        empleado.setPermisos(new HashSet<>(request.permisos()));
        Usuario empleadoActualizado = usuarioRepository.save(empleado);
        log.info("Permisos actualizados. Empleado: {}, Permisos: {}", empleadoId, request.permisos());

        registroAuditoriaService.registrarAdministrativa(actor, empleadoActualizado, AccionAuditoria.ACTUALIZAR_PERMISOS_EMPLEADO,
                "Permisos actualizados: " + request.permisos());
        return empleadoMapper.mapToResponse(empleadoActualizado);
    }

    public EmpleadoResponse cambiarPin(Long establecimientoId, Long empleadoId, CambiarPinRequest request, String email) {
        Usuario empleado = buscarEmpleadoDelEstablecimiento(establecimientoId, empleadoId);
        Usuario actor = validarPropietarioOAdmin(empleado.getEstablecimiento(), email);

        empleado.setPassword(passwordEncoder.encode(request.pin()));
        Usuario empleadoActualizado = usuarioRepository.save(empleado);
        log.info("PIN actualizado. Empleado: {}", empleadoId);

        // No se loguea el PIN nuevo ni el viejo en el detalle, solo que el cambio ocurrió.
        registroAuditoriaService.registrarAdministrativa(actor, empleadoActualizado, AccionAuditoria.CAMBIAR_PIN_EMPLEADO,
                "PIN actualizado");
        return empleadoMapper.mapToResponse(empleadoActualizado);
    }

    public void desactivarEmpleado(Long establecimientoId, Long empleadoId, String email) {
        Usuario empleado = buscarEmpleadoDelEstablecimiento(establecimientoId, empleadoId);
        Usuario actor = validarPropietarioOAdmin(empleado.getEstablecimiento(), email);

        empleado.setIsActive(false);
        Usuario empleadoDesactivado = usuarioRepository.save(empleado);
        log.info("Empleado desactivado. ID: {}", empleadoId);

        registroAuditoriaService.registrarAdministrativa(actor, empleadoDesactivado, AccionAuditoria.DESACTIVAR_EMPLEADO,
                "Empleado desactivado");
    }

    private String generarEmailSintetico() {
        return "empleado-" + UUID.randomUUID() + "@" + DOMINIO_EMAIL_SINTETICO;
    }

    private Establecimiento buscarEstablecimientoPorId(Long establecimientoId) {
        return establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }

    private Usuario buscarEmpleadoDelEstablecimiento(Long establecimientoId, Long empleadoId) {
        Usuario empleado = usuarioRepository.findById(empleadoId)
                .orElseThrow(() -> new EntityNotFoundException("Empleado no encontrado"));
        if (empleado.getRol() != Role.EMPLOYEE
                || empleado.getEstablecimiento() == null
                || !empleado.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("El empleado no pertenece a este establecimiento");
        }
        return empleado;
    }

    /**
     * Devuelve el usuario autenticado (OWNER real o ADMIN) para que los llamadores puedan
     * reutilizarlo como actor de la auditoría administrativa, sin una segunda consulta
     * (ver M31 en la auditoría).
     */
    private Usuario validarPropietarioOAdmin(Establecimiento establecimiento, String email) {
        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
        if (usuarioAutenticado.getRol() != Role.ADMIN &&
                !establecimiento.getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }
        return usuarioAutenticado;
    }
}
