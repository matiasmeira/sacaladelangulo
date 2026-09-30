package com.matiasmeira.sacaladelangulo.seguridad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.caja.model.CodigoEmparejamientoCaja;
import com.matiasmeira.sacaladelangulo.caja.model.DispositivoCaja;
import com.matiasmeira.sacaladelangulo.caja.repository.CodigoEmparejamientoCajaRepository;
import com.matiasmeira.sacaladelangulo.caja.repository.DispositivoCajaRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/v1/establecimientos/{id}/caja/dispositivos (pendiente 50): gestión de las PCs de confianza.
 * Los cuatro endpoints llevan @PreAuthorize OWNER/ADMIN (DispositivoCajaController:35, :49, :58, :66);
 * el rechazo del rol sale con el mensaje genérico de Spring ("Access Denied", ERROR_PREAUTHORIZE) y el del
 * dueño ajeno lo hace el service con AutorizacionEmpleadoService.validarPropietarioOAdmin (línea 135):
 * 403 {"error":"No autorizado en este establecimiento"}. ADMIN pasa en cualquier establecimiento
 * (AutorizacionEmpleadoService:133).
 *
 * <p>Fuera de alcance a propósito (decisión de producto pendiente): qué pasa con el token de empleado ya
 * emitido cuando se revoca el dispositivo, y la doble revocación.
 */
@DisplayName("/api/v1/establecimientos/{id}/caja/dispositivos")
class DispositivosCajaAutorizacionTest extends AbstractSecurityWebTest {

    private static final String CUERPO_NO_AUTORIZADO_ESTABLECIMIENTO = "{\"error\":\"No autorizado en este establecimiento\"}";
    private static final String CUERPO_PREAUTHORIZE = "{\"error\":\"" + ERROR_PREAUTHORIZE + "\"}";
    private static final String CUERPO_DISPOSITIVO_NO_AUTORIZADO = "{\"error\":\"Dispositivo no autorizado\"}";

    @Autowired
    private DispositivoCajaRepository dispositivoCajaRepository;

    @Autowired
    private CodigoEmparejamientoCajaRepository codigoEmparejamientoCajaRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private static String base(Long establecimientoId) {
        return "/api/v1/establecimientos/" + establecimientoId + "/caja/dispositivos";
    }

    private static String sha256(String crudo) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(crudo.getBytes(StandardCharsets.UTF_8)));
    }

    /** El dispositivo persistido detrás de una cookie: se busca por el hash SHA-256 del valor de la cookie. */
    private DispositivoCaja dispositivoDe(Cookie cookie) throws Exception {
        return dispositivoCajaRepository.findByTokenHash(sha256(cookie.getValue())).orElseThrow();
    }

    private ResultActions loginConCookie(Cookie cookie) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/empleados/login")
                .with(desdeIp(ipUnica()))
                .cookie(cookie)
                .contentType("application/json")
                .content("{\"nombre\":\"" + nombreUnico() + "\",\"pin\":\"4827\"}"));
    }

    // ------------------------------------------------------------ activar-local

    @Nested
    @DisplayName("POST /activar-local")
    class ActivarLocal {

        private ResultActions activar(Long establecimientoId, Usuario actor) throws Exception {
            return mockMvc.perform(post(base(establecimientoId) + "/activar-local")
                    .header("Authorization", bearer(actor))
                    .contentType("application/json")
                    .content("{\"label\":\"Caja mostrador\"}"));
        }

        @Test
        @DisplayName("jugador_Devuelve403YNoCreaDispositivo")
        void jugador_Devuelve403YNoCreaDispositivo() throws Exception {
            activar(establecimientoA.getId(), jugador)
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(content().string(CUERPO_PREAUTHORIZE));
            assertEquals(0, dispositivoCajaRepository.count());
        }

        @Test
        @DisplayName("empleado_Devuelve403YNoCreaDispositivo")
        void empleado_Devuelve403YNoCreaDispositivo() throws Exception {
            activar(establecimientoA.getId(), empleadoConPermiso)
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(content().string(CUERPO_PREAUTHORIZE));
            assertEquals(0, dispositivoCajaRepository.count());
        }

        @Test
        @DisplayName("duenoDeOtroEstablecimiento_Devuelve403YNoCreaDispositivo")
        void duenoDeOtroEstablecimiento_Devuelve403YNoCreaDispositivo() throws Exception {
            activar(establecimientoA.getId(), duenoB)
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(content().string(CUERPO_NO_AUTORIZADO_ESTABLECIMIENTO));
            assertEquals(0, dispositivoCajaRepository.count());
        }

        @Test
        @DisplayName("admin_Devuelve200EnCualquierEstablecimiento")
        void admin_Devuelve200EnCualquierEstablecimiento() throws Exception {
            activar(establecimientoA.getId(), admin).andExpect(status().isOk());
            activar(establecimientoB.getId(), admin).andExpect(status().isOk());
            assertEquals(2, dispositivoCajaRepository.count());
        }

        @Test
        @DisplayName("duenoDelEstablecimiento_Devuelve200ConCookieSeguraYGuardaSoloElHash")
        void duenoDelEstablecimiento_Devuelve200ConCookieSeguraYGuardaSoloElHash() throws Exception {
            MvcResult resultado = activar(establecimientoA.getId(), duenoA)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.label").value("Caja mostrador"))
                    .andReturn();

            // Atributos fijados por DispositivoCajaGate.setCookie (líneas 57-65).
            String setCookie = resultado.getResponse().getHeader("Set-Cookie");
            assertTrue(setCookie.startsWith(COOKIE_DISPOSITIVO + "="), setCookie);
            assertTrue(setCookie.contains("HttpOnly"), setCookie);
            assertTrue(setCookie.contains("Secure"), setCookie);
            assertTrue(setCookie.contains("SameSite=None"), setCookie);
            assertTrue(setCookie.contains("Path=/"), setCookie);

            String crudo = cookieDeSetCookie(setCookie).getValue();
            List<DispositivoCaja> guardados = dispositivoCajaRepository.findAll();
            assertEquals(1, guardados.size());
            DispositivoCaja guardado = guardados.get(0);
            assertNotEquals(crudo, guardado.getTokenHash());
            assertEquals(sha256(crudo), guardado.getTokenHash());
            assertEquals(establecimientoA.getId(), guardado.getEstablecimiento().getId());
            assertTrue(guardado.getActivo());
            JsonNode cuerpo = objectMapper.readTree(resultado.getResponse().getContentAsString());
            assertEquals(guardado.getId(), cuerpo.get("dispositivoId").asLong());
            assertFalse(resultado.getResponse().getContentAsString().contains(crudo));
        }

        @Test
        @DisplayName("establecimientoInexistente_Devuelve404")
        void establecimientoInexistente_Devuelve404() throws Exception {
            activar(9_999_999L, duenoA)
                    .andExpect(status().isNotFound())
                    .andExpect(content().string("{\"error\":\"Establecimiento no encontrado\"}"));
            assertEquals(0, dispositivoCajaRepository.count());
        }
    }

    // ---------------------------------------------------------------- emparejar

    @Nested
    @DisplayName("POST /emparejar")
    class Emparejar {

        private ResultActions emparejar(Long establecimientoId, Usuario actor) throws Exception {
            return mockMvc.perform(post(base(establecimientoId) + "/emparejar")
                    .header("Authorization", bearer(actor))
                    .contentType("application/json")
                    .content("{\"label\":\"Caja del fondo\"}"));
        }

        @Test
        @DisplayName("jugador_Devuelve403YNoGeneraCodigo")
        void jugador_Devuelve403YNoGeneraCodigo() throws Exception {
            emparejar(establecimientoA.getId(), jugador)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(CUERPO_PREAUTHORIZE));
            assertEquals(0, codigoEmparejamientoCajaRepository.count());
        }

        @Test
        @DisplayName("empleado_Devuelve403YNoGeneraCodigo")
        void empleado_Devuelve403YNoGeneraCodigo() throws Exception {
            emparejar(establecimientoA.getId(), empleadoConPermiso)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(CUERPO_PREAUTHORIZE));
            assertEquals(0, codigoEmparejamientoCajaRepository.count());
        }

        @Test
        @DisplayName("duenoDeOtroEstablecimiento_Devuelve403YNoGeneraCodigo")
        void duenoDeOtroEstablecimiento_Devuelve403YNoGeneraCodigo() throws Exception {
            emparejar(establecimientoA.getId(), duenoB)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(CUERPO_NO_AUTORIZADO_ESTABLECIMIENTO));
            assertEquals(0, codigoEmparejamientoCajaRepository.count());
        }

        @Test
        @DisplayName("admin_Devuelve200EnCualquierEstablecimiento")
        void admin_Devuelve200EnCualquierEstablecimiento() throws Exception {
            emparejar(establecimientoA.getId(), admin).andExpect(status().isOk());
            emparejar(establecimientoB.getId(), admin).andExpect(status().isOk());
            assertEquals(2, codigoEmparejamientoCajaRepository.count());
        }

        @Test
        @DisplayName("duenoDelEstablecimiento_Devuelve200ConCodigoYUrl_SinCookie_YGuardaSoloElHash")
        void duenoDelEstablecimiento_Devuelve200ConCodigoYUrl_SinCookie_YGuardaSoloElHash() throws Exception {
            MvcResult resultado = emparejar(establecimientoA.getId(), duenoA)
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andReturn();

            JsonNode cuerpo = objectMapper.readTree(resultado.getResponse().getContentAsString());
            String codigo = cuerpo.get("codigo").asText();
            assertFalse(codigo.isBlank());
            assertTrue(cuerpo.get("urlEmparejamiento").asText().endsWith("/caja/emparejar?codigo=" + codigo),
                    cuerpo.get("urlEmparejamiento").asText());
            assertFalse(cuerpo.get("expiraEn").asText().isBlank());

            List<CodigoEmparejamientoCaja> guardados = codigoEmparejamientoCajaRepository.findAll();
            assertEquals(1, guardados.size());
            assertNotEquals(codigo, guardados.get(0).getCodigoHash());
            assertEquals(sha256(codigo), guardados.get(0).getCodigoHash());
            assertEquals(establecimientoA.getId(), guardados.get(0).getEstablecimiento().getId());
            assertFalse(guardados.get(0).getUsado());
            // Generar el código no crea ningún dispositivo: eso pasa recién al consumirlo.
            assertEquals(0, dispositivoCajaRepository.count());
        }
    }

    // ------------------------------------------------------------------- listar

    @Nested
    @DisplayName("GET /")
    class Listar {

        private ResultActions listar(Long establecimientoId, Usuario actor) throws Exception {
            return mockMvc.perform(get(base(establecimientoId)).header("Authorization", bearer(actor)));
        }

        @Test
        @DisplayName("jugador_Devuelve403")
        void jugador_Devuelve403() throws Exception {
            cookieDispositivo(establecimientoA, duenoA);
            listar(establecimientoA.getId(), jugador)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(CUERPO_PREAUTHORIZE));
        }

        @Test
        @DisplayName("empleado_Devuelve403")
        void empleado_Devuelve403() throws Exception {
            cookieDispositivo(establecimientoA, duenoA);
            listar(establecimientoA.getId(), empleadoConPermiso)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(CUERPO_PREAUTHORIZE));
        }

        @Test
        @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
        void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
            cookieDispositivo(establecimientoA, duenoA);
            listar(establecimientoA.getId(), duenoB)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(CUERPO_NO_AUTORIZADO_ESTABLECIMIENTO));
        }

        @Test
        @DisplayName("duenoDelEstablecimiento_ListaSoloLosActivosDeSuLocal_SinTokenHash")
        void duenoDelEstablecimiento_ListaSoloLosActivosDeSuLocal_SinTokenHash() throws Exception {
            Cookie activoA = cookieDispositivo(establecimientoA, duenoA);
            Cookie revocadoA = cookieDispositivo(establecimientoA, duenoA);
            cookieDispositivo(establecimientoB, duenoB);
            DispositivoCaja revocado = dispositivoDe(revocadoA);
            revocado.setActivo(false);
            dispositivoCajaRepository.save(revocado);
            DispositivoCaja esperado = dispositivoDe(activoA);

            MvcResult resultado = listar(establecimientoA.getId(), duenoA)
                    .andExpect(status().isOk())
                    .andReturn();

            String json = resultado.getResponse().getContentAsString();
            JsonNode lista = objectMapper.readTree(json);
            assertEquals(1, lista.size(), json);
            assertEquals(esperado.getId(), lista.get(0).get("id").asLong());
            List<String> campos = new ArrayList<>();
            lista.get(0).fieldNames().forEachRemaining(campos::add);
            assertEquals(Set.of("id", "label", "createdAt", "lastUsedAt"), Set.copyOf(campos), json);
            assertFalse(json.contains(esperado.getTokenHash()), "el hash del token no se expone");
            assertFalse(json.contains(activoA.getValue()), "el token crudo no se expone");
        }

        @Test
        @DisplayName("admin_Devuelve200")
        void admin_Devuelve200() throws Exception {
            cookieDispositivo(establecimientoA, duenoA);
            listar(establecimientoA.getId(), admin)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }

    // ------------------------------------------------------------------ revocar

    @Nested
    @DisplayName("DELETE /{dispositivoId}")
    class Revocar {

        private ResultActions revocar(Long establecimientoId, Long dispositivoId, Usuario actor) throws Exception {
            return mockMvc.perform(delete(base(establecimientoId) + "/" + dispositivoId)
                    .header("Authorization", bearer(actor)));
        }

        private void assertSigueActivo(Long dispositivoId) {
            assertTrue(dispositivoCajaRepository.findById(dispositivoId).orElseThrow().getActivo());
        }

        @Test
        @DisplayName("jugador_Devuelve403YNoRevoca")
        void jugador_Devuelve403YNoRevoca() throws Exception {
            DispositivoCaja d = dispositivoDe(cookieDispositivo(establecimientoA, duenoA));
            revocar(establecimientoA.getId(), d.getId(), jugador)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(CUERPO_PREAUTHORIZE));
            assertSigueActivo(d.getId());
        }

        @Test
        @DisplayName("empleado_Devuelve403YNoRevoca")
        void empleado_Devuelve403YNoRevoca() throws Exception {
            DispositivoCaja d = dispositivoDe(cookieDispositivo(establecimientoA, duenoA));
            revocar(establecimientoA.getId(), d.getId(), empleadoConPermiso)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(CUERPO_PREAUTHORIZE));
            assertSigueActivo(d.getId());
        }

        @Test
        @DisplayName("duenoDeBConElPathDeA_Devuelve403YNoRevoca")
        void duenoDeBConElPathDeA_Devuelve403YNoRevoca() throws Exception {
            DispositivoCaja d = dispositivoDe(cookieDispositivo(establecimientoA, duenoA));
            revocar(establecimientoA.getId(), d.getId(), duenoB)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(CUERPO_NO_AUTORIZADO_ESTABLECIMIENTO));
            assertSigueActivo(d.getId());
        }

        @Test
        @DisplayName("duenoDeBConSuPathYElIdDeUnDispositivoDeA_Devuelve404YNoRevoca")
        void duenoDeBConSuPathYElIdDeUnDispositivoDeA_Devuelve404YNoRevoca() throws Exception {
            // El cruce de establecimientos: B es dueño del path, pero el dispositivo es de A. Lo corta
            // findByIdAndEstablecimientoId (DispositivoCajaService:138): se comporta como "no existe".
            DispositivoCaja deA = dispositivoDe(cookieDispositivo(establecimientoA, duenoA));
            revocar(establecimientoB.getId(), deA.getId(), duenoB)
                    .andExpect(status().isNotFound())
                    .andExpect(content().string("{\"error\":\"Dispositivo no encontrado\"}"));
            assertSigueActivo(deA.getId());
        }

        @Test
        @DisplayName("idInexistente_Devuelve404")
        void idInexistente_Devuelve404() throws Exception {
            DispositivoCaja d = dispositivoDe(cookieDispositivo(establecimientoA, duenoA));
            revocar(establecimientoA.getId(), 9_999_999L, duenoA)
                    .andExpect(status().isNotFound())
                    .andExpect(content().string("{\"error\":\"Dispositivo no encontrado\"}"));
            assertSigueActivo(d.getId());
        }

        @Test
        @DisplayName("duenoDelEstablecimiento_Devuelve204_DesapareceDelListado_YSuCookieDejaDeServir")
        void duenoDelEstablecimiento_Devuelve204_DesapareceDelListado_YSuCookieDejaDeServir() throws Exception {
            Cookie cookie = cookieDispositivo(establecimientoA, duenoA);
            Cookie otra = cookieDispositivo(establecimientoA, duenoA);
            DispositivoCaja d = dispositivoDe(cookie);

            revocar(establecimientoA.getId(), d.getId(), duenoA)
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));

            assertFalse(dispositivoCajaRepository.findById(d.getId()).orElseThrow().getActivo());
            MvcResult listado = mockMvc.perform(get(base(establecimientoA.getId())).header("Authorization", bearer(duenoA)))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode lista = objectMapper.readTree(listado.getResponse().getContentAsString());
            assertEquals(1, lista.size());
            assertEquals(dispositivoDe(otra).getId(), lista.get(0).get("id").asLong());

            // Por HTTP, con la cookie ya revocada: ni el login por PIN ni la lista de empleados.
            loginConCookie(cookie)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(CUERPO_DISPOSITIVO_NO_AUTORIZADO));
            mockMvc.perform(get("/api/v1/establecimientos/" + establecimientoA.getId() + "/empleados/activos").cookie(cookie))
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(CUERPO_DISPOSITIVO_NO_AUTORIZADO));
            // La otra caja del mismo local no se toca.
            mockMvc.perform(get("/api/v1/establecimientos/" + establecimientoA.getId() + "/empleados/activos").cookie(otra))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("admin_Devuelve204EnCualquierEstablecimiento")
        void admin_Devuelve204EnCualquierEstablecimiento() throws Exception {
            DispositivoCaja d = dispositivoDe(cookieDispositivo(establecimientoA, duenoA));
            revocar(establecimientoA.getId(), d.getId(), admin).andExpect(status().isNoContent());
            assertFalse(dispositivoCajaRepository.findById(d.getId()).orElseThrow().getActivo());
        }
    }
}
