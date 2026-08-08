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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
}
