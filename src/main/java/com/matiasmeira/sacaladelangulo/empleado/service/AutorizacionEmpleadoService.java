package com.matiasmeira.sacaladelangulo.empleado.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Autorización compartida por los servicios que ahora permiten que, además del dueño
 * real o un administrador, un EMPLOYEE con el permiso puntual habilitado realice la
 * acción sobre el establecimiento donde trabaja.
 */
@Service
@RequiredArgsConstructor
public class AutorizacionEmpleadoService {

    private final UsuarioRepository usuarioRepository;

    /**
     * Resuelve el usuario autenticado y valida que sea el dueño real del
     * establecimiento, un ADMIN, o un EMPLOYEE de ese establecimiento con el permiso
     * indicado. Lanza AccessDeniedException si no cumple ninguna condición.
     */
    public Usuario validarAccion(Establecimiento establecimiento, String email, PermisoEmpleado permisoRequerido) {
        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        boolean esAdmin = usuarioAutenticado.getRol() == Role.ADMIN;
        boolean esDueno = usuarioAutenticado.getRol() == Role.OWNER
                && establecimiento.getDueno().getId().equals(usuarioAutenticado.getId());

        if (!esAdmin && !esDueno && !tienePermiso(usuarioAutenticado, establecimiento, permisoRequerido)) {
            throw new AccessDeniedException("No autorizado para realizar esta acción en este establecimiento");
        }
        return usuarioAutenticado;
    }

    /**
     * Chequeo puro (no lanza excepción): ¿este usuario es un empleado de este
     * establecimiento con el permiso indicado habilitado?
     */
    public boolean tienePermiso(Usuario usuario, Establecimiento establecimiento, PermisoEmpleado permiso) {
        return usuario.getRol() == Role.EMPLOYEE
                && usuario.getEstablecimiento() != null
                && usuario.getEstablecimiento().getId().equals(establecimiento.getId())
                && usuario.getPermisos().contains(permiso);
    }
}
