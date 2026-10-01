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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DELETE /api/v1/establecimientos/{id}/gastos/{gastoId}. @PreAuthorize OWNER/ADMIN
 * (GastoController:63). En el service, GastoService.eliminarGasto busca con
 * findByIdAndEstablecimientoId (línea 148: un gasto de otro complejo da 404 "Gasto no
 * encontrado", línea 149) y después validarPropietarioOAdmin (GastoService:150;
 * AutorizacionEmpleadoService:130-137) rechaza al dueño ajeno con AccessDeniedException -> 403.
 * La baja es lógica (GastoService:170): la fila sigue y isActive pasa a false.
 */
@DisplayName("DELETE /api/v1/establecimientos/{id}/gastos/{gastoId}")
class GastoEliminarAutorizacionTest extends AbstractSecurityWebTest {

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

    private ResultActions eliminar(Long establecimientoId, Long gastoId, Usuario usuario) throws Exception {
        return mockMvc.perform(delete("/api/v1/establecimientos/" + establecimientoId + "/gastos/" + gastoId)
                .header("Authorization", bearer(usuario)));
    }

    private boolean activo(Gasto gasto) {
        return gastoRepository.findById(gasto.getId()).orElseThrow().getIsActive();
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        eliminar(establecimientoA.getId(), gastoA.getId(), jugador)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(true, activo(gastoA));
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve403")
    void empleadoConPermiso_Devuelve403() throws Exception {
        eliminar(establecimientoA.getId(), gastoA.getId(), empleadoConPermiso)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(true, activo(gastoA));
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403")
    void empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        // Lo que corta es el rol, no la falta de permisos.
        Usuario empleadoTodo = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
        eliminar(establecimientoA.getId(), gastoA.getId(), empleadoTodo)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(true, activo(gastoA));
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimientoConPathAjeno_Devuelve403")
    void duenoDeOtroEstablecimientoConPathAjeno_Devuelve403() throws Exception {
        // AutorizacionEmpleadoService:135 (invocado desde GastoService:150)
        eliminar(establecimientoA.getId(), gastoA.getId(), duenoB)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertEquals(true, activo(gastoA));
        assertEquals(true, activo(gastoB));
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimientoConPathPropioEIdAjeno_Devuelve404")
    void duenoDeOtroEstablecimientoConPathPropioEIdAjeno_Devuelve404() throws Exception {
        // GastoService:149: findByIdAndEstablecimientoId (línea 148) no encuentra el gasto de A bajo B
        eliminar(establecimientoB.getId(), gastoA.getId(), duenoB)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Gasto no encontrado"));
        assertEquals(true, activo(gastoA));
        assertEquals(true, activo(gastoB));
    }

    @Test
    @DisplayName("admin_Devuelve204YDaDeBajaSinBorrarLaFila")
    void admin_Devuelve204YDaDeBajaSinBorrarLaFila() throws Exception {
        eliminar(establecimientoA.getId(), gastoA.getId(), admin).andExpect(status().isNoContent());
        assertEquals(false, activo(gastoA));
        assertEquals(true, activo(gastoB));
        assertEquals(2, gastoRepository.count());
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve204YDaDeBajaSinBorrarLaFila")
    void duenoDelEstablecimiento_Devuelve204YDaDeBajaSinBorrarLaFila() throws Exception {
        eliminar(establecimientoA.getId(), gastoA.getId(), duenoA).andExpect(status().isNoContent());
        assertEquals(false, activo(gastoA));
        assertEquals(true, activo(gastoB));
        assertEquals(2, gastoRepository.count());
    }
}
