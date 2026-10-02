package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import com.matiasmeira.sacaladelangulo.support.Canchas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acciones sobre {@code /api/v1/establecimientos/{id}} que no tenían una clase de autorización propia
 * (PATCH /estado, POST /solicitar-verificacion, GET /previsualizacion, DELETE y PATCH /estado de cancha),
 * sin oráculo de existencia (pendiente 69): se autoriza contra el establecimiento del path ANTES de buscar
 * nada, así que un establecimiento inexistente responde el mismo 403 y mensaje que uno ajeno; la cancha se
 * busca acotada al establecimiento ya autorizado (inexistente o de otro complejo: el mismo 404).
 */
@DisplayName("Acciones sobre /api/v1/establecimientos/{id} sin oráculo de existencia")
class EstablecimientoSinOraculoAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";
    private static final long ID_INEXISTENTE = 987654321L;

    private Cancha canchaB;

    @BeforeEach
    void sembrarCanchaB() {
        canchaB = canchaRepository.save(Canchas.canchaDesactivada(establecimientoB));
    }

    private ResultActions con(MockHttpServletRequestBuilder req, Usuario quien, String cuerpo) throws Exception {
        MockHttpServletRequestBuilder conAuth = req.header("Authorization", bearer(quien));
        if (cuerpo != null) {
            conAuth = conAuth.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        }
        return mockMvc.perform(conAuth);
    }

    private void assert403(ResultActions r) throws Exception {
        r.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
    }

    @Test
    @DisplayName("estado_establecimientoInexistente_Devuelve403IgualAlAjeno")
    void estado_establecimientoInexistente() throws Exception {
        String body = "{\"activo\":false}";
        assert403(con(patch("/api/v1/establecimientos/" + establecimientoA.getId() + "/estado"), duenoB, body));
        assert403(con(patch("/api/v1/establecimientos/" + ID_INEXISTENTE + "/estado"), duenoA, body));
        assertEquals(true, establecimientoRepository.findById(establecimientoA.getId()).orElseThrow().getIsActive());
    }

    @Test
    @DisplayName("solicitarVerificacion_establecimientoInexistente_Devuelve403IgualAlAjeno")
    void solicitarVerificacion_establecimientoInexistente() throws Exception {
        String body = "{\"cuit\":\"20-12345678-6\",\"razonSocial\":\"Canchas SA\",\"telefonoContacto\":\"1155550000\","
                + "\"urlRedSocial\":\"https://instagram.com/canchas\"}";
        assert403(con(post("/api/v1/establecimientos/" + establecimientoA.getId() + "/solicitar-verificacion"), duenoB, body));
        assert403(con(post("/api/v1/establecimientos/" + ID_INEXISTENTE + "/solicitar-verificacion"), duenoA, body));
        assertNull(establecimientoRepository.findById(establecimientoA.getId()).orElseThrow().getCuit());
    }

    @Test
    @DisplayName("solicitarVerificacion_cuitInvalidoDeDuenoAjeno_Devuelve403No400")
    void solicitarVerificacion_cuitInvalidoDeDuenoAjeno() throws Exception {
        String body = "{\"cuit\":\"1\",\"razonSocial\":\"Canchas SA\",\"telefonoContacto\":\"1155550000\","
                + "\"urlRedSocial\":\"https://instagram.com/canchas\"}";
        assert403(con(post("/api/v1/establecimientos/" + establecimientoA.getId() + "/solicitar-verificacion"), duenoB, body));
        con(post("/api/v1/establecimientos/" + establecimientoA.getId() + "/solicitar-verificacion"), duenoA, body)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("previsualizacion_establecimientoInexistente_Devuelve403IgualAlAjeno")
    void previsualizacion_establecimientoInexistente() throws Exception {
        assert403(con(get("/api/v1/establecimientos/" + establecimientoA.getId() + "/previsualizacion"), duenoB, null));
        assert403(con(get("/api/v1/establecimientos/" + ID_INEXISTENTE + "/previsualizacion"), duenoA, null));
        con(get("/api/v1/establecimientos/" + ID_INEXISTENTE + "/previsualizacion"), admin, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
    }

    @Test
    @DisplayName("eliminarCancha_establecimientoInexistente_Devuelve403IgualAlAjeno")
    void eliminarCancha_establecimientoInexistente() throws Exception {
        assert403(con(delete("/api/v1/establecimientos/" + ID_INEXISTENTE + "/canchas/" + canchaB.getId()), duenoA, null));
        assertNull(canchaRepository.findById(canchaB.getId()).orElseThrow().getDeletedAt());
    }

    @Test
    @DisplayName("eliminarCancha_canchaDeOtroComplejoPorElPathPropio_Devuelve404IgualAlInexistenteSinEliminarla")
    void eliminarCancha_canchaDeOtroComplejo() throws Exception {
        String propio = "/api/v1/establecimientos/" + establecimientoA.getId() + "/canchas/";
        con(delete(propio + canchaB.getId()), duenoA, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Cancha no encontrada"));
        con(delete(propio + ID_INEXISTENTE), duenoA, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Cancha no encontrada"));
        assertNull(canchaRepository.findById(canchaB.getId()).orElseThrow().getDeletedAt());
    }

    @Test
    @DisplayName("estadoDeCancha_canchaDeOtroComplejoOEstablecimientoInexistente")
    void estadoDeCancha() throws Exception {
        String body = "{\"activo\":true}";
        String propio = "/api/v1/establecimientos/" + establecimientoA.getId() + "/canchas/";
        assert403(con(patch("/api/v1/establecimientos/" + ID_INEXISTENTE + "/canchas/" + canchaA.getId() + "/estado"), duenoA, body));
        con(patch(propio + canchaB.getId() + "/estado"), duenoA, body)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Cancha no encontrada"));
        con(patch(propio + ID_INEXISTENTE + "/estado"), duenoA, body)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Cancha no encontrada"));
        assertEquals(false, canchaRepository.findById(canchaB.getId()).orElseThrow().getIsActive());
    }
}
