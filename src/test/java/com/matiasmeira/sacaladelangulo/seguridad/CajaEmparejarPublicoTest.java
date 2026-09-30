package com.matiasmeira.sacaladelangulo.seguridad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matiasmeira.sacaladelangulo.caja.model.CodigoEmparejamientoCaja;
import com.matiasmeira.sacaladelangulo.caja.repository.CodigoEmparejamientoCajaRepository;
import com.matiasmeira.sacaladelangulo.caja.repository.DispositivoCajaRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/caja/emparejar (pendiente 50): el endpoint público con el que una PC canjea el código de
 * un solo uso por su cookie de dispositivo. La chain lo deja pasar sin token (SecurityConfig:84) y lo que
 * autentica es el código del body (CajaPublicoController:27-37 -> DispositivoCajaService.consumirCodigo).
 *
 * <p>Un código inexistente, usado o expirado devuelve SIEMPRE 403 {"error":"Código de emparejamiento
 * inválido"} (DispositivoCajaService:161 y :163-165): distinguirlos sería un oráculo del estado del código.
 * Un body sin código lo corta la validación del DTO (400) antes del service.
 *
 * <p>Aislamiento: el bucket por IP del filtro (10 cada 5 min, RateLimitFilter:60) vive en el contexto
 * compartido y no se resetea; cada test usa una IP propia ({@link #ipUnica()}).
 */
@DisplayName("POST /api/v1/caja/emparejar")
class CajaEmparejarPublicoTest extends AbstractSecurityWebTest {

    private static final String RUTA = "/api/v1/caja/emparejar";
    private static final String CUERPO_CODIGO_INVALIDO = "{\"error\":\"Código de emparejamiento inválido\"}";
    private static final String CUERPO_429_IP =
            "{\"error\":\"Demasiados intentos desde esta IP. Intente nuevamente en unos minutos.\"}";

    @Autowired
    private DispositivoCajaRepository dispositivoCajaRepository;

    @Autowired
    private CodigoEmparejamientoCajaRepository codigoEmparejamientoCajaRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /** Código crudo generado por el camino real: el dueño lo pide por HTTP. */
    private String generarCodigo() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/establecimientos/" + establecimientoA.getId()
                        + "/caja/dispositivos/emparejar")
                        .header("Authorization", bearer(duenoA))
                        .contentType("application/json")
                        .content("{\"label\":\"Caja del fondo\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("codigo").asText();
    }

    private ResultActions consumir(String ip, String body) throws Exception {
        return mockMvc.perform(post(RUTA).with(desdeIp(ip)).contentType("application/json").content(body));
    }

    private static String cuerpo(String codigo) {
        return "{\"codigo\":\"" + codigo + "\"}";
    }

    private CodigoEmparejamientoCaja codigoGuardado() {
        List<CodigoEmparejamientoCaja> todos = codigoEmparejamientoCajaRepository.findAll();
        assertEquals(1, todos.size());
        return todos.get(0);
    }

    @Test
    @DisplayName("codigoInvalido_Devuelve403YNoCreaDispositivo")
    void codigoInvalido_Devuelve403YNoCreaDispositivo() throws Exception {
        String valido = generarCodigo();

        consumir(ipUnica(), cuerpo("no-es-un-codigo"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().string(CUERPO_CODIGO_INVALIDO));

        assertEquals(0, dispositivoCajaRepository.count());
        assertFalse(codigoGuardado().getUsado(), "un intento con otro código no consume el válido");
        assertFalse(valido.isBlank());
    }

    @Test
    @DisplayName("codigoEnBlanco_Devuelve400")
    void codigoEnBlanco_Devuelve400() throws Exception {
        generarCodigo();

        for (String body : List.of("{\"codigo\":\"   \"}", "{\"codigo\":\"\"}", "{}")) {
            consumir(ipUnica(), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(content().string("{\"codigo\":\"El código es obligatorio\"}"));
        }
        assertEquals(0, dispositivoCajaRepository.count());
        assertFalse(codigoGuardado().getUsado());
    }

    @Test
    @DisplayName("sinBody_Devuelve400")
    void sinBody_Devuelve400() throws Exception {
        mockMvc.perform(post(RUTA).with(desdeIp(ipUnica())).contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("{\"error\":\"Cuerpo de la petición inválido o mal formado\"}"));
        assertEquals(0, dispositivoCajaRepository.count());
    }

    @Test
    @DisplayName("codigoValido_Devuelve200ConCookieYLabel_YLaCookieSirveEnActivos")
    void codigoValido_Devuelve200ConCookieYLabel_YLaCookieSirveEnActivos() throws Exception {
        String codigo = generarCodigo();

        MvcResult resultado = consumir(ipUnica(), cuerpo(codigo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.establecimientoId").value(establecimientoA.getId()))
                .andExpect(jsonPath("$.label").value("Caja del fondo"))
                .andReturn();

        String setCookie = resultado.getResponse().getHeader("Set-Cookie");
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("Secure")
                && setCookie.contains("SameSite=None") && setCookie.contains("Path=/"), setCookie);
        assertEquals(1, dispositivoCajaRepository.count());
        assertTrue(codigoGuardado().getUsado());

        Cookie cookie = cookieDeSetCookie(setCookie);
        mockMvc.perform(get("/api/v1/establecimientos/" + establecimientoA.getId() + "/empleados/activos").cookie(cookie))
                .andExpect(status().isOk());
        // ...pero sólo para el local del código: en el de B no sirve.
        mockMvc.perform(get("/api/v1/establecimientos/" + establecimientoB.getId() + "/empleados/activos").cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(content().string("{\"error\":\"Dispositivo no autorizado\"}"));
    }

    @Test
    @DisplayName("codigoYaUsado_Devuelve403YNoCreaOtroDispositivo")
    void codigoYaUsado_Devuelve403YNoCreaOtroDispositivo() throws Exception {
        String codigo = generarCodigo();
        consumir(ipUnica(), cuerpo(codigo)).andExpect(status().isOk());

        consumir(ipUnica(), cuerpo(codigo))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().string(CUERPO_CODIGO_INVALIDO));

        assertEquals(1, dispositivoCajaRepository.count());
    }

    @Test
    @DisplayName("codigoExpirado_Devuelve403YNoLoConsume")
    void codigoExpirado_Devuelve403YNoLoConsume() throws Exception {
        String codigo = generarCodigo();
        expirar();

        consumir(ipUnica(), cuerpo(codigo))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().string(CUERPO_CODIGO_INVALIDO));

        assertEquals(0, dispositivoCajaRepository.count());
        assertFalse(codigoGuardado().getUsado(), "un código expirado no se marca como usado");
    }

    @Test
    @DisplayName("inexistenteUsadoYExpirado_MismoCuerpo_SinOraculo")
    void inexistenteUsadoYExpirado_MismoCuerpo_SinOraculo() throws Exception {
        String usado = generarCodigo();
        consumir(ipUnica(), cuerpo(usado)).andExpect(status().isOk());
        String expirado = generarCodigo();
        CodigoEmparejamientoCaja aExpirar = codigoEmparejamientoCajaRepository.findAll().stream()
                .filter(c -> !c.getUsado()).findFirst().orElseThrow();
        aExpirar.setExpiraEn(LocalDateTime.now().minusMinutes(1));
        codigoEmparejamientoCajaRepository.save(aExpirar);

        String cuerpoInexistente = consumir(ipUnica(), cuerpo("no-existe")).andReturn().getResponse().getContentAsString();
        String cuerpoUsado = consumir(ipUnica(), cuerpo(usado)).andReturn().getResponse().getContentAsString();
        String cuerpoExpirado = consumir(ipUnica(), cuerpo(expirado)).andReturn().getResponse().getContentAsString();

        assertEquals(CUERPO_CODIGO_INVALIDO, cuerpoInexistente);
        assertEquals(CUERPO_CODIGO_INVALIDO, cuerpoUsado);
        assertEquals(CUERPO_CODIGO_INVALIDO, cuerpoExpirado);
        assertEquals(1, dispositivoCajaRepository.count());
    }

    @Test
    @DisplayName("porIp_ElRequest11DesdeLaMismaIpDevuelve429_YNoConsumeElCodigo")
    void porIp_ElRequest11DesdeLaMismaIpDevuelve429_YNoConsumeElCodigo() throws Exception {
        // Límite por IP de RateLimitFilter:60 (10 cada 5 min). El del service (DispositivoCajaService:156,
        // también 10 cada 5 min, con otra clave) no llega a disparar por HTTP: el filtro corta antes. No estaba
        // cubierto por HTTP: RateLimitFilterTest no prueba esta ruta y el test del service mockea el limitador.
        String codigo = generarCodigo();
        String ip = ipUnica();
        for (int i = 1; i <= 10; i++) {
            consumir(ip, cuerpo("no-es-un-codigo-" + i)).andExpect(status().isForbidden());
        }

        consumir(ip, cuerpo(codigo))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().string(CUERPO_429_IP));

        assertEquals(0, dispositivoCajaRepository.count());
        assertFalse(codigoGuardado().getUsado(), "el código bueno no se consumió con la IP bloqueada");
        // Otra IP sí lo puede canjear.
        consumir(ipUnica(), cuerpo(codigo)).andExpect(status().isOk());
    }

    private void expirar() {
        CodigoEmparejamientoCaja codigo = codigoGuardado();
        codigo.setExpiraEn(LocalDateTime.now().minusMinutes(1));
        codigoEmparejamientoCajaRepository.save(codigo);
    }
}
