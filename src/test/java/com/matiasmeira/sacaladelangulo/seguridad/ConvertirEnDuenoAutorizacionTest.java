package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/usuarios/me/convertir-en-dueno (pendiente 86). @PreAuthorize PLAYER/OWNER
 * (UsuarioController); el usuario sale siempre del token. PLAYER se convierte en OWNER con plan TRIAL; OWNER
 * recibe su perfil sin cambios (idempotente); ADMIN y EMPLOYEE reciben 403. El MISMO token sigue
 * valiendo porque el JWT no lleva el rol: se lee de la base en cada request.
 */
@DisplayName("POST /api/v1/usuarios/me/convertir-en-dueno")
class ConvertirEnDuenoAutorizacionTest extends AbstractSecurityWebTest {

    private static final String CONVERTIR = "/api/v1/usuarios/me/convertir-en-dueno";
    private static final String BODY_ESTABLECIMIENTO = "{\"nombre\":\"Complejo Nuevo\",\"direccion\":\"Calle 123\","
            + "\"latitud\":-34.6,\"longitud\":-58.4,\"requiereSena\":false,\"requiereTelefonoVerificado\":false}";

    private ResultActions convertir(Usuario usuario) throws Exception {
        return mockMvc.perform(post(CONVERTIR).header("Authorization", bearer(usuario)));
    }

    private Usuario recargar(Usuario usuario) {
        return usuarioRepository.findById(usuario.getId()).orElseThrow();
    }

    @Test
    @DisplayName("jugador_Devuelve200ConPerfilOwnerTrialYQuedaPersistido")
    void jugador_Devuelve200ConPerfilOwnerTrialYQuedaPersistido() throws Exception {
        int tokenVersionAntes = recargar(jugador).getTokenVersion();

        convertir(jugador).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jugador.getId()))
                .andExpect(jsonPath("$.email").value(jugador.getEmail()))
                .andExpect(jsonPath("$.rol").value("OWNER"))
                .andExpect(jsonPath("$.planSuscripcion").value("TRIAL"));

        Usuario despues = recargar(jugador);
        assertEquals(Role.OWNER, despues.getRol());
        assertEquals(PlanSuscripcion.TRIAL, despues.getPlanSuscripcion());
        assertNull(despues.getFechaFinPrueba());
        assertEquals(tokenVersionAntes, despues.getTokenVersion());
        verify(emailService, timeout(5000).times(1)).enviar(eq(jugador.getEmail()), any(), any());
    }

    @Test
    @DisplayName("jugador_ConElMismoTokenDespuesDeConvertir_ElPerfilEsOwnerYPuedeCrearUnEstablecimiento")
    void jugador_ConElMismoTokenDespuesDeConvertir_ElPerfilEsOwnerYPuedeCrearUnEstablecimiento() throws Exception {
        String authorization = bearer(jugador);
        mockMvc.perform(post("/api/v1/establecimientos").header("Authorization", authorization)
                        .contentType("application/json").content(BODY_ESTABLECIMIENTO))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(CONVERTIR).header("Authorization", authorization)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/usuarios/me").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("OWNER"))
                .andExpect(jsonPath("$.planSuscripcion").value("TRIAL"));
        mockMvc.perform(post("/api/v1/establecimientos").header("Authorization", authorization)
                        .contentType("application/json").content(BODY_ESTABLECIMIENTO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duenoId").value(jugador.getId()));
    }

    @Test
    @DisplayName("jugador_SegundaLlamada_Devuelve200IdempotenteYNoMandaOtroMail")
    void jugador_SegundaLlamada_Devuelve200IdempotenteYNoMandaOtroMail() throws Exception {
        convertir(jugador).andExpect(status().isOk());
        verify(emailService, timeout(5000).times(1)).enviar(eq(jugador.getEmail()), any(), any());

        convertir(jugador).andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("OWNER"))
                .andExpect(jsonPath("$.planSuscripcion").value("TRIAL"));

        verify(emailService, after(500).times(1)).enviar(eq(jugador.getEmail()), any(), any());
    }

    @Test
    @DisplayName("owner_Devuelve200ConSuPerfilSinCambiosNiMail")
    void owner_Devuelve200ConSuPerfilSinCambiosNiMail() throws Exception {
        PlanSuscripcion planAntes = recargar(duenoA).getPlanSuscripcion();

        convertir(duenoA).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(duenoA.getId()))
                .andExpect(jsonPath("$.rol").value("OWNER"));

        assertEquals(planAntes, recargar(duenoA).getPlanSuscripcion());
        verify(emailService, after(300).never()).enviar(any(), any(), any());
    }

    @Test
    @DisplayName("admin_Devuelve403PreAuthorizeYNoCambiaNada")
    void admin_Devuelve403PreAuthorizeYNoCambiaNada() throws Exception {
        convertir(admin).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(Role.ADMIN, recargar(admin).getRol());
    }

    @Test
    @DisplayName("empleado_Devuelve403PreAuthorizeYNoCambiaNada")
    void empleado_Devuelve403PreAuthorizeYNoCambiaNada() throws Exception {
        convertir(empleadoConPermiso).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(Role.EMPLOYEE, recargar(empleadoConPermiso).getRol());
    }

    @Test
    @DisplayName("sinToken_Devuelve401")
    void sinToken_Devuelve401() throws Exception {
        mockMvc.perform(post(CONVERTIR)).andExpect(status().isUnauthorized());
        assertEquals(Role.PLAYER, recargar(jugador).getRol());
    }
}
