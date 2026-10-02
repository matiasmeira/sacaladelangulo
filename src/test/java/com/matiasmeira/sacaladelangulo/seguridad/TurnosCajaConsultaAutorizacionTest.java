package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.EstadoTurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.TurnoCajaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Consulta de caja: GET /caja/abierta (OWNER/ADMIN/EMPLOYEE, CierreCajaController:54; el service exige
 * OPERAR_CAJA con validarAccion, TurnoCajaService:297 -> AutorizacionEmpleadoService:59), GET /caja/turnos
 * (CierreCajaController:82) y GET /caja/turnos/{turnoId} (:91), ambos OWNER/ADMIN por anotación y
 * validarPropietarioOAdmin en el service (:330, :344 -> AutorizacionEmpleadoService:135): el empleado queda
 * afuera aunque tenga OPERAR_CAJA. También el cruce de turno por path en el cierre (POST /{turnoId}/cerrar):
 * como el service autoriza ANTES de buscar el turno (TurnoCajaService:232-236), un turno de otro complejo
 * da 404 al dueño legítimo del path y no se cierra. El resto de /cerrar está en CierreCajaAutorizacionTest.
 *
 * <p>Sin oráculo de existencia (pendiente 69): en todos los endpoints de caja (abrir, movimientos, cerrar,
 * abierta, turnos y detalle) un establecimiento inexistente responde el mismo 403 que uno ajeno, con el
 * mensaje del chequeo que corresponda (acción o propietario); el admin recibe 404.
 */
@DisplayName("Consulta de caja y turnos")
class TurnosCajaConsultaAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_ACCION = "No autorizado para realizar esta acción en este establecimiento";
    private static final String MENSAJE_403_PROPIETARIO = "No autorizado en este establecimiento";

    @Autowired
    private TurnoCajaRepository turnoCajaRepository;

    private TurnoCaja turnoAbiertoA;
    private TurnoCaja turnoCerradoA;
    private TurnoCaja turnoAbiertoB;

    @BeforeEach
    void sembrarTurnos() {
        turnoCerradoA = turnoCajaRepository.save(TurnoCaja.builder()
                .establecimiento(establecimientoA).usuarioApertura(duenoA)
                .fechaApertura(LocalDateTime.now().minusDays(1)).fechaCierre(LocalDateTime.now().minusDays(1).plusHours(8))
                .fondoInicial(new BigDecimal("500")).saldoTeoricoEfectivo(new BigDecimal("500"))
                .saldoRealContado(new BigDecimal("500")).diferencia(BigDecimal.ZERO)
                .estado(EstadoTurnoCaja.CERRADO).build());
        turnoAbiertoA = turnoAbierto(establecimientoA, duenoA);
        turnoAbiertoB = turnoAbierto(establecimientoB, duenoB);
    }

    private TurnoCaja turnoAbierto(Establecimiento e, Usuario apertura) {
        return turnoCajaRepository.save(TurnoCaja.builder()
                .establecimiento(e).usuarioApertura(apertura)
                .fechaApertura(LocalDateTime.now().minusHours(1)).fondoInicial(new BigDecimal("1000"))
                .estado(EstadoTurnoCaja.ABIERTO).build());
    }

    private String base(Establecimiento e) {
        return "/api/v1/establecimientos/" + e.getId() + "/caja";
    }

    private Usuario conTodosLosPermisos() {
        return empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
    }

    @Nested
    @DisplayName("GET /caja/abierta")
    class Abierta {
        private ResultActions abierta(Usuario quien) throws Exception {
            return mockMvc.perform(get(base(establecimientoA) + "/abierta").header("Authorization", bearer(quien)));
        }

        @Test
        @DisplayName("duenoPropio_Devuelve200ConSuTurno")
        void duenoPropio() throws Exception {
            abierta(duenoA).andExpect(status().isOk()).andExpect(jsonPath("$.turno.id").value(turnoAbiertoA.getId()))
                    .andExpect(jsonPath("$.turno.establecimientoId").value(establecimientoA.getId()));
        }

        @Test
        @DisplayName("admin_Devuelve200")
        void admin() throws Exception {
            abierta(admin).andExpect(status().isOk()).andExpect(jsonPath("$.turno.id").value(turnoAbiertoA.getId()));
        }

        @Test
        @DisplayName("duenoAjeno_Devuelve403")
        void duenoAjeno() throws Exception {
            abierta(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador() throws Exception {
            abierta(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        }

        @Test
        @DisplayName("empleadoConOperarCaja_Devuelve200")
        void empleadoConPermiso() throws Exception {
            abierta(empleadoConPermiso).andExpect(status().isOk())
                    .andExpect(jsonPath("$.turno.id").value(turnoAbiertoA.getId()));
        }

        @Test
        @DisplayName("empleadoSinPermiso_Devuelve403")
        void empleadoSinPermiso() throws Exception {
            abierta(empleadoSinPermiso).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
        }

        @Test
        @DisplayName("empleadoConOtrosPermisosSinOperarCaja_Devuelve403")
        void empleadoSinOperarCaja() throws Exception {
            Usuario otro = empleado(establecimientoA, EnumSet.complementOf(EnumSet.of(PermisoEmpleado.OPERAR_CAJA)));
            abierta(otro).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
        }

        @Test
        @DisplayName("empleadoDeOtroEstablecimientoConOperarCaja_Devuelve403")
        void empleadoDeOtroEstablecimiento() throws Exception {
            Usuario deB = empleado(establecimientoB, Set.of(PermisoEmpleado.OPERAR_CAJA));
            abierta(deB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
        }
    }

    @Nested
    @DisplayName("GET /caja/turnos")
    class Listar {
        private ResultActions listar(Usuario quien) throws Exception {
            return mockMvc.perform(get(base(establecimientoA) + "/turnos").header("Authorization", bearer(quien)));
        }

        @Test
        @DisplayName("duenoPropio_Devuelve200SoloConLosTurnosDeSuComplejo")
        void duenoPropio() throws Exception {
            listar(duenoA).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.content[0].establecimientoId").value(establecimientoA.getId()))
                    .andExpect(jsonPath("$.content[1].establecimientoId").value(establecimientoA.getId()));
        }

        @Test
        @DisplayName("admin_Devuelve200")
        void admin() throws Exception {
            listar(admin).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2));
        }

        @Test
        @DisplayName("duenoAjeno_Devuelve403")
        void duenoAjeno() throws Exception {
            listar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_PROPIETARIO));
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador() throws Exception {
            listar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        }

        @Test
        @DisplayName("empleadoConOperarCaja_Devuelve403PorAnotacion")
        void empleadoConOperarCaja() throws Exception {
            listar(empleadoConPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        }

        @Test
        @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
        void empleadoTodos() throws Exception {
            listar(conTodosLosPermisos()).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        }
    }

    @Nested
    @DisplayName("GET /caja/turnos/{turnoId}")
    class Detalle {
        private ResultActions detalle(Usuario quien, Establecimiento e, TurnoCaja turno) throws Exception {
            return mockMvc.perform(get(base(e) + "/turnos/" + turno.getId()).header("Authorization", bearer(quien)));
        }

        @Test
        @DisplayName("duenoPropio_Devuelve200")
        void duenoPropio() throws Exception {
            detalle(duenoA, establecimientoA, turnoCerradoA).andExpect(status().isOk())
                    .andExpect(jsonPath("$.turno.id").value(turnoCerradoA.getId()));
        }

        @Test
        @DisplayName("admin_Devuelve200")
        void admin() throws Exception {
            detalle(admin, establecimientoA, turnoAbiertoA).andExpect(status().isOk())
                    .andExpect(jsonPath("$.turno.id").value(turnoAbiertoA.getId()));
        }

        @Test
        @DisplayName("duenoAjeno_Devuelve403")
        void duenoAjeno() throws Exception {
            detalle(duenoB, establecimientoA, turnoAbiertoA).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(MENSAJE_403_PROPIETARIO));
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador() throws Exception {
            detalle(jugador, establecimientoA, turnoAbiertoA).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        }

        @Test
        @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
        void empleadoTodos() throws Exception {
            detalle(conTodosLosPermisos(), establecimientoA, turnoAbiertoA).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        }

        @Test
        @DisplayName("duenoPropioConTurnoDeOtroComplejo_Devuelve404")
        void turnoAjenoPorPath() throws Exception {
            // Autoriza primero (TurnoCajaService:344-346) y recién después busca findByIdAndEstablecimientoId.
            detalle(duenoA, establecimientoA, turnoAbiertoB).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("duenoAjenoConSuPathYTurnoDelOtro_Devuelve404")
        void duenoBConTurnoDeA() throws Exception {
            detalle(duenoB, establecimientoB, turnoAbiertoA).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /caja/{turnoId}/cerrar con turno de otro complejo")
    class CerrarCruzado {
        private ResultActions cerrar(Usuario quien) throws Exception {
            return mockMvc.perform(post(base(establecimientoA) + "/" + turnoAbiertoB.getId() + "/cerrar")
                    .header("Authorization", bearer(quien))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"saldoRealContado\":1000}"));
        }

        private void assertBSigueAbierto() {
            assertEquals(EstadoTurnoCaja.ABIERTO, turnoCajaRepository.findById(turnoAbiertoB.getId()).orElseThrow().getEstado());
        }

        @Test
        @DisplayName("duenoDelPath_Devuelve404YNoCierraElTurnoAjeno")
        void duenoDelPath() throws Exception {
            cerrar(duenoA).andExpect(status().isNotFound());
            assertBSigueAbierto();
        }

        @Test
        @DisplayName("empleadoConOperarCajaDelPath_Devuelve404YNoCierraElTurnoAjeno")
        void empleadoDelPath() throws Exception {
            cerrar(empleadoConPermiso).andExpect(status().isNotFound());
            assertBSigueAbierto();
        }

        @Test
        @DisplayName("duenoDelTurnoAjenoConElPathDeA_Devuelve403YNoCierra")
        void duenoDelTurno() throws Exception {
            cerrar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
            assertBSigueAbierto();
        }
    }

    @Nested
    @DisplayName("sin oráculo de existencia")
    class SinOraculo {
        private static final long INEXISTENTE = 987654321L;

        private String base() {
            return "/api/v1/establecimientos/" + INEXISTENTE + "/caja";
        }

        private ResultActions post(String ruta, String cuerpo, Usuario quien) throws Exception {
            return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base() + ruta)
                    .header("Authorization", bearer(quien)).contentType(MediaType.APPLICATION_JSON).content(cuerpo));
        }

        private ResultActions get(String ruta, Usuario quien) throws Exception {
            return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base() + ruta)
                    .header("Authorization", bearer(quien)));
        }

        private void assert403(ResultActions r, String mensaje) throws Exception {
            r.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(mensaje));
        }

        @Test
        @DisplayName("establecimientoInexistente_Devuelve403IgualAlAjeno")
        void inexistente() throws Exception {
            assert403(post("/abrir", "{\"fondoInicial\":1000}", duenoA), MENSAJE_403_ACCION);
            assert403(post("/movimientos", "{\"tipo\":\"INGRESO\",\"monto\":500,\"descripcion\":\"x\"}", duenoA), MENSAJE_403_ACCION);
            assert403(post("/" + turnoAbiertoA.getId() + "/cerrar", "{\"saldoRealContado\":1000}", duenoA), MENSAJE_403_ACCION);
            assert403(get("/abierta", duenoA), MENSAJE_403_ACCION);
            assert403(get("/turnos", duenoA), MENSAJE_403_PROPIETARIO);
            assert403(get("/turnos/" + turnoAbiertoA.getId(), duenoA), MENSAJE_403_PROPIETARIO);
            // Nada se tocó: el turno propio sigue abierto
            assertEquals(EstadoTurnoCaja.ABIERTO, turnoCajaRepository.findById(turnoAbiertoA.getId()).orElseThrow().getEstado());
        }

        @Test
        @DisplayName("establecimientoInexistenteComoAdmin_Devuelve404")
        void inexistenteAdmin() throws Exception {
            get("/abierta", admin).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
            get("/turnos", admin).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
        }
    }
}
