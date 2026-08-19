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

import java.util.Set;

/**
 * Autorización compartida por los servicios que ahora permiten que, además del dueño
 * real o un administrador, un EMPLOYEE con el permiso puntual habilitado realice la
 * acción sobre el establecimiento donde trabaja.
 */
@Service
@RequiredArgsConstructor
public class AutorizacionEmpleadoService {

    /**
     * Permisos que habilitan a un empleado a leer lo que se necesita para OPERAR el
     * mostrador: la agenda del día y el listado de canchas sobre el que se dibuja.
     *
     * Es el conjunto de acciones que se ejercen desde la agenda. No se pide un permiso
     * puntual porque la pantalla no le pertenece a ninguna: quien cobra, quien cancela y
     * quien marca ausencias necesitan la misma lista, y sin ella no hay forma de llegar
     * al id de la reserva sobre la que actuar.
     *
     * Las canchas van en el mismo conjunto y no sólo en CREAR_RESERVA_MANUAL: la agenda
     * se dibuja POR cancha, así que sin ese listado no se renderiza aunque el empleado
     * sólo vaya a cobrar.
     */
    public static final Set<PermisoEmpleado> PERMISOS_OPERATIVOS_DE_RESERVA = Set.of(
            PermisoEmpleado.CREAR_RESERVA_MANUAL,
            PermisoEmpleado.FINALIZAR_RESERVA,
            PermisoEmpleado.CANCELAR_RESERVA,
            PermisoEmpleado.MARCAR_AUSENTE);

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
     * Igual que validarAccion pero alcanza con UNO de varios permisos.
     *
     * Existe para los LISTADOS, que no se corresponden con una acción sola: la agenda
     * la necesita tanto el que cobra (FINALIZAR_RESERVA) como el que cancela
     * (CANCELAR_RESERVA) o el que marca ausencias, y exigir un permiso puntual dejaría
     * afuera a los otros dos. La lectura se habilita si el empleado puede hacer al
     * menos una de las cosas que se hacen desde esa pantalla.
     */
    public Usuario validarLectura(Establecimiento establecimiento, String email, Set<PermisoEmpleado> permisosQueHabilitan) {
        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        boolean esAdmin = usuarioAutenticado.getRol() == Role.ADMIN;
        boolean esDueno = usuarioAutenticado.getRol() == Role.OWNER
                && establecimiento.getDueno().getId().equals(usuarioAutenticado.getId());
        boolean esEmpleadoHabilitado = permisosQueHabilitan.stream()
                .anyMatch(permiso -> tienePermiso(usuarioAutenticado, establecimiento, permiso));

        if (!esAdmin && !esDueno && !esEmpleadoHabilitado) {
            throw new AccessDeniedException("No autorizado para ver esta información de este establecimiento");
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

    /**
     * Resuelve el usuario autenticado y valida que sea el dueño real del establecimiento o
     * un ADMIN, sin excepción para empleados (a diferencia de validarAccion). Usado por
     * acciones que solo el dueño/admin puede realizar, como los gastos.
     */
    public Usuario validarPropietarioOAdmin(Establecimiento establecimiento, String email) {
        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
        if (usuarioAutenticado.getRol() != Role.ADMIN &&
                !establecimiento.getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }
        return usuarioAutenticado;
    }
}
