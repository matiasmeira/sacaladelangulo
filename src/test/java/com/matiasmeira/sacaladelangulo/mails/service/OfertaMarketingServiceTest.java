package com.matiasmeira.sacaladelangulo.mails.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.mails.dto.EnviarOfertaRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OfertaMarketingService - Autorización y disparo del broadcast de ofertas")
class OfertaMarketingServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private OfertaMarketingBatchSender ofertaMarketingBatchSender;

    @InjectMocks
    private OfertaMarketingService service;

    @Test
    @DisplayName("enviarOferta_UsuarioNoAdmin_LanzaAccessDeniedExceptionYNoInvocaElBatchSender")
    void enviarOferta_UsuarioNoAdmin_LanzaAccessDeniedExceptionYNoInvocaElBatchSender() {
        Usuario noAdmin = usuarioDePrueba(Role.OWNER);
        when(usuarioRepository.findByEmail("owner@test.com")).thenReturn(Optional.of(noAdmin));
        EnviarOfertaRequest request = new EnviarOfertaRequest("Asunto", "<p>Cuerpo</p>");

        assertThrows(AccessDeniedException.class, () -> service.enviarOferta(request, "owner@test.com"));

        verifyNoInteractions(ofertaMarketingBatchSender);
    }

    @Test
    @DisplayName("enviarOferta_UsuarioAdmin_InvocaElBatchSenderConElRequest")
    void enviarOferta_UsuarioAdmin_InvocaElBatchSenderConElRequest() {
        Usuario admin = usuarioDePrueba(Role.ADMIN);
        when(usuarioRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(admin));
        EnviarOfertaRequest request = new EnviarOfertaRequest("Asunto", "<p>Cuerpo</p>");

        service.enviarOferta(request, "admin@test.com");

        ArgumentCaptor<EnviarOfertaRequest> requestCaptor = ArgumentCaptor.forClass(EnviarOfertaRequest.class);
        verify(ofertaMarketingBatchSender).enviarEnLotes(requestCaptor.capture());
        assertEquals(request, requestCaptor.getValue());
    }

    @Test
    @DisplayName("enviarOferta_UsuarioInexistente_LanzaEntityNotFoundException")
    void enviarOferta_UsuarioInexistente_LanzaEntityNotFoundException() {
        when(usuarioRepository.findByEmail("no-existe@test.com")).thenReturn(Optional.empty());
        EnviarOfertaRequest request = new EnviarOfertaRequest("Asunto", "<p>Cuerpo</p>");

        assertThrows(EntityNotFoundException.class, () -> service.enviarOferta(request, "no-existe@test.com"));

        verifyNoInteractions(ofertaMarketingBatchSender);
    }

    private Usuario usuarioDePrueba(Role rol) {
        Usuario usuario = Usuario.builder()
                .email(rol == Role.ADMIN ? "admin@test.com" : "owner@test.com")
                .password("hash")
                .nombre("Usuario de prueba")
                .rol(rol)
                .build();
        usuario.setId(1L);
        return usuario;
    }
}
