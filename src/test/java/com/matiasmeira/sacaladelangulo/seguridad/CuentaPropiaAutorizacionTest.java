package com.matiasmeira.sacaladelangulo.seguridad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matiasmeira.sacaladelangulo.auth.model.CodigoVerificacion;
import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.CodigoVerificacionRepository;
import com.matiasmeira.sacaladelangulo.core.security.TokenHasher;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cuenta propia: GET/DELETE /api/v1/usuarios/me y POST /api/v1/usuarios/telefono/{solicitar,verificar}-codigo
 * (UsuarioController:27, :36, :45, :51). Ninguno lleva @PreAuthorize (CoberturaPreAuthorizeTest los lista como
 * "cuenta propia"): la chain exige estar autenticado (anyRequest().authenticated(), SecurityConfig:97) y el
 * usuario sale SIEMPRE del token (userDetails.getUsername()), nunca del body, del path ni de la query. Ninguna
 * de estas rutas tiene rate limit (no figuran en RateLimitFilter.LIMITES_POR_RUTA ni son /mails); el login de
 * mostrador que se usa para obtener el token real sí, y usa una IP propia.
 *
 * <p>El token de mostrador (AuthService:128) es un JWT común del empleado con vida corta y el claim
 * {@code empleadoId}; ese claim no lo lee nada en src/main, así que el filtro lo trata igual que a un token normal.
 */
@DisplayName("Cuenta propia /api/v1/usuarios (me y teléfono)")
class CuentaPropiaAutorizacionTest extends AbstractSecurityWebTest {

    private static final String ME = "/api/v1/usuarios/me";
    private static final String SOLICITAR = "/api/v1/usuarios/telefono/solicitar-codigo";
    private static final String VERIFICAR = "/api/v1/usuarios/telefono/verificar-codigo";
    private static final String PIN = "4827";
    private static final String CODIGO = "123456";

    @Autowired
    private CodigoVerificacionRepository codigoVerificacionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // ---- helpers ----

    private ResultActions me(String authorization) throws Exception {
        return mockMvc.perform(get(ME).header("Authorization", authorization));
    }

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

    // ---- GET /me ----

    @Test
    @DisplayName("me_jugador_VeSuPerfilSinEstablecimientoNiPermisos")
    void me_jugador_VeSuPerfil() throws Exception {
        me(bearer(jugador)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jugador.getId()))
                .andExpect(jsonPath("$.email").value(jugador.getEmail()))
                .andExpect(jsonPath("$.rol").value("PLAYER"))
                .andExpect(jsonPath("$.establecimientoId").value((Object) null))
                .andExpect(jsonPath("$.permisos").isEmpty());
    }

    @Test
    @DisplayName("me_dueno_VeSuPerfil")
    void me_dueno_VeSuPerfil() throws Exception {
        me(bearer(duenoA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(duenoA.getId()))
                .andExpect(jsonPath("$.rol").value("OWNER"))
                .andExpect(jsonPath("$.establecimientoId").value((Object) null));
    }

    @Test
    @DisplayName("me_admin_VeSuPerfil")
    void me_admin_VeSuPerfil() throws Exception {
        me(bearer(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(admin.getId()))
                .andExpect(jsonPath("$.rol").value("ADMIN"));
    }

    @Test
    @DisplayName("me_empleado_VeSuPerfilConEstablecimientoYPermisos")
    void me_empleado_VeSuPerfilConEstablecimientoYPermisos() throws Exception {
        me(bearer(empleadoConPermiso)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(empleadoConPermiso.getId()))
                .andExpect(jsonPath("$.rol").value("EMPLOYEE"))
                .andExpect(jsonPath("$.establecimientoId").value(establecimientoA.getId()))
                .andExpect(jsonPath("$.permisos[0]").value(PermisoEmpleado.OPERAR_CAJA.name()));
    }

    @Test
    @DisplayName("me_tokenDeMostradorReal_VeElPerfilDelEmpleadoComoUnTokenNormal")
    void me_tokenDeMostradorReal_VeElPerfil() throws Exception {
        Usuario cajero = empleadoConPin(establecimientoA, nombreUnico(), PIN);
        me(tokenMostradorReal(cajero)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cajero.getId()))
                .andExpect(jsonPath("$.rol").value("EMPLOYEE"))
                .andExpect(jsonPath("$.establecimientoId").value(establecimientoA.getId()));
    }

    @Test
    @DisplayName("me_empleadoDeshabilitadoConTokenVigente_Devuelve401")
    void me_empleadoDeshabilitadoConTokenVigente_Devuelve401() throws Exception {
        String token = bearer(empleadoConPermiso);
        Usuario e = recargar(empleadoConPermiso);
        e.setIsActive(false);
        usuarioRepository.save(e);

        me(token).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("me_conIdOEmailAjenoEnLaQuery_IgnoraLaQueryYDevuelveElPropio")
    void me_conIdOEmailAjenoEnLaQuery_DevuelveElPropio() throws Exception {
        mockMvc.perform(get(ME).param("id", String.valueOf(duenoB.getId())).param("email", duenoB.getEmail())
                        .header("Authorization", bearer(jugador)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jugador.getId()))
                .andExpect(jsonPath("$.email").value(jugador.getEmail()));
    }

    @Test
    @DisplayName("usuarioPorId_NoExisteEndpointParaLeerOtraCuenta")
    void usuarioPorId_NoExisteEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios/" + duenoB.getId()).header("Authorization", bearer(jugador)))
                .andExpect(status().isNotFound());
    }

    // ---- DELETE /me ----

    @Test
    @DisplayName("eliminarMiCuenta_admin_Devuelve403YNoElimina")
    void eliminarMiCuenta_admin_Devuelve403() throws Exception {
        mockMvc.perform(delete(ME).header("Authorization", bearer(admin))
                        .contentType("application/json").content("{\"password\":\"hash\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Este endpoint no está disponible para tu rol"));
        assertNull(recargar(admin).getDeletedAt());
    }

    @Test
    @DisplayName("eliminarMiCuenta_empleadoConTokenDeMostradorReal_Devuelve403YNoElimina")
    void eliminarMiCuenta_empleadoMostradorReal_Devuelve403() throws Exception {
        Usuario cajero = empleadoConPin(establecimientoA, nombreUnico(), PIN);
        mockMvc.perform(delete(ME).header("Authorization", tokenMostradorReal(cajero))
                        .contentType("application/json").content("{\"password\":\"" + PIN + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Este endpoint no está disponible para tu rol"));
        Usuario recargado = recargar(cajero);
        assertNull(recargado.getDeletedAt());
        assertTrue(recargado.getIsActive());
    }

    @Test
    @DisplayName("eliminarMiCuenta_conIdAjenoEnLaQuery_NoTocaLaCuentaAjena")
    void eliminarMiCuenta_conIdAjenoEnLaQuery_NoTocaLaCuentaAjena() throws Exception {
        // password "hash" no coincide con ningún BCrypt: se rechaza, y la víctima de la query no se toca
        mockMvc.perform(delete(ME).param("id", String.valueOf(duenoB.getId()))
                        .header("Authorization", bearer(jugador))
                        .contentType("application/json").content("{\"password\":\"hash\"}"))
                .andExpect(status().isUnauthorized());
        assertNull(recargar(duenoB).getDeletedAt());
        assertNull(recargar(jugador).getDeletedAt());
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
