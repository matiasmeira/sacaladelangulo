package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.gastos.model.CategoriaGasto;
import com.matiasmeira.sacaladelangulo.gastos.model.Gasto;
import com.matiasmeira.sacaladelangulo.gastos.repository.GastoRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/establecimientos/{id}/gastos. @PreAuthorize OWNER/ADMIN (GastoController:73). El
 * dueño ajeno lo rechaza validarPropietarioOAdmin (GastoService:183;
 * AutorizacionEmpleadoService:130-137) con AccessDeniedException -> 403. El listado excluye los
 * gastos dados de baja (GastoRepository.buscar: isActive = true) y los de otros complejos.
 */
@DisplayName("GET /api/v1/establecimientos/{id}/gastos")
class GastoListarAutorizacionTest extends AbstractSecurityWebTest {

    @Autowired
    private GastoRepository gastoRepository;

    private Gasto gastoA;

    @BeforeEach
    void sembrarGastos() {
        gastoA = gastoRepository.save(gasto(establecimientoA, duenoA, true));
        gastoRepository.save(gasto(establecimientoA, duenoA, false));
        gastoRepository.save(gasto(establecimientoB, duenoB, true));
    }

    private Gasto gasto(Establecimiento establecimiento, Usuario registro, boolean activo) {
        return Gasto.builder()
                .establecimiento(establecimiento)
                .fecha(LocalDate.now())
                .monto(new BigDecimal("500"))
                .categoria(CategoriaGasto.INSUMOS)
                .descripcion("Pelotas")
                .metodoPago(MetodoPago.TRANSFERENCIA)
                .usuarioRegistro(registro)
                .isActive(activo)
                .build();
    }

    private ResultActions listar(Usuario usuario) throws Exception {
        return mockMvc.perform(get("/api/v1/establecimientos/" + establecimientoA.getId() + "/gastos")
                .header("Authorization", bearer(usuario)));
    }

    @Test
    @DisplayName("establecimientoInexistente_Devuelve403IgualAlAjeno")
    void establecimientoInexistente_Devuelve403IgualAlAjeno() throws Exception {
        mockMvc.perform(get("/api/v1/establecimientos/987654321/gastos").header("Authorization", bearer(duenoA)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No autorizado en este establecimiento"));
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        listar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve403")
    void empleadoConPermiso_Devuelve403() throws Exception {
        listar(empleadoConPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403")
    void empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        Usuario empleadoTodo = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
        listar(empleadoTodo).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403SinContenido")
    void duenoDeOtroEstablecimiento_Devuelve403SinContenido() throws Exception {
        // AutorizacionEmpleadoService:135 (invocado desde GastoService:183)
        listar(duenoB)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No autorizado en este establecimiento"))
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200SoloConSusGastosActivos")
    void duenoDelEstablecimiento_Devuelve200SoloConSusGastosActivos() throws Exception {
        listar(duenoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(gastoA.getId()));
    }

    @Test
    @DisplayName("admin_Devuelve200SoloConLosGastosActivosDelComplejo")
    void admin_Devuelve200SoloConLosGastosActivosDelComplejo() throws Exception {
        listar(admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(gastoA.getId()));
    }
}
