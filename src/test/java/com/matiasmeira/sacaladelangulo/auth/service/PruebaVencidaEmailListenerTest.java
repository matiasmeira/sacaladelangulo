package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PruebaVencidaEmailListener - Envío del email de degradación de plan")
class PruebaVencidaEmailListenerTest {

    private static final String FRONTEND_URL = "http://localhost:5173";

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private EmailRenderer emailRenderer;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private PruebaVencidaEmailListener listener;

    @Test
    @DisplayName("enviarAvisoDeDegradacion_UsuarioExistente_RenderizaLaPlantillaYEnviaElEmail")
    void enviarAvisoDeDegradacion_UsuarioExistente_RenderizaLaPlantillaYEnviaElEmail() {
        ReflectionTestUtils.setField(listener, "frontendUrl", FRONTEND_URL);

        Usuario usuario = Usuario.builder()
                .email("dueno@test.com")
                .password("hash")
                .nombre("Carlos")
                .build();
        usuario.setId(10L);
        when(usuarioRepository.findById(10L)).thenReturn(Optional.of(usuario));
        when(emailRenderer.render(eq("prueba-vencida"), anyMap())).thenReturn("<html>prueba-vencida</html>");

        listener.enviarAvisoDeDegradacion(new PruebaVencidaEvent(10L));

        ArgumentCaptor<Map<String, Object>> modeloCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailRenderer).render(eq("prueba-vencida"), modeloCaptor.capture());
        assertEquals("Carlos", modeloCaptor.getValue().get("nombre"));
        assertEquals(FRONTEND_URL + "/panel/configuracion", modeloCaptor.getValue().get("ctaUrl"));

        verify(emailService).enviar(eq("dueno@test.com"), eq("Tu prueba gratuita terminó"), eq("<html>prueba-vencida</html>"));
    }

    @Test
    @DisplayName("enviarAvisoDeDegradacion_UsuarioNoEncontrado_NoEnviaEmailNiLanzaExcepcion")
    void enviarAvisoDeDegradacion_UsuarioNoEncontrado_NoEnviaEmailNiLanzaExcepcion() {
        when(usuarioRepository.findById(99L)).thenReturn(Optional.empty());

        listener.enviarAvisoDeDegradacion(new PruebaVencidaEvent(99L));

        verify(emailService, never()).enviar(anyString(), anyString(), anyString());
        verifyNoInteractions(emailRenderer);
    }

    @Test
    @DisplayName("enviarAvisoDeDegradacion_CuentaEliminada_NoEnviaEmail")
    void enviarAvisoDeDegradacion_CuentaEliminada_NoEnviaEmail() {
        Usuario usuario = Usuario.builder()
                .email("deleted+11@saque.deleted")
                .password("hash")
                .nombre("Usuario eliminado")
                .deletedAt(LocalDateTime.now())
                .build();
        usuario.setId(11L);
        when(usuarioRepository.findById(11L)).thenReturn(Optional.of(usuario));

        listener.enviarAvisoDeDegradacion(new PruebaVencidaEvent(11L));

        verify(emailService, never()).enviar(anyString(), anyString(), anyString());
        verifyNoInteractions(emailRenderer);
    }
}
