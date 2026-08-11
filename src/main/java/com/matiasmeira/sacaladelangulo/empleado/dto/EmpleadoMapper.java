package com.matiasmeira.sacaladelangulo.empleado.dto;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class EmpleadoMapper {

    /**
     * Set.copyOf materializa la colección lazy `permisos` mientras la sesión de
     * Hibernate del caller (transaccional) sigue abierta, en vez de reenviar el
     * PersistentSet vivo: con spring.jpa.open-in-view=false esa sesión se cierra antes
     * de que Jackson serialice la respuesta en la capa web, y un Set lazy sin
     * inicializar revienta con LazyInitializationException (ver el mismo fix en
     * PerfilMapper, auth.dto).
     */
    public EmpleadoResponse mapToResponse(Usuario empleado) {
        return new EmpleadoResponse(
                empleado.getId(),
                empleado.getNombre(),
                Set.copyOf(empleado.getPermisos()),
                empleado.getIsActive(),
                empleado.getEstablecimiento().getId()
        );
    }

    public EmpleadoNombreResponse mapToNombreResponse(Usuario empleado) {
        return new EmpleadoNombreResponse(empleado.getId(), empleado.getNombre());
    }
}
