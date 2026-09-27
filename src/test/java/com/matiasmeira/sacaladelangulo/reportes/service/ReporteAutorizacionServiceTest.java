package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * A diferencia de los 6 ReporteXServiceTest (que mockean ReporteAutorizacionService), este test
 * usa AutorizacionEmpleadoService REAL: lo que se quiere probar acá es justamente que la
 * delegación llega hasta la regla real de "dueño o admin", no sólo que se la invoque.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReporteAutorizacionService")
class ReporteAutorizacionServiceTest {

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    private ReporteAutorizacionService reporteAutorizacionService;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder()
                .id(2L)
                .email("dueno@test.com")
                .rol(Role.OWNER)
                .build();

        establecimiento = Establecimientos.establecimientoOperativo(b -> b
                .id(10L)
                .nombre("Establecimiento Test")
                .direccion("Calle Test 123")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .dueno(dueno)
                .requiereSena(true));

        AutorizacionEmpleadoService autorizacionEmpleadoService = new AutorizacionEmpleadoService(usuarioRepository);
        reporteAutorizacionService = new ReporteAutorizacionService(establecimientoRepository, autorizacionEmpleadoService);
    }

    @Test
    @DisplayName("validarDuenoDelEstablecimiento_Fallo_EstablecimientoInexistente")
    void validarDuenoDelEstablecimiento_Fallo_EstablecimientoInexistente() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> reporteAutorizacionService.validarDuenoDelEstablecimiento(10L, dueno.getEmail())
        );
    }

    @Test
    @DisplayName("validarDuenoDelEstablecimiento_Exito_EsDuenoReal_DevuelveElEstablecimiento")
    void validarDuenoDelEstablecimiento_Exito_EsDuenoReal_DevuelveElEstablecimiento() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        Establecimiento resultado = reporteAutorizacionService.validarDuenoDelEstablecimiento(10L, dueno.getEmail());

        assertEquals(establecimiento, resultado);
    }

    @Test
    @DisplayName("validarDuenoDelEstablecimiento_Fallo_DuenoDeOtroEstablecimiento")
    void validarDuenoDelEstablecimiento_Fallo_DuenoDeOtroEstablecimiento() {
        Usuario otroDueno = Usuario.builder().id(3L).email("otro@test.com").rol(Role.OWNER).build();
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(otroDueno.getEmail())).thenReturn(Optional.of(otroDueno));

        assertThrows(
                AccessDeniedException.class,
                () -> reporteAutorizacionService.validarDuenoDelEstablecimiento(10L, otroDueno.getEmail())
        );
    }
}
