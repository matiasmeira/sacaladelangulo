package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.repository.CodigoVerificacionRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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

    @InjectMocks
    private UsuarioService usuarioService;

    @Test
    @DisplayName("solicitarCodigo_Fallo_CarreraDeInsercion_TraduceAExcepcionDeNegocio")
    void solicitarCodigo_Fallo_CarreraDeInsercion_TraduceAExcepcionDeNegocio() {
        // El deleteByEmail + save no es atómico: si dos requests casi simultáneas para el
        // mismo email pasan ambas el delete, la constraint única sobre "email" hace que el
        // segundo insert falle. Ese 500 no controlado debe traducirse a un mensaje de negocio.
        when(codigoVerificacionRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> usuarioService.solicitarCodigo("jugador@test.com", "1122334455")
        );

        assertEquals("Ya se generó un código recientemente. Esperá unos segundos e intentá de nuevo.", exception.getMessage());
    }
}
