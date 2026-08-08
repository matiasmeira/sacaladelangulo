package com.matiasmeira.sacaladelangulo.auth.dto;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PerfilMapper")
class PerfilMapperTest {

    private final PerfilMapper perfilMapper = new PerfilMapper();

    @Test
    @DisplayName("mapToResponse_Empleado_IncluyeEstablecimientoIdYPermisos")
    void mapToResponse_Empleado_IncluyeEstablecimientoIdYPermisos() {
        Establecimiento establecimiento = Establecimiento.builder().id(7L).build();
        Usuario empleado = Usuario.builder()
                .id(1L)
                .email("empleado@test.com")
                .nombre("Juan")
                .rol(Role.EMPLOYEE)
                .emailVerified(true)
                .telefonoVerificado(false)
                .establecimiento(establecimiento)
                .permisos(Set.of(PermisoEmpleado.OPERAR_CAJA))
                .build();

        PerfilResponse response = perfilMapper.mapToResponse(empleado);

        assertEquals(1L, response.id());
        assertEquals("empleado@test.com", response.email());
        assertEquals("Juan", response.nombre());
        assertEquals(Role.EMPLOYEE, response.rol());
        assertEquals(true, response.emailVerified());
        assertEquals(false, response.telefonoVerificado());
        assertEquals(7L, response.establecimientoId());
        assertEquals(Set.of(PermisoEmpleado.OPERAR_CAJA), response.permisos());
    }

    @Test
    @DisplayName("mapToResponse_NoEmpleado_EstablecimientoIdNuloYPermisosVacio")
    void mapToResponse_NoEmpleado_EstablecimientoIdNuloYPermisosVacio() {
        Usuario jugador = Usuario.builder()
                .id(2L)
                .email("jugador@test.com")
                .nombre("Ana")
                .rol(Role.PLAYER)
                .planSuscripcion(PlanSuscripcion.FREE)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build();

        PerfilResponse response = perfilMapper.mapToResponse(jugador);

        assertEquals(Role.PLAYER, response.rol());
        assertEquals(PlanSuscripcion.FREE, response.planSuscripcion());
        assertNull(response.establecimientoId());
        assertTrue(response.permisos().isEmpty());
    }
}
