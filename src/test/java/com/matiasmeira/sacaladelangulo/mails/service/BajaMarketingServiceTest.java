package com.matiasmeira.sacaladelangulo.mails.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BajaMarketingService - Baja de emails de marketing por token")
class BajaMarketingServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @InjectMocks
    private BajaMarketingService service;

    @Test
    @DisplayName("darDeBaja_TokenValido_DesactivaAceptaMarketingYGuarda")
    void darDeBaja_TokenValido_DesactivaAceptaMarketingYGuarda() {
        Usuario usuario = usuarioDePrueba();
        when(usuarioRepository.findByUnsubscribeToken("token-valido")).thenReturn(Optional.of(usuario));

        service.darDeBaja("token-valido");

        assertFalse(usuario.getAceptaMarketing());

        ArgumentCaptor<Usuario> usuarioCaptor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(usuarioCaptor.capture());
        assertEquals(usuario, usuarioCaptor.getValue());
    }

    @Test
    @DisplayName("darDeBaja_TokenDesconocido_LanzaEntityNotFoundExceptionYNoGuarda")
    void darDeBaja_TokenDesconocido_LanzaEntityNotFoundExceptionYNoGuarda() {
        when(usuarioRepository.findByUnsubscribeToken("token-invalido")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.darDeBaja("token-invalido"));

        verify(usuarioRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private Usuario usuarioDePrueba() {
        Usuario usuario = Usuario.builder()
                .email("usuario@test.com")
                .password("hash")
                .nombre("Usuario de prueba")
                .aceptaMarketing(true)
                .unsubscribeToken("token-valido")
                .build();
        usuario.setId(1L);
        return usuario;
    }
}
