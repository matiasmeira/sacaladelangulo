package com.matiasmeira.sacaladelangulo.seguridad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matiasmeira.sacaladelangulo.auth.model.CodigoVerificacion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.CodigoVerificacionRepository;
import com.matiasmeira.sacaladelangulo.core.security.TokenHasher;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/usuarios/telefono/{solicitar,verificar}-codigo con la verificación de teléfono ENCENDIDA
 * ({@code app.telefono.verificacion-habilitada=true}). Son los casos del flujo que estaban en
 * CuentaPropiaAutorizacionTest (lote G del 52) antes de apagar la verificación (pendiente 99): se movieron
 * acá sin cambios de fondo para que sigan cubriendo el flujo cuando se vuelva a habilitar. Con la propiedad
 * en su default (false) las mismas rutas responden 410; eso se prueba en CuentaPropiaAutorizacionTest.
 *
 * <p>La propiedad distinta hace que este test levante su propio contexto de Spring (no comparte el del lote).
 */
@TestPropertySource(properties = "app.telefono.verificacion-habilitada=true")
@DisplayName("Cuenta propia /api/v1/usuarios/telefono (verificación encendida)")
class CuentaPropiaTelefonoHabilitadoTest extends AbstractSecurityWebTest {

    private static final String SOLICITAR = "/api/v1/usuarios/telefono/solicitar-codigo";
    private static final String VERIFICAR = "/api/v1/usuarios/telefono/verificar-codigo";
    private static final String PIN = "4827";
    private static final String CODIGO = "123456";

    @Autowired
    private CodigoVerificacionRepository codigoVerificacionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // ---- helpers ----

    private ResultActions solicitar(Usuario quien, String cuerpo) throws Exception {
        return mockMvc.perform(post(SOLICITAR).header("Authorization", bearer(quien))
                .contentType("application/json").content(cuerpo));
    }

    private ResultActions verificar(Usuario quien, String codigo) throws Exception {
        return mockMvc.perform(post(VERIFICAR).header("Authorization", bearer(quien))
                .contentType("application/json").content("{\"codigo\":\"" + codigo + "\"}"));
    }

    private void sembrarCodigo(Usuario usuario, String telefono) {
        codigoVerificacionRepository.save(CodigoVerificacion.builder()
                .email(usuario.getEmail())
                .codigoHash(TokenHasher.sha256Hex(CODIGO))
                .telefonoPendiente(telefono)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(5))
                .build());
    }

    private Usuario recargar(Usuario usuario) {
        return usuarioRepository.findById(usuario.getId()).orElseThrow();
    }

    /** Token de mostrador real: dispositivo activado por el dueño + POST /auth/empleados/login con PIN. */
    private String tokenMostradorReal(Usuario empleadoConPin) throws Exception {
        Cookie cookie = cookieDispositivo(establecimientoA, duenoA);
        String respuesta = mockMvc.perform(post("/api/v1/auth/empleados/login")
                        .cookie(cookie).with(desdeIp(ipUnica()))
                        .contentType("application/json")
                        .content("{\"establecimientoId\":" + establecimientoA.getId() + ",\"nombre\":\""
                                + empleadoConPin.getNombre() + "\",\"pin\":\"" + PIN + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(respuesta).get("token").asText();
    }

    // ---- POST telefono/solicitar-codigo ----

    @Test
    @DisplayName("solicitarCodigo_cualquierRolAutenticado_Devuelve200YGuardaElCodigoParaSuPropioEmail")
    void solicitarCodigo_cualquierRol_Devuelve200() throws Exception {
        Usuario[] todos = {jugador, duenoA, admin, empleadoConPermiso, empleadoSinPermiso};
        for (Usuario u : todos) {
            solicitar(u, "{\"telefono\":\"+5491100000000\"}").andExpect(status().isOk());
            CodigoVerificacion c = codigoVerificacionRepository.findByEmail(u.getEmail()).orElseThrow();
            assertEquals("+5491100000000", c.getTelefonoPendiente());
        }
    }

    @Test
    @DisplayName("solicitarCodigo_conEmailAjenoEnElBody_GuardaElCodigoParaElDelToken")
    void solicitarCodigo_conEmailAjenoEnElBody_GuardaParaElDelToken() throws Exception {
        solicitar(jugador, "{\"telefono\":\"+5491100000001\",\"email\":\"" + duenoB.getEmail() + "\"}")
                .andExpect(status().isOk());
        assertTrue(codigoVerificacionRepository.findByEmail(jugador.getEmail()).isPresent());
        assertTrue(codigoVerificacionRepository.findByEmail(duenoB.getEmail()).isEmpty());
    }

    @Test
    @DisplayName("solicitarCodigo_noPisaElCodigoPendienteDeOtroUsuario")
    void solicitarCodigo_noPisaElCodigoDeOtro() throws Exception {
        sembrarCodigo(duenoB, "+5491100000002");
        solicitar(jugador, "{\"telefono\":\"+5491199999999\"}").andExpect(status().isOk());
        assertEquals("+5491100000002",
                codigoVerificacionRepository.findByEmail(duenoB.getEmail()).orElseThrow().getTelefonoPendiente());
    }

    @Test
    @DisplayName("solicitarCodigo_telefonoVacio_Devuelve400")
    void solicitarCodigo_telefonoVacio_Devuelve400() throws Exception {
        solicitar(jugador, "{\"telefono\":\" \"}").andExpect(status().isBadRequest());
        assertTrue(codigoVerificacionRepository.findByEmail(jugador.getEmail()).isEmpty());
    }

    // ---- POST telefono/verificar-codigo ----

    @Test
    @DisplayName("verificarCodigo_propio_Devuelve200YVinculaElTelefonoSoloAEseUsuario")
    void verificarCodigo_propio_Devuelve200() throws Exception {
        sembrarCodigo(jugador, "+5491100000003");
        verificar(jugador, CODIGO).andExpect(status().isOk());

        Usuario j = recargar(jugador);
        assertEquals("+5491100000003", j.getTelefono());
        assertTrue(j.getTelefonoVerificado());
        assertFalse(recargar(duenoB).getTelefonoVerificado());
        assertTrue(codigoVerificacionRepository.findByEmail(jugador.getEmail()).isEmpty());
    }

    @Test
    @DisplayName("verificarCodigo_empleadoConTokenDeMostradorReal_Devuelve200")
    void verificarCodigo_empleadoMostradorReal_Devuelve200() throws Exception {
        Usuario cajero = empleadoConPin(establecimientoA, nombreUnico(), PIN);
        sembrarCodigo(cajero, "+5491100000004");
        mockMvc.perform(post(VERIFICAR).header("Authorization", tokenMostradorReal(cajero))
                        .contentType("application/json").content("{\"codigo\":\"" + CODIGO + "\"}"))
                .andExpect(status().isOk());
        assertEquals("+5491100000004", recargar(cajero).getTelefono());
    }

    @Test
    @DisplayName("verificarCodigo_conElCodigoDePendienteDeOtroUsuario_Devuelve400YNoTocaNada")
    void verificarCodigo_conCodigoDeOtro_Devuelve400() throws Exception {
        sembrarCodigo(duenoB, "+5491100000005");
        // el jugador conoce el código de la víctima, pero no tiene uno propio pendiente
        verificar(jugador, CODIGO).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Código inválido o expirado"));

        assertNull(recargar(jugador).getTelefono());
        assertFalse(recargar(jugador).getTelefonoVerificado());
        assertNull(recargar(duenoB).getTelefono());
        assertTrue(codigoVerificacionRepository.findByEmail(duenoB.getEmail()).isPresent());
    }
}
