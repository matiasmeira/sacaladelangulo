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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/v1/establecimientos/{id}/gastos/{gastoId}. @PreAuthorize OWNER/ADMIN
 * (GastoController:52). En el service, GastoService.editarGasto busca el gasto con
 * findByIdAndEstablecimientoId (línea 86: un gasto de otro complejo da 404 "Gasto no
 * encontrado", línea 87) y después AutorizacionEmpleadoService.validarPropietarioOAdmin
 * (GastoService:88; AutorizacionEmpleadoService:130-137) rechaza al dueño ajeno con
 * AccessDeniedException -> 403.
 */
@DisplayName("PUT /api/v1/establecimientos/{id}/gastos/{gastoId}")
class GastoEditarAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";

    @Autowired
    private GastoRepository gastoRepository;

    private Gasto gastoA;
    private Gasto gastoB;

    @BeforeEach
    void sembrarGastos() {
        gastoA = gastoRepository.save(gasto(establecimientoA, duenoA));
        gastoB = gastoRepository.save(gasto(establecimientoB, duenoB));
    }

    private Gasto gasto(Establecimiento establecimiento, Usuario registro) {
        return Gasto.builder()
                .establecimiento(establecimiento)
                .fecha(LocalDate.now())
                .monto(new BigDecimal("500"))
                .categoria(CategoriaGasto.INSUMOS)
                .descripcion("Pelotas")
                .metodoPago(MetodoPago.TRANSFERENCIA)
                .usuarioRegistro(registro)
                .isActive(true)
                .build();
    }

    private ResultActions editar(Long establecimientoId, Long gastoId, Usuario usuario) throws Exception {
        return mockMvc.perform(put("/api/v1/establecimientos/" + establecimientoId + "/gastos/" + gastoId)
                .header("Authorization", bearer(usuario))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fecha\":\"" + LocalDate.now() + "\",\"monto\":750,\"categoria\":\"ALQUILER\","
                        + "\"descripcion\":\"Editado\",\"metodoPago\":\"TRANSFERENCIA\"}"));
    }

    private void assertSinCambios(Gasto original) {
        Gasto actual = gastoRepository.findById(original.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("500").compareTo(actual.getMonto()));
        assertEquals(CategoriaGasto.INSUMOS, actual.getCategoria());
        assertEquals("Pelotas", actual.getDescripcion());
        assertEquals(true, actual.getIsActive());
    }

    private void assertEditado(Gasto original) {
        Gasto actual = gastoRepository.findById(original.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("750").compareTo(actual.getMonto()));
        assertEquals(CategoriaGasto.ALQUILER, actual.getCategoria());
        assertEquals("Editado", actual.getDescripcion());
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        editar(establecimientoA.getId(), gastoA.getId(), jugador)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios(gastoA);
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve403")
    void empleadoConPermiso_Devuelve403() throws Exception {
        editar(establecimientoA.getId(), gastoA.getId(), empleadoConPermiso)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios(gastoA);
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403")
    void empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        // Lo que corta es el rol, no la falta de permisos.
        Usuario empleadoTodo = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
        editar(establecimientoA.getId(), gastoA.getId(), empleadoTodo)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios(gastoA);
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimientoConPathAjeno_Devuelve403")
    void duenoDeOtroEstablecimientoConPathAjeno_Devuelve403() throws Exception {
        // AutorizacionEmpleadoService:135 (invocado desde GastoService:88)
        editar(establecimientoA.getId(), gastoA.getId(), duenoB)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertSinCambios(gastoA);
        assertSinCambios(gastoB);
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimientoConPathPropioEIdAjeno_Devuelve404")
    void duenoDeOtroEstablecimientoConPathPropioEIdAjeno_Devuelve404() throws Exception {
        // GastoService:87: findByIdAndEstablecimientoId (línea 86) no encuentra el gasto de A bajo B
        editar(establecimientoB.getId(), gastoA.getId(), duenoB)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Gasto no encontrado"));
        assertSinCambios(gastoA);
        assertSinCambios(gastoB);
    }

    @Test
    @DisplayName("admin_Devuelve200YEdita")
    void admin_Devuelve200YEdita() throws Exception {
        editar(establecimientoA.getId(), gastoA.getId(), admin).andExpect(status().isOk());
        assertEditado(gastoA);
        assertSinCambios(gastoB);
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200YEdita")
    void duenoDelEstablecimiento_Devuelve200YEdita() throws Exception {
        editar(establecimientoA.getId(), gastoA.getId(), duenoA).andExpect(status().isOk());
        assertEditado(gastoA);
        assertSinCambios(gastoB);
        assertEquals(2, gastoRepository.count());
    }
}
