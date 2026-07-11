package com.matiasmeira.sacaladelangulo.empleado.dto;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.springframework.stereotype.Component;

@Component
public class EmpleadoMapper {

    public EmpleadoResponse mapToResponse(Usuario empleado) {
        return new EmpleadoResponse(
                empleado.getId(),
                empleado.getNombre(),
                empleado.getPermisos(),
                empleado.getIsActive(),
                empleado.getEstablecimiento().getId()
        );
    }

    public EmpleadoNombreResponse mapToNombreResponse(Usuario empleado) {
        return new EmpleadoNombreResponse(empleado.getId(), empleado.getNombre());
    }
}
