package com.matiasmeira.sacaladelangulo.seguridad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.caja.model.DispositivoCaja;
import com.matiasmeira.sacaladelangulo.caja.repository.DispositivoCajaRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/establecimientos/{id}/empleados/activos (pendiente 50): la lista de nombres que el kiosco
 * muestra antes del PIN. La chain lo deja pasar sin JWT (SecurityConfig:83) y la autorización real es la
 * cookie de dispositivo (EmpleadoController:63-66). Un bearer, aunque sea el del dueño, no sirve: el
 * controller no mira al usuario autenticado.
 *
 * <p>No se repiten acá "sin cookie" ni "cookie de otro local", que ya fija ModoCajaFlujoCompletoTest.
 * Cuerpo de todos los rechazos: 403 {"error":"Dispositivo no autorizado"} (DispositivoCajaGate:46,
 * DispositivoCajaService:197 y :200, EmpleadoController:65).
 */
@DisplayName("GET /api/v1/establecimientos/{id}/empleados/activos")
class ModoCajaEmpleadosActivosAutorizacionTest extends AbstractSecurityWebTest {

    private static final String CUERPO_403 = "{\"error\":\"Dispositivo no autorizado\"}";

    @Autowired
    private DispositivoCajaRepository dispositivoCajaRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String ruta(Long establecimientoId) {
        return "/api/v1/establecimientos/" + establecimientoId + "/empleados/activos";
    }

    @Test
    @DisplayName("cookieBasura_Devuelve403")
    void cookieBasura_Devuelve403() throws Exception {
        cookieDispositivo(establecimientoA, duenoA);

        mockMvc.perform(get(ruta(establecimientoA.getId()))
                        .cookie(new Cookie(COOKIE_DISPOSITIVO, "esto-no-es-un-token-de-dispositivo")))
                .andExpect(status().isForbidden())
                .andExpect(content().string(CUERPO_403));
    }

    @Test
    @DisplayName("dispositivoRevocado_Devuelve403")
    void dispositivoRevocado_Devuelve403() throws Exception {
        Cookie cookie = cookieDispositivo(establecimientoA, duenoA);
        DispositivoCaja dispositivo = dispositivoCajaRepository.findAll().get(0);
        dispositivo.setActivo(false);
        dispositivoCajaRepository.save(dispositivo);

        mockMvc.perform(get(ruta(establecimientoA.getId())).cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(content().string(CUERPO_403));
        assertFalse(dispositivoCajaRepository.findById(dispositivo.getId()).orElseThrow().getActivo());
    }

    @Test
    @DisplayName("bearerDelDuenoSinCookie_Devuelve403")
    void bearerDelDuenoSinCookie_Devuelve403() throws Exception {
        // La sesión del dueño no reemplaza al dispositivo: lo que habilita la lista es la PC de confianza.
        mockMvc.perform(get(ruta(establecimientoA.getId())).header("Authorization", bearer(duenoA)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(CUERPO_403));
    }

    @Test
    @DisplayName("cookieValida_Devuelve200ConSoloIdYNombre_SinInactivosNiDeOtroLocal")
    void cookieValida_Devuelve200ConSoloIdYNombre_SinInactivosNiDeOtroLocal() throws Exception {
        Usuario activo = empleadoConPin(establecimientoA, nombreUnico(), "4827");
        Usuario inactivo = empleadoConPin(establecimientoA, nombreUnico(), "4827");
        inactivo.setIsActive(false);
        usuarioRepository.save(inactivo);
        Usuario deOtroLocal = empleadoConPin(establecimientoB, nombreUnico(), "4827");
        Cookie cookie = cookieDispositivo(establecimientoA, duenoA);

        MvcResult resultado = mockMvc.perform(get(ruta(establecimientoA.getId())).cookie(cookie))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode lista = objectMapper.readTree(resultado.getResponse().getContentAsString());
        Set<Long> ids = new TreeSet<>();
        for (JsonNode item : lista) {
            ids.add(item.get("id").asLong());
            List<String> campos = new ArrayList<>();
            item.fieldNames().forEachRemaining(campos::add);
            assertEquals(Set.of("id", "nombre"), Set.copyOf(campos), "sólo id y nombre: " + item);
        }
        // Los dos empleados del escenario base (activos, del local A) más el nuevo; ni el inactivo, ni el de B,
        // ni el dueño ni el jugador.
        assertEquals(new TreeSet<>(Set.of(empleadoConPermiso.getId(), empleadoSinPermiso.getId(), activo.getId())), ids);
        assertFalse(ids.contains(inactivo.getId()));
        assertFalse(ids.contains(deOtroLocal.getId()));
    }
}
