package com.matiasmeira.sacaladelangulo.mails.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import com.matiasmeira.sacaladelangulo.mails.dto.EnviarOfertaRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OfertaMarketingBatchSender - Envío en lotes del broadcast de ofertas")
class OfertaMarketingBatchSenderTest {

    private static final String FRONTEND_URL = "http://localhost:5173";

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private EmailRenderer emailRenderer;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private OfertaMarketingBatchSender batchSender;

    @Test
    @DisplayName("enviarEnLotes_DosUsuariosConOptIn_EnviaUnEmailPorUsuarioConSuPropioLinkDeBaja")
    void enviarEnLotes_DosUsuariosConOptIn_EnviaUnEmailPorUsuarioConSuPropioLinkDeBaja() {
        ReflectionTestUtils.setField(batchSender, "frontendUrl", FRONTEND_URL);

        Usuario usuario1 = usuarioDePrueba(1L, "uno@test.com", "token-1");
        Usuario usuario2 = usuarioDePrueba(2L, "dos@test.com", "token-2");

        Page<Usuario> primeraPagina = new PageImpl<>(List.of(usuario1, usuario2), Pageable.ofSize(50), 2);
        Page<Usuario> paginaVacia = new PageImpl<>(List.of(), Pageable.ofSize(50).withPage(1), 2);

        when(usuarioRepository.findByAceptaMarketingTrue(any(Pageable.class)))
                .thenReturn(primeraPagina)
                .thenReturn(paginaVacia);
        when(emailRenderer.render(eq("oferta"), anyMap())).thenReturn("<html>oferta</html>");

        EnviarOfertaRequest request = new EnviarOfertaRequest("Oferta especial", "<p>Cuerpo</p>");

        batchSender.enviarEnLotes(request);

        verify(emailService, times(1)).enviar(eq("uno@test.com"), eq("Oferta especial"), any());
        verify(emailService, times(1)).enviar(eq("dos@test.com"), eq("Oferta especial"), any());

        ArgumentCaptor<Map<String, Object>> modeloCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailRenderer, times(2)).render(eq("oferta"), modeloCaptor.capture());

        List<Map<String, Object>> modelos = modeloCaptor.getAllValues();
        assertEquals(2, modelos.size());

        assertTrue(modelos.stream().anyMatch(modelo ->
                (FRONTEND_URL + "/baja-mails?token=token-1").equals(modelo.get("unsubscribeLink"))
                        && "<p>Cuerpo</p>".equals(modelo.get("cuerpoHtml"))));
        assertTrue(modelos.stream().anyMatch(modelo ->
                (FRONTEND_URL + "/baja-mails?token=token-2").equals(modelo.get("unsubscribeLink"))));
    }

    @Test
    @DisplayName("enviarEnLotes_ConFrontendUrlConSlashFinal_NoDuplicaElSlashEnElLinkDeBaja")
    void enviarEnLotes_ConFrontendUrlConSlashFinal_NoDuplicaElSlashEnElLinkDeBaja() {
        ReflectionTestUtils.setField(batchSender, "frontendUrl", "http://localhost:3000/");

        Usuario usuario = usuarioDePrueba(1L, "uno@test.com", "token-1");
        Page<Usuario> primeraPagina = new PageImpl<>(List.of(usuario), Pageable.ofSize(50), 1);
        Page<Usuario> paginaVacia = new PageImpl<>(List.of(), Pageable.ofSize(50).withPage(1), 1);

        when(usuarioRepository.findByAceptaMarketingTrue(any(Pageable.class)))
                .thenReturn(primeraPagina)
                .thenReturn(paginaVacia);
        when(emailRenderer.render(eq("oferta"), anyMap())).thenReturn("<html>oferta</html>");

        batchSender.enviarEnLotes(new EnviarOfertaRequest("Oferta especial", "<p>Cuerpo</p>"));

        ArgumentCaptor<Map<String, Object>> modeloCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailRenderer).render(eq("oferta"), modeloCaptor.capture());
        assertEquals("http://localhost:3000/baja-mails?token=token-1", modeloCaptor.getValue().get("unsubscribeLink"));
    }

    @Test
    @DisplayName("enviarEnLotes_SinUsuariosConOptIn_NoEnviaNingunEmail")
    void enviarEnLotes_SinUsuariosConOptIn_NoEnviaNingunEmail() {
        ReflectionTestUtils.setField(batchSender, "frontendUrl", FRONTEND_URL);

        Page<Usuario> paginaVacia = new PageImpl<>(List.of(), Pageable.ofSize(50), 0);
        when(usuarioRepository.findByAceptaMarketingTrue(any(Pageable.class))).thenReturn(paginaVacia);

        EnviarOfertaRequest request = new EnviarOfertaRequest("Oferta especial", "<p>Cuerpo</p>");

        batchSender.enviarEnLotes(request);

        verify(emailService, org.mockito.Mockito.never()).enviar(any(), any(), any());
    }

    private Usuario usuarioDePrueba(Long id, String email, String unsubscribeToken) {
        Usuario usuario = Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Usuario " + id)
                .aceptaMarketing(true)
                .unsubscribeToken(unsubscribeToken)
                .build();
        usuario.setId(id);
        return usuario;
    }
}
