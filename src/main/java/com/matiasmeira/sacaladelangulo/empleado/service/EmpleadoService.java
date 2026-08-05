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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * Defensa en profundidad adicional al rate limiting de A2: bloquea los PIN más
     * triviales (secuencias/repeticiones obvias) para que un atacante no necesite ni
     * agotar el rate limit para adivinar el PIN de un empleado (ver B22 en la auditoría).
     */
    private static final Set<String> PINES_PROHIBIDOS = construirPinesProhibidos();

    private static Set<String> construirPinesProhibidos() {
        Set<String> prohibidos = new HashSet<>();
        for (int digito = 0; digito <= 9; digito++) {
            prohibidos.add(String.valueOf(digito).repeat(4));
        }
        prohibidos.addAll(Set.of("1234", "4321", "0123", "9876", "2580", "1212", "6969"));
        return prohibidos;
    }

    private final UsuarioRepository usuarioRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmpleadoMapper empleadoMapper;
    private final RegistroAuditoriaService registroAuditoriaService;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;

    public EmpleadoResponse crearEmpleado(Long establecimientoId, EmpleadoRequest request, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario actor = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        // Trim + IgnoreCase para que "Juan" y "juan "/" JUAN" cuenten como el mismo
        // nombre tanto acá como al loguear (ver B4 en la auditoría).
        String nombre = request.nombre() == null ? null : request.nombre().trim();
        if (usuarioRepository.existsByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(establecimientoId, nombre, Role.EMPLOYEE)) {
            throw new IllegalArgumentException("Ya existe un empleado con ese nombre en este establecimiento");
        }
        validarPinNoTrivial(request.pin());

        Usuario empleado = Usuario.builder()
                .email(generarEmailSintetico())
                .password(passwordEncoder.encode(request.pin()))
                .nombre(nombre)
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
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

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
        Usuario actor = autorizacionEmpleadoService.validarPropietarioOAdmin(empleado.getEstablecimiento(), email);

        empleado.setPermisos(new HashSet<>(request.permisos()));
        Usuario empleadoActualizado = usuarioRepository.save(empleado);
        log.info("Permisos actualizados. Empleado: {}, Permisos: {}", empleadoId, request.permisos());

        registroAuditoriaService.registrarAdministrativa(actor, empleadoActualizado, AccionAuditoria.ACTUALIZAR_PERMISOS_EMPLEADO,
                "Permisos actualizados: " + request.permisos());
        return empleadoMapper.mapToResponse(empleadoActualizado);
    }

    public EmpleadoResponse cambiarPin(Long establecimientoId, Long empleadoId, CambiarPinRequest request, String email) {
        Usuario empleado = buscarEmpleadoDelEstablecimiento(establecimientoId, empleadoId);
        Usuario actor = autorizacionEmpleadoService.validarPropietarioOAdmin(empleado.getEstablecimiento(), email);
        validarPinNoTrivial(request.pin());

        empleado.setPassword(passwordEncoder.encode(request.pin()));
        // Invalida cualquier JWT de mostrador ya emitido con el PIN viejo (ver B3 en la auditoría).
        empleado.setTokenVersion(empleado.getTokenVersion() + 1);
        Usuario empleadoActualizado = usuarioRepository.save(empleado);
        log.info("PIN actualizado. Empleado: {}", empleadoId);

        // No se loguea el PIN nuevo ni el viejo en el detalle, solo que el cambio ocurrió.
        registroAuditoriaService.registrarAdministrativa(actor, empleadoActualizado, AccionAuditoria.CAMBIAR_PIN_EMPLEADO,
                "PIN actualizado");
        return empleadoMapper.mapToResponse(empleadoActualizado);
    }

    public void desactivarEmpleado(Long establecimientoId, Long empleadoId, String email) {
        Usuario empleado = buscarEmpleadoDelEstablecimiento(establecimientoId, empleadoId);
        Usuario actor = autorizacionEmpleadoService.validarPropietarioOAdmin(empleado.getEstablecimiento(), email);

        empleado.setIsActive(false);
        Usuario empleadoDesactivado = usuarioRepository.save(empleado);
        log.info("Empleado desactivado. ID: {}", empleadoId);

        registroAuditoriaService.registrarAdministrativa(actor, empleadoDesactivado, AccionAuditoria.DESACTIVAR_EMPLEADO,
                "Empleado desactivado");
    }

    private void validarPinNoTrivial(String pin) {
        if (PINES_PROHIBIDOS.contains(pin)) {
            throw new IllegalArgumentException("El PIN elegido es demasiado común (secuencia o repetición obvia); elegí otro");
        }
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

}
