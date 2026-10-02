package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.PerfilMapper;
import com.matiasmeira.sacaladelangulo.auth.dto.PerfilResponse;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.CodigoVerificacionRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UsuarioService - Verificación de teléfono por OTP")
class UsuarioServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private CodigoVerificacionRepository codigoVerificacionRepository;

    @Mock
    private PerfilMapper perfilMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private UsuarioService usuarioService;

    @Test
    @DisplayName("solicitarCodigo_Fallo_CarreraDeInsercion_TraduceAExcepcionDeNegocio")
    void solicitarCodigo_Fallo_CarreraDeInsercion_TraduceAExcepcionDeNegocio() {
        when(codigoVerificacionRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> usuarioService.solicitarCodigo("jugador@test.com", "1122334455")
        );

        assertEquals("Ya se generó un código recientemente. Esperá unos segundos e intentá de nuevo.", exception.getMessage());
    }

    @Test
    @DisplayName("obtenerPerfil_Exito_DevuelvePerfilMapeado")
    void obtenerPerfil_Exito_DevuelvePerfilMapeado() {
        Usuario usuario = Usuario.builder().id(1L).email("jugador@test.com").rol(Role.PLAYER).build();
        PerfilResponse perfilEsperado = new PerfilResponse(
                1L, "jugador@test.com", null, Role.PLAYER, null, null, null, null, Set.of());
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(usuario));
        when(perfilMapper.mapToResponse(usuario)).thenReturn(perfilEsperado);

        PerfilResponse resultado = usuarioService.obtenerPerfil("jugador@test.com");

        assertEquals(perfilEsperado, resultado);
    }

    @Test
    @DisplayName("obtenerPerfil_UsuarioNoExiste_LanzaEntityNotFoundException")
    void obtenerPerfil_UsuarioNoExiste_LanzaEntityNotFoundException() {
        when(usuarioRepository.findByEmail("fantasma@test.com")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> usuarioService.obtenerPerfil("fantasma@test.com"));
    }

    // ---- convertirEnDueno (pendiente 86) ----

    private static Usuario usuarioConRol(Role rol) {
        return Usuario.builder().id(7L).email("u@test.com").nombre("Ana Pérez").rol(rol).build();
    }

    @Test
    @DisplayName("convertirEnDueno_Player_ActualizaConCondicionalPublicaBienvenidaDeDuenoYDevuelvePerfilRecargado")
    void convertirEnDueno_Player_ActualizaConCondicionalPublicaBienvenidaDeDuenoYDevuelvePerfilRecargado() {
        Usuario antes = usuarioConRol(Role.PLAYER);
        Usuario despues = usuarioConRol(Role.OWNER);
        PerfilResponse perfil = new PerfilResponse(
                7L, "u@test.com", "Ana Pérez", Role.OWNER, null, null, null, null, Set.of());
        when(usuarioRepository.findByEmail("u@test.com")).thenReturn(Optional.of(antes), Optional.of(despues));
        when(usuarioRepository.convertirEnDuenoSiEsJugador(7L)).thenReturn(1);
        when(perfilMapper.mapToResponse(despues)).thenReturn(perfil);

        PerfilResponse resultado = usuarioService.convertirEnDueno("u@test.com");

        assertEquals(perfil, resultado);
        ArgumentCaptor<RegistroCompletadoEvent> evento = ArgumentCaptor.forClass(RegistroCompletadoEvent.class);
        verify(eventPublisher, times(1)).publishEvent(evento.capture());
        assertEquals(new RegistroCompletadoEvent("u@test.com", "Ana Pérez", Role.OWNER), evento.getValue());
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("convertirEnDueno_PlayerQueOtraRequestYaConvirtio_UpdateDevuelve0_NoPublicaOtroMail")
    void convertirEnDueno_PlayerQueOtraRequestYaConvirtio_UpdateDevuelve0_NoPublicaOtroMail() {
        Usuario antes = usuarioConRol(Role.PLAYER);
        Usuario despues = usuarioConRol(Role.OWNER);
        when(usuarioRepository.findByEmail("u@test.com")).thenReturn(Optional.of(antes), Optional.of(despues));
        when(usuarioRepository.convertirEnDuenoSiEsJugador(7L)).thenReturn(0);
        when(perfilMapper.mapToResponse(despues)).thenReturn(
                new PerfilResponse(7L, "u@test.com", "Ana Pérez", Role.OWNER, null, null, null, null, Set.of()));

        PerfilResponse resultado = usuarioService.convertirEnDueno("u@test.com");

        assertEquals(Role.OWNER, resultado.rol());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("convertirEnDueno_Owner_DevuelveElPerfilSinCambiosNiMail")
    void convertirEnDueno_Owner_DevuelveElPerfilSinCambiosNiMail() {
        Usuario owner = usuarioConRol(Role.OWNER);
        PerfilResponse perfil = new PerfilResponse(
                7L, "u@test.com", "Ana Pérez", Role.OWNER, null, null, null, null, Set.of());
        when(usuarioRepository.findByEmail("u@test.com")).thenReturn(Optional.of(owner));
        when(perfilMapper.mapToResponse(owner)).thenReturn(perfil);

        assertEquals(perfil, usuarioService.convertirEnDueno("u@test.com"));

        verify(usuarioRepository, never()).convertirEnDuenoSiEsJugador(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("convertirEnDueno_Admin_LanzaAccessDeniedSinTocarNada")
    void convertirEnDueno_Admin_LanzaAccessDeniedSinTocarNada() {
        when(usuarioRepository.findByEmail("u@test.com")).thenReturn(Optional.of(usuarioConRol(Role.ADMIN)));

        assertThrows(AccessDeniedException.class, () -> usuarioService.convertirEnDueno("u@test.com"));

        verify(usuarioRepository, never()).convertirEnDuenoSiEsJugador(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("convertirEnDueno_Empleado_LanzaAccessDeniedSinTocarNada")
    void convertirEnDueno_Empleado_LanzaAccessDeniedSinTocarNada() {
        when(usuarioRepository.findByEmail("u@test.com")).thenReturn(Optional.of(usuarioConRol(Role.EMPLOYEE)));

        assertThrows(AccessDeniedException.class, () -> usuarioService.convertirEnDueno("u@test.com"));

        verify(usuarioRepository, never()).convertirEnDuenoSiEsJugador(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("convertirEnDueno_UsuarioNoExiste_LanzaEntityNotFoundException")
    void convertirEnDueno_UsuarioNoExiste_LanzaEntityNotFoundException() {
        when(usuarioRepository.findByEmail("fantasma@test.com")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> usuarioService.convertirEnDueno("fantasma@test.com"));
    }
}
